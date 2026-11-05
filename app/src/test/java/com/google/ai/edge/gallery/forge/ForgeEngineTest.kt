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
import com.google.ai.edge.gallery.forge.engine.ShellPort
import com.google.ai.edge.gallery.forge.journal.FeedItem
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.journal.SessionJournal
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeEngineTest {
  /** A model that answers from a script; it sees exactly what the engine sends. */
  private class ScriptedModel(private val answers: MutableList<String>, override val local: Boolean = false) : ModelPort {
    val seen = ArrayList<List<AgentMsg>>()
    var system = ""
    override val name = "test"
    override val historyBudgetChars = 200_000

    override suspend fun generate(system: String, messages: List<AgentMsg>, onChunk: (String) -> Unit): String {
      this.system = system
      seen.add(messages)
      val a = if (answers.isEmpty()) "Terminé." else answers.removeAt(0)
      onChunk(a)
      return a
    }

    override fun stop() {}
  }

  private class FakeShell : ShellPort {
    val commands = ArrayList<String>()

    override suspend fun ensureReady(onProgress: (String) -> Unit): String? = null

    override suspend fun run(command: String, wait: com.google.ai.edge.gallery.tools.CommandWait.Policy, onOutput: (String) -> Unit): ShellPort.Result {
      commands.add(command)
      return ShellPort.Result(0, "ok: $command", "/root/projets/demo", false)
    }

    override fun startJob(command: String) = "job1"

    override fun checkJob(id: String, stop: Boolean, maxChars: Int) = mapOf("job_id" to id, "state" to "running", "new_output" to "", "status" to "succeeded")
  }

  private class FakeUser(
    var approval: ApprovalAnswer = ApprovalAnswer(ApprovalAnswer.Choice.OUI),
    var plan: PlanReview = PlanReview(PlanReview.Outcome.VALIDE),
  ) : ForgeInteractions {
    val asked = ArrayList<ApprovalRequest>()

    override suspend fun approve(request: ApprovalRequest): ApprovalAnswer {
      asked.add(request)
      return approval
    }

    override suspend fun ask(callId: String, question: String, options: List<String>) = options.firstOrNull()

    override suspend fun reviewPlan(callId: String, markdown: String) = plan
  }

  private fun call(name: String, args: JSONObject) =
    "<tool_call>\n" + JSONObject().put("name", name).put("arguments", args).toString() + "\n</tool_call>"

  private class Setup(val root: File, val engine: ForgeEngine, val model: ScriptedModel, val user: FakeUser, val shell: FakeShell, val journal: SessionJournal)

  private fun setup(answers: List<String>, mode: String = Preset.DEMANDER.mode.key(), user: FakeUser = FakeUser(), limits: ForgeLimits? = null): Setup {
    val root = Files.createTempDirectory("forge-engine").toFile()
    val journal = SessionJournal(File(Files.createTempDirectory("forge-j").toFile(), "s.jsonl"))
    journal.append(ForgeEvent.SessionStart("s", "demo", "test", mode, ""))
    val model = ScriptedModel(answers.toMutableList())
    val shell = FakeShell()
    val ctx = object : ForgeContext {
      override fun systemPrompt() = "Consignes de test"
    }
    val engine =
      ForgeEngine(journal, model, ProjectPaths(root, "/root/projets/demo"), shell, user, ctx, limits ?: ForgeLimits.forModel(model))
    return Setup(root, engine, model, user, shell, journal)
  }

  @Test
  fun readThenEdit_withApproval_thenAnswer() = runBlocking {
    val s =
      setup(
        listOf(
          call("Read", JSONObject().put("file_path", "a.js")),
          call("Edit", JSONObject().put("file_path", "a.js").put("old_string", "1").put("new_string", "2")),
          "J'ai remplacé 1 par 2.",
        )
      )
    File(s.root, "a.js").writeText("const x = 1;\n")
    assertEquals("done", s.engine.runTurn("remplace 1 par 2"))
    assertEquals("const x = 2;\n", File(s.root, "a.js").readText())
    // Demander: the edit was asked, with its diff.
    assertEquals(1, s.user.asked.size)
    assertTrue(s.user.asked[0].isDiff)
    assertTrue(s.user.asked[0].detail.contains("+\tconst x = 2;"))
    // The tools block is in the system prompt; the result reached the model.
    assertTrue(s.model.system.contains("\"Read\""))
    assertTrue(s.model.seen[1].last().content.contains("<tool_response>"))
    val state = s.engine.state.value
    assertFalse(state.turnOpen)
    assertEquals(listOf("a.js"), state.lastTurnFiles)
    assertEquals("+1 −1", state.feed.filterIsInstance<FeedItem.ToolCard>().last().result!!.summary)
  }

  @Test
  fun refusal_givesTheReasonToTheModel() = runBlocking {
    val user = FakeUser(approval = ApprovalAnswer(ApprovalAnswer.Choice.NON, "utilise pnpm"))
    val s = setup(listOf(call("Bash", JSONObject().put("command", "npm install")), "D'accord."), user = user)
    s.engine.runTurn("installe")
    assertTrue(s.shell.commands.isEmpty())
    val toModel = s.model.seen[1].last().content
    assertTrue(toModel, toModel.contains("utilise pnpm"))
    assertTrue(toModel.contains("\"status\":\"refused\""))
  }

  @Test
  fun stopFromApproval_endsTheTurn() = runBlocking {
    val user = FakeUser(approval = ApprovalAnswer(ApprovalAnswer.Choice.ARRETER))
    val s = setup(listOf(call("Bash", JSONObject().put("command", "make")), "jamais lu"), user = user)
    assertEquals("stopped", s.engine.runTurn("compile"))
    assertEquals(1, s.model.seen.size)
  }

  @Test
  fun planMode_refusesEdits_untilThePlanIsValidated() = runBlocking {
    val s =
      setup(
        listOf(
          call("Write", JSONObject().put("file_path", "src/a.js").put("content", "x")),
          call("ExitPlanMode", JSONObject().put("plan", "# Plan\n1. Créer src/a.js\n2. Tester")),
          call("Write", JSONObject().put("file_path", "src/a.js").put("content", "x")),
          "Fait.",
        ),
        mode = Preset.PLAN.mode.key(),
      )
    s.engine.runTurn("crée a.js")
    // The first Write was refused (Plan), the second one ran after the validation.
    val results = s.journal.snapshot().filterIsInstance<ForgeEvent.ToolResult>()
    assertEquals("refused", results[0].status)
    assertEquals("ok", results[2].status)
    assertEquals("x", File(s.root, "src/a.js").readText())
    // The plan reminder was given, the validated plan saved, the mode back to Demander.
    assertTrue(s.model.seen[0][0].content.contains("Mode Plan"))
    assertEquals(Preset.DEMANDER.mode, s.engine.mode)
    assertTrue(File(s.root, ".altrex/plans").listFiles()!!.any { it.name.endsWith("-plan.md") })
  }

  @Test
  fun exitPlanMode_outsidePlan_isAnError_andRejectStops() = runBlocking {
    val s = setup(listOf(call("ExitPlanMode", JSONObject().put("plan", "# Un plan assez long pour passer")), "ok"))
    s.engine.runTurn("x")
    assertEquals("error", s.journal.snapshot().filterIsInstance<ForgeEvent.ToolResult>().single().status)

    val user = FakeUser(plan = PlanReview(PlanReview.Outcome.REFUSE))
    val p = setup(listOf(call("ExitPlanMode", JSONObject().put("plan", "# Un plan assez long pour passer")), "jamais"), mode = Preset.PLAN.mode.key(), user = user)
    assertEquals("stopped", p.engine.runTurn("x"))
    assertTrue(p.engine.mode.isPlan)
  }

  @Test
  fun sameCallEightTimes_stopsTheTurn() = runBlocking {
    val read = call("Glob", JSONObject().put("pattern", "*.js"))
    val s = setup(List(10) { read })
    assertEquals("blocked", s.engine.runTurn("cherche"))
    val results = s.journal.snapshot().filterIsInstance<ForgeEvent.ToolResult>()
    assertEquals(8, results.size)
    assertEquals("refused", results[4].status)
    assertTrue(results[2].modelText.contains("Rappel de Forge"))
  }

  @Test
  fun stepLimit_forcesAFinalAnswer() = runBlocking {
    val limits = ForgeLimits(maxSteps = 2, maxTokens = 1_000_000, maxDurationMs = 60_000, maxResultChars = 5_000, readLines = 100)
    val s =
      setup(
        listOf(call("Glob", JSONObject().put("pattern", "a")), call("Glob", JSONObject().put("pattern", "b")), "Voilà où j'en suis."),
        limits = limits,
      )
    assertEquals("limit", s.engine.runTurn("x"))
    assertTrue(s.model.seen.last().last().content.contains("Limite d'étapes"))
  }

  @Test
  fun todos_andQuestions_areJournaled() = runBlocking {
    val todos = org.json.JSONArray().put(JSONObject().put("content", "Lire").put("status", "in_progress"))
    val s =
      setup(
        listOf(
          call("TodoWrite", JSONObject().put("todos", todos)),
          call("AskUserQuestion", JSONObject().put("question", "Quel framework ?").put("options", org.json.JSONArray().put("React").put("Vue"))),
          "Je pars sur React.",
        )
      )
    s.engine.runTurn("fais un site")
    val st = s.engine.state.value
    assertEquals("Lire", st.todos.single().content)
    assertEquals("React", st.feed.filterIsInstance<FeedItem.Question>().single().answer)
  }

  @Test
  fun bash_inAutoMode_isAsked_andRunsInTheShell() = runBlocking {
    val s = setup(listOf(call("Bash", JSONObject().put("command", "npm test")), "Tests verts."), mode = Preset.EDITIONS_AUTO.mode.key())
    s.engine.runTurn("teste")
    assertEquals(listOf("npm test"), s.shell.commands)
    assertEquals(1, s.user.asked.size)
    val r = s.journal.snapshot().filterIsInstance<ForgeEvent.ToolResult>().single()
    assertEquals("code 0", r.summary)
    assertTrue(JSONObject(r.modelText).getString("output").contains("npm test"))
  }

  @Test
  fun twoEngines_doNotShareState() = runBlocking {
    val a = setup(listOf("A"))
    val b = setup(listOf("B"), mode = Preset.PLAN.mode.key())
    a.engine.runTurn("1")
    b.engine.runTurn("1")
    assertEquals(Preset.DEMANDER.mode, a.engine.mode)
    assertTrue(b.engine.mode.isPlan)
    assertEquals("A", a.engine.state.value.feed.filterIsInstance<FeedItem.AssistantText>().single().text)
    assertEquals("B", b.engine.state.value.feed.filterIsInstance<FeedItem.AssistantText>().single().text)
  }
}
