package com.google.ai.edge.gallery.ui.common.chat

import java.text.Normalizer
import kotlin.math.ln

/**
 * [ALTREX] How a document too long for the model's context is read.
 * - [Direct]: it fits, sent whole.
 * - [Passages]: a precise question; only the passages that match it are sent (keyword search).
 * - [Sections]: a request about the whole document (summary, analysis…); it is read part by part,
 *   notes are taken on each part, and the answer is written from the notes.
 */
sealed class DocumentPlan {
  data class Direct(val prompt: String) : DocumentPlan()

  data class Passages(val prompt: String, val pages: List<Int>) : DocumentPlan()

  data class Sections(val sections: List<Section>, val condensed: Boolean) : DocumentPlan()

  /** A part of the document: its first and last page (1-based) and its text. */
  data class Section(val firstPage: Int, val lastPage: Int, val text: String)
}

object DocumentPlanner {
  /** Beyond this many parts the reading gets too long on a phone: parts are condensed instead. */
  const val MAX_SECTIONS = 8

  private const val CHUNK_CHARS = 900

  fun plan(doc: AttachedDocument, question: String, budgetChars: Int, argoraFormat: Boolean): DocumentPlan {
    val budget = budgetChars.coerceAtLeast(2_000)
    if (doc.charCount + doc.pageTexts.size * 20 <= budget) {
      return DocumentPlan.Direct(DocumentReader.buildPrompt(doc, question, budget, argoraFormat).first)
    }
    val chunks = chunk(doc)
    val terms = queryTerms(question)
    if (!isGlobalRequest(question) && terms.isNotEmpty()) {
      val scores = bm25(chunks, terms)
      if ((scores.maxOrNull() ?: 0.0) > 0.0) {
        return passages(doc, question, chunks, scores, budget, argoraFormat)
      }
    }
    return sections(doc, chunks, budget)
  }

  /** Text pieces of about [CHUNK_CHARS] characters, cut at paragraph or sentence ends. */
  private data class Chunk(val page: Int, val order: Int, val text: String)

  private fun chunk(doc: AttachedDocument): List<Chunk> {
    val out = ArrayList<Chunk>()
    for ((pageIndex, page) in doc.pageTexts.withIndex()) {
      val pageNo = if (doc.isPdf) pageIndex + 1 else 0
      val current = StringBuilder()
      fun flush() {
        if (current.isNotBlank()) out.add(Chunk(pageNo, out.size, current.toString().trim()))
        current.clear()
      }
      for (paragraph in page.split(Regex("\n\\s*\n"))) {
        val pieces =
          if (paragraph.length <= CHUNK_CHARS) listOf(paragraph)
          else paragraph.split(Regex("(?<=[.!?;])\\s+|\n")).flatMap { it.chunked(CHUNK_CHARS) }
        for (piece in pieces) {
          if (current.length + piece.length > CHUNK_CHARS) flush()
          current.append(piece).append('\n')
        }
      }
      flush()
    }
    return out
  }

  // ---- Keyword search (BM25) ----

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

  private val GLOBAL_REQUEST =
    Regex(
      "r[ée]sum|synth[èe]s|de quoi (parle|traite)|points? cl[ée]s|id[ée]es? principal|analys|" +
        "audit|relis|relecture|critique|avis|plan du|structure|en gros|vue d'ensemble|" +
        "tout le document|l'ensemble|summar|overview|key points",
      RegexOption.IGNORE_CASE,
    )

  private fun isGlobalRequest(question: String): Boolean =
    question.isBlank() || GLOBAL_REQUEST.containsMatchIn(question)

  private fun normalize(word: String): String {
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

  private fun tokens(text: String): List<String> =
    text.split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.length >= 2 }.mapNotNull { raw ->
      val folded =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
      if (folded in STOPWORDS || (folded.length < 3 && !folded.any(Char::isDigit))) null else normalize(raw)
    }

  private fun queryTerms(question: String): Set<String> = tokens(question).toSet()

  private fun bm25(chunks: List<Chunk>, terms: Set<String>): DoubleArray {
    val k1 = 1.2
    val b = 0.75
    val docTokens = chunks.map { tokens(it.text) }
    val avgLen = docTokens.map { it.size }.average().coerceAtLeast(1.0)
    val df = terms.associateWith { term -> docTokens.count { term in it } }
    val n = chunks.size.toDouble()
    return DoubleArray(chunks.size) { i ->
      val words = docTokens[i]
      val tf = words.groupingBy { it }.eachCount()
      terms.sumOf { term ->
        val f = tf[term] ?: return@sumOf 0.0
        val idf = ln(1 + (n - df.getValue(term) + 0.5) / (df.getValue(term) + 0.5))
        idf * f * (k1 + 1) / (f + k1 * (1 - b + b * words.size / avgLen))
      }
    }
  }

  private fun passages(
    doc: AttachedDocument,
    question: String,
    chunks: List<Chunk>,
    scores: DoubleArray,
    budget: Int,
    argoraFormat: Boolean,
  ): DocumentPlan.Passages {
    val ranked = chunks.indices.filter { scores[it] > 0.0 }.sortedByDescending { scores[it] }
    val chosen = sortedSetOf<Int>()
    var used = 0
    for (index in ranked) {
      // The best passage and the next one, which often finishes its sentence or its table.
      for (i in listOf(index, index + 1)) {
        if (i !in chunks.indices || i in chosen) continue
        val cost = chunks[i].text.length + 24
        if (used + cost > budget) continue
        chosen.add(i)
        used += cost
      }
      if (used > budget - 200) break
    }
    val body = StringBuilder()
    var lastPage = -1
    var lastIndex = -2
    for (i in chosen) {
      val c = chunks[i]
      if (doc.isPdf && c.page != lastPage) {
        body.append("--- Page ").append(c.page).append(" ---\n")
      } else if (i != lastIndex + 1) {
        body.append("[…]\n")
      }
      body.append(c.text).append('\n')
      lastPage = c.page
      lastIndex = i
    }
    val pages = chosen.map { chunks[it].page }.filter { it > 0 }.distinct()
    val prompt =
      if (argoraFormat) {
        val fence = if (body.contains("```")) "~~~" else "```"
        "${question.trim()}\n\n$fence${DocumentReader.codeLanguage(doc.name)}\n${body.toString().trimEnd()}\n$fence"
      } else {
        val where = if (pages.isEmpty()) "" else " (pages ${pages.joinToString(", ")})"
        "Document joint : ${doc.name}" + (if (doc.isPdf) " (${doc.pages} pages)" else "") +
          " — trop long pour être lu en entier : voici les passages liés à la question$where.\n" +
          "<<<\n${body.toString().trimEnd()}\n>>>\n\n$question"
      }
    return DocumentPlan.Passages(prompt = prompt, pages = pages)
  }

  /**
   * Consecutive groups of chunks of at most [budget] characters. When more than [MAX_SECTIONS]
   * would be needed, the document is cut in [MAX_SECTIONS] parts and each one keeps its most
   * representative passages (those sharing the most words with the rest of the document).
   */
  private fun sections(doc: AttachedDocument, chunks: List<Chunk>, budget: Int): DocumentPlan.Sections {
    val sectionBudget = (budget * 0.85).toInt()
    val total = chunks.sumOf { it.text.length + 1 }
    val needed = (total + sectionBudget - 1) / sectionBudget
    val count = needed.coerceIn(1, MAX_SECTIONS)
    val condensed = needed > MAX_SECTIONS
    val perGroup = (chunks.size + count - 1) / count
    val groups = chunks.chunked(perGroup.coerceAtLeast(1))

    val salience: Map<Int, Double> =
      if (condensed) {
        val docFreq = chunks.flatMap { tokens(it.text).toSet() }.groupingBy { it }.eachCount()
        chunks.associate { c ->
          val words = tokens(c.text)
          c.order to if (words.isEmpty()) 0.0 else words.sumOf { ln(1.0 + (docFreq[it] ?: 0)) } / words.size
        }
      } else {
        emptyMap()
      }

    val sections =
      groups.map { group ->
        val kept =
          if (!condensed) {
            group
          } else {
            // Most representative passages, always with the first one (titles, introductions).
            val picked = sortedSetOf(group.first().order)
            var used = group.first().text.length
            for (c in group.drop(1).sortedByDescending { salience[it.order] ?: 0.0 }) {
              if (used + c.text.length > sectionBudget) continue
              picked.add(c.order)
              used += c.text.length
            }
            group.filter { it.order in picked }
          }
        val text = StringBuilder()
        var lastPage = -1
        for (c in kept) {
          if (doc.isPdf && c.page != lastPage) text.append("--- Page ").append(c.page).append(" ---\n")
          text.append(c.text).append('\n')
          lastPage = c.page
        }
        DocumentPlan.Section(
          firstPage = group.first().page,
          lastPage = group.last().page,
          text = text.toString().take(sectionBudget),
        )
      }
    return DocumentPlan.Sections(sections = sections, condensed = condensed)
  }

  /** Request for the notes on one part of the document. */
  fun notesPrompt(doc: AttachedDocument, section: DocumentPlan.Section, index: Int, count: Int, question: String): String {
    val where =
      if (doc.isPdf) " (pages ${section.firstPage}–${section.lastPage})" else ""
    val goal = question.ifBlank { "Résume ce document." }
    return "Voici la partie ${index + 1} sur $count$where du document « ${doc.name} ».\n" +
      "Prends des notes qui serviront ensuite à répondre à cette demande : « $goal »\n" +
      "Écris 4 à 8 points courts, avec les faits, chiffres, noms et numéros de page importants. " +
      "Pas d'introduction ni de conclusion.\n" +
      "<<<\n${section.text.trimEnd()}\n>>>"
  }

  /** Final request: the notes of every part, then the user's question. */
  fun finalPrompt(
    doc: AttachedDocument,
    plan: DocumentPlan.Sections,
    notes: List<String>,
    question: String,
    budgetChars: Int,
  ): String {
    val perNote = (budgetChars.coerceAtLeast(2_000) * 0.8 / notes.size.coerceAtLeast(1)).toInt()
    val body = StringBuilder()
    for ((i, note) in notes.withIndex()) {
      val s = plan.sections[i]
      body.append("Partie ").append(i + 1)
      if (doc.isPdf) body.append(" (pages ").append(s.firstPage).append("–").append(s.lastPage).append(")")
      body.append(" :\n").append(note.trim().take(perNote)).append("\n\n")
    }
    val partial = if (plan.condensed) " (les passages principaux de chaque partie)" else ""
    return "Document joint : ${doc.name}" + (if (doc.isPdf) " (${doc.pages} pages)" else "") +
      ". Il a été lu en ${notes.size} parties$partial ; voici les notes prises sur chacune.\n" +
      "<<<\n${body.toString().trimEnd()}\n>>>\n\n" + question.ifBlank { "Résume ce document." }
  }
}
