package com.google.ai.edge.gallery.ui.llmsingleturn

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Actions under the model's response: create/modify files proposed by the model, apply a code
 * block to the opened file's editor, undo, copy.
 */
@Composable
fun ResponseActionsBar(
  response: String,
  inProgress: Boolean,
  openedFileUri: Uri?,
  onCopy: () -> Unit,
  onApplyToEditor: (String) -> Unit,
  onFileChanged: (Uri, String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val tick = ProjectEvents.tick.intValue
  val canUndo = remember(tick) { FileActions.canUndo() }

  val auto = AgentSettings.autoMode
  val actions =
    remember(response, inProgress, auto) {
      if (inProgress || auto) emptyList() else FileActions.parse(response)
    }
  val codeBlock =
    remember(response, inProgress) {
      if (inProgress) null else FileActions.largestCodeBlock(response)
    }
  val showApplyToEditor = !auto && actions.isEmpty() && openedFileUri != null && codeBlock != null

  var results by remember(response) { mutableStateOf<Map<String, FileActionResult>>(emptyMap()) }
  var existsMap by remember(actions) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
  var undoMessage by remember(response) { mutableStateOf<String?>(null) }

  LaunchedEffect(actions, tick) {
    if (actions.isNotEmpty()) {
      existsMap =
        withContext(Dispatchers.IO) {
          actions.associate { it.path to FileActions.exists(context, it.path) }
        }
    }
  }

  val buttonColors =
    IconButtonDefaults.iconButtonColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor = MaterialTheme.colorScheme.primary,
    )

  if (response.isNotEmpty() || canUndo) {
    Column(modifier = modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (undoMessage != null) {
          Text(
            text = undoMessage ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
        }
        Text(
          text = if (auto) "Agent auto" else "Manuel",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
          onClick = { AgentSettings.autoMode = !AgentSettings.autoMode },
          colors =
            IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
              contentColor =
                if (auto) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
          Icon(
            Icons.Outlined.SmartToy,
            contentDescription = "Basculer le mode agent automatique",
            modifier = Modifier.size(20.dp),
          )
        }
        if (canUndo) {
          IconButton(
            onClick = {
              scope.launch {
                val u = withContext(Dispatchers.IO) { FileActions.undoLast(context) }
                undoMessage = u.message
                results = emptyMap()
                if (u.uri != null && u.restoredContent != null) {
                  onFileChanged(u.uri, u.restoredContent)
                }
              }
            },
            colors = buttonColors,
          ) {
            Icon(
              Icons.Outlined.Undo,
              contentDescription = "Annuler la dernière modification",
              modifier = Modifier.size(20.dp),
            )
          }
        }
        if (showApplyToEditor) {
          IconButton(onClick = { codeBlock?.let { onApplyToEditor(it) } }, colors = buttonColors) {
            Icon(
              Icons.Outlined.Check,
              contentDescription = "Appliquer au fichier ouvert",
              modifier = Modifier.size(20.dp),
            )
          }
        }
        if (response.isNotEmpty()) {
          IconButton(onClick = onCopy, colors = buttonColors) {
            Icon(
              Icons.Outlined.ContentCopy,
              contentDescription = "Copier la réponse",
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }

      for (action in actions) {
        val result = results[action.path]
        val exists = existsMap[action.path] == true
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = action.path,
              style = MaterialTheme.typography.bodySmall,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text =
                when {
                  result == null -> if (exists) "Fichier existant : sera modifié" else "Nouveau fichier"
                  result.ok -> "✓ ${result.message}"
                  else -> result.message
                },
              style = MaterialTheme.typography.labelSmall,
              color =
                if (result != null && !result.ok) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (result?.ok != true) {
            FilledTonalButton(
              onClick = {
                scope.launch {
                  val r = withContext(Dispatchers.IO) { FileActions.apply(context, action) }
                  results = results + (action.path to r)
                  if (r.ok && r.uri != null) onFileChanged(r.uri, action.content)
                }
              }
            ) {
              Text(if (exists) "Modifier" else "Créer")
            }
          }
        }
      }

      if (actions.size > 1) {
        TextButton(
          onClick = {
            scope.launch {
              for (action in actions) {
                if (results[action.path]?.ok == true) continue
                val r = withContext(Dispatchers.IO) { FileActions.apply(context, action) }
                results = results + (action.path to r)
                if (r.ok && r.uri != null) onFileChanged(r.uri, action.content)
              }
            }
          }
        ) {
          Text("Tout appliquer (${actions.size} fichiers)")
        }
      }
    }
  }
}