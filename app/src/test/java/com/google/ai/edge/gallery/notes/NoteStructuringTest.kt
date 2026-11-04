package com.google.ai.edge.gallery.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteStructuringTest {
  private val raw =
    "il faut que je chiffre les sauvegardes du serveur et que je change la clé api du dépôt " +
      "parce qu'elle a fuité dans les logs"
  private val clean =
    "Il faut que je chiffre les sauvegardes du serveur et que je change la clé API du dépôt, " +
      "parce qu'elle a fuité dans les logs."

  @Test
  fun goodJson() {
    val s =
      NoteStructuring.parse(
        """{"titre": "Clé API et sauvegardes", "texte": "$clean", "resume": "Chiffrer et changer la clé.", "tags": ["Sécurité", "#sauvegarde", "clé api"]}""",
        raw,
      )!!
    assertEquals("Clé API et sauvegardes", s.title)
    assertEquals(clean, s.text)
    assertEquals("Chiffrer et changer la clé.", s.summary)
    assertEquals(listOf("sécurité", "sauvegarde", "clé api"), s.tags)
  }

  @Test
  fun fencedWithThinkingAndChatter() {
    val answer =
      "<think>je dois rendre du JSON</think>Voici la note :\n```json\n" +
        """{"title": "Clé API", "text": "$clean", "summary": "Résumé.", "tags": ["sécurité", "logs"],}""" +
        "\n```\nBonne journée !"
    val s = NoteStructuring.parse(answer, raw)!!
    assertEquals("Clé API", s.title)
    assertEquals(clean, s.text)
    assertTrue("logs" in s.tags)
  }

  @Test
  fun cutShortAnswerIsClosed() {
    val s = NoteStructuring.parse("""{"titre": "Clé API", "resume": "Changer la clé", "tags": ["sécurité", "clé""", raw)!!
    assertEquals("Clé API", s.title)
    assertEquals("Changer la clé", s.summary)
    // The text was not given: the tidied transcript stands in for it.
    assertEquals(NoteText.tidyTranscript(raw), s.text)
    assertTrue(s.tags.contains("sécurité"))
  }

  @Test
  fun brokenJsonReadFieldByField() {
    val s = NoteStructuring.parse("""{"titre": "Clé "API" fuitée" "resume": "Résumé." tags: ["a", "b"]""", raw)
    assertNotNull(s)
    assertEquals("Résumé.", s!!.summary)
  }

  @Test
  fun emptyOrUselessAnswers() {
    assertNull(NoteStructuring.parse("", raw))
    assertNull(NoteStructuring.parse("   ", raw))
    assertNull(NoteStructuring.parse("Je ne peux pas faire ça.", raw))
    assertNull(NoteStructuring.parse("{}", raw))
    assertNull(NoteStructuring.parse("<think>hmm", raw))
  }

  @Test
  fun aSummaryInPlaceOfTheTextIsRejected() {
    val s = NoteStructuring.parse("""{"titre": "Clé", "texte": "Changer la clé.", "tags": ["a", "b"]}""", raw)!!
    assertEquals(NoteText.tidyTranscript(raw), s.text)
  }

  @Test
  fun heuristicsFillEverything() {
    val h = NoteStructuring.heuristic(raw)
    assertTrue(h.title.isNotBlank())
    assertTrue(h.summary.isNotBlank())
    assertTrue(h.tags.isNotEmpty())
    assertEquals(NoteText.tidyTranscript(raw), h.text)
  }

  @Test
  fun promptAsksForJsonAndContainsTheTranscript() {
    val m = NoteStructuring.messages(raw)
    assertEquals("system", m.first().first)
    assertTrue(m.last().second.contains(raw))
    assertTrue(m.last().second.contains("\"texte\""))
    val long = NoteStructuring.messages("mot ".repeat(3000))
    assertTrue(!long.last().second.contains("\"texte\""))
  }
}
