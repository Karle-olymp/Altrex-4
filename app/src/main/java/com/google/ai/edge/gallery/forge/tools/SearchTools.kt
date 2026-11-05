package com.google.ai.edge.gallery.forge.tools

import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * [ALTREX] Glob and Grep of Forge, written in Kotlin on the project folder: nothing to install in
 * the Linux, same results on every phone. Bounded everywhere (files visited, results, time) so a
 * big `node_modules` or a slow pattern cannot freeze a session.
 */
class SearchTools(private val paths: ProjectPaths) {
  /** Folders skipped unless the pattern or path names them explicitly. */
  private val SKIPPED = setOf(".git", "node_modules", ".gradle", "__pycache__", ".venv", "venv", ".altrex")

  private class Walk(val deadline: Long) {
    var visited = 0
    var cut = false
  }

  private fun walk(dir: File, rel: String, w: Walk, keepSkipped: Boolean, visit: (File, String) -> Boolean) {
    val children = dir.listFiles()?.sortedBy { it.name } ?: return
    for (c in children) {
      if (w.cut) return
      if (++w.visited > MAX_VISITED || System.currentTimeMillis() > w.deadline) {
        w.cut = true
        return
      }
      val r = if (rel.isEmpty()) c.name else "$rel/${c.name}"
      if (c.isDirectory) {
        if (!keepSkipped && c.name in SKIPPED) continue
        // A link to a folder is not followed (it could loop, or lead outside).
        if (java.nio.file.Files.isSymbolicLink(c.toPath())) continue
        walk(c, r, w, keepSkipped, visit)
      } else if (!visit(c, r)) {
        w.cut = true
        return
      }
    }
  }

  // ------------------------------------------------------------------------------------- Glob

  fun glob(pattern: String, rawPath: String?): ToolOutcome {
    val pat = pattern.trim()
    if (pat.isEmpty()) return ToolOutcome.error("Glob demande un « pattern », par exemple **/*.js.")
    val base = paths.resolve(rawPath?.takeIf { it.isNotBlank() } ?: ".") ?: return ToolOutcome.error("« $rawPath » est hors du projet.")
    if (!base.file.isDirectory) return ToolOutcome.error("${base.rel.ifEmpty { "." }} n'est pas un dossier.")
    val regex =
      try {
        globToRegex(pat)
      } catch (e: PatternSyntaxException) {
        return ToolOutcome.error("Motif Glob invalide : ${e.description}")
      }
    // A pattern without '/' ("*.kt") matches the file name at any depth.
    val onName = !pat.contains('/')
    val keepSkipped = SKIPPED.any { pat.startsWith("$it/") || pat.contains("/$it/") }
    val found = ArrayList<Pair<String, Long>>()
    val w = Walk(System.currentTimeMillis() + TIME_LIMIT_MS)
    walk(base.file, "", w, keepSkipped) { f, r ->
      if (regex.matcher(if (onName) f.name else r).matches()) {
        found.add((if (base.rel.isEmpty()) r else "${base.rel}/$r") to f.lastModified())
      }
      found.size < MAX_GLOB_SCAN
    }
    val sorted = found.sortedByDescending { it.second }.map { it.first }
    val shown = sorted.take(MAX_GLOB_RESULTS)
    val note =
      when {
        sorted.size > shown.size -> "${sorted.size - shown.size} autres fichiers non affichés : précise le motif ou le dossier."
        w.cut -> "Recherche arrêtée avant la fin (projet trop gros) : précise le dossier."
        shown.isEmpty() -> "Aucun fichier ne correspond."
        else -> null
      }
    return ToolOutcome(
      ToolOutcome.Status.OK,
      ToolOutcome.json("pattern" to pat, "count" to sorted.size, "files" to shown, "note" to note, "status" to "ok"),
      summary = "${sorted.size} fichier${if (sorted.size > 1) "s" else ""}",
      detail = shown.joinToString("\n"),
    )
  }

  // ------------------------------------------------------------------------------------- Grep

  data class GrepArgs(
    val pattern: String,
    val path: String? = null,
    val glob: String? = null,
    /** "files_with_matches" (default), "content" or "count". */
    val outputMode: String? = null,
    val caseInsensitive: Boolean = false,
    val context: Int = 0,
    val headLimit: Int? = null,
  )

  fun grep(a: GrepArgs): ToolOutcome {
    if (a.pattern.isEmpty()) return ToolOutcome.error("Grep demande un « pattern » (expression régulière).")
    val regex =
      try {
        Pattern.compile(a.pattern, if (a.caseInsensitive) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
      } catch (e: PatternSyntaxException) {
        return ToolOutcome.error("Expression régulière invalide : ${e.description}. Échappe les caractères spéciaux ( ) [ ] { } . * + ? avec \\.")
      }
    val base = paths.resolve(a.path?.takeIf { it.isNotBlank() } ?: ".") ?: return ToolOutcome.error("« ${a.path} » est hors du projet.")
    if (!base.file.exists()) return ToolOutcome.error("${base.rel} n'existe pas.")
    val mode = when (a.outputMode?.trim()?.lowercase()) {
      null, "", "files", "files_with_matches" -> "files_with_matches"
      "content", "lines" -> "content"
      "count" -> "count"
      else -> return ToolOutcome.error("output_mode doit valoir files_with_matches, content ou count.")
    }
    val fileFilter =
      a.glob?.trim()?.takeIf { it.isNotEmpty() }?.let { g ->
        try {
          globToRegex(g) to !g.contains('/')
        } catch (e: PatternSyntaxException) {
          return ToolOutcome.error("Motif glob invalide : ${e.description}")
        }
      }
    val ctx = a.context.coerceIn(0, 10)
    val limit = (a.headLimit?.takeIf { it > 0 } ?: if (mode == "content") 100 else 100).coerceAtMost(500)
    val files = ArrayList<String>()
    val counts = ArrayList<Pair<String, Int>>()
    val lines = ArrayList<String>()
    var totalMatches = 0
    var truncated = false
    val w = Walk(System.currentTimeMillis() + TIME_LIMIT_MS)

    fun scan(f: File, r: String): Boolean {
      if (fileFilter != null && !fileFilter.first.matcher(if (fileFilter.second) f.name else r).matches()) return true
      if (f.length() > MAX_GREP_FILE_BYTES) return true
      val bytes = runCatching { f.readBytes() }.getOrNull() ?: return true
      if (FileTools.looksBinary(bytes)) return true
      val text = bytes.toString(Charsets.UTF_8).split('\n')
      var n = 0
      var lastShown = -1
      for ((i, raw) in text.withIndex()) {
        if (System.currentTimeMillis() > w.deadline) {
          w.cut = true
          return false
        }
        val line = raw.trimEnd('\r')
        if (!regex.matcher(if (line.length > 2_000) line.take(2_000) else line).find()) continue
        n++
        totalMatches++
        if (mode == "content") {
          val from = maxOf(i - ctx, lastShown + 1)
          if (lastShown >= 0 && from > lastShown + 1 && ctx > 0) lines.add("--")
          for (k in from..minOf(text.lastIndex, i + ctx)) {
            if (lines.size >= limit) {
              truncated = true
              return false
            }
            val sep = if (k == i) ":" else "-"
            lines.add("$r$sep${k + 1}$sep${text[k].trimEnd('\r').take(MAX_LINE_CHARS)}")
            lastShown = k
          }
        }
      }
      if (n > 0) {
        if (files.size >= limit && mode != "content") {
          truncated = true
          return false
        }
        files.add(r)
        counts.add(r to n)
      }
      return true
    }

    if (base.file.isFile) {
      scan(base.file, base.rel)
    } else {
      val keepSkipped = SKIPPED.any { base.rel == it || base.rel.startsWith("$it/") }
      walk(base.file, "", w, keepSkipped) { f, r -> scan(f, if (base.rel.isEmpty()) r else "${base.rel}/$r") }
    }
    val note =
      when {
        truncated -> "Résultats coupés à $limit : précise le motif, le dossier ou le glob (ou augmente head_limit)."
        w.cut -> "Recherche arrêtée avant la fin (projet trop gros ou trop lent) : précise le dossier."
        totalMatches == 0 -> "Aucune correspondance."
        else -> null
      }
    val json =
      when (mode) {
        "content" -> ToolOutcome.json("pattern" to a.pattern, "matches" to totalMatches, "lines" to lines.joinToString("\n"), "note" to note, "status" to "ok")
        "count" -> ToolOutcome.json("pattern" to a.pattern, "matches" to totalMatches, "counts" to counts.joinToString("\n") { "${it.first}:${it.second}" }, "note" to note, "status" to "ok")
        else -> ToolOutcome.json("pattern" to a.pattern, "count" to files.size, "files" to files, "note" to note, "status" to "ok")
      }
    return ToolOutcome(
      ToolOutcome.Status.OK,
      json,
      summary = if (mode == "content") "$totalMatches résultat${if (totalMatches > 1) "s" else ""}" else "${files.size} fichier${if (files.size > 1) "s" else ""}",
      detail = if (mode == "content") lines.joinToString("\n") else files.joinToString("\n"),
    )
  }

  companion object {
    private const val MAX_VISITED = 30_000
    private const val MAX_GLOB_SCAN = 5_000
    private const val MAX_GLOB_RESULTS = 200
    private const val MAX_GREP_FILE_BYTES = 1_000_000L
    private const val MAX_LINE_CHARS = 300
    private const val TIME_LIMIT_MS = 15_000L

    /** `**` any folders, `*` and `?` within a name, `{a,b}` choices, `[…]` classes. */
    fun globToRegex(glob: String): Pattern {
      val sb = StringBuilder("^")
      var i = 0
      var inBraces = 0
      while (i < glob.length) {
        val c = glob[i]
        when {
          c == '*' && glob.startsWith("**/", i) -> {
            sb.append("(?:.*/)?")
            i += 3
            continue
          }
          c == '*' && glob.startsWith("**", i) -> {
            sb.append(".*")
            i += 2
            continue
          }
          c == '*' -> sb.append("[^/]*")
          c == '?' -> sb.append("[^/]")
          c == '{' -> {
            inBraces++
            sb.append("(?:")
          }
          c == '}' && inBraces > 0 -> {
            inBraces--
            sb.append(")")
          }
          c == ',' && inBraces > 0 -> sb.append("|")
          c == '[' -> {
            val end = glob.indexOf(']', i + 1)
            if (end < 0) {
              sb.append("\\[")
            } else {
              sb.append('[').append(glob.substring(i + 1, end).replace("\\", "\\\\").let { if (it.startsWith("!")) "^" + it.drop(1) else it }).append(']')
              i = end
            }
          }
          else -> sb.append(Pattern.quote(c.toString()))
        }
        i++
      }
      sb.append("$")
      return Pattern.compile(sb.toString())
    }
  }
}
