package com.google.ai.edge.gallery.connectors

import android.content.Context
import com.google.ai.edge.gallery.terminal.LinuxEnv
import com.google.ai.edge.gallery.tools.AgentShell
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * [ALTREX] Local connectors: MCP servers that run as a program (npx, uvx, python…) inside the
 * model's Linux ([LinuxEnv.AGENT]) and talk over stdin/stdout, like the ones Claude Code or Claude
 * Desktop start on a computer. Thousands of these exist; Altrex only reached remote ones (https).
 *
 * A local connector is saved like a remote one, its "url" being [PREFIX] + the command. The command
 * is the user's own (typed or picked in the catalog); its tools follow the same approvals as any
 * connector, and it only sees the model's Linux, not the phone.
 */
object LocalMcp {
  const val PREFIX = "stdio:"

  fun isLocal(url: String): Boolean = url.startsWith(PREFIX)

  fun url(command: String): String = PREFIX + command.trim()

  fun command(url: String): String = url.removePrefix(PREFIX).trim()

  data class Entry(val id: String, val name: String, val description: String, val command: String, val color: Long)

  /** Well-known local servers (official MCP reference servers, and REA asked for by the user). */
  val CATALOG: List<Entry> =
    listOf(
      Entry(
        "fs", "Fichiers", "Lire, chercher et modifier les fichiers de /root (son Linux)",
        "npx -y @modelcontextprotocol/server-filesystem /root", 0xFF607D8B,
      ),
      Entry(
        "memory", "Mémoire", "Un carnet de connaissances que le modèle garde entre les conversations",
        "npx -y @modelcontextprotocol/server-memory", 0xFF8E24AA,
      ),
      Entry(
        "thinking", "Réflexion pas à pas", "Aide le modèle à découper un problème en étapes",
        "npx -y @modelcontextprotocol/server-sequential-thinking", 0xFF3949AB,
      ),
      Entry("fetch", "Pages web", "Lire une page web et la convertir en texte", "uvx mcp-server-fetch", 0xFF00897B),
      Entry("time", "Heure", "Heure et fuseaux horaires", "uvx mcp-server-time", 0xFF6D4C41),
      Entry(
        "rea", "REA", "Rétro-ingénierie de binaires et d'applis (rea-agents ; dépend de modules natifs)",
        "npx -y rea-agents@latest mcp", 0xFFC62828,
      ),
    )

  fun byCommand(command: String): Entry? = CATALOG.find { it.command == command.trim() }

  /** Alpine packages the command's launcher needs. */
  internal fun neededPackages(command: String): List<String> =
    when (command.trim().split(Regex("\\s+")).firstOrNull()?.substringAfterLast('/')) {
      "npx", "npm", "node" -> listOf("nodejs", "npm")
      "uvx", "uv" -> listOf("uv", "python3")
      "python", "python3", "pip", "pip3" -> listOf("python3", "py3-pip")
      "bunx", "bun" -> listOf("bun")
      else -> emptyList()
    }

  /**
   * Installs the Linux and the launcher the command needs (node for npx, uv for uvx…). Returns
   * null when ready, else an error. [progress] gets short French statuses.
   */
  suspend fun prepare(context: Context, command: String, progress: (String) -> Unit): String? {
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    if (!env.isSupported) return "Linux n'est pas disponible sur cet appareil."
    if (!env.isInstalled) {
      progress("Installation du Linux du modèle…")
      val r = env.install {}
      if (r.isFailure || !env.isInstalled) return "Installation de Linux impossible : ${r.exceptionOrNull()?.message}"
    }
    val packages = neededPackages(command)
    if (packages.isEmpty()) return null
    progress("Installation de ${packages.joinToString(", ")}…")
    val check = packages.joinToString(" ") { "'$it'" }
    val r = AgentShell.run(context, "apk info -e $check >/dev/null 2>&1 || apk add $check", 300_000L)
    return if (r.exitCode == 0) null else "Installation de ${packages.joinToString(", ")} impossible :\n" + r.output.takeLast(400)
  }

  /** Starts the server in the model's Linux, from /root; stderr kept apart (logged by the SDK). */
  fun start(context: Context, command: String): Pair<StdioClientTransport, Process> {
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    check(env.isInstalled) { "Le Linux du modèle n'est pas installé : reconnecte ce connecteur." }
    val process =
      env.start(
        listOf("/bin/sh", "-lc", "cd /root; [ -f /root/.altrex/env ] && . /root/.altrex/env >/dev/null 2>&1; exec /bin/sh -c \"\$1\"", "sh", command),
        mergeStderr = false,
      )
    val transport =
      StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
        error = process.errorStream.asSource().buffered(),
      )
    return transport to process
  }
}
