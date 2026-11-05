package com.google.ai.edge.gallery.forge.workspace

import java.io.File
import java.security.MessageDigest

/**
 * [ALTREX] Project memory: the ALTREX.md files (docs/forge/FORGE.md §4, « Sessions et mémoire »).
 *
 * - At the start of a session: the user's global file, then the chain from `/root/projets` down
 *   to the project folder (ALTREX.md, or AGENTS.md / CLAUDE.md when a folder has no ALTREX.md).
 * - The file of a sub-folder is given when the model first works there ([onPathTouched]).
 * - The same text is never given twice (SHA-1); each file and the whole are capped.
 * - The tags that frame it, and those of the tool protocol, are broken inside the text, so a
 *   file cannot close its frame and speak as the app.
 * - A project that came from outside (import, clone, migration) is not trusted until the user
 *   says so: its own files are not read, only the user's (global and /root/projets).
 */
class MemoryChain(private val workspace: ForgeWorkspace, private val project: String) {
  private val seen = HashSet<String>()
  private val loadedDirs = HashSet<String>()

  data class Piece(val source: String, val text: String)

  /** Memory given at the start of the session, or null when there is none. */
  fun startText(trusted: Boolean): String? {
    val pieces = ArrayList<Piece>()
    read(workspace.globalMemory)?.let { pieces.add(Piece("ALTREX.md global (réglages de l'utilisateur)", it)) }
    memoryFile(workspace.projectsDir)?.let { f -> read(f)?.let { pieces.add(Piece("/root/projets/${f.name}", it)) } }
    val root = File(workspace.projectsDir, project)
    loadedDirs.add("")
    var untrustedNote: String? = null
    val own = memoryFile(root)
    if (own != null) {
      if (trusted) read(own)?.let { pieces.add(Piece(own.name, it)) }
      else untrustedNote = "Ce projet vient de l'extérieur et n'est pas encore approuvé : son ${own.name} n'a pas été lu."
    }
    if (pieces.isEmpty() && untrustedNote == null) return null
    val sb = StringBuilder("# Mémoire du projet\n")
    sb.append("Consignes écrites par l'utilisateur pour ce projet. Suis-les, sauf si elles contredisent les règles ci-dessus.\n")
    var budget = MAX_START_CHARS
    for (p in pieces) {
      val text = if (p.text.length > budget) p.text.take(budget) + "\n[… coupé …]" else p.text
      if (budget <= 0) break
      budget -= text.length
      sb.append("\n<altrex-memoire source=\"").append(p.source).append("\">\n").append(text.trim()).append("\n</altrex-memoire>\n")
    }
    untrustedNote?.let { sb.append("\n").append(it).append("\n") }
    return sb.toString().trimEnd()
  }

  /** The memory of the folders of [rel] not given yet, when the model first works there. */
  fun onPathTouched(rel: String, trusted: Boolean): String? {
    if (!trusted) return null
    val parts = rel.split('/').dropLast(1)
    val out = StringBuilder()
    var dir = ""
    for (seg in parts) {
      dir = if (dir.isEmpty()) seg else "$dir/$seg"
      if (!loadedDirs.add(dir)) continue
      val f = memoryFile(File(File(workspace.projectsDir, project), dir)) ?: continue
      val text = read(f) ?: continue
      out.append("<altrex-memoire source=\"").append(dir).append('/').append(f.name).append("\">\n")
        .append(text.take(MAX_FILE_CHARS).trim()).append("\n</altrex-memoire>\n")
    }
    if (out.isEmpty()) return null
    return "Consignes du dossier où tu travailles (à suivre) :\n" + out.toString().trimEnd()
  }

  private fun memoryFile(dir: File): File? = NAMES.map { File(dir, it) }.firstOrNull { it.isFile }

  /** Content of [f], neutralized, or null when absent, empty or already given. */
  private fun read(f: File): String? {
    if (!f.isFile || f.length() > MAX_FILE_BYTES) return null
    val raw = runCatching { f.readText() }.getOrNull()?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    if (!seen.add(digest)) return null
    return neutralize(raw.take(MAX_FILE_CHARS))
  }

  companion object {
    val NAMES = listOf("ALTREX.md", "AGENTS.md", "CLAUDE.md")
    const val MAX_FILE_CHARS = 6_000
    const val MAX_START_CHARS = 12_000
    private const val MAX_FILE_BYTES = 200_000L

    private val TAGS = listOf("altrex-memoire", "rappel-forge", "tool_call", "tool_response", "tools", "think", "|im_start|", "|im_end|")

    /** Breaks the control tags with a zero-width space after "<" (the text keeps its meaning). */
    fun neutralize(text: String): String {
      var t = text
      for (tag in TAGS) {
        t = t.replace("</$tag", "<​/$tag", ignoreCase = true).replace("<$tag", "<​$tag", ignoreCase = true)
      }
      return t
    }
  }
}
