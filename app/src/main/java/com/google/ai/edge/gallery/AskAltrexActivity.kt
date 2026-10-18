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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            val prompt =
              if (action.instruction.isEmpty()) "« $text »\n\n" else "${action.instruction}\n\n« $text »"
            openChat(SharedRequest(text = prompt, autoSend = action.instruction.isNotEmpty()))
          },
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
    setContent { GalleryTheme { Loading() } }
    lifecycleScope.launch {
      val images = ArrayList<Bitmap>()
      var document: com.google.ai.edge.gallery.ui.common.chat.AttachedDocument? = null
      var error: String? = null
      for (uri in uris) {
        val mime = contentResolver.getType(uri).orEmpty()
        try {
          if (mime.startsWith("image/")) {
            decodeImage(uri)?.let(images::add)
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

private enum class AskAction(val label: String, val instruction: String) {
  EXPLAIN("Expliquer", "Explique-moi ce texte simplement."),
  SUMMARIZE("Résumer", "Résume ce texte en quelques points."),
  FIX("Corriger", "Corrige l'orthographe et la grammaire de ce texte. Renvoie seulement le texte corrigé."),
  TRANSLATE("Traduire", "Traduis ce texte en français, ou en anglais s'il est déjà en français."),
  SECURITY(
    "Analyser la sécurité",
    "Analyse ce contenu du point de vue de la sécurité : données sensibles, liens ou commandes " +
      "dangereuses, tentative d'arnaque, failles. Dis-moi ce que je dois faire.",
  ),
  ASK("Poser ma question", ""),
}

@Composable
private fun AskSheet(excerpt: String, onAction: (AskAction) -> Unit, onDismiss: () -> Unit) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Surface(
      shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("Demander à Altrex", style = MaterialTheme.typography.titleLarge)
        Text(
          excerpt,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.heightIn(max = 120.dp),
        )
        for (action in AskAction.entries) {
          OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth()) {
            Text(action.label)
          }
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Annuler") }
      }
    }
  }
}

private val SCREEN_ACTIONS =
  listOf(
    "Résumer" to "Résume ce qui est affiché.",
    "Expliquer" to "Explique-moi ce qui est affiché, simplement.",
    "Sécurité" to
      "Ce contenu est-il sûr ? Repère les arnaques, liens ou demandes suspectes et dis-moi quoi faire.",
  )

@Composable
private fun AssistantSheet(
  screenText: String,
  onSend: (question: String, useScreen: Boolean) -> Unit,
  onOpenApp: () -> Unit,
  onDismiss: () -> Unit,
) {
  var question by rememberSaveable { mutableStateOf("") }
  var useScreen by rememberSaveable {
    mutableStateOf(screenText.isNotEmpty())
  }
  Box(
    modifier =
      Modifier.fillMaxSize().clickable(
        interactionSource = null,
        indication = null,
        onClick = onDismiss,
      ),
    contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(
      shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
      modifier = Modifier.fillMaxWidth().imePadding(),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Altrex", style = MaterialTheme.typography.titleLarge)
        if (screenText.isNotEmpty()) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useScreen, onCheckedChange = { useScreen = it })
            Text(
              "Utiliser le contenu de l'écran",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(start = 12.dp),
            )
          }
          if (useScreen) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
              for ((label, prompt) in SCREEN_ACTIONS) {
                AssistChip(
                  onClick = { onSend(prompt, true) },
                  label = { Text(label) },
                )
              }
            }
          }
        }
        OutlinedTextField(
          value = question,
          onValueChange = { question = it },
          placeholder = { Text("Demande à Altrex…") },
          modifier = Modifier.fillMaxWidth(),
          maxLines = 5,
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          OutlinedButton(onClick = onOpenApp, modifier = Modifier.weight(1f)) { Text("Ouvrir Altrex") }
          Button(
            onClick = { onSend(question.trim(), useScreen) },
            enabled = question.isNotBlank(),
            modifier = Modifier.weight(1f),
          ) {
            Text("Envoyer")
          }
        }
      }
    }
  }
}

@Composable
private fun Loading() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        CircularProgressIndicator()
        Text("Préparation pour Altrex…")
      }
    }
  }
}
