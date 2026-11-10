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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.compute.AutoParamsCalculator
import com.google.ai.edge.gallery.compute.ComputeChoice
import com.google.ai.edge.gallery.compute.ComputeOptions
import com.google.ai.edge.gallery.compute.ComputeRuntime
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType

/** Keys drawn by [ComputeSettingsSection], left out of the generic rows. */
fun computeSectionKeys(model: Model): Set<String> =
  buildSet {
    add(ConfigKeys.ACCELERATOR.label)
    add(ConfigKeys.CONTEXT_AUTO.label)
    if (model.configs.any { it.key == ConfigKeys.CONTEXT_AUTO }) {
      add(ConfigKeys.MAX_TOKENS.label)
      add(ConfigKeys.NUM_THREADS.label)
    }
  }

/**
 * [ALTREX] "Calcul" and "Paramètres du modèle" of the Génération sheet: only the backends the
 * model's engine really has (Expérimental marked), the line that explains the Auto choice, and the
 * context and threads with their Auto values computed from the free memory.
 */
@Composable
fun ComputeSettingsSection(model: Model, values: SnapshotStateMap<String, Any>) {
  val context = LocalContext.current
  val options = remember(model.name) { ComputeRuntime.options(model) }
  if (options.isEmpty()) return
  val backends = remember(model.name) { ComputeRuntime.backends(model) }
  val status by ComputeRuntime.status.collectAsState()

  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text("Calcul", style = MaterialTheme.typography.titleSmall)
    val current = ComputeOptions.resolve(values[ConfigKeys.ACCELERATOR.label]?.toString(), backends)
    for (opt in options) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { values[ConfigKeys.ACCELERATOR.label] = opt.choice.label }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(
          selected = current == opt.choice,
          onClick = { values[ConfigKeys.ACCELERATOR.label] = opt.choice.label },
        )
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(opt.title, style = MaterialTheme.typography.bodyMedium)
            if (opt.experimental) {
              Text(
                "Expérimental",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier =
                  Modifier.clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
              )
            }
          }
          Text(
            opt.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    val st = status[model.name]
    val line =
      if (st?.backend != null && st.line.isNotEmpty()) "En cours : ${st.line}"
      else ComputeRuntime.previewLine(context, model)
    if (line.isNotEmpty()) {
      Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    if (model.runtimeType == RuntimeType.LLAMA_CPP) {
      Text(
        ComputeOptions.LLAMA_CPP_NOTE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (current == ComputeChoice.AUTO && backends.size > 1) {
      TextButton(
        onClick = {
          val memory = ComputeRuntime.memory(context)
          memory.forget(model.name)
          memory.clearFailures(model.name)
          Toast.makeText(context, "La mesure sera refaite au prochain chargement.", Toast.LENGTH_SHORT).show()
        }
      ) {
        Text("Refaire la mesure")
      }
    }
  }

  if (model.configs.none { it.key == ConfigKeys.CONTEXT_AUTO }) return
  val auto = remember(model.name) { ComputeRuntime.autoParams(context, model) }
  val mem = remember(model.name) { ComputeRuntime.memorySnapshot(context) }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Paramètres du modèle", style = MaterialTheme.typography.titleSmall)
    Text(
      "Mémoire libre ${AutoParamsCalculator.formatBytes(mem.availBytes)}, marge gardée " +
        "${AutoParamsCalculator.formatBytes(auto.safetyMarginBytes)}, modèle " +
        AutoParamsCalculator.formatBytes(model.sizeInBytes.coerceAtLeast(0L)) + ".",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!auto.fits) {
      Text(
        "Mémoire libre insuffisante pour ce modèle en ce moment : ferme d'autres applications.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    // Context.
    var contextAuto by remember { mutableStateOf(values[ConfigKeys.CONTEXT_AUTO.label] as? Boolean ?: true) }
    AutoRow(
      title = "Contexte",
      auto = contextAuto,
      autoValue = "${auto.contextTokens} tokens",
      onAuto = {
        contextAuto = it
        values[ConfigKeys.CONTEXT_AUTO.label] = it
        if (!it) values[ConfigKeys.MAX_TOKENS.label] = auto.contextTokens.toFloat()
      },
    )
    val ctxConfig = model.configs.firstOrNull { it.key == ConfigKeys.MAX_TOKENS } as? NumberSliderConfig
    if (!contextAuto && ctxConfig != null) NumberSliderRow(config = ctxConfig, values = values)

    // Threads (0 = auto).
    var threadsAuto by remember {
      mutableStateOf(((values[ConfigKeys.NUM_THREADS.label] as? Number)?.toInt() ?: 0) == 0)
    }
    AutoRow(
      title = "Threads",
      auto = threadsAuto,
      autoValue = "${auto.threads} (cœurs rapides)",
      onAuto = {
        threadsAuto = it
        values[ConfigKeys.NUM_THREADS.label] = if (it) 0f else auto.threads.toFloat()
      },
    )
    val thrConfig = model.configs.firstOrNull { it.key == ConfigKeys.NUM_THREADS } as? NumberSliderConfig
    if (!threadsAuto && thrConfig != null) NumberSliderRow(config = thrConfig, values = values)
    Text(
      "Couches déportées : sans objet (llama.cpp tourne entièrement sur le processeur ici).",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun AutoRow(title: String, auto: Boolean, autoValue: String, onAuto: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyMedium)
      if (auto) {
        Text(autoValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    FilterChip(selected = auto, onClick = { onAuto(!auto) }, label = { Text("Auto") })
  }
}
