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

package com.google.ai.edge.gallery.ui.common

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.agentchat.agentSkillTopK
import com.google.ai.edge.gallery.customtasks.agentchat.agentSkillTopKAdjusted
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPageAppBar(
  task: Task,
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  onBackClicked: () -> Unit,
  onModelSelected: (prev: Model, cur: Model) -> Unit,
  inProgress: Boolean,
  modelPreparing: Boolean,
  modifier: Modifier = Modifier,
  hideModelSelector: Boolean = false,
  useThemeColor: Boolean = false,
  onMenuClicked: (() -> Unit)? = null,
  onConfigChanged: (oldConfigValues: Map<String, Any>, newConfigValues: Map<String, Any>) -> Unit =
    { _, _ ->
    },
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  shouldShowHistoryButton: Boolean = false,
  onHistoryClicked: (Model) -> Unit = {},
  onExportClicked: (() -> Unit)? = null,
) {
  var showConfigDialog by remember { mutableStateOf(false) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val context = LocalContext.current
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]
  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]
  val isModelInitializing =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
  val isModelInitialized =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED

  CenterAlignedTopAppBar(
    title = {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        // [ALTREX] No page name (Discuter, Code, Sécurité…) above the model chip: it took a line for
        // little use. The side menu already shows which space is open.
        // Model chips pager.
        if (!hideModelSelector) {
          val enableModelPickerChip = !isModelInitializing && !inProgress
          ModelPickerChip(
            enabled = enableModelPickerChip,
            task = task,
            initialModel = model,
            modelManagerViewModel = modelManagerViewModel,
            onModelSelected = onModelSelected,
          )
        }
      }
    },
    modifier = modifier,
    // The navigation icon (menu or back button).
    navigationIcon = {
      val enableNavButton = !isModelInitializing && !inProgress
      if (onMenuClicked != null) {
        IconButton(onClick = onMenuClicked, enabled = enableNavButton) {
          Icon(
            imageVector = Icons.Rounded.Menu,
            contentDescription = stringResource(R.string.cd_menu),
          )
        }
      } else {
        IconButton(onClick = onBackClicked, enabled = enableNavButton) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.cd_navigate_back_icon),
          )
        }
      }
    },
    // The config button for the model (if existed).
    actions = {
      val downloadSucceeded = curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      val showConfigButton = model.configs.isNotEmpty() && downloadSucceeded
      val showHistory = downloadSucceeded && shouldShowHistoryButton
      val showExport = downloadSucceeded && onExportClicked != null
      // [ALTREX] llama.cpp log export, to diagnose a GGUF model on the device.
      val showNativeLog = downloadSucceeded && model.runtimeType == RuntimeType.LLAMA_CPP
      // [ALTREX] Model settings stay visible; history and PDF export go in a "more" menu, so the
      // actions never overlap the centered model chip.
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (showConfigButton) {
          val enableConfigButton = !isModelInitializing && !inProgress && isModelInitialized
          IconButton(
            onClick = { showConfigDialog = true },
            enabled = enableConfigButton,
            modifier = Modifier.alpha(if (!enableConfigButton) 0.5f else 1f),
          ) {
            Icon(
              imageVector = Icons.Rounded.Tune,
              contentDescription = stringResource(R.string.cd_model_settings_icon),
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(20.dp),
            )
          }
        }
        if (showHistory || showExport || showNativeLog) {
          var showMoreMenu by remember { mutableStateOf(false) }
          val enableMore = !isModelInitializing && !modelPreparing && !inProgress
          Box {
            IconButton(
              onClick = { showMoreMenu = true },
              enabled = enableMore,
              modifier = Modifier.alpha(if (!enableMore) 0.5f else 1f),
            ) {
              Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "Plus d'options",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
              )
            }
            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
              if (showHistory) {
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.cd_chat_history)) },
                  leadingIcon = { Icon(Icons.Rounded.History, contentDescription = null) },
                  enabled = isModelInitialized,
                  onClick = {
                    showMoreMenu = false
                    onHistoryClicked(model)
                  },
                )
              }
              if (showExport) {
                DropdownMenuItem(
                  text = { Text("Exporter en PDF") },
                  leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                  onClick = {
                    showMoreMenu = false
                    onExportClicked?.invoke()
                  },
                )
              }
              if (showNativeLog) {
                DropdownMenuItem(
                  text = { Text("Exporter le journal llama.cpp") },
                  leadingIcon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                  onClick = {
                    showMoreMenu = false
                    com.google.ai.edge.gallery.runtime.LlamaDiagnostics.share(context, model)
                  },
                )
              }
            }
          }
        }
      }
    },
  )

  // Config dialog.
  if (showConfigDialog) {
    val modelConfigs = model.configs.toMutableList()
    if (!task.allowCapability(ModelCapability.LLM_THINKING, model)) {
      modelConfigs.removeIf { it.key == ConfigKeys.ENABLE_THINKING }
    }
    var supportsSpeculativeDecoding = false
    // Check if the model file supports speculative decoding.
    try {
      // [ALTREX] LiteRT-LM 0.18: Capabilities became ModelInfo + LlmCapability.
      com.google.ai.edge.litertlm.ModelInfo.from(model.getPath(context)).use {
        supportsSpeculativeDecoding =
          (it as? com.google.ai.edge.litertlm.LlmCapability)?.hasSpeculativeDecodingSupport() == true
      }
    } catch (e: Exception) {
      // Ignore exceptions and assume not supported.
    }
    if (
      !supportsSpeculativeDecoding ||
        !task.allowCapability(ModelCapability.SPECULATIVE_DECODING, model)
    ) {
      modelConfigs.removeIf { it.key == ConfigKeys.ENABLE_SPECULATIVE_DECODING }
    }
    ConfigDialog(
      title = stringResource(R.string.config_dialog_title),
      configs = modelConfigs,
      initialValues = model.configValues,
      onDismissed = { showConfigDialog = false },
      onOk = { curConfigValues, oldSystemPrompt, newSystemPrompt ->
        // Hide config dialog.
        showConfigDialog = false

        // Check if the configs are changed or not. Also check if the model needs to be
        // re-initialized.
        var same = true
        var needReinitialization = false
        for (config in modelConfigs) {
          val key = config.key.label
          val oldValue =
            convertValueToTargetType(
              value = model.configValues.getValue(key),
              valueType = config.valueType,
            )
          val newValue =
            convertValueToTargetType(
              value = curConfigValues.getValue(key),
              valueType = config.valueType,
            )
          if (oldValue != newValue) {
            same = false
            if (config.needReinitialization) {
              needReinitialization = true
            }
            break
          }
        }
        val systemPromptChanged = newSystemPrompt != oldSystemPrompt

        if (!same || systemPromptChanged) {
          firebaseAnalytics?.logEvent(
            GalleryEvent.MODEL_CONFIG_CHANGE.id,
            Bundle().apply {
              putString("model_id", model.name)
              putString("capability_name", task.id)
              putString("model_version", model.version)
              putString("app_version", BuildConfig.VERSION_NAME)
            },
          )
        }

        if (same) {
          if (systemPromptChanged) {
            onSystemPromptChanged(newSystemPrompt)
          }
          return@ConfigDialog
        }

        // Save the config values to Model.
        val oldConfigValues = model.configValues
        model.prevConfigValues = oldConfigValues
        model.configValues = curConfigValues
        if (task.id == BuiltInTaskId.LLM_AGENT_CHAT) {
          model.agentSkillTopKAdjusted = true
          model.agentSkillTopK = curConfigValues[ConfigKeys.TOPK.label]
        }
        modelManagerViewModel.updateConfigValuesUpdateTrigger()

        if (!task.handleModelConfigChangesInTask) {
          // Force to re-initialize the model with the new configs.
          if (needReinitialization) {
            modelManagerViewModel.initializeModel(
              context = context,
              task = task,
              model = model,
              force = true,
              onDone = {
                if (oldSystemPrompt != newSystemPrompt) {
                  onSystemPromptChanged(newSystemPrompt)
                }
              },
            )
          }

          // Notify.
          onConfigChanged(oldConfigValues, model.configValues)
        }
      },
      // AICore doesn't support system prompt yet.
      showSystemPromptEditorTab =
        allowEditingSystemPrompt && model.runtimeType != RuntimeType.AICORE,
      defaultSystemPrompt = task.defaultSystemPrompt,
      curSystemPrompt = curSystemPrompt,
    )
  }
}
