package com.google.ai.edge.gallery.notes

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NoteStoreTest {
  private lateinit var dir: File
  private var now = 1_700_000_000_000L

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("notes").toFile()
  }

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  private fun store() = NoteStore(dir) { now }

  @Test
  fun roundTrip() {
    val s = store()
    val created = s.create("une idée pour le chiffrement des sauvegardes")
    now += 1000
    s.update(created.id) { it.copy(title = "Sauvegardes", tags = listOf("chiffrement"), summary = "Résumé.", structuredBy = "qwen") }

    val reloaded = store().apply { load() }
    val n = reloaded.get(created.id)
    assertNotNull(n)
    assertEquals("Sauvegardes", n!!.title)
    assertEquals(listOf("chiffrement"), n.tags)
    assertEquals("une idée pour le chiffrement des sauvegardes", n.rawTranscript)
    assertEquals("qwen", n.structuredBy)
    assertEquals(created.createdAt, n.createdAt)
    assertTrue(n.updatedAt > n.createdAt)
  }

  @Test
  fun newestFirst() {
    val s = store()
    val a = s.create("première", createdAt = 1000)
    val b = s.create("deuxième", createdAt = 2000)
    assertEquals(listOf(b.id, a.id), s.notes.value.map { it.id })
  }

  @Test
  fun atomicWriteLeavesNoTemporaryFile() {
    val s = store()
    val n = s.create("texte")
    s.update(n.id) { it.copy(text = "nouveau texte") }
    val names = dir.list()!!.toSet()
    assertEquals(setOf(n.id + ".json"), names)
    // A temp file left by a crash before the rename is ignored and the note is intact.
    File(dir, n.id + ".json.tmp").writeText("{\"id\":")
    val again = store().apply { load() }
    assertEquals("nouveau texte", again.get(n.id)!!.text)
    assertEquals(0, again.skippedFiles)
  }

  @Test
  fun corruptedFileIsSkipped() {
    val s = store()
    val good = s.create("bonne note")
    File(dir, "abc.json").writeText("{ pas du json")
    File(dir, "def.json").writeText("")
    // A file whose id does not match its name is not trusted either.
    File(dir, "ghi.json").writeText(File(dir, good.id + ".json").readText())
    val again = store().apply { load() }
    assertEquals(listOf(good.id), again.notes.value.map { it.id })
    assertEquals(3, again.skippedFiles)
    // The broken files are left in place (nothing is deleted behind the user's back).
    assertTrue(File(dir, "abc.json").exists())
  }

  @Test
  fun delete() {
    val s = store()
    val n = s.create("à supprimer")
    assertTrue(s.delete(n.id))
    assertNull(s.get(n.id))
    assertFalse(File(dir, n.id + ".json").exists())
    assertFalse(s.delete("../etc/passwd"))
  }

  @Test
  fun draftSurvivesAndBecomesANote() {
    val s = store()
    s.saveDraft(NoteDraft(startedAt = 500, updatedAt = 600, transcript = "texte dicté avant un plantage"))
    // A new process finds it.
    val again = store()
    assertEquals("texte dicté avant un plantage", again.readDraft()!!.transcript)
    val note = again.commitDraft()!!
    assertEquals(500, note.createdAt)
    assertNull(again.readDraft())
    assertEquals(1, again.notes.value.size)
    // The draft is not mistaken for a note.
    assertEquals(setOf(note.id + ".json"), dir.list()!!.toSet())
  }

  @Test
  fun emptyDraftIsIgnored() {
    val s = store()
    s.saveDraft(NoteDraft(1, 1, "   "))
    assertNull(s.readDraft())
    assertNull(s.commitDraft())
  }
}
