package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.agent.AgentToolCall
import com.google.ai.edge.gallery.agent.ToolCalling
import com.google.ai.edge.gallery.forge.engine.LoopGuard
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.tools.ForgeToolSpecs
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import com.google.ai.edge.gallery.forge.tools.SearchTools
import com.google.ai.edge.gallery.forge.tools.TodoTool
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndTodoTest {
  private fun project(): Pair<File, SearchTools> {
    val root = Files.createTempDirectory("forge-search").toFile()
    File(root, "src/app").mkdirs()
    File(root, "src/app/page.tsx").writeText("export default function Page() {\n  return <form/>;\n}\n")
    File(root, "src/app/route.ts").writeText("// TODO envoyer\nexport async function POST() {}\n")
    File(root, "README.md").writeText("# Démo\nTODO: écrire\n")
    File(root, "node_modules/x").mkdirs()
    File(root, "node_modules/x/index.js").writeText("// TODO dans une dépendance\n")
    return root to SearchTools(ProjectPaths(root, "/root/projets/demo"))
  }

  @Test
  fun glob_patterns() {
    assertTrue(SearchTools.globToRegex("**/*.ts").matcher("src/app/route.ts").matches())
    assertTrue(SearchTools.globToRegex("**/*.ts").matcher("a.ts").matches())
    assertTrue(SearchTools.globToRegex("src/**/*.{ts,tsx}").matcher("src/app/page.tsx").matches())
    assertFalse(SearchTools.globToRegex("src/*.ts").matcher("src/app/route.ts").matches())
    assertTrue(SearchTools.globToRegex("file?.[jt]s").matcher("file1.js").matches())
  }

  @Test
  fun glob_findsFiles_skipsDependencies() {
    val (_, s) = project()
    val all = JSONObject(s.glob("**/*", null).modelText)
    val files = all.getJSONArray("files").let { a -> (0 until a.length()).map { a.getString(it) } }
    assertTrue(files.contains("src/app/page.tsx"))
    assertFalse(files.any { it.startsWith("node_modules") })
    // Without '/': on the name, at any depth.
    val ts = JSONObject(s.glob("*.ts", null).modelText)
    assertEquals(1, ts.getInt("count"))
    assertEquals(1, JSONObject(s.glob("*.js", "node_modules").modelText).getInt("count"))
  }

  @Test
  fun grep_modes() {
    val (_, s) = project()
    val files = JSONObject(s.grep(SearchTools.GrepArgs("TODO")).modelText)
    assertEquals(2, files.getInt("count"))
    val content = JSONObject(s.grep(SearchTools.GrepArgs("todo", outputMode = "content", caseInsensitive = true, glob = "*.ts")).modelText)
    assertEquals("src/app/route.ts:1:// TODO envoyer", content.getString("lines"))
    val count = JSONObject(s.grep(SearchTools.GrepArgs("function", outputMode = "count")).modelText)
    assertEquals(2, count.getInt("matches"))
    val ctx = JSONObject(s.grep(SearchTools.GrepArgs("form", path = "src/app/page.tsx", outputMode = "content", context = 1)).modelText)
    assertEquals("src/app/page.tsx-1-export default function Page() {\nsrc/app/page.tsx:2:  return <form/>;\nsrc/app/page.tsx-3-}", ctx.getString("lines"))
    assertTrue(JSONObject(s.grep(SearchTools.GrepArgs("(")).modelText).getString("error").contains("invalide"))
  }

  @Test
  fun todo_strictList() {
    val ok = TodoTool.parse(JSONArray("""[{"content":"Lire","status":"completed"},{"content":"Écrire","status":"in_progress","activeForm":"Écriture"},"Tester"]"""))
    assertTrue(ok is TodoTool.Parsed.Ok)
    val items = (ok as TodoTool.Parsed.Ok).items
    assertEquals(3, items.size)
    assertEquals("pending", items[2].status)
    assertTrue(TodoTool.parse(JSONArray("""[{"content":"a","status":"in_progress"},{"content":"b","status":"in_progress"}]""")) is TodoTool.Parsed.Fail)
    assertTrue(TodoTool.parse(JSONArray("""[{"content":"a","status":"fini"}]""")) is TodoTool.Parsed.Fail)
    assertTrue(TodoTool.parse(JSONArray("""[{"content":"a"},{"content":"A"}]""")) is TodoTool.Parsed.Fail)
    assertTrue(TodoTool.parse(null) is TodoTool.Parsed.Fail)
    assertTrue(TodoTool.completedOne(listOf(ForgeEvent.Todo("a", "in_progress")), listOf(ForgeEvent.Todo("a", "completed"))))
    assertFalse(TodoTool.completedOne(listOf(ForgeEvent.Todo("a", "completed")), listOf(ForgeEvent.Todo("a", "completed"))))
  }

  @Test
  fun toolSpecs_frozenNamesAndValidJson() {
    assertEquals(
      listOf("Read", "Write", "Edit", "Glob", "Grep", "Bash", "BashOutput", "KillShell", "TodoWrite", "ExitPlanMode", "AskUserQuestion"),
      ForgeToolSpecs.specs.map { it.name },
    )
    val prompt = ToolCalling.systemPrompt(ForgeToolSpecs.specs, "x")
    val lines = prompt.substringAfter("<tools>\n").substringBefore("</tools>").lines().filter { it.startsWith("{") }
    assertEquals(11, lines.size)
    for (l in lines) JSONObject(l)
    val todo = JSONObject(lines.first { it.contains("\"TodoWrite\"") })
    assertEquals("array", todo.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("todos").getString("type"))
    assertEquals("Read", ForgeToolSpecs.canonicalName("read_file"))
    assertEquals("TodoWrite", ForgeToolSpecs.canonicalName("todo_write"))
    assertEquals("BashOutput", ForgeToolSpecs.canonicalName("bash_output"))
    assertNull(ForgeToolSpecs.canonicalName("rm"))
  }

  @Test
  fun parsing_toolCallsFromModelText() {
    val text = "Je lis le fichier.\n<tool_call>\n{\"name\": \"Read\", \"arguments\": {\"file_path\": \"src/a.js\"}}\n</tool_call>"
    val calls = ToolCalling.parseToolCalls(text)
    assertEquals(1, calls.size)
    assertEquals("src/a.js", ForgeToolSpecs.str(calls[0].arguments, "file_path"))
    // A list sent as a JSON string is accepted.
    val a = JSONObject().put("todos", "[{\"content\":\"x\",\"status\":\"pending\"}]")
    assertEquals(1, ForgeToolSpecs.array(a, "todos")!!.length())
    assertEquals(5, ForgeToolSpecs.int(JSONObject().put("offset", "5"), "offset"))
    assertTrue(ForgeToolSpecs.bool(JSONObject().put("replace_all", "true"), "replace_all"))
  }

  private fun call(name: String, arg: String) = AgentToolCall(name, JSONObject().put("x", arg), "")

  @Test
  fun loopGuard_remindsAt3_refusesAt5_stopsAt8() {
    val g = LoopGuard()
    val verdicts = (1..8).map { g.beforeCall(call("Read", "a")).verdict }
    assertEquals(LoopGuard.Verdict.RUN, verdicts[1])
    assertEquals(LoopGuard.Verdict.RUN_WITH_REMINDER, verdicts[2])
    assertEquals(LoopGuard.Verdict.REFUSE, verdicts[4])
    assertEquals(LoopGuard.Verdict.STOP, verdicts[7])
    // Another call resets the count; TodoWrite does not count.
    g.beforeCall(call("Read", "b"))
    assertEquals(LoopGuard.Verdict.RUN, g.beforeCall(call("TodoWrite", "b")).verdict)
    assertEquals(LoopGuard.Verdict.RUN, g.beforeCall(call("Read", "b")).verdict)
  }

  @Test
  fun stallCounter_replansThenStops() {
    val g = LoopGuard()
    // Alternating A B A B without progress, plus errors.
    var action: LoopGuard.StallAction? = null
    var n = 0
    while (action == null && n < 20) {
      action = g.afterCall(call("Bash", if (n % 2 == 0) "a" else "b"), ok = false)
      n++
    }
    assertTrue(action is LoopGuard.StallAction.Replan)
    action = null
    n = 0
    while (action == null && n < 20) {
      action = g.afterCall(call("Bash", "c$n"), ok = false)
      n++
    }
    assertTrue(action is LoopGuard.StallAction.Stop)
  }

  @Test
  fun stallCounter_goesDownWithProgress_andMonologueCounts() {
    val g = LoopGuard()
    g.afterCall(call("Bash", "x"), ok = false)
    g.afterCall(call("Bash", "x2"), ok = false)
    assertEquals(2, g.stalls)
    g.afterCall(call("Read", "new"), ok = true)
    assertEquals(1, g.stalls)
    g.afterCall(call("TodoWrite", "t"), ok = true, completedTask = true)
    assertEquals(0, g.stalls)
    assertNull(g.afterText("je vais lire le fichier"))
    g.afterText("je vais lire le fichier")
    assertEquals(1, g.stalls)
  }
}
