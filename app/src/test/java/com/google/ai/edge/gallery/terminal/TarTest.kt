package com.google.ai.edge.gallery.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TarTest {
  @Test
  fun parsePax_readsPathAndLink() {
    val path = "path=usr/lib/a-very-long-name.so\n"
    val link = "linkpath=../../lib/libc.musl-aarch64.so.1\n"
    fun record(s: String): String {
      // "<length> <key>=<value>\n", the length counting itself.
      var len = s.length + 3
      if ((len.toString().length + 1 + s.length) != len) len = len.toString().length + 1 + s.length
      return "$len $s"
    }
    val pax = Tar.parsePax(record(path) + record(link))
    assertEquals("usr/lib/a-very-long-name.so", pax["path"])
    assertEquals("../../lib/libc.musl-aarch64.so.1", pax["linkpath"])
  }

  @Test
  fun parseOctal_readsTarNumbers() {
    val field = "0000644\u0000".toByteArray()
    assertEquals(420L, Tar.parseOctal(field, 0, 8))
    val size = "00000012345 ".toByteArray()
    assertEquals(5349L, Tar.parseOctal(size, 0, 12))
  }
}

/** Extraction on the JVM: real files, symbolic links through java.nio. */
class TarExtractTest {
  private val fs =
    object : Tar.Fs {
      override fun symlink(target: String, path: String) {
        java.nio.file.Files.createSymbolicLink(java.nio.file.Paths.get(path), java.nio.file.Paths.get(target))
      }

      override fun chmod(path: String, mode: Int) {}
    }

  private fun tmpDir(): java.io.File = java.nio.file.Files.createTempDirectory("tar").toFile()

  @Test
  fun extract_alpineLikeArchive() {
    val out = tmpDir()
    javaClass.getResourceAsStream("/tar/sample.tar")!!.use { Tar.extract(it, out, fs) }
    assertEquals("NAME=\"Alpine Linux\"\n", java.io.File(out, "etc/os-release").readText())
    assertEquals("NAME=\"Alpine Linux\"\n", java.io.File(out, "etc/os-release.copy").readText())
    assertEquals(1L, java.io.File(out, "usr/lib/" + "a".repeat(120) + ".so").length())
    val link = java.nio.file.Paths.get(out.path, "usr/lib/libc.so")
    org.junit.Assert.assertTrue(java.nio.file.Files.isSymbolicLink(link))
    assertEquals("../../lib/ld-musl-aarch64.so.1", java.nio.file.Files.readSymbolicLink(link).toString())
  }

  @Test
  fun safeFile_rootAndEscapes() {
    val root = tmpDir().canonicalFile
    assertEquals(root, Tar.safeFile(root, "./"))
    assertEquals(root, Tar.safeFile(root, "."))
    assertEquals(java.io.File(root, "etc"), Tar.safeFile(root, "./etc/"))
    for (bad in listOf("../evil", "./../../evil", "/etc/passwd", "a/../../evil", "..")) {
      try {
        Tar.safeFile(root, bad)
        org.junit.Assert.fail("not refused: $bad")
      } catch (e: java.io.IOException) {
        // expected
      }
    }
  }

  /** The real Alpine archive, when ALPINE_TGZ points to it (checked locally, not in CI). */
  @Test
  fun extract_realAlpine_whenAvailable() {
    val path = System.getenv("ALPINE_TGZ") ?: return
    val out = tmpDir()
    java.util.zip.GZIPInputStream(java.io.File(path).inputStream().buffered()).use { Tar.extract(it, out, fs) }
    org.junit.Assert.assertTrue(java.io.File(out, "bin/busybox").exists())
    org.junit.Assert.assertTrue(java.io.File(out, "etc/alpine-release").readText().startsWith("3.24"))
    println("ALPINE OK: " + out.walkTopDown().count() + " entries in " + out)
  }
}
