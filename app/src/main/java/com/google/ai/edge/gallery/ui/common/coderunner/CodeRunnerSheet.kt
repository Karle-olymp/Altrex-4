/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common.coderunner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ai.edge.gallery.ui.theme.codeFontFamily
import kotlinx.coroutines.launch

private enum class RunState { NEEDS_DOWNLOAD, DOWNLOADING, RUNNING, DONE, ERROR }

private data class OutputPart(val text: String, val isError: Boolean)

/**
 * [ALTREX] "Exécution" sheet: runs a generated code block on the phone (JavaScript, HTML, Python)
 * and shows what it prints. Python first downloads Pyodide once if needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeRunnerSheet(language: RunLanguage, code: String, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var state by remember {
    mutableStateOf(
      if (language == RunLanguage.PYTHON && !PyodideStore.isInstalled(context)) RunState.NEEDS_DOWNLOAD
      else RunState.RUNNING
    )
  }
  var progress by remember { mutableFloatStateOf(0f) }
  var downloadError by remember { mutableStateOf<String?>(null) }
  val output = remember { mutableStateOf(listOf<OutputPart>()) }
  var inputRequest by remember { mutableStateOf<Pair<String, (String?) -> Unit>?>(null) }
  var runId by remember { mutableStateOf(0) }

  val runner = remember {
    CodeRunner(
      context,
      object : RunnerListener {
        override fun onOutput(text: String, isError: Boolean) {
          // Keep the panel light: at most ~200 KB of output.
          val current = output.value
          if (current.sumOf { it.text.length } < 200_000) output.value = current + OutputPart(text, isError)
        }

        override fun onFinished(ok: Boolean) {
          if (state == RunState.RUNNING) state = if (ok) RunState.DONE else RunState.ERROR
        }

        override fun onInputRequest(message: String, answer: (String?) -> Unit) {
          inputRequest = message to answer
        }
      },
    )
  }
  DisposableEffect(Unit) { onDispose { runner.destroy() } }

  LaunchedEffect(runId, state == RunState.RUNNING) {
    if (state == RunState.RUNNING) {
      output.value = emptyList()
      runner.run(language, code)
    }
  }

  ModalBottomSheet(
    onDismissRequest = {
      runner.stop()
      onDismiss()
    },
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
      // Header: title, language, status.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Exécution", style = MaterialTheme.typography.titleLarge)
          Text(
            language.label + " · sur le téléphone",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        StatusPill(state)
      }
      Spacer(Modifier.height(14.dp))

      if (state == RunState.NEEDS_DOWNLOAD || state == RunState.DOWNLOADING) {
        DownloadCard(
          downloading = state == RunState.DOWNLOADING,
          progress = progress,
          error = downloadError,
          onDownload = {
            downloadError = null
            state = RunState.DOWNLOADING
            scope.launch {
              try {
                PyodideStore.install(context) { p -> progress = p }
                state = RunState.RUNNING
              } catch (e: Exception) {
                downloadError = e.message ?: "Échec du téléchargement"
                state = RunState.NEEDS_DOWNLOAD
              }
            }
          },
        )
      }

      // HTML is shown as a page; for the other languages the WebView only runs the code.
      val webModifier =
        if (language == RunLanguage.HTML) {
          Modifier.fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        } else {
          Modifier.size(1.dp).alpha(0f)
        }
      if (state != RunState.NEEDS_DOWNLOAD && state != RunState.DOWNLOADING) {
        AndroidView(factory = { runner.webView }, modifier = webModifier)
        if (language == RunLanguage.HTML) Spacer(Modifier.height(12.dp))
      }

      // Output (console / stdout / stderr).
      val parts = output.value
      val errorColor = MaterialTheme.colorScheme.error
      val text =
        buildAnnotatedString {
          for (p in parts) {
            if (p.isError) withStyle(SpanStyle(color = errorColor)) { append(p.text) } else append(p.text)
          }
        }
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(min = 120.dp, max = 360.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(14.dp)
      ) {
        if (parts.isEmpty()) {
          Text(
            when (state) {
              RunState.RUNNING ->
                if (language == RunLanguage.PYTHON) "Démarrage de Python…" else "Exécution…"
              RunState.DONE -> "Terminé, sans rien afficher."
              RunState.ERROR -> "Arrêté."
              else -> "La sortie s'affichera ici."
            },
            fontFamily = codeFontFamily,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Text(text, fontFamily = codeFontFamily, fontSize = 12.5.sp, lineHeight = 19.sp, softWrap = false)
        }
      }
      Spacer(Modifier.height(14.dp))

      // Actions.
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state == RunState.RUNNING) {
          Button(onClick = { runner.stop() }) {
            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Arrêter")
          }
        } else if (state == RunState.DONE || state == RunState.ERROR) {
          Button(
            onClick = {
              state = RunState.RUNNING
              runId++
            }
          ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Relancer")
          }
        }
        if (parts.isNotEmpty()) {
          OutlinedButton(onClick = { clipboard.setText(AnnotatedString(parts.joinToString("") { it.text })) }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Copier la sortie")
          }
        }
      }
    }
  }

  // input() / prompt(): ask the user for one line.
  inputRequest?.let { (message, answer) ->
    var value by remember(inputRequest) { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = {
        answer(null)
        inputRequest = null
      },
      title = { Text("Le programme attend une saisie") },
      text = {
        Column {
          if (message.isNotBlank()) {
            Text(message, fontFamily = codeFontFamily, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
          }
          OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            // Echo the line like a terminal would.
            output.value = output.value + OutputPart(value + "\n", false)
            answer(value)
            inputRequest = null
          }
        ) {
          Text("Envoyer")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            answer(null)
            inputRequest = null
          }
        ) {
          Text("Annuler")
        }
      },
    )
  }
}

@Composable
private fun StatusPill(state: RunState) {
  val (label, color) =
    when (state) {
      RunState.NEEDS_DOWNLOAD -> "Python à installer" to MaterialTheme.colorScheme.onSurfaceVariant
      RunState.DOWNLOADING -> "Téléchargement" to MaterialTheme.colorScheme.primary
      RunState.RUNNING -> "En cours" to MaterialTheme.colorScheme.primary
      RunState.DONE -> "Terminé" to MaterialTheme.colorScheme.tertiary
      RunState.ERROR -> "Erreur" to MaterialTheme.colorScheme.error
    }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.clip(RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun DownloadCard(downloading: Boolean, progress: Float, error: String?, onDownload: () -> Unit) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
        .padding(16.dp)
  ) {
    Text("Moteur Python", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
      "Pour exécuter du Python, Altrex télécharge une seule fois Pyodide (Python ${pyVersion()}, " +
        "environ ${PyodideStore.APPROX_SIZE_MB} Mo). Il reste ensuite sur le téléphone et fonctionne hors ligne. " +
        "Le code exécuté n'a pas accès à Internet ni à tes fichiers.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    if (downloading) {
      LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
      Spacer(Modifier.height(6.dp))
      Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.labelSmall)
    } else {
      if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
      }
      Button(onClick = onDownload) {
        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Télécharger (${PyodideStore.APPROX_SIZE_MB} Mo)")
      }
    }
  }
}

private fun pyVersion(): String = "3.14"

/** The play button shown next to "Copier" on runnable code blocks. */
@Composable
fun RunCodeButton(onClick: () -> Unit) {
  TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
    Icon(
      Icons.Rounded.PlayArrow,
      contentDescription = null,
      modifier = Modifier.size(15.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.width(4.dp))
    Text("Exécuter", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
  }
}
