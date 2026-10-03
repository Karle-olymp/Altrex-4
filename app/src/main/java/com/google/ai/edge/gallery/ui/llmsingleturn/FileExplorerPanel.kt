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

package com.google.ai.edge.gallery.ui.llmsingleturn

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile

/**
 * Lets the user pick a project folder (system folder picker), browse its contents, and create
 * new files directly at the root of the project.
 */
@Composable
fun FileExplorerPanel(modifier: Modifier = Modifier, onFileClicked: (DocumentFile) -> Unit = {}) {
  val context = LocalContext.current
  var rootFolder by remember {
    mutableStateOf(ProjectFolderManager.getSavedProjectFolder(context))
  }
  var treeExpanded by remember { mutableStateOf(true) }
  var rootChildren by remember(rootFolder) { mutableStateOf<List<DocumentFile>?>(null) }
  var showCreateFileDialog by remember { mutableStateOf(false) }

  LaunchedEffect(rootFolder, ProjectEvents.tick.intValue) {
    rootChildren = rootFolder?.let { ProjectFolderManager.listChildren(it) }
  }

  val folderPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        ProjectFolderManager.saveProjectFolder(context, uri)
        rootFolder = DocumentFile.fromTreeUri(context, uri)
        treeExpanded = true
      }
    }

  Column(modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.fillMaxWidth()
          .clickable(enabled = rootFolder != null) { treeExpanded = !treeExpanded }
          .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Icon(imageVector = Icons.Outlined.Folder, contentDescription = null)
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = rootFolder?.name ?: "No project folder selected",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (rootFolder != null) {
        Icon(
          imageVector = if (treeExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = null,
          modifier = Modifier.padding(end = 4.dp),
        )
        IconButton(onClick = { showCreateFileDialog = true }, modifier = Modifier.size(32.dp)) {
          Icon(imageVector = Icons.Outlined.Add, contentDescription = "New file")
        }
      }
      TextButton(onClick = { folderPickerLauncher.launch(null) }) {
        Text(if (rootFolder == null) "Select" else "Change")
      }
    }
    if (rootFolder != null && treeExpanded) {
      HorizontalDivider()
      LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
        val children = rootChildren
        if (children != null) {
          items(children) { child ->
            FileTreeNode(file = child, depth = 0, onFileClicked = onFileClicked)
          }
        }
      }
    }
  }

  if (showCreateFileDialog) {
    var newFileName by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showCreateFileDialog = false },
      title = { Text("New file") },
      text = {
        OutlinedTextField(
          value = newFileName,
          onValueChange = { newFileName = it },
          singleLine = true,
          placeholder = { Text("e.g. script.js") },
        )
      },
      confirmButton = {
        TextButton(
          enabled = newFileName.isNotBlank(),
          onClick = {
            val folder = rootFolder
            if (folder != null) {
              val newFile = ProjectFolderManager.createFile(folder, newFileName.trim())
              rootChildren = ProjectFolderManager.listChildren(folder)
              if (newFile != null) {
                onFileClicked(newFile)
              }
            }
            showCreateFileDialog = false
          },
        ) {
          Text("Create")
        }
      },
      dismissButton = {
        TextButton(onClick = { showCreateFileDialog = false }) { Text("Cancel") }
      },
    )
  }
}

@Composable
private fun FileTreeNode(file: DocumentFile, depth: Int, onFileClicked: (DocumentFile) -> Unit) {
  var expanded by remember(file.uri) { mutableStateOf(false) }
  var children by remember(file.uri) { mutableStateOf<List<DocumentFile>?>(null) }

  val refreshTick = ProjectEvents.tick.intValue
  LaunchedEffect(file.uri, expanded, refreshTick) {
    if (expanded && file.isDirectory) {
      children = ProjectFolderManager.listChildren(file)
    }
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .clickable {
          if (file.isDirectory) {
            expanded = !expanded
          } else {
            onFileClicked(file)
          }
        }
        .padding(start = (depth * 16 + 12).dp, top = 6.dp, bottom = 6.dp, end = 12.dp),
  ) {
    Icon(
      imageVector =
        if (file.isDirectory) {
          if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder
        } else {
          Icons.AutoMirrored.Outlined.InsertDriveFile
        },
      contentDescription = null,
      modifier = Modifier.size(18.dp),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(text = file.name ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }

  if (file.isDirectory && expanded) {
    children?.forEach { child -> FileTreeNode(file = child, depth = depth + 1, onFileClicked = onFileClicked) }
  }
}