package com.google.ai.edge.gallery.tools

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [ALTREX] Stops a process and everything it started, fast and without blocking the caller.
 *
 * Why: a Linux command runs as PRoot → sh → apk → (scripts…). Killing PRoot alone could leave its
 * programs alive (an `apk add` kept the package database locked, and its children kept the output
 * pipe open, so the reader never saw the end). Here the whole tree is found from /proc (same user:
 * readable on Android too), asked to stop (SIGTERM), then killed (SIGKILL) after a short grace, on
 * a thread of its own: the coroutine that asked returns at once (the Stop button must answer in
 * well under 2 s).
 */
object ProcessTree {
  const val SIGKILL = 9
  const val SIGTERM = 15

  /** Pids found when the process was started ([startTracked]), for runtimes that hide them. */
  private val known = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Process, Int>())

  /** Process id of [p]: noted at its start, else `pid()` (JVM), else the `pid` field (Android); null if none works. */
  fun pidOf(p: Process): Int? {
    known[p]?.let { return it }
    runCatching { return (Process::class.java.getMethod("pid").invoke(p) as Number).toInt() }
    var c: Class<*>? = p.javaClass
    while (c != null && c != Any::class.java) {
      runCatching {
        val f = c.getDeclaredField("pid")
        f.isAccessible = true
        return (f.get(p) as Number).toInt()
      }
      c = c.superclass
    }
    return null
  }

  /**
   * Starts [pb] and notes the new process's id, found as the child of this app that was not there
   * before (so it is known even where reflection is refused). Starts are serialized for that.
   */
  fun startTracked(pb: ProcessBuilder): Process {
    if (!onAndroid) return pb.start()
    synchronized(known) {
      val self = selfPid()
      val before = if (self != null) childrenOf(self) else emptySet()
      val p = pb.start()
      if (self != null && pidOf(p) == null) {
        (childrenOf(self) - before).singleOrNull()?.let { known[p] = it }
      }
      return p
    }
  }

  private fun selfPid(): Int? = runCatching { File("/proc/self").canonicalFile.name.toInt() }.getOrNull()

  private fun childrenOf(pid: Int): Set<Int> = runCatching { parents().filterValues { it == pid }.keys }.getOrDefault(emptySet())

  /** Parent of each process listed in [proc] (the `ppid` of /proc/<pid>/stat). */
  internal fun parents(proc: File = File("/proc")): Map<Int, Int> {
    val out = HashMap<Int, Int>()
    for (d in proc.listFiles().orEmpty()) {
      val pid = d.name.toIntOrNull() ?: continue
      val stat = runCatching { File(d, "stat").readText() }.getOrNull() ?: continue
      parseParent(stat)?.let { out[pid] = it }
    }
    return out
  }

  /** The ppid in a /proc/<pid>/stat line: after the command name (which may hold spaces and parentheses). */
  internal fun parseParent(stat: String): Int? {
    val end = stat.lastIndexOf(')')
    if (end < 0) return null
    val fields = stat.substring(end + 1).trim().split(' ')
    return fields.getOrNull(1)?.toIntOrNull()
  }

  /** Every descendant of [pid], deepest first (children are stopped before their parents). */
  fun descendants(pid: Int, parents: Map<Int, Int> = parents()): List<Int> {
    val children = HashMap<Int, MutableList<Int>>()
    for ((c, pp) in parents) children.getOrPut(pp) { ArrayList() }.add(c)
    val out = ArrayList<Int>()
    fun walk(p: Int, depth: Int) {
      if (depth > 64) return
      for (c in children[p].orEmpty()) {
        if (c == pid || c in out) continue
        walk(c, depth + 1)
        out.add(c)
      }
    }
    walk(pid, 0)
    return out
  }

  private val onAndroid: Boolean =
    System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true) ||
      System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)

  /** Sends [sig] to [pids] (same user only, as the system allows). */
  fun signal(pids: Collection<Int>, sig: Int) {
    if (pids.isEmpty()) return
    if (onAndroid) {
      for (pid in pids) runCatching { android.os.Process.sendSignal(pid, sig) }
      return
    }
    // JVM (unit tests): the system's kill.
    runCatching {
      val p = ProcessBuilder(listOf("kill", "-$sig") + pids.map { it.toString() }).redirectErrorStream(true).start()
      p.inputStream.readBytes()
      p.waitFor(2, TimeUnit.SECONDS)
    }
  }

  private fun alive(pid: Int): Boolean = File("/proc/$pid").exists() && runCatching {
    // A zombie (state Z) is already dead: only its parent's wait is missing.
    val stat = File("/proc/$pid/stat").readText()
    stat.substring(stat.lastIndexOf(')') + 1).trim().firstOrNull() != 'Z'
  }.getOrDefault(false)

  /**
   * Stops [process] and its whole tree: SIGTERM now (PRoot then stops what it traces), SIGKILL
   * after [graceMs] for whatever is left, and the pipes closed. Returns at once; the rest happens
   * on a daemon thread. [onDead] runs there once the process has ended.
   */
  fun kill(process: Process, graceMs: Long = 700, onDead: () -> Unit = {}) {
    val pid = pidOf(process)
    val tree = pid?.let { runCatching { descendants(it) }.getOrDefault(emptyList()) }.orEmpty()
    signal(tree, SIGTERM)
    process.destroy()
    Thread {
        try {
          if (!process.waitFor(graceMs, TimeUnit.MILLISECONDS)) process.destroyForcibly()
          // Children whose parent died are not found again from the parent: the list made first counts.
          val left = tree.filter { alive(it) } + pid?.let { p -> runCatching { descendants(p) }.getOrDefault(emptyList()) }.orEmpty()
          signal(left.distinct(), SIGKILL)
          process.waitFor(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
          process.destroyForcibly()
        } finally {
          closeStreams(process)
          runCatching(onDead)
        }
      }
      .apply {
        isDaemon = true
        name = "altrex-kill"
      }
      .start()
  }

  /** [kill], then waits up to [waitMs] for the end (for callers that must know it is gone). */
  fun killAndWait(process: Process, waitMs: Long = 2_500): Boolean {
    kill(process)
    return runCatching { process.waitFor(waitMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
  }

  fun closeStreams(process: Process) {
    runCatching { process.outputStream.close() }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
  }

  /**
   * Processes of this app found in [proc] whose environment holds [marker] (a variable the Linux
   * gives all its programs) and whose command line runs one of [programs], outside [keep] (the
   * processes still followed, and their trees). What a killed command left behind: an apk that
   * still holds the package database lock.
   */
  fun strays(marker: String, programs: Set<String>, keep: Set<Int>, proc: File = File("/proc")): List<Int> {
    val parents = runCatching { parents(proc) }.getOrDefault(emptyMap())
    val kept = HashSet<Int>()
    for (k in keep) {
      kept.add(k)
      kept.addAll(descendants(k, parents))
    }
    val self = runCatching { File(proc, "self").canonicalFile.name.toInt() }.getOrNull()
    val out = ArrayList<Int>()
    for (pid in parents.keys) {
      if (pid in kept || pid == self) continue
      val env = runCatching { File(proc, "$pid/environ").readBytes() }.getOrNull() ?: continue
      if (!String(env, Charsets.ISO_8859_1).split('\u0000').contains(marker)) continue
      val argv = runCatching { String(File(proc, "$pid/cmdline").readBytes(), Charsets.UTF_8) }.getOrNull() ?: continue
      if (argv.split('\u0000').any { it.substringAfterLast('/') in programs }) out.add(pid)
    }
    return out
  }
}
