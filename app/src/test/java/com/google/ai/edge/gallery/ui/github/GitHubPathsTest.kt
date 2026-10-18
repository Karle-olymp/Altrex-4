package com.google.ai.edge.gallery.ui.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPathsTest {
  @Test
  fun parsesTheUsualWaysOfNamingARepository() {
    val ref = RepoRef("octo", "hello-world")
    assertEquals(ref, GitHubPaths.parseRepo("octo/hello-world"))
    assertEquals(ref, GitHubPaths.parseRepo(" https://github.com/octo/hello-world.git "))
    assertEquals(ref, GitHubPaths.parseRepo("github.com/octo/hello-world/"))
    assertEquals(ref, GitHubPaths.parseRepo("https://github.com/octo/hello-world/tree/dev"))
    assertEquals(RepoRef("a", "lib.js"), GitHubPaths.parseRepo("a/lib.js"))
  }

  @Test
  fun rejectsWhatIsNotARepository() {
    assertNull(GitHubPaths.parseRepo("hello-world"))
    assertNull(GitHubPaths.parseRepo("octo/.."))
    assertNull(GitHubPaths.parseRepo("../octo/repo"))
    assertNull(GitHubPaths.parseRepo("octo/repo?x=1"))
    assertNull(GitHubPaths.parseRepo("https://github.com/octo/repo/issues/3"))
  }

  @Test
  fun branchNamesCannotEscapeTheUrl() {
    assertTrue(GitHubPaths.isValidRef("main"))
    assertTrue(GitHubPaths.isValidRef("feature/login-v2"))
    assertFalse(GitHubPaths.isValidRef("../../user"))
    assertFalse(GitHubPaths.isValidRef("main?access_token=x"))
    assertFalse(GitHubPaths.isValidRef("/main"))
  }

  @Test
  fun archiveEntriesLoseTheirTopFolderAndCannotLeaveTheTarget() {
    assertEquals("src/app.js", GitHubPaths.entryPath("octo-repo-1a2b3c/src/app.js"))
    assertEquals("README.md", GitHubPaths.entryPath("octo-repo-1a2b3c/README.md"))
    assertNull(GitHubPaths.entryPath("octo-repo-1a2b3c/"))
    assertNull(GitHubPaths.entryPath("octo-repo-1a2b3c/../../etc/passwd"))
    assertNull(GitHubPaths.entryPath("octo-repo-1a2b3c/src/../../x"))
    assertNull(GitHubPaths.entryPath("octo-repo-1a2b3c\\..\\x"))
    assertNull(GitHubPaths.entryPath("octo-repo-1a2b3c/a:b"))
    // A leading slash or doubled slashes only add empty segments: still inside the target.
    assertEquals("x/y", GitHubPaths.entryPath("/top//x/y"))
  }

  @Test
  fun folderNameAvoidsExistingFolders() {
    val repo = RepoRef("octo", "site")
    assertEquals("site", GitHubPaths.folderName(repo, setOf("notes")))
    assertEquals("site-3", GitHubPaths.folderName(repo, setOf("site", "site-2")))
    assertEquals("env", GitHubPaths.folderName(RepoRef("o", ".env"), emptySet()))
  }

  @Test
  fun importedFoldersCoverTheirFilesOnly() {
    val imported = setOf("site", "libs/vendor")
    assertTrue(GitHubPaths.isInside("site/index.html", imported))
    assertTrue(GitHubPaths.isInside("site", imported))
    assertTrue(GitHubPaths.isInside("libs/vendor/a.js", imported))
    assertFalse(GitHubPaths.isInside("site-2/index.html", imported))
    assertFalse(GitHubPaths.isInside("libs/own.js", imported))
  }
}
