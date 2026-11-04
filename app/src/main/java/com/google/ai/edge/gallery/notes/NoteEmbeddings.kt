package com.google.ai.edge.gallery.notes

import android.content.Context
import com.google.ai.edge.gallery.runtime.SharedModelSession
import com.google.ai.edge.gallery.search.Embedder
import com.google.ai.edge.gallery.search.LocalMemory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * [ALTREX] Vectors of meaning of the notes, for the "similar" links of the graph. Only when the
 * user has already turned on the local memory's search by meaning (its EmbeddingGemma model is
 * downloaded and wanted): the same on-device model, through [Embedder] (one computation at a time,
 * guarded by its own lock, safe against release). Computed in the background, one note at a time,
 * never while the chat writes an answer, and cached on disk by note id and content hash, so each
 * note is computed once per version. Nothing leaves the phone.
 */
class NoteEmbeddings(private val context: Context, private val dir: File) {
  private val file = File(dir, "vectors.bin")
  private val cache = HashMap<String, Pair<String, FloatArray>>()
  private var loaded = false

  fun isAvailable(): Boolean = LocalMemory.isSemanticEnabled(context)

  /**
   * Vectors of [notes] (those already known, plus any computed now). [onProgress] receives the
   * vectors so far after each new one. Stops early if the coroutine is cancelled.
   */
  suspend fun vectorsFor(notes: List<VoiceNote>, onProgress: (Map<String, FloatArray>) -> Unit = {}): Map<String, FloatArray> {
    if (!isAvailable()) return emptyMap()
    load()
    val out = HashMap<String, FloatArray>()
    val missing = ArrayList<Pair<VoiceNote, String>>()
    for (n in notes) {
      val h = hashOf(n)
      val c = cache[n.id]
      if (c != null && c.first == h) out[n.id] = c.second else missing.add(n to h)
    }
    if (missing.isEmpty()) return out
    var dirty = false
    for ((n, h) in missing) {
      coroutineContext.ensureActive()
      // The chat's answer comes first.
      withTimeoutOrNull(120_000) { SharedModelSession.chatGenerating.first { !it } } ?: break
      val v = Embedder.embedPassage(context, n.title, (n.summary + "\n" + n.text).take(1_500)) ?: break
      cache[n.id] = h to v
      out[n.id] = v
      dirty = true
      onProgress(HashMap(out))
    }
    // Notes deleted since: forget their vectors.
    val ids = notes.map { it.id }.toSet()
    if (cache.keys.retainAll(ids)) dirty = true
    if (dirty) save()
    return out
  }

  private fun hashOf(n: VoiceNote): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update((n.title + "\u0000" + n.summary + "\u0000" + n.text).toByteArray())
    return md.digest().take(12).joinToString("") { "%02x".format(it) }
  }

  @Synchronized
  private fun load() {
    if (loaded) return
    loaded = true
    if (!file.isFile) return
    try {
      DataInputStream(file.inputStream().buffered()).use { input ->
        val count = input.readInt()
        repeat(count) {
          val id = input.readUTF()
          val h = input.readUTF()
          val dim = input.readInt()
          if (dim !in 1..4096) return
          val v = FloatArray(dim) { input.readFloat() }
          cache[id] = h to v
        }
      }
    } catch (e: Exception) {
      // A broken cache is only a cache: start again.
      cache.clear()
    }
  }

  @Synchronized
  private fun save() {
    try {
      dir.mkdirs()
      val tmp = File(dir, "vectors.bin.tmp")
      DataOutputStream(tmp.outputStream().buffered()).use { out ->
        out.writeInt(cache.size)
        for ((id, p) in cache) {
          out.writeUTF(id)
          out.writeUTF(p.first)
          out.writeInt(p.second.size)
          for (f in p.second) out.writeFloat(f)
        }
      }
      if (!tmp.renameTo(file)) tmp.delete()
    } catch (_: Exception) {}
  }
}
