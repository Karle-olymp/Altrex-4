package com.google.ai.edge.gallery.forge.workspace

import android.content.Context
import com.google.ai.edge.gallery.forge.engine.ShellPort
import com.google.ai.edge.gallery.terminal.LinuxEnv
import com.google.ai.edge.gallery.tools.AgentJobs
import com.google.ai.edge.gallery.tools.AgentShell

/**
 * [ALTREX] Forge's Bash: the model's own Alpine ([LinuxEnv.AGENT]), through the shell Discuter
 * already uses ([AgentShell], [AgentJobs]), but with a state of its own per project (current
 * folder and exported variables in `/root/.altrex/forge/<projet>`) and the project folder as the
 * starting point. Commands go in through stdin, paths as arguments: nothing is pasted in a shell
 * line.
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

  override suspend fun run(command: String, timeoutMs: Long, onOutput: (String) -> Unit): ShellPort.Result {
    val r = AgentShell.runIn(context, stateDir, cwd, command, timeoutMs, onOutput)
    return ShellPort.Result(r.exitCode, r.output, r.cwd, r.timedOut)
  }

  override fun startJob(command: String): String = AgentJobs.startIn(context, command, stateDir, cwd)

  override fun checkJob(id: String, stop: Boolean, maxChars: Int): Map<String, String> = AgentJobs.check(id, stop, maxChars)
}
