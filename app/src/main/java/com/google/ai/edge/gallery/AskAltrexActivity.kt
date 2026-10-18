package com.google.ai.edge.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
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
import com.google.ai.edge.gallery.ui.modelmanager.sharedModelManager
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGAskAltrexActivity"

/** Longest selected text kept (a small model's context is limited). */
private const val MAX_SELECTED_CHARS = 12_000

/** Longest selected or on-screen text sent with a question answered in the panel. */
private const val MAX_PANEL_CHARS = 6_000

/** Images shared to Altrex are scaled down to this size on their longest side. */
private const val MAX_IMAGE_SIDE = 1024

/**
 * [ALTREX] Entry point from other apps:
 * - "Demander à Altrex" in the text selection menu (ACTION_PROCESS_TEXT) and the phone's assistant
 *   gesture: a small sheet over the current app, where the loaded model answers;
 * - the share sheet (ACTION_SEND / ACTION_SEND_MULTIPLE): text, a PDF or text file, or images land
 *   in the chat input, ready for a question.
 */
@AndroidEntryPoint
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

  /** The panel's conversation with the model; null for the share flow. */
  private var session: AskAltrexPanelSession? = null

  private fun newSession(): AskAltrexPanelSession =
    AskAltrexPanelSession(applicationContext, lifecycleScope, sharedModelManager(this)).also {
      session = it
      it.peekModel()
    }

  private fun showActions() {
    val selected =
      intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
    if (selected.isEmpty()) {
      finish()
      return
    }
    val text = selected.take(MAX_SELECTED_CHARS)
    // The text sent with the first question: what fits easily in a small model's context.
    // Framed as data: a selected web text cannot give orders to the model.
    val quoted =
      com.google.ai.edge.gallery.security.UntrustedContent.frame(
        "texte sélectionné",
        text.take(MAX_PANEL_CHARS) + if (text.length > MAX_PANEL_CHARS) " […]" else "",
      )
    // Read-only selections (a web page…) cannot be replaced.
    val canReplace = !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    val session = newSession()
    fun ask(label: String, request: String, argoraMode: String? = null) {
      val prompt = if (session.turns.isEmpty()) "$request\n\n$quoted" else request
      session.ask(label = label, prompt = prompt, argoraMode = argoraMode)
    }
    setContent {
      GalleryTheme {
        AskSheet(
          excerpt = text,
          session = session,
          canReplace = canReplace,
          onAction = { action ->
            ask(action.label, action.instruction, if (action == AskAction.SECURITY) "analyse" else null)
          },
          onQuestion = { question -> ask(question, question) },
          onCopy = ::copy,
          onReplace = { answer ->
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, cleanForReplace(answer)))
            finish()
          },
          onOpenInChat = { openChat(SharedRequest(text = "« $text »\n\n")) },
          onDismiss = { finish() },
        )
      }
    }
  }

  /** The assistant sheet: a question, optionally about the text on screen, answered in place. */
  private fun showAssistant() {
    val screenText = intent.getStringExtra(EXTRA_SCREEN_TEXT).orEmpty().take(MAX_PANEL_CHARS)
    val session = newSession()
    setContent {
      GalleryTheme {
        AssistantSheet(
          screenText = screenText,
          session = session,
          onAsk = { label, question, useScreen ->
            val prompt =
              if (useScreen && screenText.isNotEmpty()) {
                "Voici le texte affiché sur mon écran :\n" +
                  com.google.ai.edge.gallery.security.UntrustedContent.frame("texte de l'écran", screenText) +
                  "\n\n$question"
              } else {
                question
              }
            session.ask(label = label, prompt = prompt, argoraMode = if (label == "Sécurité") "analyse" else null)
          },
          onCopy = ::copy,
          onOpenApp = { openChat(SharedRequest(text = "")) },
          onDismiss = { finish() },
        )
      }
    }
  }

  private fun copy(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Altrex", text))
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      Toast.makeText(this, "Copié", Toast.LENGTH_SHORT).show()
    }
  }

  /** The answer as plain text for the source app: no code fence or quotes around it. */
  private fun cleanForReplace(answer: String): String {
    var out = answer.trim()
    val fence = Regex("^```[\\w-]*\\n([\\s\\S]*?)\\n```$")
    fence.find(out)?.let { out = it.groupValues[1].trim() }
    if (out.startsWith("«") && out.endsWith("»")) out = out.removePrefix("«").removeSuffix("»").trim()
    return out
  }

  override fun onDestroy() {
    // A closed panel stops writing (the model stays loaded for the next time).
    if (isFinishing) session?.takeIf { it.busy }?.stop()
    super.onDestroy()
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

