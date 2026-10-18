package com.google.ai.edge.gallery.search

import java.io.BufferedReader
import java.io.Writer
import kotlin.math.ln
import org.json.JSONArray

/** A file of the memory folder, as it was when indexed. */
data class MemoryFile(val uri: String, val path: String, val modified: Long, val size: Long)

/** A piece of a file: its [page] (1-based, 0 for a text file), its rank in the file, its text. */
data class MemoryChunk(val file: Int, val page: Int, val order: Int, val text: String)

/** A passage found for a question. */
data class MemoryHit(val path: String, val page: Int, val text: String, val score: Double)

/**
 * [ALTREX] The local memory's index: every file of the chosen folder cut into short pieces, with an
 * inverted index for BM25 search. Pure Kotlin (no Android) so that it can be tested on its own.
 */
class MemoryIndex(val files: List<MemoryFile>, val chunks: List<MemoryChunk>) {
  /** Term -> (piece, count) pairs, packed as two ints. */
  private val postings: Map<String, IntArray>
  private val lengths: IntArray
  private val avgLength: Double

  init {
    val builder = HashMap<String, ArrayList<Int>>()
    lengths = IntArray(chunks.size)
    for ((i, chunk) in chunks.withIndex()) {
      val words = TextSearch.tokens(chunk.text)
      lengths[i] = words.size
      for ((term, count) in words.groupingBy { it }.eachCount()) {
        builder.getOrPut(term) { ArrayList() }.apply {
          add(i)
          add(count)
        }
      }
    }
    postings = builder.mapValues { it.value.toIntArray() }
    avgLength = if (chunks.isEmpty()) 1.0 else lengths.average().coerceAtLeast(1.0)
  }

  /** Search by meaning: the pieces' vectors and the question's vector (same embedding model). */
  class Semantic(val vectors: VectorStore, val query: FloatArray, val minCosine: Float)

  /**
   * The passages that best match [query], each with the piece that follows it in the same file
   * (it often finishes the sentence or the list), within [budgetChars]. Empty when nothing is
   * relevant enough: small talk must not drag random notes into the prompt.
   *
   * With [semantic], the keyword ranking (BM25) and the meaning ranking are merged (reciprocal rank
   * fusion): a note about "backup" answers a question about "sauvegarde", and an exact name or
   * error code still counts.
   */
  fun search(query: String, budgetChars: Int, maxPassages: Int = 6, semantic: Semantic? = null): List<MemoryHit> {
    if (chunks.isEmpty()) return emptyList()
    val scores = bm25(TextSearch.tokens(query).toSet())
    val best = scores.values.maxOrNull() ?: 0.0
    val lexical =
      if (best >= MIN_SCORE) {
        scores.entries.filter { it.value >= best * RELATIVE_CUTOFF }.sortedByDescending { it.value }.map { it.key }
      } else {
        emptyList()
      }
    val meaning =
      semantic
        ?.takeIf { it.vectors.count == chunks.size }
        ?.let { sem ->
          val near = sem.vectors.nearest(sem.query, SEMANTIC_CANDIDATES)
          val top = near.firstOrNull()?.second ?: 0f
          if (top < sem.minCosine) emptyList()
          else near.filter { it.second >= maxOf(sem.minCosine, top - SEMANTIC_MARGIN) }.map { it.first }
        }
        .orEmpty()
    if (lexical.isEmpty() && meaning.isEmpty()) return emptyList()

    val fused = HashMap<Int, Double>()
    for ((rank, i) in lexical.withIndex()) fused[i] = (fused[i] ?: 0.0) + 1.0 / (RRF_K + rank)
    for ((rank, i) in meaning.withIndex()) fused[i] = (fused[i] ?: 0.0) + 1.0 / (RRF_K + rank)
    val ranked = fused.entries.sortedByDescending { it.value }
    return assemble(ranked.map { it.key }, fused, budgetChars, maxPassages)
  }

  /** BM25 score of every piece that contains at least one of [terms]. */
  private fun bm25(terms: Set<String>): Map<Int, Double> {
    val k1 = 1.2
    val b = 0.75
    val n = chunks.size.toDouble()
    val scores = HashMap<Int, Double>()
    for (term in terms) {
      val list = postings[term] ?: continue
      val df = list.size / 2
      val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
      var p = 0
      while (p < list.size) {
        val chunk = list[p]
        val f = list[p + 1].toDouble()
        val norm = f * (k1 + 1) / (f + k1 * (1 - b + b * lengths[chunk] / avgLength))
        scores[chunk] = (scores[chunk] ?: 0.0) + idf * norm
        p += 2
      }
    }
    return scores
  }

  /** Takes the [ranked] pieces (with their follower) within the budget and joins neighbours. */
  private fun assemble(ranked: List<Int>, scores: Map<Int, Double>, budgetChars: Int, maxPassages: Int): List<MemoryHit> {
    val picked = sortedSetOf<Int>()
    var used = 0
    var passages = 0
    for (index in ranked) {
      if (passages >= maxPassages || used >= budgetChars) break
      var added = false
      for (i in listOf(index, index + 1)) {
        if (i !in chunks.indices || i in picked) continue
        if (i != index && chunks[i].file != chunks[index].file) continue
        val cost = chunks[i].text.length + 40
        if (used + cost > budgetChars) continue
        picked.add(i)
        used += cost
        added = true
      }
      if (added) passages++
    }
    // Contiguous pieces of the same file become one passage, in file order.
    val hits = ArrayList<MemoryHit>()
    var current: MutableList<Int> = ArrayList()
    fun flush() {
      if (current.isEmpty()) return
      val first = chunks[current.first()]
      hits.add(
        MemoryHit(
          path = files[first.file].path,
          page = first.page,
          text = current.joinToString("\n") { chunks[it].text },
          score = current.maxOf { scores[it] ?: 0.0 },
        )
      )
      current = ArrayList()
    }
    for (i in picked) {
      if (current.isNotEmpty() && (i != current.last() + 1 || chunks[i].file != chunks[current.last()].file)) {
        flush()
      }
      current.add(i)
    }
    flush()
    return hits.sortedByDescending { it.score }
  }

  /** Saves the index as JSON lines: one line per file, then one per piece. */
  fun write(out: Writer) {
    out.write(JSONArray().put("v").put(FORMAT_VERSION).toString())
    out.write("\n")
    for (f in files) {
      out.write(JSONArray().put("f").put(f.uri).put(f.path).put(f.modified).put(f.size).toString())
      out.write("\n")
    }
    for (c in chunks) {
      out.write(JSONArray().put("c").put(c.file).put(c.page).put(c.order).put(c.text).toString())
      out.write("\n")
    }
  }

  companion object {
    const val FORMAT_VERSION = 1
    private const val CHUNK_CHARS = 500
    /** Below this score the best match is a coincidence (a common word or two). */
    private const val MIN_SCORE = 1.5
    /** Passages much weaker than the best one are left out. */
    private const val RELATIVE_CUTOFF = 0.35
    private const val SEMANTIC_CANDIDATES = 24
    /** Meaning matches clearly behind the best one are left out. */
    private const val SEMANTIC_MARGIN = 0.06f
    /** Reciprocal rank fusion constant (the usual value). */
    private const val RRF_K = 60.0

    /**
     * The files the passages come from, each named once with its pages in order:
     * "rapport.pdf (p. 4, 8, 25), notes.md".
     */
    fun sourcesLabel(hits: List<MemoryHit>): String {
      val pages = LinkedHashMap<String, java.util.TreeSet<Int>>()
      for (h in hits) {
        val set = pages.getOrPut(h.path) { java.util.TreeSet() }
        if (h.page > 0) set.add(h.page)
      }
      return pages.entries.joinToString(", ") { (path, set) ->
        val name = path.substringAfterLast('/')
        if (set.isEmpty()) name else "$name (p. ${set.joinToString(", ")})"
      }
    }

    fun read(reader: BufferedReader): MemoryIndex? {
      val files = ArrayList<MemoryFile>()
      val chunks = ArrayList<MemoryChunk>()
      var version = -1
      reader.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val a = JSONArray(line)
        when (a.getString(0)) {
          "v" -> version = a.getInt(1)
          "f" -> files.add(MemoryFile(a.getString(1), a.getString(2), a.getLong(3), a.getLong(4)))
          "c" -> chunks.add(MemoryChunk(a.getInt(1), a.getInt(2), a.getInt(3), a.getString(4)))
        }
      }
      return if (version == FORMAT_VERSION) MemoryIndex(files, chunks) else null
    }

    /**
     * Cuts the text of one page (or of a whole text file, [page] 0) into pieces of about
     * [CHUNK_CHARS] characters, at paragraph or sentence ends.
     */
    fun chunkText(text: String, file: Int, page: Int, firstOrder: Int): List<MemoryChunk> {
      val out = ArrayList<MemoryChunk>()
      val current = StringBuilder()
      fun flush() {
        if (current.isNotBlank()) out.add(MemoryChunk(file, page, firstOrder + out.size, current.toString().trim()))
        current.clear()
      }
      for (paragraph in text.split(Regex("\n\\s*\n"))) {
        val pieces =
          if (paragraph.length <= CHUNK_CHARS) listOf(paragraph)
          else paragraph.split(Regex("(?<=[.!?;])\\s+|\n")).flatMap { it.chunked(CHUNK_CHARS) }
        for (piece in pieces) {
          if (current.length + piece.length > CHUNK_CHARS) flush()
          current.append(piece).append('\n')
        }
      }
      flush()
      return out
    }
  }
}
