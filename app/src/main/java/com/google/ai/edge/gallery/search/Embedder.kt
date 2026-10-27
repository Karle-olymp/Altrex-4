package com.google.ai.edge.gallery.search

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaContext

/**
 * [ALTREX] The embedding model of the local memory's search by meaning: Google's EmbeddingGemma
 * 300M (multilingual, French included), QAT Q4_0 GGUF published by ggml-org, run by Altrex's own
 * llama.cpp on the CPU. Chosen after testing on French questions: a relevant note scores about
 * 0.53–0.58, unrelated ones below 0.40, small talk below 0.36.
 *
 * The file is downloaded once (278 MB) from a pinned revision and checked against its SHA-256
 * before use. Gemma terms of use: https://ai.google.dev/gemma/terms
 */
object Embedder {
  private const val TAG = "AGEmbedder"

  const val MODEL_NAME = "embeddinggemma-300m-qat-q4_0"
  private const val URL =
    "https://huggingface.co/ggml-org/embeddinggemma-300M-qat-q4_0-GGUF/resolve/" +
      "8dd0ca2a66a8f14470acb0e2a71f801afbc5fb73/embeddinggemma-300M-qat-Q4_0.gguf"
  private const val SHA256 = "50d28e22432a148f6f8a86eab3700f92add5d1f54baf7790675a2a4dadbccf26"
  const val SIZE_BYTES = 277_852_192L
  const val DIM = 768

  /** Below this cosine, a passage is not about the question (measured, see above). */
  const val MIN_COSINE = 0.45f

  private val mutex = Mutex()
  private var context: LlamaContext? = null

  /** [ALTREX] Set by [release] while a vector is being computed: the running [embed] frees it. */
  @Volatile private var releaseRequested = false

  fun modelFile(context: Context) = File(context.filesDir, "memory/$MODEL_NAME.gguf")

  fun isDownloaded(context: Context): Boolean = modelFile(context).length() == SIZE_BYTES

  /**
   * Downloads the model, checking its SHA-256. [onProgress] receives the bytes received so far.
   * Returns null when done, or an error message in French.
   */
  suspend fun download(context: Context, onProgress: (Long) -> Unit): String? =
    withContext(Dispatchers.IO) {
      val target = modelFile(context)
      target.parentFile?.mkdirs()
      val part = File(target.parentFile, "$MODEL_NAME.part")
      try {
        val conn = URL(URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (conn.responseCode != 200) return@withContext "le serveur a répondu ${conn.responseCode}."
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        conn.inputStream.use { input ->
          part.outputStream().use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
              val n = input.read(buf)
              if (n < 0) break
              out.write(buf, 0, n)
              digest.update(buf, 0, n)
              received += n
              if (received > SIZE_BYTES) return@withContext "fichier plus gros que prévu, téléchargement refusé."
              onProgress(received)
            }
          }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (received != SIZE_BYTES || hash != SHA256) {
          part.delete()
          return@withContext "fichier altéré ou incomplet (empreinte SHA-256 différente), il a été supprimé."
        }
        if (!part.renameTo(target)) return@withContext "impossible d'enregistrer le fichier."
        null
      } catch (e: Exception) {
        Log.e(TAG, "Download failed", e)
        part.delete()
        "téléchargement interrompu (${e.message ?: "erreur réseau"})."
      }
    }

  fun delete(context: Context) {
    release()
    modelFile(context).delete()
  }

  /** Vector of a question. */
  suspend fun embedQuery(context: Context, question: String): FloatArray? =
    embed(context, "task: search result | query: ${question.take(1_000)}")

  /** Vector of a passage of the memory ([title]: its file name). */
  suspend fun embedPassage(context: Context, title: String, text: String): FloatArray? =
    embed(context, "title: ${title.ifBlank { "none" }} | text: $text")

  private suspend fun embed(context: Context, input: String): FloatArray? =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          val ctx = context(context) ?: return@withLock null
          ctx.embed(input)?.takeIf { it.size == DIM }
        } finally {
          if (releaseRequested) releaseLocked()
        }
      }
    }

  /** Loaded on first use, kept for the next questions (about 300 MB of RAM, mostly mapped). */
  private fun context(app: Context): LlamaContext? {
    context?.let { return it }
    val file = modelFile(app)
    if (file.length() != SIZE_BYTES) return null
    return try {
      val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      val params =
        mapOf<String, Any>(
          "model" to Uri.fromFile(file).toString(),
          "model_fd" to pfd.detachFd(),
          "embedding" to true,
          "n_ctx" to 512,
          "n_batch" to 512,
          "n_threads" to 0,
          "use_mmap" to true,
          "use_mlock" to false,
        )
      LlamaContext(Random.nextInt(Int.MAX_VALUE), params).also { context = it }
    } catch (e: Throwable) {
      Log.e(TAG, "Cannot load the embedding model", e)
      null
    }
  }

  /**
   * Frees the model (when the memory is turned off or the phone is short of RAM). [ALTREX] Never
   * while a vector is being computed: freeing the native context under a running embedText was a
   * use-after-free (native crash). If [embed] holds the lock, it frees the model when it is done.
   */
  fun release() {
    releaseRequested = true
    if (mutex.tryLock()) {
      try {
        releaseLocked()
      } finally {
        mutex.unlock()
      }
    }
  }

  /** Frees the model; the caller holds [mutex]. */
  private fun releaseLocked() {
    releaseRequested = false
    val ctx = context ?: return
    context = null
    runCatching { ctx.release() }
  }
}
