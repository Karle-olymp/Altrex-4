package com.google.ai.edge.gallery.tools

import android.content.Context
import com.google.ai.edge.gallery.security.SecretRedactor
import com.google.ai.edge.gallery.terminal.LinuxEnv
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Output sent back to the model (its end, where errors usually are). */
private const val MAX_OUTPUT_TO_MODEL = 8_000

/** Lines given by read_file when the model does not say. */
private const val DEFAULT_READ_LINES = 300

/** Longest wait for a foreground command; longer work goes to the background. */
private const val MAX_TIMEOUT_S = 600

/**
 * [ALTREX] The model's terminal, in the spirit of Claude Code: a shell whose directory and variables
 * carry over ([AgentShell]), exact file tools, and background jobs ([AgentJobs]), all in its own
 * Alpine Linux ([LinuxEnv.AGENT]), separate from the user's.
 *
 * Approvals ([AgentShell.Mode]): by default read-only commands run freely and the rest is shown
 * first; "always" in the dialog stops asking for that program (or, for a file, for file edits).
 * Results go back to the model framed as data (DefaultAgentRuntimeExecutor), like web pages.
 */
class RunCommandTool(private val context: Context) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Run a shell command in your own Alpine Linux environment (apk add, python3, git, curl…). " +
        "The user sees and approves every command. Returns the exit code and the output."
  )
  fun runCommand(
    @ToolParam(description = "The shell command to run (sh syntax), from /root.") command: String
  ): Map<String, String> = runBlocking(Dispatchers.IO) { execute(command, 0, false) }

  @Tool(description = "Read a text file of your Linux, with line numbers.")
  fun readFile(@ToolParam(description = "Path of the file (absolute, or relative to the current directory).") path: String): Map<String, String> =
    runBlocking(Dispatchers.IO) { read(path, 1, DEFAULT_READ_LINES) }

  @Tool(description = "Create or overwrite a text file of your Linux with the given content.")
  fun writeFile(
    @ToolParam(description = "Path of the file.") path: String,
    @ToolParam(description = "The whole new content of the file.") content: String,
  ): Map<String, String> = runBlocking(Dispatchers.IO) { write(path, content) }

  @Tool(description = "Replace an exact piece of text in a file of your Linux (it must appear only once).")
  fun editFile(
    @ToolParam(description = "Path of the file.") path: String,
    @ToolParam(description = "The exact text to replace, copied from read_file (without line numbers).") oldString: String,
    @ToolParam(description = "The new text.") newString: String,
  ): Map<String, String> = runBlocking(Dispatchers.IO) { edit(path, oldString, newString, false) }

  @Tool(description = "Read the new output of a background job started with run_command, or stop it.")
  fun checkJob(@ToolParam(description = "The job id given by run_command.") jobId: String): Map<String, String> =
    AgentJobs.check(jobId.trim(), stop = false, maxChars = MAX_OUTPUT_TO_MODEL)

  // ---------------------------------------------------------------- shared steps

  private fun failed(message: String) = mapOf("error" to message, "status" to "failed")

  private val refusedNoScreen =
    mapOf(
      "result" to "refused: the user could not be asked to allow this action. Do not try again; tell the user.",
      "status" to "failed",
    )
  private val refusedByUser =
    mapOf(
      "result" to "refused: the user did not allow this action. Do not try again; tell the user.",
      "status" to "failed",
    )

  /** Asks the user; null when there is no screen to ask on. Unanswered counts as a refusal. */
  private suspend fun ask(label: String, details: String): PermissionResult? {
    val channel = executionContext?.actionChannel ?: return null
    val action = AskActionPermissionAction(label = label, parameters = details)
    channel.send(action)
    return withTimeoutOrNull(USER_REPLY_TIMEOUT_MS) { action.result.await() }
      ?: PermissionResult.DENY.also { action.result.complete(it) }
  }

  /** Installs the Linux on first use; null when ready, else the error result. */
  private suspend fun ensureLinux(): Map<String, String>? {
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    if (!env.isSupported) return failed("Linux is not available on this device.")
    if (env.isInstalled) return null
    executionContext?.actionChannel?.trySend(SkillProgressToolAction(label = "Installation de Linux (Alpine)…", inProgress = true))
    val installed = env.install {}
    return if (installed.isFailure || !env.isInstalled) {
      failed("Linux could not be installed: ${installed.exceptionOrNull()?.message}")
    } else {
      null
    }
  }

  private fun tail(text: String, max: Int = MAX_OUTPUT_TO_MODEL): String {
    val clean = SecretRedactor.redact(text)
    return if (clean.length > max) "[… ${clean.length - max} caractères du début omis …]\n" + clean.takeLast(max) else clean
  }

  private fun progress(label: String, title: String? = null, description: String? = null, done: Boolean = false) {
    executionContext?.actionChannel?.trySend(
      SkillProgressToolAction(label = label, inProgress = !done, addItemTitle = title ?: "", addItemDescription = description ?: "")
    )
  }

  // ---------------------------------------------------------------- run_command

  /** [timeoutS] 0 = default; [background] starts a job instead of waiting. */
  suspend fun execute(command: String, timeoutS: Int, background: Boolean): Map<String, String> {
    val cmd = command.trim()
    if (cmd.isEmpty()) return failed("The command is empty.")
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    if (!env.isSupported) return failed("Linux is not available on this device.")

    val mode = AgentShell.mode(context)
    if (AgentShell.needsApproval(mode, AgentShell.allowedPrefixes(context), cmd)) {
      val program = AgentShell.programOf(cmd)
      val label =
        (if (env.isInstalled) "Altrex veut exécuter une commande dans son Linux" else "Altrex veut installer son Linux (Alpine, ≈ 4 Mo) puis exécuter une commande") +
          (if (background) " (en arrière-plan)" else "") +
          (program?.let { " · « Toujours » : ne plus demander pour « $it »" } ?: "")
      when (ask(label, cmd) ?: return refusedNoScreen) {
        PermissionResult.DENY -> return refusedByUser
        PermissionResult.ALWAYS_ALLOW -> program?.let { AgentShell.allowPrefix(context, it) }
        PermissionResult.ALLOW_ONCE -> {}
      }
    }
    ensureLinux()?.let { return it }

    if (background) {
      val id = AgentJobs.start(context, cmd)
      if (id.startsWith("error:")) return failed(id.removePrefix("error:").trim())
      progress("Tâche de fond lancée : ${cmd.take(50)}", title = "run_command ($id)", description = cmd, done = true)
      return mapOf(
        "job_id" to id,
        "note" to "Started in the background. Read its output with check_job(\"$id\"); stop it with check_job(\"$id\", stop=true).",
        "status" to "succeeded",
      )
    }

    val timeoutMs =
      (if (timeoutS > 0) timeoutS.coerceAtMost(MAX_TIMEOUT_S)
      else if (isSlow(cmd)) MAX_TIMEOUT_S
      else LinuxEnv.DEFAULT_TIMEOUT_MS.toInt() / 1000) * 1000L
    progress("Commande : ${cmd.take(60)}", title = "run_command", description = cmd)
    // Live: the last line written, every second and a half at most.
    var lastSent = 0L
    val partial = StringBuilder()
    val result =
      try {
        AgentShell.run(context, cmd, timeoutMs) { chunk ->
          synchronized(partial) {
            partial.append(chunk)
            if (partial.length > 4_000) partial.delete(0, partial.length - 2_000)
          }
          val now = System.currentTimeMillis()
          if (now - lastSent > 1_500) {
            lastSent = now
            val line = synchronized(partial) { partial.toString() }.trimEnd().substringAfterLast('\n').take(70)
            if (line.isNotBlank() && !line.startsWith("\u001E")) progress("▸ $line")
          }
        }
      } catch (e: Exception) {
        return failed(e.message ?: "The command could not run.")
      }
    progress(
      "Commande terminée (code ${result.exitCode})",
      title = "Résultat (code ${result.exitCode})",
      description = tail(result.output, 1_500).ifEmpty { "(aucune sortie)" },
      done = true,
    )
    return buildMap {
      put("exit_code", result.exitCode.toString())
      if (result.timedOut) {
        put("note", "stopped: took longer than ${timeoutMs / 1000} s. For long work, use background=true and check_job.")
      }
      put("output", tail(result.output).ifEmpty { "(no output)" })
      if (result.output.contains("Unable to lock database")) {
        put(
          "hint",
          "Another package command is still running. Wait for it (check with `ps`), or run long installs with background=true. Do not delete lock files.",
        )
      }
      result.cwd?.let { put("cwd", it) }
      put("status", if (result.exitCode == 0 && !result.timedOut) "succeeded" else "failed")
    }
  }

  // ---------------------------------------------------------------- files

  suspend fun read(path: String, offset: Int, limit: Int): Map<String, String> {
    val p = path.trim().ifEmpty { return failed("The read_file tool requires a \"path\".") }
    ensureLinux()?.let { return it }
    val from = offset.coerceAtLeast(1)
    val count = (if (limit > 0) limit else DEFAULT_READ_LINES).coerceAtMost(2_000)
    val r = AgentShell.script(context, AgentShell.READ_SCRIPT, listOf(p, from.toString(), count.toString()))
    if (r.exitCode != 0) return failed(r.output.trim().take(300).ifEmpty { "Could not read $p." })
    val total = r.output.lineSequence().firstOrNull()?.removePrefix("TOTAL=")?.trim()?.toIntOrNull() ?: 0
    val body = r.output.substringAfter('\n', "")
    progress("Lecture : $p", title = "read_file", description = "$p (lignes $from-${from + count - 1} sur $total)", done = true)
    return buildMap {
      put("path", p)
      put("total_lines", total.toString())
      put("content", tail(body, 12_000).ifEmpty { "(empty)" })
      if (from + count - 1 < total) put("note", "Lines ${from + count} to $total not shown: call read_file again with offset.")
      put("status", "succeeded")
    }
  }

  /** File writes and edits: shown first, unless the user allowed them. */
  private suspend fun approveFileChange(label: String, details: String): Map<String, String>? {
    if (AgentShell.editsAllowed(context)) return null
    return when (ask("$label · « Toujours » : ne plus demander pour les fichiers", details.take(3_000)) ?: return refusedNoScreen) {
      PermissionResult.DENY -> refusedByUser
      PermissionResult.ALWAYS_ALLOW -> {
        AgentShell.setEditsAllowed(context, true)
        null
      }
      PermissionResult.ALLOW_ONCE -> null
    }
  }

  suspend fun write(path: String, content: String): Map<String, String> {
    val p = path.trim().ifEmpty { return failed("The write_file tool requires a \"path\".") }
    approveFileChange("Altrex veut écrire le fichier $p (${content.lines().size} lignes)", content)?.let { return it }
    ensureLinux()?.let { return it }
    val r = AgentShell.script(context, AgentShell.WRITE_SCRIPT, listOf(p), stdin = content)
    if (r.exitCode != 0) return failed(r.output.trim().take(300).ifEmpty { "Could not write $p." })
    progress("Fichier écrit : $p", title = "write_file", description = p, done = true)
    return mapOf("path" to p, "bytes" to r.output.trim(), "status" to "succeeded")
  }

  suspend fun edit(path: String, old: String, new: String, all: Boolean): Map<String, String> {
    val p = path.trim().ifEmpty { return failed("The edit_file tool requires a \"path\".") }
    ensureLinux()?.let { return it }
    val current = AgentShell.script(context, AgentShell.CAT_SCRIPT, listOf(p))
    if (current.exitCode != 0) return failed(current.output.trim().take(300).ifEmpty { "Could not read $p." })
    if (current.output.length >= LinuxEnv.MAX_OUTPUT_CHARS) {
      return failed("The file is too large for edit_file; change it with run_command (sed) instead.")
    }
    val updated =
      when (val e = AgentShell.applyEdit(current.output, old, new, all)) {
        is AgentShell.Edit.Fail -> return failed(e.reason)
        is AgentShell.Edit.Ok -> e
      }
    approveFileChange("Altrex veut modifier le fichier $p", "- " + old.take(1_400) + "\n+ " + new.take(1_400))?.let { return it }
    val r = AgentShell.script(context, AgentShell.WRITE_SCRIPT, listOf(p), stdin = updated.text)
    if (r.exitCode != 0) return failed(r.output.trim().take(300).ifEmpty { "Could not write $p." })
    progress("Fichier modifié : $p", title = "edit_file", description = "$p (${updated.count} remplacement(s))", done = true)
    return mapOf("path" to p, "replacements" to updated.count.toString(), "status" to "succeeded")
  }

  companion object {
    /** Installs and builds that take minutes on a phone: they get the longest delay by default. */
    private val SLOW =
      Regex("""(^|[;&|]\s*)(altrex-setup|pkg\s+(install|upgrade)|apk\s+(add|upgrade)|npm\s+(i|install|ci)\b|pnpm\s|yarn\s|pip3?\s+install|cargo\s+(build|install)|go\s+(build|install)|make\b|cmake\b|git\s+clone)""")

    internal fun isSlow(command: String): Boolean = SLOW.containsMatchIn(command)

    /** The older on/off switch, now "allow everything" ([AgentShell.Mode.ALLOW_ALL]). */
    fun isAlwaysAllowed(context: Context): Boolean = AgentShell.mode(context) == AgentShell.Mode.ALLOW_ALL

    fun setAlwaysAllowed(context: Context, allowed: Boolean) {
      AgentShell.setMode(context, if (allowed) AgentShell.Mode.ALLOW_ALL else AgentShell.Mode.READS_FREE)
    }
  }
}
