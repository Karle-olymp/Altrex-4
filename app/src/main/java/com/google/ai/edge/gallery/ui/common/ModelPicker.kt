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

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
// import com.google.ai.edge.gallery.ui.preview.TASK_TEST1
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme

import android.content.Intent
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.ui.common.modelitem.StatusIcon
import com.google.ai.edge.gallery.ui.modelmanager.ModelImportDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelImportingDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.validateAndProcessModelUri
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow

@Composable
fun ModelPicker(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  onModelSelected: (Model) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  var showMemoryWarning by remember { mutableStateOf(false) }
  var modelToPick by remember { mutableStateOf<Model?>(null) }
  val context = LocalContext.current

  // Local model/GGUF import state, mirroring the flow used in GlobalModelManager's "Models"
  // screen, so a model can be imported without leaving the chat.
  var showImportDialog by remember { mutableStateOf(false) }
  var showImportingDialog by remember { mutableStateOf(false) }
  var showUnsupportedModelDialog by remember { mutableStateOf(false) }
  var unsupportedModelErrorMessage by remember { mutableStateOf("") }
  var selectedLocalModelFileUri by remember { mutableStateOf<Uri?>(null) }
  var selectedImportedModelInfo by remember { mutableStateOf<ImportedModel?>(null) }

  val processModelUri: (Uri) -> Unit = { uri ->
    validateAndProcessModelUri(
      uri = uri,
      context = context,
      isWebImport = false,
      onUnsupportedModelError = { errorMessage ->
        unsupportedModelErrorMessage = errorMessage
        showUnsupportedModelDialog = true
      },
      onValidModelUri = { validUri ->
        selectedLocalModelFileUri = validUri
        showImportDialog = true
      },
    )
  }

  val filePickerLauncher: ActivityResultLauncher<Intent> =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        result.data?.data?.let { uri -> processModelUri(uri) }
      }
    }

  var showDownloadable by remember { mutableStateOf(false) }
  // Reading task.updateTrigger keeps this list in sync when a model is imported while this
  // picker is open (import mutates task.models and bumps this trigger).
  task.updateTrigger.value
  val onDevice =
    task.models.filter {
      it.imported ||
        modelManagerUiState.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }
  val downloadable = task.models.filter { m -> onDevice.none { it.name == m.name } }
  val pickModel: (Model) -> Unit = { model ->
    // Show memory warning before proceeding.
    if (isMemoryLow(context = context, model = model)) {
      modelToPick = model
      showMemoryWarning = true
    } else {
      onModelSelected(model)
    }
  }

  Column(modifier = Modifier.padding(bottom = 12.dp)) {
    // Title.
    Row(
      modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        task.icon ?: ImageVector.vectorResource(task.iconVectorResourceId!!),
        tint = getTaskIconColor(task = task),
        modifier = Modifier.size(20.dp),
        contentDescription = null,
      )
      Text(
        "Modèles",
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    Text(
      "SUR L'APPAREIL",
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp),
    )
    if (onDevice.isEmpty()) {
      Text(
        "Aucun modèle sur l'appareil. Importe un fichier .gguf ci-dessous.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
      )
    }
    for (model in onDevice) {
      PickerModelRow(
        task = task,
        model = model,
        selected = model.name == modelManagerUiState.selectedModel.name,
        downloadStatus = modelManagerUiState.modelDownloadStatus[model.name],
        onClick = { pickModel(model) },
      )
    }

    // Import a local model file (.gguf or .litertlm) without leaving the chat.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 4.dp)
          .clip(RoundedCornerShape(16.dp))
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
          .clickable {
            val intent =
              Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
              }
            filePickerLauncher.launch(intent)
          }
          .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      Icon(
        Icons.AutoMirrored.Outlined.NoteAdd,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
      Text(
        "Importer un modèle depuis un fichier",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    // The catalog of models to download (hidden by default).
    if (downloadable.isNotEmpty()) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
          Modifier.fillMaxWidth()
            .clickable { showDownloadable = !showDownloadable }
            .padding(horizontal = 20.dp, vertical = 14.dp),
      ) {
        Text(
          "CATALOGUE À TÉLÉCHARGER (${downloadable.size})",
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.primary,
        )
        Icon(
          if (showDownloadable) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      AnimatedVisibility(visible = showDownloadable) {
        Column {
          for (model in downloadable) {
            PickerModelRow(
              task = task,
              model = model,
              selected = model.name == modelManagerUiState.selectedModel.name,
              downloadStatus = modelManagerUiState.modelDownloadStatus[model.name],
              onClick = { pickModel(model) },
            )
          }
        }
      }
    }
  }

  if (showMemoryWarning) {
    MemoryWarningAlert(
      onProceeded = {
        val curModelToPick = modelToPick
        if (curModelToPick != null) {
          onModelSelected(curModelToPick)
        }
        showMemoryWarning = false
      },
      onDismissed = { showMemoryWarning = false },
    )
  }

  // Import dialog: lets the user set config (context length, image/audio support, etc.)
  // for the picked file.
  if (showImportDialog) {
    selectedLocalModelFileUri?.let { uri ->
      ModelImportDialog(
        uri = uri,
        huggingFaceApiClient = modelManagerViewModel.huggingFaceApiClient,
        onDismiss = { showImportDialog = false },
        onDone = { info ->
          selectedImportedModelInfo = info
          showImportDialog = false
          showImportingDialog = true
        },
        accessToken = modelManagerViewModel.dataStoreRepository.readAccessTokenData()?.accessToken,
      )
    }
  }

  // Importing-in-progress dialog. On success, the model is added to every compatible task
  // (including this one), and the reactive task.updateTrigger read above makes it show up
  // in this same list immediately.
  if (showImportingDialog) {
    selectedLocalModelFileUri?.let { uri ->
      selectedImportedModelInfo?.let { info ->
        ModelImportingDialog(
          uri = uri,
          info = info,
          onDismiss = { showImportingDialog = false },
          onDone = {
            modelManagerViewModel.addImportedLlmModel(info = it)
            showImportingDialog = false
          },
        )
      }
    }
  }

  // Alert dialog for unsupported model file types.
  if (showUnsupportedModelDialog) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      onDismissRequest = { showUnsupportedModelDialog = false },
      title = { Text(stringResource(R.string.unsupported_model_title)) },
      text = { Text(unsupportedModelErrorMessage) },
      confirmButton = {
        Button(onClick = { showUnsupportedModelDialog = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }
}

// @Preview(showBackground = true)
// @Composable
// fun ModelPickerPreview() {
//   val context = LocalContext.current

//   GalleryTheme {
//     ModelPicker(
//       task = TASK_TEST1,
//       modelManagerViewModel = PreviewModelManagerViewModel(context = context),
//       onModelSelected = {},
//     )
//   }
// }

@Composable
private fun PickerModelRow(
  task: Task,
  model: Model,
  selected: Boolean,
  downloadStatus: ModelDownloadStatus?,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(16.dp)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 3.dp)
        .clip(shape)
        .background(
          if (selected) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceContainerLow
        )
        .border(
          width = if (selected) 1.5.dp else 1.dp,
          color =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          shape = shape,
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        model.displayName.ifEmpty { model.name },
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color =
          if (selected) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onSurface,
      )
      if (model.runtimeType != RuntimeType.AICORE) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          StatusIcon(task = task, model = model, downloadStatus = downloadStatus)
          Text(
            if (model.localFileRelativeDirPathOverride.isEmpty()) {
              model.sizeInBytes.humanReadableSize()
            } else {
              "Modèle importé"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = labelSmallNarrow.copy(lineHeight = 10.sp),
          )
        }
      }
    }
    if (selected) {
      Icon(
        Icons.Filled.CheckCircle,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.primary,
        contentDescription = stringResource(R.string.cd_selected_icon),
      )
    }
  }
}
