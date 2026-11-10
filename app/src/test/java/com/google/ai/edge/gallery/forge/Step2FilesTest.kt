package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.agent.AgentMsg
import com.google.ai.edge.gallery.forge.checkpoints.Checkpoints
import com.google.ai.edge.gallery.forge.checkpoints.CopySnapshotStore
import com.google.ai.edge.gallery.forge.checkpoints.GitRunner
import com.google.ai.edge.gallery.forge.checkpoints.GitSnapshotStore
import com.google.ai.edge.gallery.forge.checkpoints.SnapshotStore
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
import com.google.ai.edge.gallery.forge.journal.SessionState
import com.google.ai.edge.gallery.forge.tools.Mentions
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import com.google.ai.edge.gallery.forge.tools.ToolOutcome
import com.google.ai.edge.gallery.forge.web.HtmlText
import com.google.ai.edge.gallery.forge.web.HttpWeb
import com.google.ai.edge.gallery.forge.web.UrlGuard
import com.google.ai.edge.gallery.forge.web.WebPort
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** Step 2, phase A: snapshots and /undo, MultiEdit, @fichier, conflicts, WebFetch guard. */
class Step2FilesTest {
  private fun tmp(prefix: String) = Files.createTempDirectory(prefix).toFile()

  // ------------------------------------------------------------------------------- snapshots

  private fun undoScenario(store: SnapshotStore, root: File) = runBlocking {
    File(root, "a.txt").writeText("un\n")
    File(root, "garde.txt").writeText("à moi\n")
    val pre = store.snapshot("avant")
    // The agent's turn: changes a.txt and garde.txt, creates b.txt.
    File(root, "a.txt").writeText("deux\n")
    File(root, "garde.txt").writeText("agent\n")
    File(root, "b.txt").writeText("nouveau\n")
    val post = store.snapshot("après")
    val changes = store.changes(pre, post)
    assertEquals(listOf("a.txt" to "M", "b.txt" to "A", "garde.txt" to "M"), changes.map { it.path to it.kind }.sortedBy { it.first })
    assertEquals(1, changes.first { it.path == "a.txt" }.added)
    // Meanwhile the user changes garde.txt himself.
    File(root, "garde.txt").writeText("l'utilisateur\n")
    val cp = Checkpoints(store)
    val plan = cp.planUndo(1, pre, post)
    assertEquals(listOf("a.txt", "b.txt"), plan.restore.map { it.path }.sorted())
    assertEquals(listOf("garde.txt"), plan.conflicts)
    val r = cp.applyUndo(plan)
    assertEquals("un\n", File(root, "a.txt").readText())
    assertFalse(File(root, "b.txt").exists())
    // Never touched: what the user changed himself.
    assertEquals("l'utilisateur\n", File(root, "garde.txt").readText())
    assertEquals(listOf("garde.txt"), r.skipped)
    // The backup taken before /undo still has the agent's version.
    assertEquals("deux\n", String(store.content(r.backup, "a.txt")!!))
  }

  @Test
  fun copyStore_undoIsProtectedAndTransactional() {
    val root = tmp("forge-proj")
    undoScenario(CopySnapshotStore(root, tmp("forge-snap")), root)
  }

  @Test
  fun copyStore_skipsHeavyFoldersAndAltrexOutputs() = runBlocking {
    val root = tmp("forge-proj")
    val store = CopySnapshotStore(root, tmp("forge-snap"))
    File(root, "node_modules/x").mkdirs()
    File(root, "node_modules/x/i.js").writeText("lourd")
    File(root, ".altrex/sorties").mkdirs()
    File(root, ".altrex/sorties/o.txt").writeText("sortie")
    File(root, ".altrex/plans").mkdirs()
    File(root, ".altrex/plans/p.md").writeText("# plan")
    val a = store.snapshot("a")
    File(root, "node_modules/x/i.js").writeText("autre")
    File(root, ".altrex/plans/p.md").writeText("# plan 2")
    val b = store.snapshot("b")
    assertEquals(listOf(".altrex/plans/p.md"), store.changes(a, b).map { it.path })
  }

  @Test
  fun undo_failureRestoresTheBackup() = runBlocking {
    val root = tmp("forge-proj")
    val real = CopySnapshotStore(root, tmp("forge-snap"))
    var failNext = false
    val store =
      object : SnapshotStore by real {
        override suspend fun restore(rev: String, paths: List<String>) {
          if (failNext) {
            failNext = false
            // Half done, then a failure.
            File(root, "a.txt").writeText("cassé\n")
            throw java.io.IOException("disque plein")
          }
          real.restore(rev, paths)
        }
      }
    File(root, "a.txt").writeText("un\n")
    val pre = store.snapshot("avant")
    File(root, "a.txt").writeText("deux\n")
    val post = store.snapshot("après")
    val cp = Checkpoints(store)
    val plan = cp.planUndo(1, pre, post)
    failNext = true
    val e = runCatching { cp.applyUndo(plan) }.exceptionOrNull()
    assertNotNull(e)
    assertTrue(e!!.message!!.contains("rien n'a été changé"))
    assertEquals("deux\n", File(root, "a.txt").readText())
  }

  /** git of the computer, on a shadow GIT_DIR: the project's own repository is never touched. */
  private class HostGit(private val root: File, private val gitDir: File) : GitRunner {
    override suspend fun git(args: List<String>): GitRunner.Out {
      val pb = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true)
      pb.environment()["GIT_DIR"] = gitDir.path
      pb.environment()["GIT_WORK_TREE"] = root.path
      pb.environment()["LC_ALL"] = "C"
      val p = pb.start()
      val out = p.inputStream.bufferedReader().readText()
      return GitRunner.Out(p.waitFor(), out)
    }
  }

  private fun hasGit() = runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

  @Test
  fun gitShadowStore_undoWithoutTouchingTheUsersRepo() {
    Assume.assumeTrue(hasGit())
    val root = tmp("forge-git")
    // The user's own repository, with one commit.
    fun userGit(vararg a: String) = ProcessBuilder(listOf("git", "-c", "user.name=u", "-c", "user.email=u@x", *a)).directory(root).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() }
    userGit("init", "-q")
    File(root, "readme.md").writeText("x\n")
    userGit("add", "-A")
    userGit("commit", "-q", "-m", "init")
    val head = File(root, ".git/HEAD").readText()
    val logBefore = ProcessBuilder("git", "log", "--oneline").directory(root).start().inputStream.bufferedReader().readText()
    val shadow = File(tmp("forge-shadow"), "demo.git")
    undoScenario(GitSnapshotStore(HostGit(root, shadow), root, shadow), root)
    assertEquals(head, File(root, ".git/HEAD").readText())
    assertEquals(logBefore, ProcessBuilder("git", "log", "--oneline").directory(root).start().inputStream.bufferedReader().readText())
  }

  // ------------------------------------------------------------------------------- engine

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

  private class Shell(val root: File) : ShellPort {
    override suspend fun ensureReady(onProgress: (String) -> Unit): String? = null

    override suspend fun run(command: String, wait: com.google.ai.edge.gallery.tools.CommandWait.Policy, onOutput: (String) -> Unit): ShellPort.Result {
      if (command.startsWith("touch ")) File(root, command.removePrefix("touch ").trim()).writeText("")
      return ShellPort.Result(0, "", "/root/projets/demo", false)
    }

    override fun startJob(command: String) = "job1"

    override fun checkJob(id: String, stop: Boolean, maxChars: Int) = mapOf("status" to "succeeded")
  }

  private class User(val onApprove: () -> Unit = {}) : ForgeInteractions {
    val asked = ArrayList<ApprovalRequest>()

    override suspend fun approve(request: ApprovalRequest): ApprovalAnswer {
      asked.add(request)
      onApprove()
      return ApprovalAnswer(ApprovalAnswer.Choice.OUI)
    }

    override suspend fun ask(callId: String, question: String, options: List<String>) = options.firstOrNull()

    override suspend fun reviewPlan(callId: String, markdown: String) = PlanReview(PlanReview.Outcome.VALIDE)
  }

  private fun call(name: String, args: JSONObject) = "<tool_call>\n" + JSONObject().put("name", name).put("arguments", args).toString() + "\n</tool_call>"

  private fun engine(root: File, answers: List<String>, mode: Preset = Preset.TOUT_AUTORISER, user: User = User(), snapshots: SnapshotStore? = null, web: WebPort? = null): Pair<ForgeEngine, Model> {
    val journal = SessionJournal(File(tmp("forge-j"), "s.jsonl"))
    journal.append(ForgeEvent.SessionStart("s", "demo", "test", mode.mode.key(), ""))
    val model = Model(answers.toMutableList())
    val ctx = object : ForgeContext {
      override fun systemPrompt() = "Consignes"
    }
    return ForgeEngine(journal, model, ProjectPaths(root, "/root/projets/demo"), Shell(root), user, ctx, ForgeLimits.forModel(model), snapshots = snapshots, web = web) to model
  }

  @Test
  fun engine_takesSnapshotsAroundChanges_andListsBashFiles() = runBlocking {
    val root = tmp("forge-proj")
    File(root, "a.js").writeText("const x = 1;\n")
    val store = CopySnapshotStore(root, tmp("forge-snap"))
    val (e, _) =
      engine(
        root,
        listOf(
          call("Read", JSONObject().put("file_path", "a.js")),
          call("Edit", JSONObject().put("file_path", "a.js").put("old_string", "1").put("new_string", "2")),
          call("Bash", JSONObject().put("command", "touch made.txt")),
          "Fait.",
        ),
        snapshots = store,
      )
    assertEquals("done", e.runTurn("change"))
    val st = e.state.value
    val cp = st.checkpoints.single()
    assertNotNull(cp.post)
    assertEquals(listOf("a.js", "made.txt"), cp.files.sorted())
    assertEquals(listOf("a.js", "made.txt"), st.lastTurnFiles.sorted())
    assertEquals(1, st.checkpoints.size)
    // A turn that only reads takes no snapshot.
    val (e2, _) = engine(root, listOf(call("Read", JSONObject().put("file_path", "a.js")), "Lu."), snapshots = store)
    e2.runTurn("lis")
    assertTrue(e2.state.value.checkpoints.isEmpty())
    // /undo puts a.js back and removes made.txt.
    val cps = Checkpoints(store)
    val r = cps.applyUndo(cps.planUndo(cp.turn, cp.pre, cp.post))
    e.record(ForgeEvent.Undo(cp.turn, r.restored, r.skipped, r.backup, r.after))
    assertEquals("const x = 1;\n", File(root, "a.js").readText())
    assertFalse(File(root, "made.txt").exists())
    assertNull(e.state.value.undoable)
  }

  @Test
  fun multiEdit_allOrNothing() = runBlocking {
    val root = tmp("forge-proj")
    File(root, "a.js").writeText("let a = 1;\nlet b = 2;\n")
    val edits = JSONArray().put(JSONObject().put("old_string", "a = 1").put("new_string", "a = 10")).put(JSONObject().put("old_string", "b = 2").put("new_string", "b = 20"))
    val bad = JSONArray().put(JSONObject().put("old_string", "a = 10").put("new_string", "a = 11")).put(JSONObject().put("old_string", "introuvable").put("new_string", "x"))
    val (e, m) =
      engine(
        root,
        listOf(
          call("Read", JSONObject().put("file_path", "a.js")),
          call("MultiEdit", JSONObject().put("file_path", "a.js").put("edits", edits)),
          call("MultiEdit", JSONObject().put("file_path", "a.js").put("edits", bad)),
          "Fait.",
        ),
      )
    e.runTurn("change")
    assertEquals("let a = 10;\nlet b = 20;\n", File(root, "a.js").readText())
    val results = e.state.value.feed.filterIsInstance<FeedItem.ToolCard>().filter { it.name == "MultiEdit" }.map { it.result!! }
    assertEquals("ok", results[0].status)
    assertEquals(JSONObject(results[0].modelText).getInt("edits"), 2)
    assertEquals("error", results[1].status)
    assertTrue(results[1].modelText.contains("Remplacement n° 2 sur 2"))
    assertTrue(m.seen.isNotEmpty())
    assertEquals(listOf("a.js"), e.state.value.lastTurnFiles)
  }

  @Test
  fun fileChangedDuringApproval_isNeverOverwritten() = runBlocking {
    val root = tmp("forge-proj")
    val f = File(root, "a.js").apply { writeText("v1\n") }
    // While the approval card waits, the user edits the file in the Terminal.
    val user = User(onApprove = { f.writeText("v1 modifié par moi\n") })
    val (e, _) =
      engine(
        root,
        listOf(call("Read", JSONObject().put("file_path", "a.js")), call("Write", JSONObject().put("file_path", "a.js").put("content", "v2\n")), "Fait."),
        mode = Preset.DEMANDER,
        user = user,
      )
    e.runTurn("écris")
    assertEquals("v1 modifié par moi\n", f.readText())
    val st = e.state.value
    assertTrue(st.feed.any { it is FeedItem.Note && it.kind == "conflit" })
    assertTrue(st.feed.filterIsInstance<FeedItem.ToolCard>().last().result!!.modelText.contains("Conflit"))
  }

  @Test
  fun mentions_fuzzyAndExpansion() = runBlocking {
    val root = tmp("forge-proj")
    File(root, "src/components").mkdirs()
    File(root, "src/components/Button.tsx").writeText("export const B = 1;\n")
    File(root, "src/app.ts").writeText("import x\n")
    File(root, "node_modules/z").mkdirs()
    File(root, "node_modules/z/Button.js").writeText("")
    val files = Mentions.listFiles(root)
    assertFalse(files.any { it.startsWith("node_modules") })
    assertEquals("src/components/Button.tsx", Mentions.fuzzy("btn", files).first())
    assertEquals(listOf("src/app.ts", "src/components/Button.tsx"), Mentions.extract("regarde @src/app.ts et @src/components/Button.tsx, merci"))
    assertTrue(Mentions.extract("mail a@b.fr").isEmpty())
    // The engine gives the content with the request, and the file counts as read (Edit at once).
    val (e, m) =
      engine(
        root,
        listOf(call("Edit", JSONObject().put("file_path", "src/app.ts").put("old_string", "import x").put("new_string", "import y")), "Fait."),
      )
    e.runTurn("corrige @src/app.ts")
    assertTrue(m.seen[0].last().content.contains("<fichier path=\"src/app.ts\" lignes=\"1-1/1\">"))
    assertEquals("import y\n", File(root, "src/app.ts").readText())
  }

  // ------------------------------------------------------------------------------- web

  @Test
  fun urlGuard_publicHttpsOnly() {
    val pub: (String) -> List<InetAddress> = { listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))) }
    assertNull(UrlGuard.check("https://example.com/page", pub))
    assertNotNull(UrlGuard.check("http://example.com/", pub))
    assertNotNull(UrlGuard.check("file:///etc/passwd", pub))
    assertNotNull(UrlGuard.check("https://localhost/", pub))
    assertNotNull(UrlGuard.check("https://user:pw@example.com/", pub))
    assertNotNull(UrlGuard.check("https://example.com:8443/", pub))
    for (ip in listOf("127.0.0.1", "10.1.2.3", "192.168.1.1", "172.20.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1")) {
      val addr = InetAddress.getByName(ip)
      assertNotNull(ip, UrlGuard.check("https://evil.example/", { listOf(addr) }))
    }
    assertFalse(UrlGuard.isPrivate(InetAddress.getByName("8.8.8.8")))
  }

  @Test
  fun htmlText_extractsReadableText() {
    val page = HtmlText.extract("<html><head><title>Doc &amp; API</title><style>p{}</style></head><body><script>alert(1)</script><h1>Titre</h1><p>Un&nbsp;paragraphe &eacute;crit.</p><ul><li>a</li><li>b</li></ul></body></html>")
    assertEquals("Doc & API", page.title)
    assertFalse(page.text.contains("alert"))
    assertTrue(page.text.contains("Un paragraphe écrit."))
    assertTrue(page.text.contains("- a"))
    val (ex, cut) = HtmlText.excerpt("intro inutile\n\n" + "bla ".repeat(300) + "\n\nla configuration du serveur nginx est ici", "configuration nginx", 120)
    assertTrue(cut)
    assertTrue(ex.contains("nginx"))
    assertEquals(2, HttpWeb.parseBrave("""{"web":{"results":[{"title":"A <b>x</b>","url":"https://a","description":"d"},{"title":"B","url":"https://b","description":""}]}}""").size)
  }

  @Test
  fun webTools_goThroughThePort() = runBlocking {
    val root = tmp("forge-proj")
    val web =
      object : WebPort {
        override suspend fun fetch(url: String, prompt: String?, maxChars: Int) = ToolOutcome(ToolOutcome.Status.OK, ToolOutcome.json("url" to url, "content" to "texte", "status" to "ok"), summary = "page")

        override suspend fun search(query: String, maxChars: Int) = ToolOutcome.error(HttpWeb.NO_KEY)
      }
    val (e, m) = engine(root, listOf(call("WebFetch", JSONObject().put("url", "https://example.com")), call("WebSearch", JSONObject().put("query", "kotlin")), "Fait."), web = web)
    e.runTurn("cherche")
    val last = m.seen.last().last().content
    assertTrue(last.contains("Brave Search"))
    assertTrue(m.seen[1].last().content.contains("\"content\":\"texte\""))
  }
}
