package com.google.ai.edge.gallery.ui.llmsingleturn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.customtasks.agentchat.SecretEditorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bar shown at the top of the Code space: status of the Brave Search API key, plus the key dialog
 * that the web_search tool can trigger when no key is stored.
 */
@Composable
fun WebBar(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var hasKey by remember { mutableStateOf(false) }
  var showKeyDialog by remember { mutableStateOf(false) }
  var keyInput by remember { mutableStateOf("") }
  val keyRequested by WebSettings.keyDialogRequest

  LaunchedEffect(Unit) {
    hasKey = withContext(Dispatchers.IO) { WebSettings.getBraveKey(context) != null }
  }
  LaunchedEffect(keyRequested) {
    if (keyRequested) {
      keyInput = ""
      showKeyDialog = true
      WebSettings.keyDialogRequest.value = false
    }
  }

  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = "🌐 Recherche web",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
      onClick = {
        keyInput = ""
        showKeyDialog = true
      }
    ) {
      Text(if (hasKey) "🔑 Clé Brave : enregistrée" else "🔑 Clé Brave : absente")
    }
  }

  if (showKeyDialog) {
    SecretEditorDialog(
      title = "Clé API Brave Search",
      fieldLabel =
        "Colle ta clé Brave Search (api-dashboard.search.brave.com → API keys). Elle est stockée " +
          "chiffrée sur ce téléphone et n'est jamais envoyée au modèle. Seules tes requêtes de " +
          "recherche partent vers Brave, pas ton code. L'app limite les recherches à " +
          "${WebSettings.DAILY_LIMIT} par jour.",
      value = keyInput,
      onValueChange = { keyInput = it },
      onDone = {
        val newKey = keyInput.trim()
        showKeyDialog = false
        if (newKey.isNotEmpty()) {
          scope.launch(Dispatchers.IO) {
            val ok = WebSettings.saveBraveKey(context, newKey)
            if (ok) hasKey = true
          }
        }
      },
      onDismiss = { showKeyDialog = false },
    )
  }
}
