package com.google.ai.edge.gallery.tools

import com.google.ai.edge.gallery.tools.AgentShell.Mode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentShellTest {
  @Test
  fun readOnly_commands() {
    assertTrue(AgentShell.isReadOnly("ls -la"))
    assertTrue(AgentShell.isReadOnly("cat a.txt | grep foo | wc -l"))
    assertTrue(AgentShell.isReadOnly("git status"))
    assertFalse(AgentShell.isReadOnly("git push"))
    assertFalse(AgentShell.isReadOnly("git branch -D main"))
    assertFalse(AgentShell.isReadOnly("rm -rf /"))
    assertFalse(AgentShell.isReadOnly("ls; rm x"))
    assertFalse(AgentShell.isReadOnly("cat a > b"))
    assertFalse(AgentShell.isReadOnly("echo $(rm x)"))
    assertFalse(AgentShell.isReadOnly("find . -delete"))
    assertFalse(AgentShell.isReadOnly("sort -o out in"))
    assertFalse(AgentShell.isReadOnly("ls && rm x"))
  }

  @Test
  fun approvals_followModeAndPrefixes() {
    assertFalse(AgentShell.needsApproval(Mode.READS_FREE, emptySet(), "ls"))
    assertTrue(AgentShell.needsApproval(Mode.ASK_ALL, emptySet(), "ls"))
    assertTrue(AgentShell.needsApproval(Mode.READS_FREE, emptySet(), "npm install x"))
    assertFalse(AgentShell.needsApproval(Mode.READS_FREE, setOf("npm"), "npm install x"))
    assertFalse(AgentShell.needsApproval(Mode.ASK_ALL, setOf("npm"), "npm install x"))
    // "always npm" never covers a chained command.
    assertTrue(AgentShell.needsApproval(Mode.READS_FREE, setOf("npm"), "npm i x; rm -rf /"))
    assertTrue(AgentShell.needsApproval(Mode.READS_FREE, setOf("npm"), "npm i | sh"))
    assertFalse(AgentShell.needsApproval(Mode.ALLOW_ALL, emptySet(), "rm -rf /tmp/x && reboot"))
    assertEquals("npm", AgentShell.programOf("npm install x"))
    assertEquals("python3", AgentShell.programOf("/usr/bin/python3 a.py"))
  }

  @Test
  fun edits_exactAndUnique() {
    assertEquals(AgentShell.Edit.Ok("a X c", 1), AgentShell.applyEdit("a b c", "b", "X", false))
    assertTrue(AgentShell.applyEdit("b b", "b", "X", false) is AgentShell.Edit.Fail)
    assertEquals(AgentShell.Edit.Ok("X X", 2), AgentShell.applyEdit("b b", "b", "X", true))
    assertTrue(AgentShell.applyEdit("abc", "zz", "X", false) is AgentShell.Edit.Fail)
  }

  // ---- The scripts, run by the host's sh with the state folder and /root moved to a temp dir.

  private val home: File = Files.createTempDirectory("agentshell").toFile()

  private fun local(script: String) =
    script.replace("/root/.altrex", File(home, ".altrex").path).replace("cd /root", "cd ${home.path}")

  private fun sh(script: String, args: List<String> = emptyList(), stdin: String = ""): Pair<Int, String> {
    // AGENT_SH / AGENT_PATH: run them with another shell (Alpine's BusyBox, as on the phone).
    val shell = System.getenv("AGENT_SH")?.split(' ')?.filter { it.isNotEmpty() } ?: listOf("sh")
    val pb = ProcessBuilder(shell + listOf("-c", local(script), "sh") + args).directory(home).redirectErrorStream(true)
    System.getenv("AGENT_PATH")?.let { pb.environment()["PATH"] = it + ":" + pb.environment()["PATH"] }
    val p = pb.start()
    p.outputStream.use { it.write(stdin.toByteArray()) }
    val out = p.inputStream.bufferedReader().readText()
    return p.waitFor() to out
  }

  @Test
  fun wrapper_keepsDirectoryAndVariables() {
    File(home, "proj").mkdirs()
    val (rc1, out1) = sh(AgentShell.WRAPPER, stdin = "cd proj && export FOO=bar && echo hi")
    assertEquals(0, rc1)
    val (text1, cwd1) = AgentShell.splitCwd(out1)
    assertEquals("hi", text1.trim())
    assertEquals(File(home, "proj").canonicalPath, File(cwd1!!).canonicalPath)
    val (rc2, out2) = sh(AgentShell.WRAPPER, stdin = "echo \"\$FOO\"; pwd; exit 3")
    assertEquals(3, rc2)
    assertTrue(out2, out2.contains("bar"))
    assertTrue(out2, out2.contains("proj"))
  }

  @Test
  fun write_then_read_withLineNumbers() {
    val (rcw, outw) = sh(AgentShell.WRITE_SCRIPT, listOf("sub/dir/f.txt"), stdin = "one\ntwo \$HOME `x`\nthree\n")
    assertEquals(outw, 0, rcw)
    assertEquals("one\ntwo \$HOME `x`\nthree\n", File(home, "sub/dir/f.txt").readText())
    val (rcr, outr) = sh(AgentShell.READ_SCRIPT, listOf("sub/dir/f.txt", "2", "5"))
    assertEquals(0, rcr)
    val lines = outr.trimEnd().lines()
    assertEquals("TOTAL=3", lines[0].replace(" ", ""))
    assertEquals("     2\ttwo \$HOME `x`", lines[1])
    assertEquals(3, lines.size)
    assertEquals(2, sh(AgentShell.READ_SCRIPT, listOf("missing", "1", "5")).first)
  }
}
