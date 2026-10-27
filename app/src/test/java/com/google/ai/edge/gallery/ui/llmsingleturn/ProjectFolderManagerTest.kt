package com.google.ai.edge.gallery.ui.llmsingleturn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFolderManagerTest {
  @Test
  fun looksBinary_text() {
    assertFalse(ProjectFolderManager.looksBinary("server {\n  listen 443 ssl;\n}\n".toByteArray()))
    assertFalse(ProjectFolderManager.looksBinary("Sécurité : déjà vérifié ✓".toByteArray()))
    assertFalse(ProjectFolderManager.looksBinary(ByteArray(0)))
  }

  @Test
  fun looksBinary_binary() {
    // ZIP / APK header with NUL bytes, and Latin-1 bytes that are not UTF-8.
    assertTrue(ProjectFolderManager.looksBinary(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
    assertTrue(ProjectFolderManager.looksBinary(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
  }

  @Test
  fun looksBinary_cutInsideACharacter() {
    // 8 KB of text whose last character ("é", 2 bytes) is cut by the 8 KB window.
    val text = "a".repeat(8 * 1024 - 1) + "é" + "fin"
    assertFalse(ProjectFolderManager.looksBinary(text.toByteArray()))
  }
}
