/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.data.getConfigValueString
import com.google.ai.edge.gallery.ui.theme.bodySmallNarrow
import com.google.ai.edge.gallery.ui.theme.titleSmaller

private data class ConfigRowData(
  @androidx.annotation.StringRes val labelRes: Int,
  val oldValueDisplay: String,
  val newValueDisplay: String,
  val isChanged: Boolean,
)

/**
 * Composable function to display a message indicating configuration value changes.
 *
 * This function renders a centered row containing a box that displays the old and new values of
 * configuration settings that have been updated.
 */
@Composable
fun MessageBodyConfigUpdate(message: ChatMessageConfigValuesChange) {
  val density = LocalDensity.current
  val windowInfo = LocalWindowInfo.current
  val screenWidthDp = remember { with(density) { windowInfo.containerSize.width.toDp() } }

  val configRows =
    remember(message) {
      val oldValues = message.oldValues
      val newValues = message.newValues
      val commonKeys = oldValues.keys.intersect(newValues.keys)
      commonKeys.mapNotNull { key ->
        val config =
          message.model.configs.firstOrNull { it.key.label == key } ?: return@mapNotNull null
        val oldValue: Any =
          convertValueToTargetType(
            value = message.oldValues.getValue(key),
            valueType = config.valueType,
          )
        val newValue: Any =
          convertValueToTargetType(
            value = message.newValues.getValue(key),
            valueType = config.valueType,
          )

        val isChanged = oldValue != newValue
        val oldValueDisplay = getConfigValueString(oldValue, config)
        val newValueDisplay = getConfigValueString(newValue, config)

        ConfigRowData(config.key.labelRes, oldValueDisplay, newValueDisplay, isChanged)
      }
    }
  // [ALTREX] Only the values that actually changed; all of them if nothing differs.
  val shownRows = configRows.filter { it.isChanged }.ifEmpty { configRows }

  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
    Box(
      modifier =
        Modifier.clip(RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
      Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        // Title.
        Text(
          androidx.compose.ui.res.stringResource(com.google.ai.edge.gallery.R.string.configs_updated),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = titleSmaller,
        )

        Row(modifier = Modifier.padding(top = 8.dp)) {
          // Keys
          Column(modifier = Modifier.widthIn(max = screenWidthDp / 2)) {
            for (rowData in shownRows) {
              Text(
                "${androidx.compose.ui.res.stringResource(rowData.labelRes)} :",
                style = bodySmallNarrow,
                modifier = Modifier.alpha(0.6f),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
              )
            }
          }

          Spacer(modifier = Modifier.width(4.dp))

          // Values
          Column {
            for (rowData in shownRows) {
              if (!rowData.isChanged) {
                Text(rowData.newValueDisplay, style = bodySmallNarrow, maxLines = 1)
              } else {
                val annotatedString = buildAnnotatedString {
                  withStyle(style = bodySmallNarrow.toSpanStyle()) {
                    append(rowData.oldValueDisplay)
                  }
                  withStyle(style = bodySmallNarrow.copy(fontSize = 12.sp).toSpanStyle()) {
                    append(" ▸ ") // Added spaces for visual separation
                  }
                  withStyle(
                    style =
                      bodySmallNarrow
                        .copy(fontWeight = FontWeight.Bold)
                        .toSpanStyle()
                        .copy(color = MaterialTheme.colorScheme.primary)
                  ) {
                    append(rowData.newValueDisplay)
                  }
                }
                Text(annotatedString, maxLines = 1, lineHeight = 12.sp)
              }
            }
          }
        }
      }
    }
  }
}
