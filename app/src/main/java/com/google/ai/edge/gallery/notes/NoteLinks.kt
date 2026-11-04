package com.google.ai.edge.gallery.notes

import kotlin.math.sqrt

/** A link between notes [a] and [b] (indices in the list given to [NoteLinks.compute], a < b). */
data class NoteEdge(val a: Int, val b: Int, val weight: Float, val kinds: Int) {
  val byTags: Boolean
    get() = kinds and NoteLinks.KIND_TAGS != 0

  val byWikilink: Boolean
    get() = kinds and NoteLinks.KIND_WIKILINK != 0

  val bySimilarity: Boolean
    get() = kinds and NoteLinks.KIND_SIMILAR != 0
}

/**
 * [ALTREX] The links of the notes graph, from three signals:
 * - shared tags, a rare tag counting more than a tag found on many notes (1/√(notes with it));
 * - explicit `[[Title]]` wikilinks in the text (either way), compared without case or accents;
 * - similarity of meaning, when vectors are given (cosine above [SIMILARITY_MIN]).
 *
 * They combine like independent evidence: weight = 1 − Π(1 − wᵢ), so each signal adds to the link
 * without passing 1. Weak links are dropped and each note keeps at most [maxTagNeighbors] tag-only
 * links and [maxSimilarNeighbors] similarity-only links, so a tag on every note does not turn the
 * graph into a hairball. Pure and deterministic.
 */
object NoteLinks {
  const val KIND_TAGS = 1
  const val KIND_WIKILINK = 2
  const val KIND_SIMILAR = 4

  const val TAG_FACTOR = 0.6f
  const val WIKILINK_FACTOR = 0.9f
  const val SIMILAR_FACTOR = 0.55f
  /** Cosine of two notes' vectors from which they are said to be about the same thing. */
  const val SIMILARITY_MIN = 0.62f
  const val MIN_WEIGHT = 0.12f

  fun compute(
    notes: List<VoiceNote>,
    vectors: Map<String, FloatArray> = emptyMap(),
    maxTagNeighbors: Int = 8,
    maxSimilarNeighbors: Int = 3,
  ): List<NoteEdge> {
    val n = notes.size
    if (n < 2) return emptyList()

    // Tags, compared folded; how many notes carry each.
    val tagSets = notes.map { note -> note.tags.map(NoteText::fold).filter { it.isNotEmpty() }.toSet() }
    val df = HashMap<String, Int>()
    for (set in tagSets) for (t in set) df[t] = (df[t] ?: 0) + 1

    // Wikilinks: folded title → notes with that title.
    val byTitle = HashMap<String, MutableList<Int>>()
    for ((i, note) in notes.withIndex()) byTitle.getOrPut(NoteText.fold(note.title)) { ArrayList() }.add(i)
    val cites = Array(n) { HashSet<Int>() }
    for ((i, note) in notes.withIndex()) {
      for (title in NoteText.extractWikilinks(note.text)) {
        for (j in byTitle[NoteText.fold(title)].orEmpty()) if (j != i) cites[i].add(j)
      }
    }

    val vecs = Array(n) { i -> vectors[notes[i].id]?.let(::normalized) }

    val candidates = ArrayList<Candidate>()
    // Pairs worth looking at: those sharing a tag, citing each other, or (with vectors) all pairs.
    val pairs = HashSet<Long>()
    fun key(i: Int, j: Int) = minOf(i, j).toLong() * n + maxOf(i, j)
    val byTag = HashMap<String, MutableList<Int>>()
    for ((i, set) in tagSets.withIndex()) for (t in set) byTag.getOrPut(t) { ArrayList() }.add(i)
    for (members in byTag.values) {
      if (members.size > 1) for (x in members.indices) for (y in x + 1 until members.size) pairs.add(key(members[x], members[y]))
    }
    for (i in 0 until n) for (j in cites[i]) pairs.add(key(i, j))
    val withVectors = vecs.count { it != null } >= 2
    if (withVectors) for (i in 0 until n) if (vecs[i] != null) for (j in i + 1 until n) if (vecs[j] != null) pairs.add(key(i, j))

    for (p in pairs.sorted()) {
      val i = (p / n).toInt()
      val j = (p % n).toInt()
      var tag = 0f
      for (t in tagSets[i]) if (t in tagSets[j]) tag += 1f / sqrt((df[t] ?: 1).toFloat())
      tag = tag.coerceAtMost(1f)
      val link = j in cites[i] || i in cites[j]
      var sim = 0f
      val vi = vecs[i]
      val vj = vecs[j]
      if (vi != null && vj != null && vi.size == vj.size) {
        val c = dot(vi, vj)
        if (c >= SIMILARITY_MIN) sim = ((c - SIMILARITY_MIN) / (1f - SIMILARITY_MIN)).coerceIn(0f, 1f)
      }
      if (tag > 0f || link || sim > 0f) candidates.add(Candidate(i, j, tag, link, sim))
    }

    fun weightOf(c: Candidate): Float {
      val w = 1f - (1f - TAG_FACTOR * c.tag) * (1f - if (c.link) WIKILINK_FACTOR else 0f) * (1f - SIMILAR_FACTOR * c.sim)
      return w.coerceIn(0f, 1f)
    }

    // Caps per note for the links that rest on one weak signal only.
    val scored = candidates.map { it to weightOf(it) }.filter { it.second >= MIN_WEIGHT }
    val tagOnlyRank = rankPerNode(n, scored.filter { (c, _) -> !c.link && c.sim == 0f })
    val simOnlyRank = rankPerNode(n, scored.filter { (c, _) -> !c.link && c.tag == 0f })
    val out = ArrayList<NoteEdge>()
    for ((c, w) in scored) {
      val k = key(c.a, c.b)
      if (!c.link && c.sim == 0f && !keep(tagOnlyRank, k, c.a, c.b, maxTagNeighbors)) continue
      if (!c.link && c.tag == 0f && !keep(simOnlyRank, k, c.a, c.b, maxSimilarNeighbors)) continue
      var kinds = 0
      if (c.tag > 0f) kinds = kinds or KIND_TAGS
      if (c.link) kinds = kinds or KIND_WIKILINK
      if (c.sim > 0f) kinds = kinds or KIND_SIMILAR
      out.add(NoteEdge(c.a, c.b, w, kinds))
    }
    return out
  }

  /** For each note, the rank (0 = strongest) of each of its links among [edges]. */
  private fun rankPerNode(n: Int, edges: List<Pair<Candidate, Float>>): Array<HashMap<Long, Int>> {
    val perNode = Array(n) { ArrayList<Pair<Candidate, Float>>() }
    for (e in edges) {
      perNode[e.first.a].add(e)
      perNode[e.first.b].add(e)
    }
    // Equal weights: the note recorded closest in time first (a chain of related notes, not a star
    // around the oldest ones).
    return Array(n) { i ->
      val m = HashMap<Long, Int>()
      perNode[i]
        .sortedWith(
          compareByDescending<Pair<Candidate, Float>> { it.second }
            .thenBy { it.first.b - it.first.a }
            .thenBy { it.first.a }
        )
        .forEachIndexed { r, (c, _) -> m[c.a.toLong() * n + c.b] = r }
      m
    }
  }

  /** A capped link stays when it is among the strongest of at least one of its two notes. */
  private fun keep(rank: Array<HashMap<Long, Int>>, k: Long, a: Int, b: Int, max: Int): Boolean =
    (rank[a][k] ?: Int.MAX_VALUE) < max || (rank[b][k] ?: Int.MAX_VALUE) < max

  /** Index of each note's neighbours in [edges], for highlighting. */
  fun neighbours(n: Int, edges: List<NoteEdge>): Array<IntArray> {
    val lists = Array(n) { ArrayList<Int>() }
    for (e in edges) {
      lists[e.a].add(e.b)
      lists[e.b].add(e.a)
    }
    return Array(n) { lists[it].toIntArray() }
  }

  /** Ids of the notes linked to [id] (the strongest first), for the Markdown export. */
  fun linkedIds(notes: List<VoiceNote>, edges: List<NoteEdge>, id: String): List<String> {
    val i = notes.indexOfFirst { it.id == id }
    if (i < 0) return emptyList()
    return edges.filter { it.a == i || it.b == i }
      .sortedByDescending { it.weight }
      .map { notes[if (it.a == i) it.b else it.a].id }
  }

  fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (k in a.indices) s += a[k] * b[k]
    return s
  }

  private fun normalized(v: FloatArray): FloatArray? {
    var s = 0f
    for (x in v) s += x * x
    if (s <= 0f || s.isNaN() || s.isInfinite()) return null
    val inv = 1f / sqrt(s)
    return FloatArray(v.size) { v[it] * inv }
  }
}

/** A pair of notes with at least one signal ([a] < [b]). */
private class Candidate(val a: Int, val b: Int, val tag: Float, val link: Boolean, val sim: Float)
