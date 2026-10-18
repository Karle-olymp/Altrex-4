package com.google.ai.edge.gallery.search

import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryIndexTest {
  private fun build(): MemoryIndex {
    val texts =
      listOf(
        "notes/nginx.md" to
          "Durcissement nginx du serveur maison.\n\nDésactiver server_tokens et autoindex.\n\n" +
          "Le dossier .git ne doit jamais être servi : ajouter location ~ /\\.git { deny all; }",
        "notes/sauvegardes.md" to
          "Les sauvegardes partent chaque nuit vers le NAS avec restic, chiffrées, rétention 30 jours.",
        "courses.txt" to "Acheter du pain, du lait et des pommes pour la semaine.",
      ) + (1..30).map { "remplissage/$it.txt" to "Paragraphe de remplissage numéro $it sur la météo du jour et le jardin." }
    val files = ArrayList<MemoryFile>()
    val chunks = ArrayList<MemoryChunk>()
    for ((i, pair) in texts.withIndex()) {
      files.add(MemoryFile("content://x/$i", pair.first, 1000L + i, pair.second.length.toLong()))
      chunks.addAll(MemoryIndex.chunkText(pair.second, i, 0, 0))
    }
    return MemoryIndex(files, chunks)
  }

  @Test
  fun findsTheRightNoteWithAccentsAndPlurals() {
    val hits = build().search("Comment sont faites mes sauvegardes ?", budgetChars = 2_000)
    assertTrue(hits.isNotEmpty())
    assertEquals("notes/sauvegardes.md", hits.first().path)
  }

  @Test
  fun smallTalkBringsNothing() {
    assertTrue(build().search("Bonjour, ça va ?", budgetChars = 2_000).isEmpty())
  }

  @Test
  fun passagesStayWithinBudgetAndKeepFollowingPiece() {
    val hits = build().search("nginx git autoindex", budgetChars = 1_000)
    assertEquals("notes/nginx.md", hits.first().path)
    assertTrue(hits.first().text.contains("deny all"))
    assertTrue(hits.sumOf { it.text.length } <= 1_000)
  }

  @Test
  fun indexSurvivesSaveAndLoad() {
    val index = build()
    val out = StringWriter()
    index.write(out)
    val loaded = MemoryIndex.read(BufferedReader(StringReader(out.toString())))!!
    assertEquals(index.files, loaded.files)
    assertEquals(index.chunks, loaded.chunks)
    assertEquals("notes/sauvegardes.md", loaded.search("restic NAS", 2_000).first().path)
  }

  @Test
  fun sourcesNameEachFileOnceWithItsPages() {
    val hits =
      listOf(
        MemoryHit("docs/rapport.pdf", 25, "a", 1.0),
        MemoryHit("notes.md", 0, "b", 0.9),
        MemoryHit("docs/rapport.pdf", 4, "c", 0.8),
        MemoryHit("docs/rapport.pdf", 25, "d", 0.7),
      )
    assertEquals("rapport.pdf (p. 4, 25), notes.md", MemoryIndex.sourcesLabel(hits))
  }
}
