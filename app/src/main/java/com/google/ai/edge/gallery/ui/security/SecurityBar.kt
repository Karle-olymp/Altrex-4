package com.google.ai.edge.gallery.ui.security

import androidx.compose.material.icons.Icons
import com.google.ai.edge.gallery.ui.common.ToolStatusBar
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
 * Bar shown at the top of the "Sécurité" space: status of the SecurCode API key, plus the two
 * dialogs the scan tool can trigger (API key entry, per-project consent to send code).
 */
@Composable
fun SecurityBar(modifier: Modifier = Modifier, showStatus: Boolean = true) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var hasKey by remember { mutableStateOf(false) }
  var showKeyDialog by remember { mutableStateOf(false) }
  var keyInput by remember { mutableStateOf("") }
  val keyRequested by SecurCodeSettings.keyDialogRequest
  val pending by SecurCodeSettings.pendingConsent

  // Refreshed when the key changes in the Settings.
  val keysVersion by com.google.ai.edge.gallery.ui.common.ApiKeyEvents.version
  LaunchedEffect(keysVersion) {
    hasKey = withContext(Dispatchers.IO) { SecurCodeSettings.getApiKey(context) != null }
  }
  LaunchedEffect(keyRequested) {
    if (keyRequested) {
      keyInput = ""
      showKeyDialog = true
      SecurCodeSettings.keyDialogRequest.value = false
    }
  }

  // [ALTREX] Shared status bar (icon + label, key pill) instead of emoji text. Without it
  // ([showStatus] false) only the dialogs remain; the keys are also managed in the Settings.
  if (showStatus) ToolStatusBar(
    icon = Icons.Outlined.Shield,
    label = "Lecture seule",
    keyName = "Clé SecurCode",
    hasKey = hasKey,
    onKeyClicked = {
      keyInput = ""
      showKeyDialog = true
    },
    modifier = modifier,
  )

  if (showKeyDialog) {
    SecretEditorDialog(
      title = "Clé API SecurCode",
      fieldLabel =
        "Colle ta clé `sc_live_…` (SecurCode → Profil → Clés API). Elle est stockée chiffrée sur ce " +
          "téléphone et n'est jamais envoyée au modèle. **Attention :** le code que tu analyses " +
          "est envoyé à SecurCode, puis à Groq (analyse IA) et à OSV.dev (dépendances). Une " +
          "confirmation te sera demandée pour chaque projet.",
      value = keyInput,
      onValueChange = { keyInput = it },
      onDone = {
        val newKey = keyInput.trim()
        showKeyDialog = false
        if (newKey.isNotEmpty()) {
          scope.launch(Dispatchers.IO) {
            val ok = SecurCodeSettings.saveApiKey(context, newKey)
            if (ok) hasKey = true
          }
        }
      },
      onDismiss = { showKeyDialog = false },
    )
  }

  val request = pending
  if (request != null) {
    AlertDialog(
      onDismissRequest = { SecurCodeSettings.pendingConsent.value = null },
      title = { Text("Envoyer « ${request.label} » à SecurCode ?") },
      text = {
        Text(
          "Le code de ce projet sera envoyé à SecurCode, puis à Groq (modèle Llama 3.3 70B) pour " +
            "l'analyse IA ; les noms de dépendances iront à OSV.dev. SecurCode ne conserve pas le " +
            "contenu des fichiers. N'autorise que du code que tu as le droit de partager. " +
            "Après avoir autorisé, relance ta demande."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val id = request.projectId
            SecurCodeSettings.pendingConsent.value = null
            scope.launch(Dispatchers.IO) { SecurCodeSettings.grantConsent(context, id) }
          }
        ) {
          Text("Autoriser")
        }
      },
      dismissButton = {
        TextButton(onClick = { SecurCodeSettings.pendingConsent.value = null }) { Text("Refuser") }
      },
    )
  }
}
