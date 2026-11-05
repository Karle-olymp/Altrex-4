package com.google.ai.edge.gallery.tools

import android.content.Context
import com.google.ai.edge.gallery.terminal.LinuxEnv
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ALTREX] Commands the model runs in the background (a server, a long install, a build): each in
 * its own PRoot process, kept while the app runs. Their output is kept (the end, [MAX_KEPT]) and
 * read with check_job, which also stops them.
 */
object AgentJobs {
  private const val MAX_RUNNING = 4
  private const val MAX_KEPT = 100_000

  private class Job(val id: String, val command: String, val process: Process) {
    val output = StringBuilder()
    /** Characters already given to the model. */
    var read = 0
    /** Characters dropped from the start of [output] to keep it under [MAX_KEPT]. */
    var dropped = 0
  }

  private val jobs = LinkedHashMap<String, Job>()
  private val counter = AtomicInteger(0)

  /** Starts [command]; returns its id, or an error message (prefixed "error:"). */
  fun start(context: Context, command: String): String =
    start(context, command, listOf("/bin/sh", "-lc", AgentShell.WRAPPER_BG))

  /** [ALTREX] Forge: a job started in the project's shell state ([AgentShell.FORGE_WRAPPER_BG]). */
  fun startIn(context: Context, command: String, stateDir: String, defaultCwd: String): String =
    start(context, command, listOf("/bin/sh", "-lc", AgentShell.FORGE_WRAPPER_BG, "sh", stateDir, defaultCwd))

  @Synchronized
  private fun start(context: Context, command: String, argv: List<String>): String {
    if (jobs.values.count { it.process.isAlive } >= MAX_RUNNING) {
      return "error: $MAX_RUNNING background jobs are already running; stop one with check_job first."
    }
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    val process = env.start(argv)
    try {
      process.outputStream.use { it.write(command.toByteArray()) }
    } catch (e: IOException) {
      // Ended at once: its output says why.
    }
    val job = Job("job${counter.incrementAndGet()}", command, process)
    jobs[job.id] = job
    Thread {
        try {
          process.inputStream.bufferedReader().use { r ->
            val buf = CharArray(4096)
            while (true) {
              val n = r.read(buf)
              if (n < 0) break
              synchronized(job) {
                job.output.append(buf, 0, n)
                val over = job.output.length - MAX_KEPT
                if (over > 0) {
                  job.output.delete(0, over)
                  job.dropped += over
                }
              }
            }
          }
        } catch (e: IOException) {
          // Stopped.
        }
      }
      .apply { isDaemon = true }
      .start()
    return job.id
  }

  /** State of a job and its output since the last check (the end, [maxChars]); [stop] ends it. */
  @Synchronized
  fun check(id: String, stop: Boolean, maxChars: Int): Map<String, String> {
    val job = jobs[id] ?: return mapOf("error" to "No background job \"$id\". Running: ${list()}", "status" to "failed")
    if (stop && job.process.isAlive) {
      LinuxEnv.stopTree(job.process)
    }
    val fresh =
      synchronized(job) {
        val from = (job.read - job.dropped).coerceAtLeast(0)
        val text = job.output.substring(from.coerceAtMost(job.output.length))
        job.read = job.dropped + job.output.length
        text
      }
    val shown = if (fresh.length > maxChars) "[… ${fresh.length - maxChars} caractères omis …]\n" + fresh.takeLast(maxChars) else fresh
    return buildMap {
      put("job_id", id)
      put("command", job.command.take(200))
      if (job.process.isAlive) {
        put("state", "running")
      } else {
        put("state", if (stop) "stopped" else "finished")
        put("exit_code", job.process.exitValue().toString())
      }
      put("new_output", shown.ifEmpty { "(no new output)" })
      put("status", "succeeded")
    }
  }

  @Synchronized
  fun list(): String =
    jobs.values.joinToString(", ") { "${it.id} (${if (it.process.isAlive) "running" else "ended"})" }.ifEmpty { "none" }

  /** Stops every job (the Linux was reset). */
  @Synchronized
  fun stopAll() {
    for (j in jobs.values) if (j.process.isAlive) LinuxEnv.stopTree(j.process)
    jobs.clear()
  }
}
