package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.agent.AgentMsg
import com.google.ai.edge.gallery.forge.engine.ApprovalAnswer
import com.google.ai.edge.gallery.forge.engine.ApprovalRequest
import com.google.ai.edge.gallery.forge.engine.ConversationReuse
import com.google.ai.edge.gallery.forge.engine.ForgeContext
import com.google.ai.edge.gallery.forge.engine.ForgeEngine
import com.google.ai.edge.gallery.forge.engine.ForgeInteractions
import com.google.ai.edge.gallery.forge.engine.ForgeLimits
import com.google.ai.edge.gallery.forge.engine.ForgePrompts
import com.google.ai.edge.gallery.forge.engine.LightMode
import com.google.ai.edge.gallery.forge.engine.ModelPort
import com.google.ai.edge.gallery.forge.engine.PlanReview
import com.google.ai.edge.gallery.forge.engine.Preset
import com.google.ai.edge.gallery.forge.engine.ShellPort
import com.google.ai.edge.gallery.forge.journal.FeedItem
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.journal.SessionJournal
import com.google.ai.edge.gallery.forge.journal.SessionState
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import com.google.ai.edge.gallery.forge.workspace.ForgeSettings
import com.google.ai.edge.gallery.forge.workspace.ForgeWorkspace
import com.google.ai.edge.gallery.forge.workspace.ProjectContext
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2, phase B: compaction, light mode, conversation reuse, typing while the agent works, settings. */
class Step2ContextTest {
  private fun tmp(prefix: String) = Files.createTempDirectory(prefix).toFile()

  private class Model(
    private val answers: MutableList<String>,
    override val historyBudgetChars: Int = 200_000,
    override val local: Boolean = false,
    override val runtime: String = if (local) "litert" else "cloud",
    val onGenerate: (Int) -> Unit = {},
  ) : ModelPort {
    val seen = ArrayList<List<AgentMsg>>()
    val systems = ArrayList<String>()
    var summaries = 0
    override val name = "test"

    override suspend fun generate(system: String, messages: List<AgentMsg>, onChunk: (String) -> Unit): String {
      if (system.startsWith("Tu résumes")) {
        summaries++
        return "L'utilisateur veut lire long.txt. Trois lectures faites, rien de modifié. Reste : répondre."
      }
      systems.add(system)
      seen.add(messages)
      onGenerate(seen.size)
      return if (answers.isEmpty()) "Terminé." else answers.removeAt(0)
    }

    override fun stop() {}
  }

  private class Shell : ShellPort {
    override suspend fun ensureReady(onProgress: (String) -> Unit): String? = null

    override suspend fun run(command: String, wait: com.google.ai.edge.gallery.tools.CommandWait.Policy, onOutput: (String) -> Unit) = ShellPort.Result(0, "", null, false)

    override fun startJob(command: String) = "job1"

    override fun checkJob(id: String, stop: Boolean, maxChars: Int) = mapOf("status" to "succeeded")
  }

  private object User : ForgeInteractions {
    override suspend fun approve(request: ApprovalRequest) = ApprovalAnswer(ApprovalAnswer.Choice.OUI)

    override suspend fun ask(callId: String, question: String, options: List<String>) = options.firstOrNull()

    override suspend fun reviewPlan(callId: String, markdown: String) = PlanReview(PlanReview.Outcome.VALIDE)
  }

  private fun call(name: String, args: JSONObject) = "<tool_call>\n" + JSONObject().put("name", name).put("arguments", args).toString() + "\n</tool_call>"

  private fun engine(root: File, model: Model, light: Boolean = false): ForgeEngine {
    val journal = SessionJournal(File(tmp("forge-j"), "s.jsonl"))
    journal.append(ForgeEvent.SessionStart("s1", "demo", "test", Preset.TOUT_AUTORISER.mode.key(), ""))
    val ctx =
      object : ForgeContext {
        override fun systemPrompt() = "Consignes complètes"

        override fun lightPrompt() = "Consignes courtes"
      }
    return ForgeEngine(journal, model, ProjectPaths(root, "/root/projets/demo"), Shell(), User, ctx, ForgeLimits.forModel(model), light = light)
  }

  // ------------------------------------------------------------------------------- compaction

  @Test
  fun compaction_tombstonesThenSummary_journalIntact() = runBlocking {
    val root = tmp("forge-proj")
    File(root, "long.txt").writeText((1..400).joinToString("\n") { "ligne $it " + "x".repeat(30) } + "\n")
    fun read(o: Int) = call("Read", JSONObject().put("file_path", "long.txt").put("offset", o).put("limit", 90))
    val model = Model(mutableListOf(read(1), read(91), read(181), read(271), "Fini."), historyBudgetChars = 9_000)
    val e = engine(root, model)
    assertEquals("done", e.runTurn("lis long.txt en entier"))
    val events = e.state.value.let { _ -> File(tmp("x"), "y") }.let { _ -> null }
    val journal = SessionState.modelMessages(emptyList(), 1)
    assertTrue(journal.isEmpty())
    // Level 1: old Read results became tombstones in what the model saw.
    val later = model.seen.last().joinToString("\n") { it.content }
    assertTrue(later, later.contains("retiré du contexte") || later.contains("Résumé de la conversation"))
    // Level 2 was asked once the tombstones were not enough; the request stays visible.
    assertTrue(model.summaries >= 1)
    assertTrue(later.contains("Résumé de la conversation") && later.contains("lis long.txt en entier"))
    // The journal keeps every result whole.
    val results = e.state.value.feed.filterIsInstance<FeedItem.ToolCard>().map { it.result!! }
    assertEquals(4, results.size)
    assertTrue(results.all { it.modelText.contains("ligne") })
    assertTrue(events == null)
  }

  @Test
  fun tombstone_inHistory_withRecoverableTrace() {
    val ev =
      listOf(
        ForgeEvent.SessionStart("s1", "demo", "m", "PROJET/DEMANDER", ""),
        ForgeEvent.TurnStart(1),
        ForgeEvent.User(1, "demande"),
        ForgeEvent.Assistant(1, "<tool_call>…</tool_call>", ""),
        ForgeEvent.ToolCall(1, "c1-1", "Bash", "{}"),
        ForgeEvent.ToolResult(1, "c1-1", "Bash", "ok", "{\"output\":\"" + "y".repeat(2000) + "\"}"),
        ForgeEvent.Assistant(1, "<tool_call>…</tool_call>", ""),
        ForgeEvent.ToolCall(1, "c1-2", "Read", "{}"),
        ForgeEvent.ToolResult(1, "c1-2", "Read", "ok", "{\"content\":\"zzz\"}", path = "a.js"),
        ForgeEvent.Compaction(1, listOf("c1-1", "c1-2")),
      )
    val text = SessionState.modelMessages(ev, 1).joinToString("\n") { it.content }
    assertFalse(text.contains("yyyy"))
    assertTrue(text.contains(".altrex/sorties/s1-c1-1.txt"))
    assertTrue(text.contains("Relis a.js avec Read"))
    // A summary stands for the first events; the request of the current turn is kept in it.
    val withSummary = ev + ForgeEvent.Summary(1, 6, "résumé court")
    val msgs = SessionState.modelMessages(withSummary, 1)
    assertEquals("user", msgs.first().role)
    assertTrue(msgs.first().content.contains("résumé court") && msgs.first().content.contains("demande"))
    assertEquals("assistant", msgs[1].role)
  }

  // ------------------------------------------------------------------------------- light mode

  @Test
  fun lightMode_shortPromptAndSevenTools() = runBlocking {
    val ws = ForgeWorkspace(tmp("forge-ws")).apply { ensure() }
    ws.create("demo")
    val ctx = ProjectContext(ws, "demo", { "Gemma 4 E4B" })
    val system = ctx.lightPrompt() + "\n\n" + ForgePrompts.LIGHT_TOOLS
    // Under 600 tokens (3 characters per token, pessimistic for French).
    assertTrue("${system.length} caractères", system.length / 3 < 600)
    for (t in listOf("Read", "Write", "Edit", "Bash", "Glob", "Grep", "TodoWrite")) assertTrue(t, ForgePrompts.LIGHT_TOOLS.contains("$t("))
    assertFalse(ForgePrompts.LIGHT_TOOLS.contains("WebFetch"))
    // The engine sends that prompt, and the full one otherwise.
    val m = Model(mutableListOf("Bonjour."), local = true)
    engine(tmp("p"), m, light = true).runTurn("salut")
    assertTrue(m.systems[0].startsWith("Consignes courtes") && m.systems[0].contains("Read(file_path"))
    val full = Model(mutableListOf("Bonjour."))
    engine(tmp("p"), full).runTurn("salut")
    assertTrue(full.systems[0].contains("\"name\":\"WebSearch\""))
  }

  @Test
  fun lightMode_decisionAndLimits() {
    val litert = Model(mutableListOf(), historyBudgetChars = 64_000, local = true, runtime = "litert")
    val bigLlama = Model(mutableListOf(), historyBudgetChars = 64_000, local = true, runtime = "llamacpp")
    val smallLlama = Model(mutableListOf(), historyBudgetChars = 16_000, local = true, runtime = "llamacpp")
    val cloud = Model(mutableListOf())
    assertTrue(LightMode.decide(litert, ForgeSettings.Light.AUTO))
    assertFalse(LightMode.decide(bigLlama, ForgeSettings.Light.AUTO))
    assertTrue(LightMode.decide(smallLlama, ForgeSettings.Light.AUTO))
    assertTrue(LightMode.decide(bigLlama, ForgeSettings.Light.ON))
    assertFalse(LightMode.decide(litert, ForgeSettings.Light.OFF))
    assertFalse(LightMode.decide(cloud, ForgeSettings.Light.ON))
    val l = ForgeLimits.forModel(litert, true, ForgeSettings(maxStepsLocal = 30, maxMinutes = 10))
    assertEquals(30, l.maxSteps)
    assertEquals(10 * 60_000L, l.maxDurationMs)
    assertEquals(1_500, l.maxResultChars)
    assertEquals(40, ForgeLimits.forModel(cloud, false, ForgeSettings()).maxSteps)
  }

  @Test
  fun settings_roundTrip() {
    val ws = ForgeWorkspace(tmp("forge-ws")).apply { ensure() }
    assertEquals(ForgeSettings(), ForgeSettings.load(ws))
    val s = ForgeSettings(ForgeSettings.Light.OFF, 50, 12, 20)
    ForgeSettings.save(ws, s)
    assertEquals(s, ForgeSettings.load(ws))
    File(ws.base, "reglages.json").writeText("""{"light":"ON","maxStepsLocal":99999}""")
    assertEquals(ForgeSettings.MAX_STEPS, ForgeSettings.load(ws).maxStepsLocal)
  }

  @Test
  fun conversationReuse_onlyWhenTheHistoryOnlyGrew() {
    val held = listOf(AgentMsg("user", "demande"), AgentMsg("assistant", ConversationReuse.normalize("<think>hm</think>  appel ")))
    assertEquals("appel", held[1].content)
    assertTrue(ConversationReuse.canContinue("S", held, "S", held + AgentMsg("user", "résultats")))
    assertFalse(ConversationReuse.canContinue("S", held, "S2", held + AgentMsg("user", "résultats")))
    assertFalse(ConversationReuse.canContinue("S", held, "S", listOf(AgentMsg("user", "demande changée"), held[1], AgentMsg("user", "r"))))
    assertFalse(ConversationReuse.canContinue("S", held, "S", held + AgentMsg("user", "a") + AgentMsg("assistant", "b") + AgentMsg("user", "c")))
    assertFalse(ConversationReuse.canContinue(null, null, "S", held))
  }

  // ------------------------------------------------------------------------------- steering

  @Test
  fun steering_reachesTheModelAtTheNextStep() = runBlocking {
    val root = tmp("forge-proj")
    File(root, "a.txt").writeText("a\n")
    lateinit var e: ForgeEngine
    val model = Model(mutableListOf(call("Read", JSONObject().put("file_path", "a.txt")), "Fait, en bleu."), onGenerate = { n -> if (n == 1) e.steer("plutôt en bleu") })
    e = engine(root, model)
    e.runTurn("change la couleur")
    assertTrue(model.seen[1].last().content.contains("Message de l'utilisateur pendant ton travail : « plutôt en bleu »"))
    assertTrue(e.state.value.feed.any { it is FeedItem.Note && it.kind == SessionState.ORIENTATION && it.text == "plutôt en bleu" })
    // Typed while the final answer was being written: the agent still reads it before ending.
    val m2 = Model(mutableListOf("Je termine.", "D'accord, j'ajoute le test."), onGenerate = { n -> if (n == 1) e.steer("ajoute un test") })
    e = engine(root, m2)
    e.runTurn("finis")
    assertEquals(2, m2.seen.size)
  }
}
