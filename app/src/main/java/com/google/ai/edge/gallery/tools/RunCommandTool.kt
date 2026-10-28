package com.google.ai.edge.gallery.tools

import android.content.Context
import com.google.ai.edge.gallery.security.SecretRedactor
import com.google.ai.edge.gallery.terminal.LinuxEnv
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val PREFS = "altrex_run_command"
private const val KEY_ALWAYS = "always_allow"

/** Output sent back to the model (its end, where errors usually are). */
private const val MAX_OUTPUT_TO_MODEL = 8_000

/**
 * [ALTREX] The model's terminal: runs a shell command in its own Alpine Linux ([LinuxEnv.AGENT]),
 * separate from the user's, so nothing it installs or breaks touches the user's environment.
 *
 * Every command is shown to the user and runs only once approved ("always allow" is the user's
 * choice and can be withdrawn in the settings). The environment has the app's network access. The
 * result goes back to the model framed as data (DefaultAgentRuntimeExecutor), like web pages.
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
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      val cmd = command.trim()
      if (cmd.isEmpty()) return@runBlocking mapOf("error" to "The command is empty.", "status" to "failed")
      val env = LinuxEnv.of(context, LinuxEnv.AGENT)
      if (!env.isSupported) {
        return@runBlocking mapOf("error" to "Linux is not available on this device.", "status" to "failed")
      }

      if (!isAlwaysAllowed(context)) {
        val channel =
          executionContext?.actionChannel
            ?: return@runBlocking mapOf(
              "result" to "refused: the user could not be asked to allow this command. Do not try again; tell the user.",
              "status" to "failed",
            )
        val ask =
          AskActionPermissionAction(
            label =
              if (env.isInstalled) "Altrex veut exécuter une commande dans son Linux"
              else "Altrex veut installer son Linux (Alpine, ≈ 4 Mo) puis exécuter une commande",
            parameters = cmd,
          )
        channel.send(ask)
        when (withTimeoutOrNull(USER_REPLY_TIMEOUT_MS) { ask.result.await() } ?: PermissionResult.DENY) {
          PermissionResult.DENY ->
            return@runBlocking mapOf(
              "result" to "refused: the user did not allow this command. Do not run it again; tell the user.",
              "status" to "failed",
            )
          PermissionResult.ALWAYS_ALLOW -> setAlwaysAllowed(context, true)
          PermissionResult.ALLOW_ONCE -> {}
        }
      }

      val progress = executionContext?.actionChannel
      if (!env.isInstalled) {
        progress?.trySend(SkillProgressToolAction(label = "Installation de Linux (Alpine)…", inProgress = true))
        val installed = env.install {}
        if (installed.isFailure || !env.isInstalled) {
          return@runBlocking mapOf(
            "error" to "Linux could not be installed: ${installed.exceptionOrNull()?.message}",
            "status" to "failed",
          )
        }
      }
      progress?.trySend(
        SkillProgressToolAction(
          label = "Commande : ${cmd.take(60)}",
          inProgress = true,
          addItemTitle = "run_command",
          addItemDescription = cmd,
        )
      )
      val result =
        try {
          env.run(cmd)
        } catch (e: Exception) {
          return@runBlocking mapOf("error" to (e.message ?: "The command could not run."), "status" to "failed")
        }
      progress?.trySend(SkillProgressToolAction(label = "Commande terminée", inProgress = false))
      val output = SecretRedactor.redact(result.output)
      val shown =
        if (output.length > MAX_OUTPUT_TO_MODEL) {
          "[… ${output.length - MAX_OUTPUT_TO_MODEL} caractères du début omis …]\n" + output.takeLast(MAX_OUTPUT_TO_MODEL)
        } else {
          output
        }
      buildMap {
        put("exit_code", result.exitCode.toString())
        if (result.timedOut) put("note", "stopped: took longer than ${LinuxEnv.DEFAULT_TIMEOUT_MS / 1000} s")
        put("output", shown.ifEmpty { "(no output)" })
        put("status", if (result.exitCode == 0 && !result.timedOut) "succeeded" else "failed")
      }
    }

  companion object {
    fun isAlwaysAllowed(context: Context): Boolean =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALWAYS, false)

    fun setAlwaysAllowed(context: Context, allowed: Boolean) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ALWAYS, allowed).apply()
    }
  }
}
