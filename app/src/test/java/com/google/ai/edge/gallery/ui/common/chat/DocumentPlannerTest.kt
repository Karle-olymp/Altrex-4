package com.google.ai.edge.gallery.ui.common.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPlannerTest {
  private fun filler(page: Int) =
    (1..12).joinToString("\n\n") { "Paragraphe $it de la page $page : le projet avance selon le calendrier prévu." }

  private val longPdf =
    AttachedDocument(
      name = "rapport.pdf",
      isPdf = true,
      pages = 40,
      pageTexts =
        List(40) { i ->
          if (i == 26) {
            filler(i + 1) + "\n\nLa configuration nginx expose le dossier .git et autorise TLSv1."
          } else {
            filler(i + 1)
          }
        },
    )

  @Test
  fun shortDocumentIsSentWhole() {
    val doc = AttachedDocument("note.txt", isPdf = false, pages = 1, pageTexts = listOf("Bonjour."))
    assertTrue(DocumentPlanner.plan(doc, "Résume", 8_000, argoraFormat = false) is DocumentPlan.Direct)
  }

  @Test
  fun preciseQuestionKeepsMatchingPassages() {
    val plan = DocumentPlanner.plan(longPdf, "Que dit le rapport sur nginx et TLSv1 ?", 8_000, false)
    assertTrue(plan is DocumentPlan.Passages)
    plan as DocumentPlan.Passages
    assertTrue(plan.pages.contains(27))
    assertTrue(plan.prompt.contains("expose le dossier .git"))
    assertTrue(plan.prompt.length < 9_000)
  }

  @Test
  fun summaryIsReadInParts() {
    val plan = DocumentPlanner.plan(longPdf, "Résume ce document", 8_000, false)
    assertTrue(plan is DocumentPlan.Sections)
    plan as DocumentPlan.Sections
    assertTrue(plan.sections.size in 2..DocumentPlanner.MAX_SECTIONS)
    assertEquals(1, plan.sections.first().firstPage)
    assertEquals(40, plan.sections.last().lastPage)
    plan.sections.forEach { assertTrue(it.text.length <= 8_000) }
  }

  @Test
  fun hugeDocumentIsCondensed() {
    val huge = longPdf.copy(pages = 400, pageTexts = List(400) { filler(it + 1) })
    val plan = DocumentPlanner.plan(huge, "", 8_000, false) as DocumentPlan.Sections
    assertEquals(DocumentPlanner.MAX_SECTIONS, plan.sections.size)
    assertTrue(plan.condensed)
  }
}
