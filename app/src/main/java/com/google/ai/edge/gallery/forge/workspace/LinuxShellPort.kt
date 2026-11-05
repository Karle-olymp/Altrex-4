package com.google.ai.edge.gallery.forge.workspace

import android.content.Context
import com.google.ai.edge.gallery.forge.engine.ShellPort
import com.google.ai.edge.gallery.terminal.LinuxEnv
import com.google.ai.edge.gallery.tools.AgentJobs
import com.google.ai.edge.gallery.tools.AgentShell
import com.google.ai.edge.gallery.tools.CommandWait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ALTREX] Forge's Bash: the model's own Alpine ([LinuxEnv.AGENT]), through the shell Discuter
 * already uses ([AgentShell], [AgentJobs]), but with a state of its own per project (current
 * folder and exported variables in `/root/.altrex/forge/<projet>`) and the project folder as the
 * starting point of every command and background job. Commands go in through stdin, paths as
 * arguments: nothing is pasted in a shell line.
 *
 * A foreground command is a hidden job followed while it writes ([CommandWait]); a command that
 * stays silent too long becomes a background job instead of being killed. Cancelling (Stop) kills
 * its whole process tree.
 */
class LinuxShellPort(private val context: Context, private val project: String) : ShellPort {
  private val stateDir = "/root/.altrex/forge/$project"
  private val cwd = "${LinuxEnv.PROJECTS_PATH}/$project"

  override suspend fun ensureReady(onProgress: (String) -> Unit): String? {
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    if (!env.isSupported) return "Le Linux n'est pas disponible sur cet appareil (arm64 requis)."
    if (env.isInstalled) return null
    onProgress("Installation du Linux (Alpine, ≈ 4 Mo)…")
    val r = env.install { onProgress(it) }
    return if (r.isFailure || !env.isInstalled) "Le Linux n'a pas pu être installé : ${r.exceptionOrNull()?.message}" else null
  }

  override suspend fun run(command: String, wait: CommandWait.Policy, onOutput: (String) -> Unit): ShellPort.Result {
    val job =
      withContext(Dispatchers.IO) {
        // An apk left alone by a command stopped earlier still holds the package database lock.
        if (CommandWait.usesApk(command)) LinuxEnv.reapStrays(LinuxEnv.AGENT)
        AgentJobs.startForeground(context, command, stateDir, cwd, onOutput)
      }
    return when (val w = AgentJobs.await(job, wait)) {
      is AgentJobs.Wait.Done -> {
        val (output, dir) = AgentShell.splitCwd(w.output)
        ShellPort.Result(w.exitCode, output, dir, false)
      }
      is AgentJobs.Wait.Detached ->
        ShellPort.Result(
          exitCode = -1,
          output = AgentJobs.clean(w.output),
          cwd = null,
          timedOut = false,
          jobId = w.jobId,
          detached = if (w.reason == CommandWait.Verdict.CAP) "cap" else "idle",
        )
    }
  }

  override fun startJob(command: String): String {
    if (CommandWait.usesApk(command)) LinuxEnv.reapStrays(LinuxEnv.AGENT)
    return AgentJobs.startIn(context, command, stateDir, cwd)
  }

  override fun checkJob(id: String, stop: Boolean, maxChars: Int): Map<String, String> = AgentJobs.check(id, stop, maxChars)

  override fun jobTail(id: String): String? = AgentJobs.tail(id)
}
