package com.google.ai.edge.gallery.forge.tools

import java.io.File

/**
 * [ALTREX] `@fichier` in Forge's input: a fuzzy picker over the project's files, then the content
 * of each mentioned file given to the model with the request (bounded per file and in all). A file
 * given whole counts as read (Edit may follow without a Read), as with Read.
 */
object Mentions {
  private val SKIPPED = setOf(".git", "node_modules", ".gradle", "__pycache__", ".venv", "venv", ".next", ".cache")
  const val MAX_FILES_LISTED = 3_000
  const val MAX_PER_FILE = 8_000
  const val MAX_TOTAL = 20_000
  const val MAX_MENTIONS = 8

  /** The project's files for the picker (relative paths), heavy folders and Forge's outputs left out. */
  fun listFiles(root: File): List<String> {
    val out = ArrayList<String>()
    fun walk(dir: File, rel: String) {
      for (f in dir.listFiles()?.sortedBy { it.name } ?: return) {
        if (out.size >= MAX_FILES_LISTED) return
        val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
        if (f.isDirectory) {
          if (f.name in SKIPPED || r == ".altrex/sorties") continue
          walk(f, r)
        } else {
          out.add(r)
        }
      }
    }
    walk(root, "")
    return out
  }

  /**
   * Files matching [query] best first: every character of the query in order (subsequence), with
   * a bonus for consecutive characters, for a match in the file name and for short paths.
   */
  fun fuzzy(query: String, files: List<String>, max: Int = 20): List<String> {
    val q = query.lowercase().trim()
    if (q.isEmpty()) return files.sortedBy { it.count { c -> c == '/' } * 100 + it.length }.take(max)
    return files.mapNotNull { f -> score(q, f.lowercase())?.let { f to it } }.sortedByDescending { it.second }.take(max).map { it.first }
  }

  internal fun score(q: String, path: String): Int? {
    val name = path.substringAfterLast('/')
    var i = 0
    var s = 0
    var run = 0
    var last = -2
    for ((j, c) in path.withIndex()) {
      if (i < q.length && c == q[i]) {
        run = if (j == last + 1) run + 1 else 1
        s += 1 + run * 2
        if (j >= path.length - name.length) s += 3
        last = j
        i++
      }
    }
    if (i < q.length) return null
    if (name.startsWith(q)) s += 30 else if (name.contains(q)) s += 15
    return s * 10 - path.length
  }

  private val MENTION = Regex("(?<![\\w@])@([\\w.\\-/]+[\\w])")

  /** The paths mentioned in [text] (`@src/app.js`), in order, without duplicates. */
  fun extract(text: String): List<String> = MENTION.findAll(text).map { it.groupValues[1] }.distinct().take(MAX_MENTIONS).toList()

  data class Expanded(val text: String, val wholeFiles: List<Pair<String, ByteArray>>)

  /** The note given to the model with the request, or null when nothing is mentioned. */
  fun expand(text: String, paths: ProjectPaths): Expanded? {
    val blocks = StringBuilder()
    val whole = ArrayList<Pair<String, ByteArray>>()
    var total = 0
    for (m in extract(text)) {
      val r = paths.resolve(m) ?: continue
      if (!r.file.isFile) continue
      val head = "<fichier path=\"${r.rel}\""
      if (r.file.length() > FileTools.MAX_READ_BYTES) {
        blocks.append(head).append(" note=\"trop gros : cherche dedans avec Grep\"/>\n")
        continue
      }
      val bytes = r.file.readBytes()
      if (FileTools.looksBinary(bytes)) {
        blocks.append(head).append(" note=\"fichier binaire (").append(bytes.size / 1024).append(" Ko) : pas de texte à montrer\"/>\n")
        continue
      }
      val lines = String(bytes, Charsets.UTF_8).removeSuffix("\n").split('\n')
      val room = minOf(MAX_PER_FILE, MAX_TOTAL - total)
      if (room < 200) {
        blocks.append(head).append(" note=\"non inclus (assez de fichiers dans ce message) : lis-le avec Read\"/>\n")
        continue
      }
      val sb = StringBuilder()
      var shown = 0
      for ((i, l) in lines.withIndex()) {
        val line = String.format("%6d\t", i + 1) + l.trimEnd('\r').take(FileTools.MAX_LINE_CHARS) + "\n"
        if (sb.length + line.length > room) break
        sb.append(line)
        shown++
      }
      total += sb.length
      val complete = shown == lines.size
      if (complete) whole.add(r.rel to bytes)
      blocks.append(head).append(" lignes=\"1-").append(shown).append('/').append(lines.size).append("\">\n").append(sb)
      if (!complete) blocks.append("[… suite non incluse : lis-la avec Read, offset=").append(shown + 1).append(" …]\n")
      blocks.append("</fichier>\n")
    }
    if (blocks.isEmpty()) return null
    return Expanded("Fichiers joints par l'utilisateur à sa demande (contenu actuel, numéros de ligne comme Read) :\n" + blocks.toString().trimEnd(), whole)
  }
}
