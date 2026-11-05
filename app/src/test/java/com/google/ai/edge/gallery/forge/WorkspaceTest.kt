package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.forge.engine.Preset
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.workspace.ForgeWorkspace
import com.google.ai.edge.gallery.forge.workspace.ProjectImporter
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {
  private fun ws() = ForgeWorkspace(Files.createTempDirectory("forge-ws").toFile()).apply { ensure() }

  @Test
  fun names_areCheckedAndSlugged() {
    assertNull(ForgeWorkspace.checkName("mon-site"))
    assertNotNull(ForgeWorkspace.checkName("Mon Site"))
    assertNotNull(ForgeWorkspace.checkName("../x"))
    assertNotNull(ForgeWorkspace.checkName(""))
    assertEquals("mon-site-vitrine", ForgeWorkspace.slug("Mon Site Vitrine !"))
    assertEquals("ete-cafe", ForgeWorkspace.slug("Été café"))
    val w = ws()
    w.create("demo").getOrThrow()
    assertEquals("demo-2", w.freeName("Démo"))
    assertTrue(w.create("demo").isFailure)
    assertEquals("/root/projets/demo", w.paths("demo").linuxPath)
  }

  @Test
  fun projects_kindBranchAndTrust() {
    val w = ws()
    w.create("site").getOrThrow()
    val dir = File(w.projectsDir, "site")
    File(dir, "package.json").writeText("{\"dependencies\":{\"next\":\"15\"}}")
    File(dir, ".git").mkdirs()
    File(dir, ".git/HEAD").writeText("ref: refs/heads/feat/auth\n")
    val info = w.list().single()
    assertEquals("Next.js", info.kind)
    assertEquals("feat/auth", info.branch)
    assertTrue(info.trusted)
    w.registerImported("site", ForgeWorkspace.ORIGIN_CLONED)
    assertFalse(w.info("site").trusted)
    assertEquals(ForgeWorkspace.ORIGIN_CLONED, w.info("site").origin)
    w.setTrusted("site", true)
    assertTrue(w.info("site").trusted)
    assertTrue(w.delete("site"))
    assertTrue(w.list().isEmpty())
  }

  @Test
  fun sessions_areListedWithProgress() {
    val w = ws()
    w.create("api").getOrThrow()
    val j = w.newSession("api", "Qwen", Preset.DEMANDER.mode.key())
    j.append(ForgeEvent.TurnStart(1))
    j.append(ForgeEvent.User(1, "Authentification JWT"))
    j.append(ForgeEvent.Todos(1, listOf(ForgeEvent.Todo("a", "completed"), ForgeEvent.Todo("b", "pending"))))
    j.append(ForgeEvent.TurnEnd(1, "done"))
    // An empty session (nothing asked) is not listed.
    w.newSession("api", "Qwen", Preset.DEMANDER.mode.key(), now = 1L)
    val s = w.sessions().single()
    assertEquals("Authentification JWT", s.title)
    assertEquals("done", s.lastEnd)
    assertEquals(1, s.todosDone)
    assertEquals(2, s.todosTotal)
    assertNotNull(w.openSession("api", s.id))
  }

  @Test
  fun import_copiesTheTree_skipsDependenciesAndLinks() {
    val src = Files.createTempDirectory("forge-src").toFile()
    File(src, "src").mkdirs()
    File(src, "src/a.js").writeText("a")
    File(src, "node_modules/x").mkdirs()
    File(src, "node_modules/x/i.js").writeText("dep")
    File(src, ".git").mkdirs()
    File(src, ".git/HEAD").writeText("ref: refs/heads/main\n")
    val outside = Files.createTempFile("secret", ".txt").toFile().apply { writeText("clé") }
    Files.createSymbolicLink(File(src, "lien.txt").toPath(), outside.toPath())
    val target = Files.createTempDirectory("forge-dst").toFile()
    val r = ProjectImporter.copyTree(ProjectImporter.FileNode(src), target)
    assertEquals("a", File(target, "src/a.js").readText())
    assertTrue(File(target, ".git/HEAD").isFile)
    assertFalse(File(target, "node_modules").exists())
    assertFalse(File(target, "lien.txt").exists())
    assertEquals(2, r.files)
    assertFalse(r.truncated)
  }

  @Test
  fun clone_urlsAreChecked() {
    assertNull(ProjectImporter.checkRepoUrl("https://github.com/moi/mon-site.git"))
    assertNotNull(ProjectImporter.checkRepoUrl("http://github.com/moi/x"))
    assertNotNull(ProjectImporter.checkRepoUrl("https://github.com/moi/x --upload-pack=evil"))
    assertNotNull(ProjectImporter.checkRepoUrl("https://github.com/moi/x;rm"))
    assertEquals("mon-site", ProjectImporter.repoName("https://github.com/moi/mon-site.git/"))
  }
}
