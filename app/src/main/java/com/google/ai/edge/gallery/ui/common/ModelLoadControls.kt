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

package com.google.ai.edge.gallery.ui.common

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.compute.ComputeRuntime
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.RemoteCheck
import com.google.ai.edge.gallery.runtime.RemoteSpace
import com.google.ai.edge.gallery.runtime.RemoteState
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/** [ALTREX] Load state of a local model, as the screens name it. */
enum class LoadState(val label: String) {
  NOT_LOADED("Non chargé"),
  LOADING("Chargement…"),
  LOADED("Chargé"),
  FAILED("Échec du chargement"),
}

@Composable
fun rememberLoadState(model: Model, modelManagerViewModel: ModelManagerViewModel): LoadState {
  val ui by modelManagerViewModel.uiState.collectAsState()
  return when (ui.modelInitializationStatus[model.name]?.status) {
    ModelInitializationStatusType.INITIALIZING -> LoadState.LOADING
    ModelInitializationStatusType.INITIALIZED ->
      if (model.instance != null) LoadState.LOADED else LoadState.NOT_LOADED
    ModelInitializationStatusType.ERROR -> LoadState.FAILED
    else -> LoadState.NOT_LOADED
  }
}

private val LoadedGreen = Color(0xFF5FD4A0)

/**
 * [ALTREX] Bar above a chat or Forge session while its model is not loaded: nothing loads by
 * itself, the user taps "Charger" (or sends a message). Hidden once the model is loaded (the chip's
 * green dot and the model picker show it, with "Décharger").
 */
@Composable
fun ModelLoadBar(
  task: Task,
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  conversationActive: Boolean,
  modifier: Modifier = Modifier,
) {
  val ui by modelManagerViewModel.uiState.collectAsState()
  if (RemoteCheck.isRemoteModel(model.name)) {
    // Cloud models and Spaces: nothing to load; the bar says whether a message would go through.
    RemoteModelBar(model, modifier, compact = true)
    return
  }
  if (!ComputeRuntime.hasLocalCompute(model)) return
  if (ui.modelDownloadStatus[model.name]?.status != ModelDownloadStatusType.SUCCEEDED) return
  val state = rememberLoadState(model, modelManagerViewModel)
  if (state == LoadState.LOADED) return
  val context = LocalContext.current
  val status by ComputeRuntime.status.collectAsState()
  val phase = status[model.name]?.phase.orEmpty()
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (state == LoadState.LOADING) {
      CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
      StateDot(loaded = false)
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        "${model.displayName.ifEmpty { model.name }} · ${state.label}",
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
      )
      Text(
        when {
          state == LoadState.LOADING && phase.isNotEmpty() -> phase
          state == LoadState.LOADING -> "Le modèle se charge en mémoire."
          else -> ComputeRuntime.previewLine(context, model) + " · charge-le, ou envoie un message."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
      )
    }
    if (state != LoadState.LOADING) {
      Button(onClick = { modelManagerViewModel.loadModel(context, task, model, conversationActive) }) {
        Text("Charger")
      }
    }
  }
}

/**
 * [ALTREX] The model in use, at the top of the model picker: its state (non chargé / chargement /
 * chargé), what it computes on, and "Charger" or "Décharger" (frees the memory at once).
 */
@Composable
fun ActiveModelCard(
  task: Task,
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  conversationActive: Boolean,
  modifier: Modifier = Modifier,
) {
  if (RemoteCheck.isRemoteModel(model.name)) {
    RemoteModelBar(model, modifier, compact = false)
    return
  }
  if (!ComputeRuntime.hasLocalCompute(model)) return
  val ui by modelManagerViewModel.uiState.collectAsState()
  if (ui.modelDownloadStatus[model.name]?.status != ModelDownloadStatusType.SUCCEEDED) return
  val context = LocalContext.current
  val state = rememberLoadState(model, modelManagerViewModel)
  val status by ComputeRuntime.status.collectAsState()
  val st = status[model.name]
  val shape = RoundedCornerShape(16.dp)
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 4.dp)
        .clip(shape)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      "MODÈLE ACTIF",
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
      color = MaterialTheme.colorScheme.primary,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state == LoadState.LOADING) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
      } else {
        StateDot(loaded = state == LoadState.LOADED)
      }
      Text(
        model.displayName.ifEmpty { model.name },
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier.weight(1f),
      )
      Text(state.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val line =
      when {
        state == LoadState.LOADING && !st?.phase.isNullOrEmpty() -> st?.phase.orEmpty()
        state == LoadState.LOADED && st != null && st.line.isNotEmpty() ->
          st.line + if (st.params.isNotEmpty()) " · ${st.params}" else ""
        else -> ComputeRuntime.previewLine(context, model)
      }
    if (line.isNotEmpty()) {
      Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Background measurement of the Auto mode: its progress, then its result.
    if (state == LoadState.LOADED && !st?.bench.isNullOrEmpty()) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (st?.benchRunning == true) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        Text(st?.bench.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
      }
    }
    Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state == LoadState.LOADED && st?.benchRunning == true) {
        OutlinedButton(onClick = { ComputeRuntime.cancelBench(model.name) }) { Text("Annuler la mesure") }
      }
      val faster = st?.suggestion
      if (state == LoadState.LOADED && faster != null && st.benchRunning != true) {
        // The measured choice applies at the next load anyway; this reloads now, on request.
        Button(
          onClick = {
            modelManagerViewModel.unloadModel(context, task, model) {
              android.os.Handler(android.os.Looper.getMainLooper()).post {
                modelManagerViewModel.loadModel(context, task, model, conversationActive = true)
              }
            }
          }
        ) {
          Text("Basculer sur ${ComputeRuntime.title(faster)}")
        }
      }
      when (state) {
        LoadState.LOADED ->
          OutlinedButton(
            onClick = {
              modelManagerViewModel.unloadModel(context, task, model) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                  Toast.makeText(context, "Modèle déchargé : mémoire libérée.", Toast.LENGTH_SHORT).show()
                }
              }
            }
          ) {
            Text("Décharger")
          }
        LoadState.LOADING -> {}
        else ->
          Button(onClick = { modelManagerViewModel.loadModel(context, task, model, conversationActive) }) {
            Text("Charger")
          }
      }
    }
  }
}

/**
 * [ALTREX] Bar of a cloud model or a Space: « Prêt », « Connexion… », « Hors ligne », « Clé
 * manquante »…, with « Vérifier la connexion » (a light request, no message sent). No
 * « Décharger »: nothing is in memory. [compact] (above a chat): hidden once « Prêt ».
 */
@Composable
fun RemoteModelBar(model: Model, modifier: Modifier = Modifier, compact: Boolean) {
  val context = LocalContext.current
  val all by RemoteCheck.status.collectAsState()
  val status = remember(all[model.name], model.name) { RemoteCheck.current(context, model.name) }
  val isSpace = RemoteSpace.isRemote(model.name)
  // A cloud check costs nothing (the provider's model list): done once by itself. A Space is only
  // woken up on request.
  LaunchedEffect(model.name) {
    if (!isSpace && RemoteCheck.current(context, model.name).state == RemoteState.UNKNOWN) RemoteCheck.check(context, model.name)
  }
  if (compact && status.state == RemoteState.READY) return
  val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
  val color =
    when (status.state) {
      RemoteState.READY -> LoadedGreen
      RemoteState.UNKNOWN, RemoteState.CHECKING, RemoteState.WAKING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
      else -> MaterialTheme.colorScheme.error
    }
  val base = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 4.dp).clip(shape)
  Column(
    modifier =
      (if (compact) base.background(MaterialTheme.colorScheme.surfaceContainerHigh) else base.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape))
        .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (!compact) {
      Text(
        if (isSpace) "MODÈLE ACTIF · SPACE HUGGING FACE" else "MODÈLE ACTIF · CLOUD",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (status.busy) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
      } else {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
      }
      Text(
        model.displayName.ifEmpty { model.name },
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        if (status.state == RemoteState.WAKING) "Space en veille" else status.state.label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (status.detail.isNotEmpty()) {
      Text(status.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
    }
    if (!status.busy) {
      OutlinedButton(onClick = { RemoteCheck.check(context, model.name) }, modifier = Modifier.padding(top = 2.dp)) {
        Text(if (status.state == RemoteState.UNKNOWN) "Vérifier la connexion" else "Vérifier de nouveau")
      }
    }
  }
}

@Composable
private fun StateDot(loaded: Boolean) {
  Box(
    modifier =
      Modifier.size(8.dp)
        .clip(CircleShape)
        .background(if (loaded) LoadedGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
  )
}
