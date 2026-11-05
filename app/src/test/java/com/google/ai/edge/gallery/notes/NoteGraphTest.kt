package com.google.ai.edge.gallery.notes

import com.google.ai.edge.gallery.notes.NoteTextTest.Companion.note
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteGraphTest {
  // --- Links ---------------------------------------------------------------------------------

  @Test
  fun noLinksForZeroOrOneNote() {
    assertTrue(NoteLinks.compute(emptyList()).isEmpty())
    assertTrue(NoteLinks.compute(listOf(note("a", "A", listOf("x")))).isEmpty())
  }

  @Test
  fun sharedTagsLink() {
    val notes = listOf(note("a", "A", listOf("réseau", "vpn")), note("b", "B", listOf("Réseau")), note("c", "C", listOf("cuisine")))
    val edges = NoteLinks.compute(notes)
    assertEquals(1, edges.size)
    val e = edges.single()
    assertEquals(0 to 1, e.a to e.b)
    assertTrue(e.byTags && !e.byWikilink)
  }

  @Test
  fun wikilinksLinkEitherWayAndWeighMore() {
    val notes =
      listOf(
        note("a", "Sauvegardes", text = "voir [[plan réseau]]"),
        note("b", "Plan réseau"),
        note("c", "Autre", listOf("t")),
        note("d", "Encore", listOf("t")),
      )
    val edges = NoteLinks.compute(notes)
    val link = edges.single { it.byWikilink }
    assertEquals(0 to 1, link.a to link.b)
    val tag = edges.single { it.byTags }
    assertTrue(link.weight > tag.weight)
  }

  @Test
  fun commonTagsWeighLessThanRareOnes() {
    val notes = (0 until 10).map { note("n$it", "N$it", listOf("commun") + if (it < 2) listOf("rare") else emptyList()) }
    val edges = NoteLinks.compute(notes, maxTagNeighbors = 100)
    val rare = edges.single { it.a == 0 && it.b == 1 }
    val common = edges.first { it.a == 2 && it.b == 3 }
    assertTrue(rare.weight > common.weight)
    assertTrue(edges.all { it.weight in 0f..1f })
  }

  @Test
  fun tagOnlyLinksAreCappedPerNote() {
    val notes = (0 until 12).map { note("n$it", "N$it", listOf("partout")) }
    val edges = NoteLinks.compute(notes, maxTagNeighbors = 3)
    val degree = IntArray(12)
    for (e in edges) {
      degree[e.a]++
      degree[e.b]++
    }
    // Every note keeps its 3 strongest links (equal here: the closest in time), no more than 2x.
    assertTrue(degree.all { it in 3..6 })
    assertTrue(edges.size < 12 * 11 / 2)
    // A tag on every note of a large set is too weak to link anything.
    assertTrue(NoteLinks.compute((0 until 40).map { note("m$it", "M$it", listOf("partout")) }).isEmpty())
  }

  @Test
  fun similarityLinksWhenVectorsGiven() {
    val notes = listOf(note("a", "A"), note("b", "B"), note("c", "C"))
    val v = mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0.95f, 0.1f), "c" to floatArrayOf(0f, 1f))
    val edges = NoteLinks.compute(notes, v)
    assertEquals(1, edges.size)
    assertTrue(edges.single().bySimilarity)
    // A broken vector is ignored, not a crash.
    assertTrue(NoteLinks.compute(notes, mapOf("a" to floatArrayOf(Float.NaN, 0f), "b" to floatArrayOf(0f, 0f))).isEmpty())
  }

  @Test
  fun deterministic() {
    val notes = sample(60)
    assertEquals(NoteLinks.compute(notes), NoteLinks.compute(notes))
  }

  @Test
  fun groupsByMainTag() {
    val notes =
      listOf(
        note("a", "A", listOf("rare", "sécurité")),
        note("b", "B", listOf("sécurité")),
        note("c", "C", listOf("cuisine")),
        note("d", "D"),
      )
    val (g, tags) = NoteGraph.groups(notes)
    assertEquals(listOf("sécurité", "cuisine"), tags)
    assertArrayEquals(intArrayOf(0, 0, 1, -1), g)
  }

  // --- Layout --------------------------------------------------------------------------------

  private fun assertFinite(l: ForceLayout) {
    for (i in l.x.indices) assertTrue("node $i", l.x[i].isFinite() && l.y[i].isFinite())
  }

  @Test
  fun layoutOfZeroOneTwoNodes() {
    for (n in 0..2) {
      val l = ForceLayout(NoteGraph.build(sample(n)))
      l.settle()
      assertTrue(l.isSettled)
      assertFinite(l)
    }
    // Two linked notes end up apart but close.
    val two = ForceLayout(NoteGraph.build(listOf(note("a", "A", listOf("x")), note("b", "B", listOf("x")))))
    two.settle()
    val d = Math.hypot((two.x[0] - two.x[1]).toDouble(), (two.y[0] - two.y[1]).toDouble())
    assertTrue("distance $d", d in 20.0..200.0)
  }

  @Test
  fun sameNotesSameLayout() {
    val g = NoteGraph.build(sample(80))
    val a = ForceLayout(g).apply { settle() }
    val b = ForceLayout(NoteGraph.build(sample(80))).apply { settle() }
    assertArrayEquals(a.x, b.x, 0f)
    assertArrayEquals(a.y, b.y, 0f)
  }

  @Test
  fun converges() {
    val l = ForceLayout(NoteGraph.build(sample(120)))
    val steps = l.settle(2_000)
    assertTrue(l.isSettled)
    assertTrue("steps $steps", steps in 100..400)
    assertTrue("energy ${l.energy()}", l.energy() < 0.05f)
    assertFinite(l)
  }

  @Test
  fun identicalPositionsDoNotProduceNaN() {
    val g = NoteGraph.build(sample(20))
    val l = ForceLayout(g, initial = g.notes.associate { it.id to (5f to 5f) })
    l.settle()
    assertFinite(l)
    // They spread out.
    assertTrue((1 until 20).any { l.x[it] != l.x[0] || l.y[it] != l.y[0] })
  }

  @Test
  fun rewarmKeepsKnownNodesNearTheirPlace() {
    val notes = sample(50)
    val first = ForceLayout(NoteGraph.build(notes)).apply { settle() }
    val more = notes + note("new", "Nouvelle", listOf("tag1"))
    val second = ForceLayout(NoteGraph.build(more), initial = first.positions(), startAlpha = 0.25f)
    second.settle()
    var moved = 0.0
    for (i in notes.indices) moved += Math.hypot((second.x[i] - first.x[i]).toDouble(), (second.y[i] - first.y[i]).toDouble())
    assertTrue("mean move ${moved / notes.size}", moved / notes.size < 40.0)
    assertFinite(second)
  }

  @Test
  fun threeHundredNodesInBoundedTime() {
    val g = NoteGraph.build(sample(300))
    // Warm-up of the JIT, then the measured run.
    ForceLayout(g).settle()
    val t0 = System.nanoTime()
    val l = ForceLayout(g)
    l.settle()
    val ms = (System.nanoTime() - t0) / 1_000_000
    assertTrue(l.isSettled)
    assertFinite(l)
    // The whole layout, not one frame; on the phone it is spread over many frames.
    assertTrue("took $ms ms", ms < 1_500)
  }

  companion object {
    private val TAGS = listOf("sécurité", "réseau", "code", "idée", "lecture", "projet", "santé", "cuisine")

    fun sample(n: Int): List<VoiceNote> =
      (0 until n).map { i ->
        val tags = listOf(TAGS[i % TAGS.size], TAGS[(i * 7 / 3) % TAGS.size], "t${i % 23}").distinct()
        val text = if (i > 0 && i % 5 == 0) "voir [[Note ${i / 2}]]" else "texte $i"
        note("id-$i", "Note $i", tags, text = text, createdAt = i.toLong())
      }
  }
}
