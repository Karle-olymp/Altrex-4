package com.google.ai.edge.gallery

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.ui.common.chat.DocumentImportException
import com.google.ai.edge.gallery.ui.common.chat.DocumentReader
import com.google.ai.edge.gallery.ui.common.chat.SharedInbox
import com.google.ai.edge.gallery.ui.common.chat.SharedRequest
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGAskAltrexActivity"

/** Longest selected text kept (a small model's context is limited). */
private const val MAX_SELECTED_CHARS = 12_000

/** Images shared to Altrex are scaled down to this size on their longest side. */
private const val MAX_IMAGE_SIDE = 1024

/**
 * [ALTREX] Entry point from other apps:
 * - "Demander à Altrex" in the text selection menu (ACTION_PROCESS_TEXT): a small sheet offers
 *   ready-made actions, then the chat opens and sends the request;
 * - the share sheet (ACTION_SEND / ACTION_SEND_MULTIPLE): text, a PDF or text file, or images land
 *   in the chat input, ready for a question.
 */
class AskAltrexActivity : ComponentActivity() {

  companion object {
    /** Opened by the assistant session ([com.google.ai.edge.gallery.assistant.AltrexAssistSession]). */
    const val ACTION_ASSIST_SHEET = "com.aistudio.altrex.ASSIST_SHEET"
    const val EXTRA_SCREEN_TEXT = "screen_text"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    when (intent?.action) {
      Intent.ACTION_PROCESS_TEXT -> showActions()
      Intent.ACTION_SEND,
      Intent.ACTION_SEND_MULTIPLE -> receiveShare()
      ACTION_ASSIST_SHEET,
      Intent.ACTION_ASSIST -> showAssistant()
      else -> finish()
    }
  }

  private fun showActions() {
    val selected =
      intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
    if (selected.isEmpty()) {
      finish()
      return
    }
    val text = selected.take(MAX_SELECTED_CHARS)
    setContent {
      GalleryTheme {
        AskSheet(
          excerpt = text,
          onAction = { action ->
            openChat(SharedRequest(text = "${action.instruction}\n\n« $text »", autoSend = true))
          },
          onQuestion = { question ->
            openChat(SharedRequest(text = "$question\n\n« $text »", autoSend = true))
          },
          onOpenInChat = { openChat(SharedRequest(text = "« $text »\n\n")) },
          onDismiss = { finish() },
        )
      }
    }
  }

  /** The assistant sheet: a question, optionally about the text on screen. */
  private fun showAssistant() {
    val screenText = intent.getStringExtra(EXTRA_SCREEN_TEXT).orEmpty().take(MAX_SELECTED_CHARS)
    setContent {
      GalleryTheme {
        AssistantSheet(
          screenText = screenText,
          onSend = { question, useScreen ->
            val prompt =
              if (useScreen && screenText.isNotEmpty()) {
                "Voici le texte affiché sur mon écran :\n« $screenText »\n\n$question"
              } else {
                question
              }
            openChat(SharedRequest(text = prompt, autoSend = true))
          },
          onOpenApp = { openChat(SharedRequest(text = "")) },
          onDismiss = { finish() },
        )
      }
    }
  }

  private fun receiveShare() {
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
    val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    val uris = sharedUris()
    val text = listOf(subject, sharedText).filter { it.isNotBlank() }.joinToString("\n").take(MAX_SELECTED_CHARS)
    if (uris.isEmpty()) {
      if (text.isBlank()) finish() else openChat(SharedRequest(text = text))
      return
    }
    setContent { GalleryTheme { SharePreparing() } }
    lifecycleScope.launch {
      val images = ArrayList<Bitmap>()
      var document: com.google.ai.edge.gallery.ui.common.chat.AttachedDocument? = null
      var error: String? = null
      for (uri in uris) {
        val mime = contentResolver.getType(uri).orEmpty()
        try {
          if (mime.startsWith("image/")) {
            decodeImage(uri)?.let(images::add)
          } else if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            error = "Altrex lit les textes, les PDF et les images, pas encore l'audio ni la vidéo."
          } else if (document == null) {
            document = DocumentReader.read(this@AskAltrexActivity, uri)
          }
        } catch (e: DocumentImportException) {
          error = e.message
        } catch (e: Exception) {
          Log.e(TAG, "Cannot read shared item $uri", e)
          error = "Impossible de lire ce fichier."
        }
      }
      if (document == null && images.isEmpty()) {
        Toast.makeText(this@AskAltrexActivity, error ?: "Rien à ouvrir dans Altrex.", Toast.LENGTH_LONG).show()
        finish()
        return@launch
      }
      openChat(SharedRequest(text = text, document = document, images = images))
    }
  }

  private fun sharedUris(): List<Uri> =
    if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
      intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
      listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }

  private suspend fun decodeImage(uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
      val source = ImageDecoder.createSource(contentResolver, uri)
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val side = maxOf(info.size.width, info.size.height)
        if (side > MAX_IMAGE_SIDE) {
          val scale = MAX_IMAGE_SIDE.toFloat() / side
          decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    }

  /** Leaves the request in the inbox and brings Altrex's chat to the front. */
  private fun openChat(request: SharedRequest) {
    SharedInbox.post(request)
    startActivity(
      Intent(this, MainActivity::class.java)
        .addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
    )
    finish()
  }
}

