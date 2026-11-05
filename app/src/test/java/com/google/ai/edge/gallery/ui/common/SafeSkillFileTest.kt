package com.google.ai.edge.gallery.ui.common

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The skill web pages may read the skills folder only, whatever path they ask for. */
class SafeSkillFileTest {
  private val files: File = Files.createTempDirectory("filesdir").toFile()

  private fun make(path: String, text: String = "x"): File =
    File(files, path).apply {
      parentFile.mkdirs()
      writeText(text)
    }

  @Test
  fun servesSkillFilesOnly() {
    make("skills/demo/scripts/index.html")
    make("datastore/user_data.pb")
    make("linux/agent/rootfs/root/secret.txt")
    assertNotNull(safeSkillFile(files, "/skills/demo/scripts/index.html"))
    assertNull(safeSkillFile(files, "/datastore/user_data.pb"))
    assertNull(safeSkillFile(files, "/linux/agent/rootfs/root/secret.txt"))
    assertNull(safeSkillFile(files, "/skills"))
    assertNull(safeSkillFile(files, "/skills/missing.js"))
  }

  @Test
  fun dotDotAndSymlinksDoNotEscape() {
    make("skills/demo/scripts/index.html")
    make("datastore/user_data.pb")
    assertNull(safeSkillFile(files, "/skills/demo/../../datastore/user_data.pb"))
    assertNull(safeSkillFile(files, "/skills/../datastore/user_data.pb"))
    // A symlink inside the skills folder pointing out of it.
    val link = File(files, "skills/demo/leak")
    Files.createSymbolicLink(link.toPath(), File(files, "datastore").toPath())
    assertNull(safeSkillFile(files, "/skills/demo/leak/user_data.pb"))
    assertEquals("index.html", safeSkillFile(files, "/skills/demo/scripts/index.html")?.name)
  }
}
