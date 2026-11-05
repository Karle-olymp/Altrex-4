package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.forge.tools.EditMatcher
import com.google.ai.edge.gallery.forge.tools.FileTools
import com.google.ai.edge.gallery.forge.tools.FileTracker
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import com.google.ai.edge.gallery.forge.tools.ToolOutcome
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileToolsTest {
  private lateinit var root: File
  private lateinit var paths: ProjectPaths
  private lateinit var tools: FileTools

  @Before
  fun setUp() {
    root = Files.createTempDirectory("forge-proj").toFile()
    paths = ProjectPaths(root, "/root/projets/demo")
    tools = FileTools(paths, FileTracker())
  }

  private fun commit(p: FileTools.Prepared): ToolOutcome =
    when (p) {
      is FileTools.Prepared.Failed -> p.outcome
      is FileTools.Prepared.Ready -> tools.commit(p.write)
    }

  private fun error(o: ToolOutcome) = JSONObject(o.modelText).optString("error")

  @Test
  fun paths_stayInsideTheProject() {
    assertEquals("src/a.js", paths.resolve("src/a.js")!!.rel)
    assertEquals("src/a.js", paths.resolve("/root/projets/demo/src/./a.js")!!.rel)
    assertEquals("a.js", paths.resolve("src/../a.js")!!.rel)
    assertEquals("", paths.resolve(".")!!.rel)
    assertNull(paths.resolve("../autre/a.js"))
    assertNull(paths.resolve("/etc/passwd"))
    assertNull(paths.resolve("/root/projets/autre/a.js"))
    assertNull(paths.resolve("~/secret"))
  }

  @Test
  fun aLinkLeadingOutside_isRefused() {
    val outside = Files.createTempDirectory("forge-outside").toFile()
    File(outside, "secret.txt").writeText("clé")
    Files.createSymbolicLink(File(root, "lien").toPath(), outside.toPath())
    assertNull(paths.resolve("lien/secret.txt"))
    val r = tools.read("lien/secret.txt", null, null)
    assertEquals(ToolOutcome.Status.ERROR, r.status)
    assertTrue(error(r).contains("hors du projet"))
  }

  @Test
  fun read_numbersLines_andPages() {
    File(root, "a.txt").writeText((1..10).joinToString("\n") { "ligne $it" } + "\n")
    val r = tools.read("a.txt", 3, 2)
    val j = JSONObject(r.modelText)
    assertEquals("3-4/10", j.getString("lines"))
    assertEquals("     3\tligne 3\n     4\tligne 4", j.getString("content"))
    assertTrue(j.getString("note").contains("offset=5"))
    assertEquals("10 l.", r.summary)
  }

  @Test
  fun write_newFile_withoutRead_isAllowed_andAtomic() {
    val o = commit(tools.prepareWrite("src/new.js", "a\nb\n"))
    assertEquals(ToolOutcome.Status.OK, o.status)
    assertEquals("a\nb\n", File(root, "src/new.js").readText())
    assertEquals(2, o.added)
    // No temporary file left behind.
    assertTrue(File(root, "src").listFiles()!!.all { !it.name.endsWith(".tmp") })
  }

  @Test
  fun write_existingFile_needsARead_andAFreshOne() {
    File(root, "a.txt").writeText("un\n")
    assertTrue(error(commit(tools.prepareWrite("a.txt", "deux\n"))).contains("pas encore lu"))
    tools.read("a.txt", null, null)
    // The user changes the file in the Terminal meanwhile.
    File(root, "a.txt").writeText("un modifié\n")
    assertTrue(error(commit(tools.prepareWrite("a.txt", "deux\n"))).contains("a changé"))
    tools.read("a.txt", null, null)
    assertEquals(ToolOutcome.Status.OK, commit(tools.prepareWrite("a.txt", "deux\n")).status)
    assertEquals("deux\n", File(root, "a.txt").readText())
    // After its own write the model may write again without reading.
    assertEquals(ToolOutcome.Status.OK, commit(tools.prepareWrite("a.txt", "trois\n")).status)
  }

  @Test
  fun edit_exact_unique_andShowsDiff() {
    File(root, "app.js").writeText("const a = 1;\nconst b = 2;\nconst c = 3;\n")
    assertTrue(error(commit(tools.prepareEdit("app.js", "const b = 2;", "const b = 20;", false))).contains("pas encore lu"))
    tools.read("app.js", null, null)
    val o = commit(tools.prepareEdit("app.js", "const b = 2;", "const b = 20;\nconst d = 4;", false))
    assertEquals(ToolOutcome.Status.OK, o.status)
    assertEquals("const a = 1;\nconst b = 20;\nconst d = 4;\nconst c = 3;\n", File(root, "app.js").readText())
    assertEquals("+2 −1", o.summary)
    assertTrue(o.detail.contains("2\t-\tconst b = 2;"))
    assertTrue(o.detail.contains("3\t+\tconst d = 4;"))
  }

  @Test
  fun edit_notFound_showsTheNearestRealLines() {
    File(root, "page.tsx").writeText("import x from 'y';\n\nfunction envoyer(d) {\n    return fetch('/api', d);\n}\n")
    tools.read("page.tsx", null, null)
    val o = commit(tools.prepareEdit("page.tsx", "function envoyer(d) {\n  return fetch('/api', d);\n}", "x", false))
    val msg = error(o)
    assertTrue(msg, msg.contains("introuvable"))
    assertTrue(msg, msg.contains("     3\tfunction envoyer(d) {"))
    assertTrue(msg, msg.contains("     4\t    return fetch('/api', d);"))
  }

  @Test
  fun edit_duplicates_needReplaceAll() {
    val m = EditMatcher.apply("x\nx\n", "x", "y", false)
    assertTrue(m is EditMatcher.Result.Fail && m.message.contains("2 fois"))
    assertEquals(EditMatcher.Result.Ok("y\ny\n", 2), EditMatcher.apply("x\nx\n", "x", "y", true))
  }

  @Test
  fun edit_keepsWindowsLineEnds() {
    assertEquals(EditMatcher.Result.Ok("a\r\nB\r\nc\r\n", 1), EditMatcher.apply("a\r\nb\r\nc\r\n", "a\nb", "a\nB", false))
  }

  @Test
  fun nearestLines_nullWhenNothingAlike() {
    assertNull(EditMatcher.nearestLines("aaaa\nbbbb\n", "zzzz qqqq"))
    assertNotNull(EditMatcher.nearestLines("hello world\n", "hello  world"))
  }

  @Test
  fun read_refusesBinaryAndFolders() {
    File(root, "img.bin").writeBytes(byteArrayOf(1, 0, 2))
    assertTrue(error(tools.read("img.bin", null, null)).contains("binaire"))
    File(root, "src").mkdirs()
    assertTrue(error(tools.read("src", null, null)).contains("dossier"))
    assertTrue(error(tools.read("absent.js", null, null)).contains("n'existe pas"))
    assertFalse(File(root, "absent.js").exists())
  }
}
