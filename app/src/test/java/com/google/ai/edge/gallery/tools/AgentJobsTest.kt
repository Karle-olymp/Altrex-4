package com.google.ai.edge.gallery.tools

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Foreground commands as jobs (Forge's Bash), with real processes of the computer (sh, sleep): a
 * blocking command is cancelled fast with its whole tree, a silent one goes to the background,
 * a talkative one is followed to its end.
 */
class AgentJobsTest {
  private fun host(script: String): Process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()

  private fun alive(pid: Int): Boolean {
    val stat = File("/proc/$pid/stat")
    if (!stat.exists()) return false
    val s = runCatching { stat.readText() }.getOrNull() ?: return false
    return s.substring(s.lastIndexOf(')') + 1).trim().firstOrNull() != 'Z'
  }

  private fun waitDead(pids: List<Int>, ms: Long): Boolean {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
      if (pids.none { alive(it) }) return true
      Thread.sleep(50)
    }
    return pids.none { alive(it) }
  }

  @Test
  fun blockingCommand_isCancelledFast_withItsWholeTree() = runBlocking {
    // Like PRoot → sh → apk: a parent, a child in the background, a grandchild that holds the pipe.
    val p = host("sleep 300 & sh -c 'sleep 300' & wait")
    val job = AgentJobs.register(p, "apk add nodejs", visible = false, stdin = null)
    val waiting = async(Dispatchers.Default) { AgentJobs.await(job, CommandWait.Policy(idleMs = 600_000, capMs = 1_800_000)) }
    delay(400)
    val pid = ProcessTree.pidOf(p)!!
    val tree = ProcessTree.descendants(pid)
    assertTrue("des enfants : $tree", tree.size >= 2)
    val t0 = System.currentTimeMillis()
    waiting.cancel()
    try {
      waiting.await()
      fail("annulation attendue")
    } catch (e: CancellationException) {
      // Expected.
    }
    val took = System.currentTimeMillis() - t0
    assertTrue("annulé en $took ms", took < 2_000)
    assertTrue("l'arbre doit être tué", waitDead(tree + pid, 3_000))
    assertTrue(AgentJobs.list(), !AgentJobs.list().contains(job.id))
  }

  @Test
  fun silentCommand_goesToTheBackground_thenBashOutputAndKillShell() = runBlocking {
    val p = host("echo debut; sleep 300")
    val job = AgentJobs.register(p, "python3 -m http.server 3000", visible = false, stdin = null)
    val w = withTimeout(10_000) { AgentJobs.await(job, CommandWait.Policy(idleMs = 500, capMs = 60_000)) }
    assertTrue(w.toString(), w is AgentJobs.Wait.Detached)
    w as AgentJobs.Wait.Detached
    assertEquals(CommandWait.Verdict.IDLE, w.reason)
    assertTrue(w.jobId.startsWith("job"))
    assertTrue(w.output, w.output.contains("debut"))
    // Still running; what the model already has is not given again.
    val check = AgentJobs.check(w.jobId, stop = false, maxChars = 1_000)
    assertEquals("running", check["state"])
    assertEquals("(no new output)", check["new_output"])
    val kill = AgentJobs.check(w.jobId, stop = true, maxChars = 1_000)
    assertEquals("succeeded", kill["status"])
    assertTrue(kill.toString(), kill["state"] == "stopped" || kill["state"] == "stopping")
    assertTrue(waitDead(listOf(ProcessTree.pidOf(p)!!), 3_000))
  }

  @Test
  fun talkativeCommand_isFollowedToTheEnd_andTheCeilingDetaches() = runBlocking {
    val p = host("for i in 1 2 3 4 5 6 7 8; do echo ligne \$i; sleep 0.15; done; exit 3")
    val job = AgentJobs.register(p, "make", visible = false, stdin = null)
    // 0.4 s of silence allowed, the command writes every 0.15 s for 1.2 s: followed to its end.
    val w = withTimeout(10_000) { AgentJobs.await(job, CommandWait.Policy(idleMs = 400, capMs = 60_000)) }
    assertTrue(w.toString(), w is AgentJobs.Wait.Done)
    w as AgentJobs.Wait.Done
    assertEquals(3, w.exitCode)
    assertTrue(w.output, w.output.contains("ligne 8"))

    val p2 = host("while true; do echo encore; sleep 0.1; done")
    val job2 = AgentJobs.register(p2, "npm run build", visible = false, stdin = null)
    val w2 = withTimeout(10_000) { AgentJobs.await(job2, CommandWait.Policy(idleMs = 60_000, capMs = 500)) }
    assertTrue(w2 is AgentJobs.Wait.Detached)
    assertEquals(CommandWait.Verdict.CAP, (w2 as AgentJobs.Wait.Detached).reason)
    AgentJobs.check(w2.jobId, stop = true, maxChars = 100)
    Unit
  }

  @Test
  fun commandFromStdin_andTheShellsReportLine_isNotShown() = runBlocking {
    val p = ProcessBuilder("sh").redirectErrorStream(true).start()
    val job = AgentJobs.register(p, "echo bonjour; printf '\\036ALTREX_CWD=/root/projets/x\\n'", visible = false)
    val w = withTimeout(10_000) { AgentJobs.await(job, CommandWait.Policy(5_000, 60_000)) } as AgentJobs.Wait.Done
    assertTrue(w.output.contains("bonjour"))
    assertEquals("bonjour", AgentJobs.clean(w.output).trim())
  }

  @Test
  fun procParsing_andStrays() {
    assertEquals(42, ProcessTree.parseParent("123 (a b) c)) S 42 123 123 0"))
    assertEquals(null, ProcessTree.parseParent("garbage"))
    val parents = mapOf(2 to 1, 3 to 2, 4 to 3, 5 to 1, 6 to 99)
    assertEquals(listOf(4, 3, 2, 5), ProcessTree.descendants(1, parents))

    // A fake /proc: 10 = an apk of the model's Linux left alone, 11 = the same in the user's
    // terminal, 12 = an apk still followed (descendant of 20), 13 = not apk.
    val proc = Files.createTempDirectory("proc").toFile()
    fun ps(pid: Int, ppid: Int, env: String, argv: String) {
      val d = File(proc, "$pid").apply { mkdirs() }
      File(d, "stat").writeText("$pid (x) S $ppid 0 0")
      File(d, "environ").writeText(env.replace(' ', '\u0000'))
      File(d, "cmdline").writeText(argv.replace(' ', '\u0000'))
    }
    ps(10, 1, "HOME=/root ALTREX_ENV=agent", "/sbin/apk add nodejs")
    ps(11, 1, "ALTREX_ENV=user", "apk add git")
    ps(20, 1, "ALTREX_ENV=agent", "/bin/sh -c x")
    ps(12, 20, "ALTREX_ENV=agent", "apk update")
    ps(13, 1, "ALTREX_ENV=agent", "sleep 10")
    assertEquals(listOf(10), ProcessTree.strays("ALTREX_ENV=agent", setOf("apk"), keep = setOf(20), proc = proc))
  }

  @Test
  fun pidOf_works() {
    val p = host("sleep 5")
    try {
      assertNotNull(ProcessTree.pidOf(p))
    } finally {
      assertTrue(ProcessTree.killAndWait(p))
      assertFalse(p.isAlive)
    }
  }
}
