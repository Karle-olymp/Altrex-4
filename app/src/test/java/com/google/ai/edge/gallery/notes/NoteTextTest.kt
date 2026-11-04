package com.google.ai.edge.gallery.notes

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextTest {
  @Test
  fun titleFromFirstSentence() {
    assertEquals("Chiffrer les sauvegardes", NoteText.heuristicTitle("Chiffrer les sauvegardes. Ensuite tester la restauration."))
  }

  @Test
  fun titleFromFirstWordsWithoutPunctuation() {
    val t = NoteText.heuristicTitle("il faudrait que je pense à chiffrer les sauvegardes du serveur avant la fin du mois")
    assertEquals("Il faudrait que je pense à chiffrer", t)
  }

  @Test
  fun titleOfEmptyText() {
    assertEquals("Note sans titre", NoteText.heuristicTitle("   "))
  }

  @Test
  fun longTitleIsCutOnAWord() {
    val t = NoteText.heuristicTitle("Anticonstitutionnellement ".repeat(6).trim() + ".")
    assertTrue(t.length <= NoteText.MAX_TITLE + 1)
    assertTrue(t.endsWith("…"))
  }

  @Test
  fun tagsFromFrequentWords() {
    val tags =
      NoteText.heuristicTags(
        "les sauvegardes du serveur doivent être chiffrées, une sauvegarde par jour, " +
          "et le serveur doit vérifier chaque sauvegarde avec une clé"
      )
    assertEquals("sauvegarde", tags.first())
    assertTrue("serveur" in tags)
    assertFalse(tags.any { it in setOf("doivent", "avec", "chaque", "être", "une") && it.length < 4 })
    assertTrue(tags.size <= 5)
  }

  @Test
  fun normalizeTags() {
    assertEquals(
      listOf("sécurité", "clé api", "réseau"),
      NoteText.normalizeTags(listOf(" #Sécurité", "clé_api", "SECURITE", "réseau", "", "une phrase bien trop longue pour un tag")),
    )
    assertEquals(NoteText.MAX_TAGS, NoteText.normalizeTags((1..10).map { "tag$it" }).size)
  }

  @Test
  fun summary() {
    assertEquals("Un. Deux.", NoteText.heuristicSummary("Un. Deux. Trois."))
    assertTrue(NoteText.heuristicSummary("mot ".repeat(40)).endsWith("…"))
  }

  @Test
  fun wikilinks() {
    val links =
      NoteText.extractWikilinks("Voir [[Sauvegardes]] et [[Plan réseau|le plan]], [[Sauvegardes#détail]], [[ ]] et [pas un lien]")
    assertEquals(listOf("Sauvegardes", "Plan réseau"), links)
    assertEquals(emptyList<String>(), NoteText.extractWikilinks("aucun lien ici [[]]"))
  }

  @Test
  fun fileNames() {
    assertEquals("Idée a b c", NoteText.fileNameFor("Idée a/b: c?"))
    val notes =
      listOf(
        note("1", "Même titre", createdAt = 1),
        note("2", "même titre", createdAt = 2),
        note("3", "Autre", createdAt = 3),
      )
    assertEquals(mapOf("1" to "Même titre", "2" to "même titre (2)", "3" to "Autre"), NoteText.exportNames(notes))
  }

  @Test
  fun markdownHasFrontMatterAndWikilinks() {
    val a = note("a", "Sauvegardes", tags = listOf("chiffrement", "clé api"), summary = "Résumé \"cité\".")
    val b = note("b", "Plan réseau")
    val md = NoteText.toMarkdown(a, NoteText.exportNames(listOf(a, b)), listOf("b"), ZoneOffset.UTC)
    assertTrue(md.startsWith("---\ntitle: \"Sauvegardes\"\n"))
    assertTrue(md.contains("  - \"clé-api\"\n"))
    assertTrue(md.contains("> Résumé \"cité\"."))
    assertTrue(md.contains("- [[Plan réseau]]"))
    assertTrue(md.contains("# Sauvegardes\n"))
  }

  @Test
  fun tidyTranscript() {
    assertEquals("Bonjour à tous.", NoteText.tidyTranscript("  bonjour   à tous "))
    assertEquals("Vraiment ?", NoteText.tidyTranscript("vraiment ?"))
  }

  companion object {
    fun note(
      id: String,
      title: String,
      tags: List<String> = emptyList(),
      text: String = "texte de $title",
      summary: String = "",
      createdAt: Long = 0,
    ) = VoiceNote(id, createdAt, createdAt, title, text, text, summary, tags, null)
  }
}
