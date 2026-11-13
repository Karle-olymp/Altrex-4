package com.google.ai.edge.gallery.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `pkg` / `apt` helpers, run by the host's sh with a fake `apk` that prints its arguments. */
class PkgScriptTest {
  private fun run(script: String, vararg args: String): Pair<Int, String> {
    val bin = Files.createTempDirectory("pkg").toFile()
    File(bin, "apk").apply { writeText("#!/bin/sh\necho \"apk \$*\"\n"); setExecutable(true) }
    File(bin, "pkg").apply { writeText(LinuxEnv.PKG_SCRIPT); setExecutable(true) }
    File(bin, "apt").apply { writeText(LinuxEnv.APT_SCRIPT); setExecutable(true) }
    val pb = ProcessBuilder(listOf(File(bin, script).path) + args).redirectErrorStream(true)
    pb.environment()["PATH"] = bin.path + ":/usr/bin:/bin"
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText().trim()
    val code = p.waitFor()
    bin.deleteRecursively()
    return code to out
  }

  @Test
  fun installAndRemoveMapToApk() {
    assertEquals(0 to "apk add python3 git", run("pkg", "install", "python3", "git"))
    assertEquals(0 to "apk del vim", run("pkg", "uninstall", "vim"))
    assertEquals(0 to "apk search curl", run("pkg", "search", "curl"))
    assertEquals(0 to "apk list --installed", run("pkg", "list-installed"))
  }

  @Test
  fun upgradeUpdatesFirst() {
    assertEquals(0 to "apk update\napk upgrade", run("pkg", "upgrade"))
  }

  @Test
  fun aptGoesThroughPkg() {
    assertEquals(0 to "apk add nmap", run("apt", "install", "nmap"))
  }

  @Test
  fun helpAndUnknown() {
    val help = run("pkg", "help")
    assertEquals(0, help.first)
    assertTrue(help.second.contains("pkg install"))
    assertEquals(1, run("pkg").first)
    val unknown = run("pkg", "frobnicate")
    assertEquals(1, unknown.first)
    assertTrue(unknown.second.contains("frobnicate"))
  }

  @Test
  fun installWithoutPackageExplains() {
    val r = run("pkg", "install")
    assertEquals(1, r.first)
    assertTrue(r.second.contains("Usage"))
  }

  @Test
  fun setupScriptIsValidShell() {
    val f = Files.createTempFile("setup", ".sh").toFile()
    f.writeText(LinuxEnv.SETUP_SCRIPT)
    val p = ProcessBuilder("sh", "-n", f.path).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    assertEquals(out, 0, p.waitFor())
    f.delete()
    assertTrue(LinuxEnv.SETUP_SCRIPT.contains("apk add bash"))
  }
}
