package com.google.ai.edge.gallery.forge.tools

/**
 * [ALTREX] Edit's exact replacement. `old_string` must be found, and only once unless
 * `replace_all`. When it is not found, the error shows the real lines of the file that look the
 * most like it, with their numbers, so the model can copy them exactly instead of guessing again
 * (small models often get indentation or one word wrong).
 *
 * One bounded tolerance: a file with Windows line ends (CRLF) is matched as if it had plain ones,
 * and keeps its CRLF after the edit.
 */
object EditMatcher {
  sealed class Result {
    data class Ok(val text: String, val count: Int) : Result()

    data class Fail(val message: String) : Result()
  }

  fun apply(text: String, old: String, new: String, all: Boolean): Result {
    val crlf = text.contains("\r\n")
    val body = if (crlf) text.replace("\r\n", "\n") else text
    val o = old.replace("\r\n", "\n")
    val n = new.replace("\r\n", "\n")
    val count = occurrences(body, o)
    return when {
      count == 0 -> Result.Fail(notFoundMessage(body, o))
      count > 1 && !all ->
        Result.Fail(
          "old_string apparaît $count fois (lignes ${lineNumbers(body, o).joinToString(", ")}). " +
            "Ajoute des lignes voisines pour qu'il soit unique, ou mets replace_all à true."
        )
      else -> {
        val out = body.replace(o, n)
        Result.Ok(if (crlf) out.replace("\n", "\r\n") else out, count)
      }
    }
  }

  internal fun occurrences(text: String, s: String): Int {
    var count = 0
    var i = text.indexOf(s)
    while (i >= 0) {
      count++
      i = text.indexOf(s, i + s.length)
    }
    return count
  }

  private fun lineNumbers(text: String, s: String): List<Int> {
    val out = ArrayList<Int>()
    var i = text.indexOf(s)
    while (i >= 0 && out.size < 8) {
      out.add(text.substring(0, i).count { it == '\n' } + 1)
      i = text.indexOf(s, i + s.length)
    }
    return out
  }

  private fun notFoundMessage(text: String, old: String): String {
    val near = nearestLines(text, old)
    val base = "old_string est introuvable dans le fichier (le texte doit être identique, espaces et indentation compris)."
    return if (near == null) {
      "$base Relis le fichier avec Read et copie le passage exact."
    } else {
      "$base Les lignes les plus proches du fichier sont (numéro, tabulation, texte) :\n$near\n" +
        "Copie-les exactement (sans les numéros) dans old_string."
    }
  }

  /**
   * The window of the file, as long as [old] in lines (at most [MAX_WINDOW]), whose lines look the
   * most like [old]'s, numbered like Read. Null when nothing looks alike.
   */
  fun nearestLines(text: String, old: String): String? {
    val lines = text.split('\n')
    val want = old.trim('\n').split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (want.isEmpty() || lines.isEmpty()) return null
    val size = old.trim('\n').split('\n').size.coerceIn(1, MAX_WINDOW)
    val wantGrams = want.map { bigrams(it) }
    // Score of each line: its best likeness with a line of old (cheap: bigram sets).
    val limit = minOf(lines.size, MAX_SCANNED_LINES)
    val lineScore = DoubleArray(limit) { i ->
      val g = bigrams(lines[i].trim())
      if (g.isEmpty()) 0.0 else wantGrams.maxOf { dice(g, it) }
    }
    var bestStart = -1
    var bestScore = 0.0
    for (start in 0 until limit) {
      var s = 0.0
      for (k in start until minOf(limit, start + size)) s += lineScore[k]
      if (s > bestScore) {
        bestScore = s
        bestStart = start
      }
    }
    if (bestStart < 0 || bestScore / size < MIN_LIKENESS) return null
    val from = (bestStart - 1).coerceAtLeast(0)
    val to = minOf(lines.size, bestStart + size + 1)
    return (from until to).joinToString("\n") { i -> String.format("%6d\t", i + 1) + lines[i].trimEnd('\r').take(300) }
  }

  private fun bigrams(s: String): Set<String> {
    val t = s.lowercase()
    if (t.length < 2) return if (t.isEmpty()) emptySet() else setOf(t)
    val out = HashSet<String>(t.length)
    for (i in 0 until t.length - 1) out.add(t.substring(i, i + 2))
    return out
  }

  private fun dice(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val (small, big) = if (a.size <= b.size) a to b else b to a
    val common = small.count { it in big }
    return 2.0 * common / (a.size + b.size)
  }

  private const val MAX_WINDOW = 30
  private const val MAX_SCANNED_LINES = 20_000
  private const val MIN_LIKENESS = 0.35
}
