package com.google.ai.edge.gallery.search

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search by meaning with real EmbeddingGemma vectors (computed by llama.cpp b11385, the version
 * Altrex runs, on the same prompts as the app: "title: … | text: …" and "task: search result |
 * query: …").
 */
class SemanticSearchTest {
  private val fixture: JSONObject by lazy {
    JSONObject(javaClass.classLoader!!.getResource("embeddinggemma_fixture.json").readText())
  }

  private fun vector(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

  private fun build(): Pair<MemoryIndex, VectorStore> {
    val notes = fixture.getJSONArray("notes")
    val files = ArrayList<MemoryFile>()
    val chunks = ArrayList<MemoryChunk>()
    for (i in 0 until notes.length()) {
      val n = notes.getJSONObject(i)
      files.add(MemoryFile("content://x/$i", n.getString("path"), 0L, 0L))
      chunks.add(MemoryChunk(i, 0, 0, n.getString("text")))
    }
    val store = VectorStore(Embedder.DIM, chunks.size, Embedder.MODEL_NAME)
    for (i in 0 until notes.length()) store.set(i, vector(notes.getJSONObject(i).getJSONArray("vector")))
    return MemoryIndex(files, chunks) to store
  }

  private fun ask(question: String): List<MemoryHit> {
    val (index, store) = build()
    val queries = fixture.getJSONArray("queries")
    val q = (0 until queries.length()).map { queries.getJSONObject(it) }.first { it.getString("text") == question }
    return index.search(question, 2_000, semantic = MemoryIndex.Semantic(store, vector(q.getJSONArray("vector")), Embedder.MIN_COSINE))
  }

  @Test
  fun synonymsAndRephrasingsAreFound() {
    // No word in common with the note ("backups", "réseau sans fil", "SSH par clé").
    assertEquals("notes/sauvegardes.md", ask("comment sont faites mes sauvegardes ?").first().path)
    assertEquals("notes/wifi.md", ask("quel mot de passe pour le wifi de la maison").first().path)
    assertEquals("notes/nginx.md", ask("comment protéger mon serveur web").first().path)
    assertEquals("notes/ssh.md", ask("est-ce que je peux me connecter à distance avec un mot de passe ?").first().path)
  }

  @Test
  fun unrelatedNotesStayOut() {
    assertTrue(ask("bonjour ça va").isEmpty())
    // The wifi question must not drag the SSH note in (it also talks about passwords).
    assertEquals(listOf("notes/wifi.md"), ask("quel mot de passe pour le wifi de la maison").map { it.path })
  }

  @Test
  fun quantizedVectorsKeepTheirCosineAndSurviveSaving() {
    val notes = fixture.getJSONArray("notes")
    val a = vector(notes.getJSONObject(0).getJSONArray("vector"))
    val (_, store) = build()
    val exact = a.indices.sumOf { (a[it] * a[it]).toDouble() }
    assertEquals(exact, store.cosine(0, a).toDouble(), 0.01)
    val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use { out -> store.write(out) } }.toByteArray()
    val loaded = VectorStore.read(DataInputStream(ByteArrayInputStream(bytes)))!!
    assertEquals(store.cosine(3, a), loaded.cosine(3, a), 1e-6f)
    assertEquals(store.count, loaded.computed)
  }
}
