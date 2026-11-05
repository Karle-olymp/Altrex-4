package com.google.ai.edge.gallery.forge.tools

import java.io.File

/**
 * [ALTREX] Where a Forge project lives: [root] on the phone (the app's storage), seen as
 * [linuxPath] (`/root/projets/<nom>`) inside both Linux environments, the user's terminal and the
 * model's, through a PRoot bind.
 *
 * Every path the model gives is resolved here. Nothing outside [root] is ever read or written by
 * the file tools: `..` that climbs out is refused, and so is a symbolic link (made with Bash) that
 * points outside, since the app itself, not PRoot, opens the files.
 */
class ProjectPaths(root: File, val linuxPath: String) {
  val root: File = root.absoluteFile

  private val canonicalRoot: File by lazy { runCatching { this.root.canonicalFile }.getOrDefault(this.root) }

  /** A path inside the project: [rel] is "" for the root, or "src/a.js" (always with '/'). */
  data class Resolved(val rel: String, val file: File)

  /**
   * [raw] relative to the project root, or absolute under [linuxPath]; null when it is elsewhere
   * (another absolute path, `..` out of the project, a link that leads outside).
   */
  fun resolve(raw: String): Resolved? {
    var p = raw.trim().removePrefix("@").replace('\\', '/')
    if (p.isEmpty() || p == ".") return Resolved("", root)
    if (p == linuxPath || p == "$linuxPath/") return Resolved("", root)
    if (p.startsWith("$linuxPath/")) {
      p = p.removePrefix("$linuxPath/")
    } else if (p.startsWith("/") || p.startsWith("~")) {
      return null
    }
    val parts = ArrayList<String>()
    for (seg in p.split('/')) {
      when (seg) {
        "", "." -> Unit
        ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
        else -> parts.add(seg)
      }
    }
    val rel = parts.joinToString("/")
    val file = if (rel.isEmpty()) root else File(root, rel)
    if (!staysInside(file)) return null
    return Resolved(rel, file)
  }

  /** The deepest existing part of [file] must really be inside the project (links followed). */
  private fun staysInside(file: File): Boolean {
    var f: File? = file
    while (f != null && !f.exists()) f = f.parentFile
    if (f == null) return false
    val c = runCatching { f.canonicalFile }.getOrNull() ?: return false
    return c == canonicalRoot || c.path.startsWith(canonicalRoot.path + File.separator)
  }

  /** "src/a.js" → "/root/projets/monsite/src/a.js". */
  fun linux(rel: String): String = if (rel.isEmpty()) linuxPath else "$linuxPath/$rel"

  /** Path of [file] relative to the root ("src/a.js"), or null when outside. */
  fun relOf(file: File): String? {
    val r = root.path
    val p = file.absoluteFile.path
    if (p == r) return ""
    return if (p.startsWith(r + File.separator)) p.substring(r.length + 1).replace(File.separatorChar, '/') else null
  }
}
