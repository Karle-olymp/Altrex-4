package com.google.ai.edge.gallery.notes

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [ALTREX] Text helpers of the voice notes that need no model: a title, a summary and tags guessed
 * from the words, `[[wikilinks]]`, and the Markdown export (Obsidian-compatible).
 */
object NoteText {
  const val MAX_TITLE = 70
  const val MIN_TAGS = 3
  const val MAX_TAGS = 6

  private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
  private val WIKILINK = Regex("\\[\\[([^\\[\\]|#\\n]{1,120})(?:#[^\\[\\]|\\n]*)?(?:\\|[^\\[\\]\\n]*)?]]")
  private val WORD = Regex("[\\p{L}][\\p{L}\\p{N}'’-]*")

  /** Words that say nothing about the subject of a note (French first, a few English). */
  private val STOPWORDS =
    setOf(
      "alors", "ainsi", "après", "aussi", "autre", "autres", "avant", "avec", "avoir", "beaucoup",
      "bien", "bon", "bref", "cela", "celle", "celui", "cette", "ceux", "chaque", "chez", "chose",
      "choses", "comme", "comment", "dans", "depuis", "donc", "dont", "elle", "elles", "encore",
      "enfin", "ensuite", "entre", "est-ce", "était", "étaient", "être", "fait", "faire", "faut",
      "fois", "genre", "jamais", "juste", "leur", "leurs", "lorsque", "mais", "même", "mêmes",
      "moins", "mais", "notre", "nous", "parce", "pendant", "peut", "peux", "peut-être", "plus",
      "plutôt", "pour", "pourquoi", "pourrait", "quand", "quel", "quelle", "quelles", "quels",
      "quelque", "quelques", "rien", "sans", "sera", "serait", "selon", "sont", "sous", "suis",
      "surtout", "tant", "tellement", "tous", "tout", "toute", "toutes", "très", "trop", "truc",
      "trucs", "vais", "vers", "voilà", "vont", "votre", "vous", "vraiment", "voir", "veux", "veut",
      "faudrait", "avait", "avais", "aurait", "était", "ai", "as", "avons", "avez", "ont", "c'est",
      "j'ai", "qu'il", "qu'on", "d'un", "d'une", "l'idée", "idée", "idées", "note", "notes", "euh",
      "heu", "ouais", "voila", "donc", "ça", "cela", "ceci", "celles", "là", "ici", "oui", "non",
      "aujourd'hui", "demain", "hier", "maintenant", "toujours", "souvent", "autour", "moment",
      "manière", "façon", "chose", "quoi", "dire", "dit", "dis", "mettre", "prendre", "pouvoir",
      "devoir", "aller", "venir", "petit", "petite", "grand", "grande", "nouveau", "nouvelle",
      // English
      "about", "after", "also", "because", "been", "before", "could", "from", "have", "just",
      "like", "more", "should", "some", "that", "their", "there", "they", "this", "what", "when",
      "where", "which", "will", "with", "would", "your",
    )

  /** Folds case and accents, for comparing titles and words. */
  fun fold(s: String): String =
    Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("\\s+"), " ")

  /** Sentences of [text] (the recognizer often gives none: then the whole text is one). */
  fun sentences(text: String): List<String> =
    text.trim().split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

  /**
   * A title from the first sentence, or from the first words when the text has no punctuation
   * (what the recognizer usually gives), cut on a word boundary.
   */
  fun heuristicTitle(text: String): String {
    val clean = text.trim().replace(Regex("\\s+"), " ")
    if (clean.isEmpty()) return "Note sans titre"
    val first = sentences(clean).first().trimEnd('.', '!', '?', '…', ',', ';', ':')
    val words = first.split(' ').filter { it.isNotBlank() }
    val base = if (words.size > 9) words.take(7).joinToString(" ") else first
    return capitalize(cutOnWord(base, MAX_TITLE))
  }

  /** One or two sentences, or the first words followed by an ellipsis. */
  fun heuristicSummary(text: String): String {
    val clean = text.trim().replace(Regex("\\s+"), " ")
    if (clean.isEmpty()) return ""
    val s = sentences(clean)
    if (s.size > 1) {
      val two = s.take(2).joinToString(" ")
      return if (two.length <= 240) two else cutOnWord(s.first(), 240)
    }
    val words = clean.split(' ')
    return if (words.size <= 30) capitalize(clean) else capitalize(words.take(25).joinToString(" ")) + "…"
  }

  /**
   * Tags from the most frequent meaningful words (4 letters or more, no stop words), the most
   * frequent first, ties broken by first appearance. Lower case, accents kept.
   */
  fun heuristicTags(text: String, max: Int = 5): List<String> {
    val counts = HashMap<String, Int>()
    val order = HashMap<String, Int>()
    for (m in WORD.findAll(text.lowercase())) {
      val w = m.value.trim('\'', '’', '-')
      if (w.length < 4 || w in STOPWORDS || fold(w) in STOPWORDS) continue
      if (w.contains('\'') || w.contains('’')) continue
      // Plurals and their singular count as one word.
      val key = singular(w)
      counts[key] = (counts[key] ?: 0) + 1
      if (key !in order) order[key] = order.size
    }
    return counts.keys
      .sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { order.getValue(it) })
      .take(max)
  }

  /** Cleans tags: lower case, no '#', short, no duplicates, at most [MAX_TAGS]. */
  fun normalizeTags(tags: List<String>): List<String> {
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    for (t in tags) {
      val tag =
        t.trim().trimStart('#').lowercase().replace(Regex("[\\s_]+"), " ").replace(Regex("[\"\\[\\]{}]"), "").trim()
      if (tag.isEmpty() || tag.length > 32 || tag.split(' ').size > 3) continue
      if (fold(tag) in STOPWORDS) continue
      if (seen.add(fold(tag))) out.add(tag)
      if (out.size == MAX_TAGS) break
    }
    return out
  }

  /** The raw transcript made readable without a model: capital letter and final full stop. */
  fun tidyTranscript(raw: String): String {
    val t = raw.trim().replace(Regex("[ \\t]+"), " ")
    if (t.isEmpty()) return t
    val capital = capitalize(t)
    return if (capital.last() in ".!?…") capital else "$capital."
  }

  /** Titles cited as `[[Title]]`, `[[Title|alias]]` or `[[Title#section]]`, in order, no duplicates. */
  fun extractWikilinks(text: String): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (m in WIKILINK.findAll(text)) {
      val title = m.groupValues[1].trim()
      if (title.isNotEmpty() && seen.add(fold(title))) out.add(title)
    }
    return out
  }

  // --- Markdown export ---------------------------------------------------------------------------

  /** A file name (without extension) that Android and Obsidian accept, from a title. */
  fun fileNameFor(title: String): String {
    val cleaned =
      title.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.')
    return cutOnWord(cleaned.ifEmpty { "Note" }, 80)
  }

  /**
   * Unique file names for [notes] (Obsidian resolves `[[name]]` by file name): two notes with the
   * same title get "Titre", "Titre (2)"... in creation order.
   */
  fun exportNames(notes: List<VoiceNote>): Map<String, String> {
    val used = HashMap<String, Int>()
    val out = HashMap<String, String>()
    for (n in notes.sortedWith(compareBy<VoiceNote> { it.createdAt }.thenBy { it.id })) {
      val base = fileNameFor(n.title)
      val key = fold(base)
      val count = (used[key] ?: 0) + 1
      used[key] = count
      out[n.id] = if (count == 1) base else "$base ($count)"
    }
    return out
  }

  /**
   * The note in Markdown: YAML front matter (dates, tags, who structured it), title, summary,
   * text, and the related notes as `[[wikilinks]]` ([linkedIds], by export name in [names]).
   */
  fun toMarkdown(
    note: VoiceNote,
    names: Map<String, String>,
    linkedIds: List<String>,
    zone: ZoneId = ZoneId.systemDefault(),
  ): String {
    val date = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    fun iso(ms: Long) = date.format(Instant.ofEpochMilli(ms).atZone(zone).withNano(0))
    fun yaml(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return buildString {
      append("---\n")
      append("title: ").append(yaml(note.title)).append('\n')
      append("created: ").append(iso(note.createdAt)).append('\n')
      append("updated: ").append(iso(note.updatedAt)).append('\n')
      if (note.tags.isNotEmpty()) {
        append("tags:\n")
        for (t in note.tags) append("  - ").append(yaml(t.replace(' ', '-'))).append('\n')
      }
      append("source: note vocale (Altrex)\n")
      note.structuredBy?.let { append("structured_by: ").append(yaml(it)).append('\n') }
      append("---\n\n")
      append("# ").append(note.title).append("\n\n")
      if (note.summary.isNotBlank()) append("> ").append(note.summary.trim().replace("\n", "\n> ")).append("\n\n")
      append(note.text.trim()).append('\n')
      val links = linkedIds.mapNotNull { names[it] }.distinct()
      if (links.isNotEmpty()) {
        append("\n## Notes liées\n\n")
        for (l in links) append("- [[").append(l).append("]]\n")
      }
      if (note.rawTranscript.isNotBlank() && note.rawTranscript.trim() != note.text.trim()) {
        append("\n<details><summary>Transcription brute</summary>\n\n")
        append(note.rawTranscript.trim()).append("\n\n</details>\n")
      }
    }
  }

  // --- Small helpers -----------------------------------------------------------------------------

  fun capitalize(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

  fun cutOnWord(s: String, max: Int): String {
    if (s.length <= max) return s
    val cut = s.take(max)
    val space = cut.lastIndexOf(' ')
    return (if (space > max / 2) cut.take(space) else cut).trimEnd(',', ';', ':', ' ') + "…"
  }

  private fun singular(w: String): String =
    if (
      w.length > 4 && (w.endsWith("s") || w.endsWith("x")) &&
        !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") && !w.endsWith("ux")
    ) {
      w.dropLast(1)
    } else {
      w
    }
}
