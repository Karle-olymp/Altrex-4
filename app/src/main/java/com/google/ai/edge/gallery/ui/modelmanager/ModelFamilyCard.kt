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

package com.google.ai.edge.gallery.ui.modelmanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.humanReadableSize

/** [ALTREX] A family of catalogue models (Gemma, Qwen…), shown as one expandable card. */
data class ModelFamily(
  val name: String,
  val category: String,
  val summary: String,
  val models: List<Model>,
)

/** Category order on the models page. */
val MODEL_CATEGORY_ORDER =
  listOf("IA générative", "Transcription et audio", "Recherche et embeddings", "Autres modèles")

private val KNOWN_FAMILIES =
  listOf(
    "gemma" to "Gemma",
    "qwen" to "Qwen",
    "deepseek" to "DeepSeek",
    "llama" to "Llama",
    "phi" to "Phi",
    "mistral" to "Mistral",
    "smol" to "SmolLM",
    "whisper" to "Whisper",
    "hammer" to "Hammer",
  )

private val FAMILY_SUMMARIES =
  mapOf(
    "Gemma" to "Modèles ouverts de Google : texte, image et audio, du petit modèle rapide au plus capable.",
    "Qwen" to "Modèles d'Alibaba, solides en code, en raisonnement et en plusieurs langues.",
    "DeepSeek" to "Modèles orientés raisonnement, qui détaillent leur réflexion.",
    "Llama" to "Modèles ouverts de Meta, polyvalents.",
    "Phi" to "Petits modèles de Microsoft, efficaces pour leur taille.",
    "Mistral" to "Modèles français de Mistral AI.",
    "SmolLM" to "Très petits modèles, rapides et légers.",
    "Whisper" to "Reconnaissance vocale et transcription.",
  )

private fun familyNameOf(model: Model): String {
  val lower = model.name.lowercase()
  for ((key, label) in KNOWN_FAMILIES) {
    if (lower.contains(key)) return label
  }
  // Unknown family: the first part of the name ("Foo-1B-it" -> "Foo").
  return model.name.split('-', '_', ' ', '.').firstOrNull { it.isNotBlank() } ?: model.name
}

private fun isEmbedding(model: Model): Boolean = model.name.lowercase().contains("embed")

private fun isTranscription(model: Model): Boolean {
  val lower = model.name.lowercase()
  return lower.contains("whisper") || lower.contains("asr") || lower.contains("speech")
}

/**
 * Groups [models] (already in catalogue order, newest first) into families. A family's category
 * follows its main model: the first one that is not an embedding model.
 */
fun groupModelFamilies(models: List<Model>): List<ModelFamily> {
  val groups = LinkedHashMap<String, MutableList<Model>>()
  for (model in models) groups.getOrPut(familyNameOf(model)) { mutableListOf() }.add(model)
  val families =
    groups.map { (name, members) ->
      val main = members.firstOrNull { !isEmbedding(it) } ?: members.first()
      val category =
        when {
          isTranscription(main) -> "Transcription et audio"
          isEmbedding(main) -> "Recherche et embeddings"
          main.isLlm -> "IA générative"
          else -> "Autres modèles"
        }
      ModelFamily(
        name = name,
        category = category,
        summary = FAMILY_SUMMARIES[name] ?: main.info.lineSequence().firstOrNull().orEmpty(),
        models = members,
      )
    }
  return families.sortedBy { MODEL_CATEGORY_ORDER.indexOf(it.category).let { i -> if (i < 0) 99 else i } }
}

/**
 * Family card: name, latest model and size, short summary and model count. The arrow opens the
 * list of the family's models, each with its usual card (download, try, benchmark).
 */
@Composable
fun ModelFamilyCard(family: ModelFamily, modelContent: @Composable (Model) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val arrowRotation by
    animateFloatAsState(
      targetValue = if (expanded) 180f else 0f,
      animationSpec = tween(250),
      label = "familyArrow",
    )
  val latest = family.models.first()
  val shape = RoundedCornerShape(22.dp)
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          family.name,
          style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        )
        val latestLabel = latest.displayName.ifEmpty { latest.name }
        val size = if (latest.sizeInBytes > 0) " · " + latest.sizeInBytes.humanReadableSize() else ""
        Text(
          "Dernier : $latestLabel$size",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp),
        )
        if (family.summary.isNotBlank()) {
          Text(
            family.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
          )
        }
      }
      Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 12.dp)) {
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier.size(36.dp)
              .clip(RoundedCornerShape(50))
              .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
          Icon(
            Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (expanded) "Replier" else "Voir les modèles",
            modifier = Modifier.rotate(arrowRotation),
          )
        }
        Text(
          if (family.models.size > 1) "${family.models.size} modèles" else "1 modèle",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
    AnimatedVisibility(
      visible = expanded,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        for (model in family.models) {
          modelContent(model)
        }
      }
    }
  }
}
