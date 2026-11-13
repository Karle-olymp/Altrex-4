package com.google.ai.edge.gallery.tools

import android.content.Context
import com.google.ai.edge.gallery.terminal.LinuxEnv
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [ALTREX] Commands the model runs in the background (a server, a long install, a build): each in
 * its own PRoot process, kept while the app runs. Their output is kept (the end, [MAX_KEPT]) and
 * read with check_job / BashOutput, which also stops them.
 *
 * Forge's foreground commands are jobs too, hidden ([startForeground]): [await] follows one while
 * it writes, and when it stays silent too long (or past the ceiling) it becomes a visible
 * background job instead of being killed ([CommandWait]). A cancelled wait (Stop) kills its tree.
 */
object AgentJobs {
  private const val MAX_RUNNING = 4
  private const val MAX_KEPT = 200_000

  /** Marks the line that reports the shell's folder at the end of a Forge command (not for the model). */
  private const val CWD_MARK = "\u001EALTREX_CWD="

  class Job internal constructor(@Volatile var id: String, val command: String, val process: Process, private val clock: () -> Long) {
    internal val output = StringBuilder()
    /** Characters already given to the model. */
    internal var read = 0
    /** Characters dropped from the start of [output] to keep it under [MAX_KEPT]. */
    internal var dropped = 0
    val startedAt: Long = clock()
    @Volatile var lastOutputAt: Long = startedAt
      internal set
    /** Shown to the model (check_job, BashOutput); false while Forge waits for it in the foreground. */
    @Volatile var visible: Boolean = true
      internal set
    @Volatile internal var listener: ((String) -> Unit)? = null
    internal var reader: Thread? = null

    internal fun append(chunk: String) {
      synchronized(this) {
        output.append(chunk)
        val over = output.length - MAX_KEPT
        if (over > 0) {
          output.delete(0, over)
          dropped += over
        }
        lastOutputAt = clock()
      }
      listener?.let { runCatching { it(chunk) } }
    }

    /** Everything kept, with a mark when the start was dropped. */
    fun text(): String =
      synchronized(this) { (if (dropped > 0) "[… $dropped caractères du début omis …]\n" else "") + output.toString() }

    internal fun markAllRead() = synchronized(this) { read = dropped + output.length }
  }

  sealed class Wait {
    data class Done(val exitCode: Int, val output: String) : Wait()

    /** Still running, now a background job [jobId]; [output] is what it wrote so far. */
    data class Detached(val jobId: String, val output: String, val reason: CommandWait.Verdict) : Wait()
  }

  private val jobs = LinkedHashMap<String, Job>()
  private val counter = AtomicInteger(0)
  private val hiddenCounter = AtomicInteger(0)

  /** Starts [command]; returns its id, or an error message (prefixed "error:"). */
  fun start(context: Context, command: String): String =
    startVisible(command) { LinuxEnv.of(context, LinuxEnv.AGENT).start(listOf("/bin/sh", "-lc", AgentShell.WRAPPER_BG)) }

  /** [ALTREX] Forge: a job started in the project's shell state ([AgentShell.FORGE_WRAPPER_BG]). */
  fun startIn(context: Context, command: String, stateDir: String, defaultCwd: String): String =
    startVisible(command) {
      LinuxEnv.of(context, LinuxEnv.AGENT).start(listOf("/bin/sh", "-lc", AgentShell.FORGE_WRAPPER_BG, "sh", stateDir, defaultCwd))
    }

  /**
   * [ALTREX] Forge's foreground command, in the project's shell state (saved at the end,
   * [AgentShell.FORGE_WRAPPER]): a hidden job, to [await].
   */
  fun startForeground(context: Context, command: String, stateDir: String, defaultCwd: String, onOutput: (String) -> Unit): Job {
    val process = LinuxEnv.of(context, LinuxEnv.AGENT).start(listOf("/bin/sh", "-lc", AgentShell.FORGE_WRAPPER, "sh", stateDir, defaultCwd))
    return register(process, command, visible = false, onOutput = onOutput)
  }

  @Synchronized
  private fun startVisible(command: String, launch: () -> Process): String {
    if (jobs.values.count { it.visible && it.process.isAlive } >= MAX_RUNNING) {
      return "error: $MAX_RUNNING background jobs are already running; stop one with check_job first."
    }
    val process = try { launch() } catch (e: IOException) { return "error: ${e.message}" }
    return register(process, command, visible = true).id
  }

  /**
   * Follows [process] (already started; the command goes to its stdin, then stdin is closed).
   * Visible jobs get the next `jobN` id; hidden ones get one only if they are [await]ed into the
   * background.
   */
  internal fun register(
    process: Process,
    command: String,
    visible: Boolean,
    stdin: String? = command,
    onOutput: ((String) -> Unit)? = null,
    clock: () -> Long = System::currentTimeMillis,
  ): Job {
    val id = if (visible) "job${counter.incrementAndGet()}" else "fg${hiddenCounter.incrementAndGet()}"
    val job = Job(id, command, process, clock)
    job.visible = visible
    job.listener = onOutput
    synchronized(this) { jobs[id] = job }
    Thread {
        try {
          process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
        } catch (e: IOException) {
          // Ended at once: its output says why.
        }
      }
      .apply { isDaemon = true }
      .start()
    job.reader =
      Thread {
          try {
            process.inputStream.bufferedReader().use { r ->
              val buf = CharArray(4096)
              while (true) {
                val n = r.read(buf)
                if (n < 0) break
                job.append(String(buf, 0, n))
              }
            }
          } catch (e: IOException) {
            // Stopped.
          }
        }
        .apply {
          isDaemon = true
          start()
        }
    return job
  }

  /**
   * Waits for [job] while it writes. Ends with [Wait.Done] when it exits, or [Wait.Detached] when
   * [policy] says it has been silent (or running) too long: it then keeps running as a visible
   * background job. Cancelling the wait kills the job's whole tree at once ([ProcessTree.kill]).
   */
  suspend fun await(job: Job, policy: CommandWait.Policy, clock: () -> Long = System::currentTimeMillis): Wait =
    withContext(Dispatchers.IO) {
      try {
        while (true) {
          if (job.process.waitFor(100, TimeUnit.MILLISECONDS)) {
            // The last bytes may still be in the pipe; children that kept it open are not waited for long.
            job.reader?.join(1_500)
            forget(job)
            return@withContext Wait.Done(job.process.exitValue(), job.text())
          }
          currentCoroutineContext().ensureActive()
          val v = CommandWait.verdict(clock(), job.startedAt, job.lastOutputAt, policy)
          if (v != CommandWait.Verdict.WAIT) return@withContext Wait.Detached(detach(job), job.text(), v)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
      } catch (e: CancellationException) {
        forget(job)
        ProcessTree.kill(job.process)
        throw e
      }
    }

  /** A hidden job becomes a background job the model can follow; returns its new id. */
  @Synchronized
  private fun detach(job: Job): String {
    jobs.remove(job.id)
    job.id = "job${counter.incrementAndGet()}"
    job.listener = null
    job.visible = true
    job.markAllRead()
    jobs[job.id] = job
    return job.id
  }

  @Synchronized
  private fun forget(job: Job) {
    if (jobs[job.id] === job) jobs.remove(job.id)
  }

  /** The output without the shell's own report line. */
  internal fun clean(text: String): String =
    if (!text.contains(CWD_MARK)) text
    else text.lineSequence().filterNot { it.startsWith(CWD_MARK) }.joinToString("\n").trimEnd('\n')

  /** State of a job and its output since the last check (the end, [maxChars]); [stop] ends it. */
  @Synchronized
  fun check(id: String, stop: Boolean, maxChars: Int): Map<String, String> {
    val job = jobs[id]?.takeIf { it.visible } ?: return mapOf("error" to "No background job \"$id\". Running: ${list()}", "status" to "failed")
    var stopped = false
    if (stop && job.process.isAlive) {
      ProcessTree.kill(job.process)
      // A short wait so that the answer says "stopped" with its exit code most of the time.
      runCatching { job.process.waitFor(1_200, TimeUnit.MILLISECONDS) }
      stopped = true
    }
    val fresh =
      synchronized(job) {
        val from = (job.read - job.dropped).coerceAtLeast(0)
        val text = job.output.substring(from.coerceAtMost(job.output.length))
        job.read = job.dropped + job.output.length
        clean(text)
      }
    val shown = if (fresh.length > maxChars) "[… ${fresh.length - maxChars} caractères omis …]\n" + fresh.takeLast(maxChars) else fresh
    return buildMap {
      put("job_id", id)
      put("command", job.command.take(200))
      if (job.process.isAlive) {
        put("state", if (stopped) "stopping" else "running")
      } else {
        put("state", if (stop || stopped) "stopped" else "finished")
        put("exit_code", job.process.exitValue().toString())
      }
      put("new_output", shown.ifEmpty { "(no new output)" })
      put("status", "succeeded")
    }
  }

  /** The last [lines] lines a job wrote (live progress), without the shell's report line. */
  fun tail(id: String, lines: Int = 4): String? {
    val job = synchronized(this) { jobs[id] } ?: return null
    val text = synchronized(job) { job.output.takeLast(4_000).toString() }
    return clean(text).trimEnd().lines().takeLast(lines).joinToString("\n")
  }

  @Synchronized
  fun list(): String =
    jobs.values.filter { it.visible }.joinToString(", ") { "${it.id} (${if (it.process.isAlive) "running" else "ended"})" }.ifEmpty { "none" }

  /** Stops every job (the Linux was reset). */
  @Synchronized
  fun stopAll() {
    for (j in jobs.values) if (j.process.isAlive) ProcessTree.kill(j.process)
    jobs.clear()
  }
}
