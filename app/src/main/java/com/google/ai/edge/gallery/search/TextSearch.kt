package com.google.ai.edge.gallery.search

import java.text.Normalizer
import kotlin.math.ln

/**
 * [ALTREX] Keyword search for French and English text, shared by long documents and the local
 * memory: accents folded, light stemming (plurals and common endings), stop words removed, and
 * BM25 ranking.
 */
object TextSearch {
  private val STOPWORDS =
    setOf(
      // French
      "les", "des", "une", "est", "sont", "que", "qui", "quoi", "dans", "pour", "par", "sur", "avec",
      "sans", "pas", "plus", "moins", "tres", "tout", "tous", "toute", "toutes", "mais", "donc", "car",
      "aux", "ces", "cet", "cette", "son", "sa", "ses", "leur", "leurs", "nous", "vous", "ils", "elle",
      "elles", "moi", "toi", "lui", "mon", "ton", "notre", "votre", "comme", "quel", "quelle", "quels",
      "quelles", "comment", "pourquoi", "quand", "combien", "ou", "etre", "avoir", "fait", "faire",
      "peut", "peux", "dit", "dis", "donne", "donner", "explique", "expliquer", "parle", "parler",
      "selon", "entre", "aussi", "alors", "ainsi", "chaque", "autre", "autres", "y", "en", "du", "de",
      "la", "le", "un", "et", "il", "je", "tu", "on", "ce", "se", "ne", "a",
      // About the document itself
      "document", "documents", "texte", "fichier", "pdf", "page", "pages", "rapport", "ci", "dessus",
      "mentionne", "indique", "contient", "trouve", "cherche",
      // English
      "the", "and", "for", "with", "what", "which", "who", "how", "why", "when", "where", "this",
      "that", "from", "are", "was", "does", "about", "file",
    )

  fun normalize(word: String): String {
    val base =
      Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    // Light stemming: plural and common endings, so "failles" finds "faille".
    return when {
      base.length > 5 && base.endsWith("ment") -> base.dropLast(4)
      base.length > 4 && (base.endsWith("es") || base.endsWith("s") || base.endsWith("x")) ->
        base.trimEnd('s', 'x').removeSuffix("e")
      base.length > 4 && base.endsWith("e") -> base.dropLast(1)
      else -> base
    }
  }

  /** Searchable words of [text], normalized. */
  fun tokens(text: String): List<String> =
    text.split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.length >= 2 }.mapNotNull { raw ->
      val folded =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
      if (folded in STOPWORDS || (folded.length < 3 && !folded.any(Char::isDigit))) null else normalize(raw)
    }

  /** BM25 score of each piece (given as its [tokens]) for the query [terms]. */
  fun bm25(docTokens: List<List<String>>, terms: Set<String>): DoubleArray {
    val k1 = 1.2
    val b = 0.75
    val avgLen = docTokens.map { it.size }.average().takeIf { !it.isNaN() }?.coerceAtLeast(1.0) ?: 1.0
    val df = terms.associateWith { term -> docTokens.count { term in it } }
    val n = docTokens.size.toDouble()
    return DoubleArray(docTokens.size) { i ->
      val words = docTokens[i]
      val tf = words.groupingBy { it }.eachCount()
      terms.sumOf { term ->
        val f = tf[term] ?: return@sumOf 0.0
        val idf = ln(1 + (n - df.getValue(term) + 0.5) / (df.getValue(term) + 0.5))
        idf * f * (k1 + 1) / (f + k1 * (1 - b + b * words.size / avgLen))
      }
    }
  }
}
