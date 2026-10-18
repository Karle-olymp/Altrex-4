package com.google.ai.edge.gallery.ui.common.chat

import com.google.ai.edge.gallery.security.UntrustedContent
import com.google.ai.edge.gallery.search.TextSearch
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

  /** Pieces read part by part. */
  private const val CHUNK_CHARS = 900

  /**
   * Pieces searched for a precise question: small, so that the score points at the right place;
   * each match is then sent with its neighbours ("parent-child" retrieval).
   */
  private const val SEARCH_CHUNK_CHARS = 450

  fun plan(doc: AttachedDocument, question: String, budgetChars: Int, argoraFormat: Boolean): DocumentPlan {
    val budget = budgetChars.coerceAtLeast(2_000)
    if (doc.charCount + doc.pageTexts.size * 20 <= budget) {
      return DocumentPlan.Direct(DocumentReader.buildPrompt(doc, question, budget, argoraFormat).first)
    }
    val terms = queryTerms(question)
    if (!isGlobalRequest(question) && terms.isNotEmpty()) {
      val small = chunk(doc, SEARCH_CHUNK_CHARS)
      val scores = bm25(small, terms)
      if ((scores.maxOrNull() ?: 0.0) > 0.0) {
        return passages(doc, question, small, scores, budget, argoraFormat)
      }
    }
    return sections(doc, chunk(doc, CHUNK_CHARS), budget)
  }

  /** Text pieces of about [size] characters, cut at paragraph or sentence ends. */
  private data class Chunk(val page: Int, val order: Int, val text: String)

  private fun chunk(doc: AttachedDocument, size: Int): List<Chunk> {
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
          if (paragraph.length <= size) listOf(paragraph)
          else paragraph.split(Regex("(?<=[.!?;])\\s+|\n")).flatMap { it.chunked(size) }
        for (piece in pieces) {
          if (current.length + piece.length > size) flush()
          current.append(piece).append('\n')
        }
      }
      flush()
    }
    return out
  }

  // ---- Keyword search: see TextSearch ----

  private val GLOBAL_REQUEST =
    Regex(
      "r[ée]sum|synth[èe]s|de quoi (parle|traite)|points? cl[ée]s|id[ée]es? principal|analys|" +
        "audit|relis|relecture|critique|avis|plan du|structure|en gros|vue d'ensemble|" +
        "tout le document|l'ensemble|summar|overview|key points",
      RegexOption.IGNORE_CASE,
    )

  private fun isGlobalRequest(question: String): Boolean =
    question.isBlank() || GLOBAL_REQUEST.containsMatchIn(question)

  private fun tokens(text: String): List<String> = TextSearch.tokens(text)

  private fun queryTerms(question: String): Set<String> = tokens(question).toSet()

  private fun bm25(chunks: List<Chunk>, terms: Set<String>): DoubleArray =
    TextSearch.bm25(chunks.map { tokens(it.text) }, terms)

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
      // The match, then its neighbours: the next piece often finishes its sentence or its table,
      // the previous one gives its title or context.
      for (i in listOf(index, index + 1, index - 1)) {
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
        "${question.trim()}\n\n$fence${DocumentReader.codeLanguage(doc.name)}\n" +
          UntrustedContent.neutralize(body.toString().trimEnd()) + "\n$fence"
      } else {
        val where = if (pages.isEmpty()) "" else " (pages ${pages.joinToString(", ")})"
        "Document joint : ${doc.name}" + (if (doc.isPdf) " (${doc.pages} pages)" else "") +
          " — trop long pour être lu en entier : voici les passages liés à la question$where.\n" +
          UntrustedContent.frame("document « ${doc.name} »", body.toString()) + "\n\n$question"
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
      UntrustedContent.frame("document « ${doc.name} »", section.text)
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
      UntrustedContent.frame("notes de lecture", body.toString()) + "\n\n" +
      question.ifBlank { "Résume ce document." }
  }
}
