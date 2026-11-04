package com.google.ai.edge.gallery.notes

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What structuring a note produces (by a model or by the heuristics). */
data class StructuredNote(
  val title: String,
  val text: String,
  val summary: String,
  val tags: List<String>,
)

/**
 * [ALTREX] Asking a model to put a voice note in shape, and reading its answer. The model is asked
 * for JSON, but small models wrap it in a code fence, add a sentence, think aloud first or stop
 * half way: the answer is read defensively, and anything missing falls back to the heuristics of
 * [NoteText]. Pure functions, no Android.
 */
object NoteStructuring {
  /** Beyond this, the transcript is not rewritten by the model (context of small models). */
  const val MAX_REWRITE_CHARS = 6_000

  /** What the model sees of a very long transcript, for its title, summary and tags only. */
  private const val MAX_PROMPT_CHARS = 8_000

  @OptIn(ExperimentalSerializationApi::class)
  private val lenientJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    allowTrailingComma = true
  }

  const val SYSTEM_PROMPT =
    "Tu mets en forme des notes vocales dictées par l'utilisateur. Tu corriges la forme, jamais le " +
      "fond : tu n'ajoutes aucune information, aucun conseil, aucun avis. Tu réponds uniquement en JSON."

  /** Messages (role, content) for [rawTranscript]. */
  fun messages(rawTranscript: String): List<Pair<String, String>> {
    val raw = rawTranscript.trim()
    val rewrite = raw.length <= MAX_REWRITE_CHARS
    val shown = if (raw.length > MAX_PROMPT_CHARS) raw.take(MAX_PROMPT_CHARS) + " […]" else raw
    val user = buildString {
      append("Voici la transcription automatique d'une note vocale (sans ponctuation, avec de possibles erreurs de reconnaissance).\n\n")
      append("Réponds UNIQUEMENT avec cet objet JSON, sans texte autour :\n")
      if (rewrite) {
        append("{\"titre\": \"...\", \"texte\": \"...\", \"resume\": \"...\", \"tags\": [\"...\", \"...\", \"...\"]}\n\n")
      } else {
        append("{\"titre\": \"...\", \"resume\": \"...\", \"tags\": [\"...\", \"...\", \"...\"]}\n\n")
      }
      append("Règles :\n")
      append("- titre : 3 à 8 mots qui disent de quoi parle la note.\n")
      if (rewrite) {
        append("- texte : la transcription corrigée (ponctuation, majuscules, paragraphes, mots mal reconnus évidents). ")
        append("Garde les mots et le sens de l'auteur, ne résume pas, ne rajoute rien.\n")
      }
      append("- resume : 1 ou 2 phrases.\n")
      append("- tags : 3 à 6 mots-clés courts, en minuscules, sans #.\n")
      append("- Écris dans la langue de la note.\n\n")
      append("Transcription :\n\"\"\"\n").append(shown).append("\n\"\"\"")
    }
    return listOf("system" to SYSTEM_PROMPT, "user" to user)
  }

  /** What the heuristics give for [raw] (no model, or an answer that could not be read). */
  fun heuristic(raw: String): StructuredNote {
    val text = NoteText.tidyTranscript(raw)
    return StructuredNote(
      title = NoteText.heuristicTitle(raw),
      text = text,
      summary = NoteText.heuristicSummary(text),
      tags = NoteText.normalizeTags(NoteText.heuristicTags(raw)),
    )
  }

  /**
   * Reads the model's [answer] for the transcript [raw]. Returns null when nothing usable is in it
   * (the caller then keeps the heuristics); otherwise every field is filled, missing ones by the
   * heuristics. A "cleaned" text much shorter than the transcript is a summary, not a clean-up:
   * the transcript is kept then.
   */
  fun parse(answer: String, raw: String): StructuredNote? {
    val fields = readFields(answer) ?: return null
    val fallback = heuristic(raw)
    val title =
      fields.title?.let { NoteText.cutOnWord(it.trim().trim('"', '#', ' ').replace(Regex("\\s+"), " "), NoteText.MAX_TITLE) }
        ?.takeIf { it.isNotBlank() }
    val rawWords = wordCount(raw)
    val text =
      fields.text?.trim()?.takeIf {
        it.isNotBlank() && raw.length <= MAX_REWRITE_CHARS && wordCount(it) >= (rawWords * 0.6).toInt()
      }
    val summary = fields.summary?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }
    val tags = NoteText.normalizeTags(fields.tags.orEmpty())
    if (title == null && text == null && summary == null && tags.isEmpty()) return null
    return StructuredNote(
      title = title ?: fallback.title,
      text = text ?: fallback.text,
      summary = NoteText.cutOnWord(summary ?: NoteText.heuristicSummary(text ?: fallback.text), 400),
      tags = if (tags.size >= 2) tags else NoteText.normalizeTags(tags + fallback.tags),
    )
  }

  private class Fields(val title: String?, val text: String?, val summary: String?, val tags: List<String>?)

  private fun readFields(answer: String): Fields? {
    var s = answer
    // Reasoning first ("<think>…</think>"), then possibly a fenced block.
    s = s.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*</think>"), "")
    Regex("(?s)```(?:json|JSON)?\\s*(.*?)```").find(s)?.let { s = it.groupValues[1] }
    if (s.isBlank()) return null
    val start = s.indexOf('{')
    if (start >= 0) {
      val end = s.lastIndexOf('}')
      if (end > start) fromJson(s.substring(start, end + 1))?.let { return it }
      // Cut short: try closing it.
      fromJson(closeJson(s.substring(start)))?.let { return it }
    }
    return fromRegex(s)
  }

  private fun fromJson(candidate: String): Fields? {
    val obj =
      try {
        lenientJson.parseToJsonElement(candidate) as? JsonObject
      } catch (e: Exception) {
        null
      } ?: return null
    fun str(vararg keys: String): String? =
      keys.firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
    val tagsElement = listOf("tags", "mots_cles", "motsCles", "keywords").firstNotNullOfOrNull { obj[it] }
    val tags =
      when (tagsElement) {
        is JsonArray -> tagsElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> tagsElement.contentOrNull?.split(',', ';')
        else -> null
      }
    val f =
      Fields(
        title = str("titre", "title"),
        text = str("texte", "text", "texte_corrige", "content"),
        summary = str("resume", "résumé", "summary", "resumé"),
        tags = tags,
      )
    return if (f.title == null && f.text == null && f.summary == null && f.tags.isNullOrEmpty()) null else f
  }

  /** Field by field, for an answer that is almost JSON (unescaped quote, missing comma...). */
  private fun fromRegex(s: String): Fields? {
    fun field(vararg keys: String): String? =
      keys.firstNotNullOfOrNull { k ->
        Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
          ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.takeIf { it.isNotBlank() }
      }
    val tags =
      Regex("\"(?:tags|keywords)\"\\s*:\\s*\\[([^\\]]*)]?", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
        ?.let { inner -> Regex("\"([^\"]+)\"").findAll(inner).map { it.groupValues[1] }.toList() }
    val f = Fields(field("titre", "title"), field("texte", "text"), field("resume", "résumé", "summary"), tags)
    return if (f.title == null && f.text == null && f.summary == null && f.tags.isNullOrEmpty()) null else f
  }

  /** Closes an open string, array and object at the end of a JSON cut short. */
  private fun closeJson(s: String): String {
    val sb = StringBuilder(s.trimEnd().trimEnd(','))
    var inString = false
    var escaped = false
    val stack = ArrayDeque<Char>()
    for (c in sb) {
      if (escaped) {
        escaped = false
        continue
      }
      when {
        c == '\\' && inString -> escaped = true
        c == '"' -> inString = !inString
        !inString && (c == '{' || c == '[') -> stack.addLast(c)
        !inString && (c == '}' || c == ']') -> stack.removeLastOrNull()
      }
    }
    if (inString) sb.append('"')
    while (stack.isNotEmpty()) sb.append(if (stack.removeLast() == '{') '}' else ']')
    return sb.toString()
  }

  private fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }
}
