package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.agent.AgentMsg
import com.google.ai.edge.gallery.forge.engine.ApprovalAnswer
import com.google.ai.edge.gallery.forge.engine.ApprovalRequest
import com.google.ai.edge.gallery.forge.engine.ForgeContext
import com.google.ai.edge.gallery.forge.engine.ForgeEngine
import com.google.ai.edge.gallery.forge.engine.ForgeInteractions
import com.google.ai.edge.gallery.forge.engine.ForgeLimits
import com.google.ai.edge.gallery.forge.engine.ModelPort
import com.google.ai.edge.gallery.forge.engine.PlanReview
import com.google.ai.edge.gallery.forge.engine.Preset
import com.google.ai.edge.gallery.forge.engine.RateLimit
import com.google.ai.edge.gallery.forge.engine.RateLimitedException
import com.google.ai.edge.gallery.forge.engine.ShellPort
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.journal.SessionJournal
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import com.google.ai.edge.gallery.tools.CommandWait
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stop always answers, waits are smart, rate limits are retried, the task list is kept honest. */
class ForgeEngineStopTest {
  private class Model(val script: MutableList<suspend () -> String>) : ModelPort {
    val seen = ArrayList<List<AgentMsg>>()
    override val name = "test"
    override val historyBudgetChars = 200_000
    override val local = false

    override suspend fun generate(system: String, messages: List<AgentMsg>, onChunk: (String) -> Unit): String {
      seen.add(messages)
      val next = if (script.isEmpty()) ({ "Terminé." }) else script.removeAt(0)
      return next().also(onChunk)
    }

    override fun stop() {}
  }

  private class Shell(val onRun: suspend (String, CommandWait.Policy, (String) -> Unit) -> ShellPort.Result) : ShellPort {
    override suspend fun ensureReady(onProgress: (String) -> Unit): String? = null

    override suspend fun run(command: String, wait: CommandWait.Policy, onOutput: (String) -> Unit) = onRun(command, wait, onOutput)

    override fun startJob(command: String) = "job1"

    override fun checkJob(id: String, stop: Boolean, maxChars: Int) = mapOf("job_id" to id, "state" to "running", "new_output" to "", "status" to "succeeded")
  }

  private object Yes : ForgeInteractions {
    override suspend fun approve(request: ApprovalRequest) = ApprovalAnswer(ApprovalAnswer.Choice.OUI)

    override suspend fun ask(callId: String, question: String, options: List<String>) = null

    override suspend fun reviewPlan(callId: String, markdown: String) = PlanReview(PlanReview.Outcome.VALIDE)
  }

  private fun call(name: String, args: JSONObject) =
    "<tool_call>\n" + JSONObject().put("name", name).put("arguments", args).toString() + "\n</tool_call>"

  private class Setup(val engine: ForgeEngine, val journal: SessionJournal, val model: Model)

  private fun setup(model: Model, shell: ShellPort, clock: () -> Long = System::currentTimeMillis): Setup {
    val root = Files.createTempDirectory("forge-stop").toFile()
    val journal = SessionJournal(File(Files.createTempDirectory("forge-stop-j").toFile(), "s.jsonl"))
    journal.append(ForgeEvent.SessionStart("s", "demo", "test", Preset.TOUT_AUTORISER.mode.key(), ""))
    val ctx = object : ForgeContext {
      override fun systemPrompt() = "Consignes"
    }
    val engine = ForgeEngine(journal, model, ProjectPaths(root, "/root/projets/demo"), shell, Yes, ctx, ForgeLimits.forModel(model), clock)
    return Setup(engine, journal, model)
  }

  private fun bash(cmd: String) = call("Bash", JSONObject().put("command", cmd))

  @Test
  fun stopDuringABlockingCommand_endsInUnderTwoSeconds() = runBlocking {
    var cancelled = false
    val shell =
      Shell { _, _, _ ->
        try {
          awaitCancellation()
        } catch (e: CancellationException) {
          cancelled = true
          throw e
        }
      }
    val s = setup(Model(mutableListOf<suspend () -> String>({ bash("apk update && apk add nodejs python3") })), shell)
    val turn = async(Dispatchers.Default) { s.engine.runTurn("installe node") }
    withTimeout(5_000) { while (!s.engine.isRunning || s.engine.live.value.tool != "Bash") delay(20) }
    delay(200)
    val t0 = System.currentTimeMillis()
    s.engine.requestStop()
    turn.cancel()
    runCatching { turn.await() }
    val took = System.currentTimeMillis() - t0
    assertTrue("arrêt en $took ms", took < 2_000)
    assertTrue(cancelled)
    val events = s.journal.snapshot()
    val result = events.filterIsInstance<ForgeEvent.ToolResult>().single()
    assertEquals("arrêté", result.summary)
    val end = events.last() as ForgeEvent.TurnEnd
    assertEquals("stopped", end.reason)
    assertEquals("Arrêté par l'utilisateur.", end.detail)
    assertFalse(s.engine.state.value.turnOpen)
  }

  @Test
  fun forceStop_closesATurnStuckInCodeThatIgnoresCancellation() = runBlocking {
    val released = java.util.concurrent.atomic.AtomicBoolean(false)
    val model =
      Model(
        mutableListOf<suspend () -> String>({
          // A runtime that ignores Stop and cancellation (blocking, no suspension point).
          while (!released.get()) Thread.sleep(20)
          "trop tard"
        })
      )
    val s = setup(model, Shell { _, _, _ -> ShellPort.Result(0, "", null, false) })
    val turn = launch(Dispatchers.IO) { runCatching { s.engine.runTurn("bonjour") } }
    withTimeout(5_000) { while (!s.engine.isRunning) delay(10) }
    s.engine.requestStop()
    turn.cancel()
    delay(300)
    s.engine.forceStop()
    assertFalse(s.engine.state.value.turnOpen)
    assertEquals("stopped", (s.journal.snapshot().last() as ForgeEvent.TurnEnd).reason)
    val count = s.journal.snapshot().size
    released.set(true)
    turn.join()
    // What the stuck code did afterwards is not written.
    assertEquals(count, s.journal.snapshot().size)
  }

  @Test
  fun silentCommand_comesBackAsABackgroundJob_withLiveLines() = runBlocking {
    var seenPolicy: CommandWait.Policy? = null
    lateinit var s: Setup
    var progress = ""
    val shell =
      Shell { _, wait, onOutput ->
        seenPolicy = wait
        onOutput("(1/4) Installing a\n(2/4) Installing b\n(3/4) Installing c\n(4/4) Installing nodejs\n(5/5) x\n")
        progress = s.engine.live.value.toolProgress
        ShellPort.Result(-1, "(2/4) Installing nodejs", null, false, jobId = "job7", detached = "idle")
      }
    s = setup(Model(mutableListOf<suspend () -> String>({ bash("apk add nodejs") }, { "Installation en cours en arrière-plan." })), shell)
    assertEquals("done", s.engine.runTurn("installe"))
    assertEquals(CommandWait.SLOW_IDLE_S * 1000L, seenPolicy!!.idleMs)
    assertEquals(4, progress.lines().size)
    assertTrue(progress, progress.endsWith("(5/5) x"))
    val r = s.journal.snapshot().filterIsInstance<ForgeEvent.ToolResult>().single()
    assertEquals("ok", r.status)
    assertEquals("en arrière-plan (job7)", r.summary)
    val o = JSONObject(r.modelText)
    assertEquals("job7", o.getString("job_id"))
    assertTrue(o.getBoolean("still_running"))
    assertTrue(o.getString("note"), o.getString("note").contains("BashOutput(\"job7\")"))
    assertTrue(o.getString("output").contains("Installing nodejs"))
  }

  @Test
  fun rateLimit_isRetriedWithAVisibleWait_thenSucceeds() = runBlocking {
    var t = 0L
    val clock = { t += 3_000; t }
    val s =
      setup(
        Model(
          mutableListOf<suspend () -> String>(
            { throw RateLimitedException("Groq a répondu 429.", 1_500, "Groq") },
            { throw RateLimitedException("Groq a répondu 429.", null, "Groq") },
            { "Voilà." },
          )
        ),
        Shell { _, _, _ -> ShellPort.Result(0, "", null, false) },
        clock,
      )
    assertEquals("done", s.engine.runTurn("bonjour"))
    val notes = s.journal.snapshot().filterIsInstance<ForgeEvent.Note>().filter { it.kind == "attente" }
    assertEquals(2, notes.size)
    assertTrue(notes[0].text, notes[0].text.startsWith("Limite du fournisseur Groq, nouvel essai dans 2 s"))
    assertTrue(notes[1].text, notes[1].text.contains("nouvel essai dans 4 s (essai 3 sur 4)"))
    assertFalse(notes.any { it.forModel })
  }

  @Test
  fun rateLimit_givesUpAfterThreeRetries_orAtOnceForALongQuota() = runBlocking {
    var t = 0L
    val always: suspend () -> String = { throw RateLimitedException("429", 1_000, "Groq") }
    val s = setup(Model(MutableList(10) { always }), Shell { _, _, _ -> ShellPort.Result(0, "", null, false) }, { t += 5_000; t })
    assertEquals("error", s.engine.runTurn("bonjour"))
    assertEquals(RateLimit.MAX_RETRIES + 1, s.model.seen.size)
    assertTrue(s.journal.snapshot().filterIsInstance<ForgeEvent.Note>().last().text.contains("après 3 nouveaux essais"))

    val quota = setup(Model(mutableListOf<suspend () -> String>({ throw RateLimitedException("429", 3_600_000, "Groq") })), Shell { _, _, _ -> ShellPort.Result(0, "", null, false) })
    assertEquals("error", quota.engine.runTurn("bonjour"))
    assertEquals(1, quota.model.seen.size)
  }

  @Test
  fun stopDuringARateLimitWait_isImmediate() = runBlocking {
    val s = setup(Model(MutableList<suspend () -> String>(5) { { throw RateLimitedException("429", 20_000, "Groq") } }), Shell { _, _, _ -> ShellPort.Result(0, "", null, false) })
    val turn = async(Dispatchers.Default) { s.engine.runTurn("bonjour") }
    withTimeout(5_000) { while (s.journal.snapshot().none { it is ForgeEvent.Note && it.kind == "attente" }) delay(20) }
    val t0 = System.currentTimeMillis()
    s.engine.requestStop()
    assertEquals("stopped", withTimeout(2_000) { turn.await() })
    assertTrue(System.currentTimeMillis() - t0 < 1_000)
  }

  @Test
  fun retryAfter_parsing_andBackoff() {
    assertEquals(7_000L, RateLimit.parseRetryAfter("7", ""))
    assertEquals(1_500L, RateLimit.parseRetryAfter("1.5", ""))
    assertEquals(7_660L, RateLimit.parseRetryAfter(null, """{"error":{"message":"Rate limit reached for model `openai/gpt-oss-120b`… Please try again in 7.66s. Need more tokens?"}}"""))
    assertEquals(90_500L, RateLimit.parseRetryAfter(null, "Please try again in 1m30.5s"))
    assertEquals(450L, RateLimit.parseRetryAfter(null, "try again in 450ms"))
    assertEquals(null, RateLimit.parseRetryAfter(null, "quota exceeded"))
    assertEquals(2_000L, RateLimit.delayMs(null, 1))
    assertEquals(4_000L, RateLimit.delayMs(null, 2))
    assertEquals(8_000L, RateLimit.delayMs(null, 3))
    assertEquals(8_160L, RateLimit.delayMs(7_660, 1))
    assertEquals(1_000L, RateLimit.delayMs(100, 1))
    assertEquals(null, RateLimit.delayMs(3_600_000, 1))
  }

  @Test
  fun unfinishedTodos_atTheEnd_getOneReminder() = runBlocking {
    val todos =
      org.json.JSONArray()
        .put(JSONObject().put("content", "Créer index.html").put("status", "completed"))
        .put(JSONObject().put("content", "Lancer le serveur").put("status", "in_progress"))
    val s =
      setup(
        Model(
          mutableListOf<suspend () -> String>(
            { call("TodoWrite", JSONObject().put("todos", todos)) },
            { "C'est fini." },
            { "C'est fini, vraiment." },
          )
        ),
        Shell { _, _, _ -> ShellPort.Result(0, "", null, false) },
      )
    assertEquals("done", s.engine.runTurn("fais un site"))
    assertEquals(3, s.model.seen.size)
    val reminder = s.model.seen[2].last().content
    assertTrue(reminder, reminder.contains("Ta liste de tâches n'est pas à jour : 1 tâche(s) non terminée(s) (« Lancer le serveur »)"))
    // Once per turn only.
    assertTrue(s.journal.snapshot().filterIsInstance<ForgeEvent.Note>().count { it.kind == "taches" } == 1)
  }
}
