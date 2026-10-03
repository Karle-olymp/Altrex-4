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

package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.ClipData
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.BufferedFadingMarkdownText
import com.google.ai.edge.gallery.ui.common.ScrollToBottomButton
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyLoading
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.Save
import androidx.compose.ui.graphics.SolidColor
import android.net.Uri
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign

private const val TAG = "AGResponsePanel"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponsePanel(
  task: Task,
  model: Model,
  viewModel: LlmSingleTurnViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
  openedFile: OpenedFile? = null,
  onFileContentSaved: (String) -> Unit = {},
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val inProgress = uiState.inProgress
  val initializing = uiState.preparing
  val selectedPromptTemplateType = uiState.selectedPromptTemplateType
  val responseScrollState = rememberScrollState()
  val codeScrollState = rememberScrollState()
  // 0 = Response tab, 1 = Code tab.
  var selectedOptionIndex by remember { mutableIntStateOf(0) }
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val pagerState =
    rememberPagerState(initialPage = task.models.indexOf(model), pageCount = { task.models.size })
  val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
  val context = LocalContext.current

  // Select the "response" tab when prompt template changes.
  // Code block proposed by the model, pending review in the Code tab (not saved yet).
  var pendingApply by remember { mutableStateOf<Pair<Uri, String>?>(null) }
  LaunchedEffect(selectedPromptTemplateType) { selectedOptionIndex = 0 }

  // Switch to the "Code" tab as soon as a file is opened.
  LaunchedEffect(openedFile) {
    if (openedFile != null) {
      selectedOptionIndex = 1
    }
  }

  // Switch back to "Response" as soon as a generation starts.
  LaunchedEffect(inProgress) {
    if (inProgress) {
      selectedOptionIndex = 0
    }
  }

  // Update selected model and clean up previous model when page is settled on a model page.
  LaunchedEffect(pagerState.settledPage) {
    val curSelectedModel = task.models.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
    Log.d(
      TAG,
      "Pager settled on model '${curSelectedModel.name}' from '${model.name}'. Updating selected model.",
    )
    if (curSelectedModel.name != model.name) {
      modelManagerViewModel.cleanupModel(context = context, task = task, model = model)
    }
    modelManagerViewModel.selectModel(curSelectedModel)
  }

  // Scroll pager when selected model changes.
  LaunchedEffect(modelManagerUiState.selectedModel) {
    val targetPage = task.models.indexOf(model)
    if (targetPage in task.models.indices) {
      pagerState.animateScrollToPage(targetPage)
    }
  }

  HorizontalPager(state = pagerState, userScrollEnabled = false) { pageIndex ->
    val curPageModel = task.models[pageIndex]

    val response =
      uiState.responsesByModel[curPageModel.name]?.get(selectedPromptTemplateType.name) ?: ""

    Column(modifier = modifier.fillMaxSize()) {
      // Compact Code/Response tab selector — only shown once a file has been opened.
      if (openedFile != null) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SegmentedTabButton(
            label = "Code",
            selected = selectedOptionIndex == 1,
            onClick = { selectedOptionIndex = 1 },
            modifier = Modifier.weight(1f),
          )
          SegmentedTabButton(
            label = "Response",
            selected = selectedOptionIndex == 0,
            onClick = { selectedOptionIndex = 0 },
            modifier = Modifier.weight(1f),
          )
        }
      }

      Box(modifier = Modifier.weight(1f)) {
        if (selectedOptionIndex == 1 && openedFile != null) {
          // Code tab: editable preview of the opened file.
          val pendingContent = pendingApply?.takeIf { it.first == openedFile.uri }?.second
          var editedContent by
            remember(openedFile.uri, pendingContent) {
              mutableStateOf(pendingContent ?: openedFile.content)
            }
          var lastSavedContent by
            remember(openedFile.uri, pendingContent) { mutableStateOf(openedFile.content) }
          var saveError by remember(openedFile.uri) { mutableStateOf(false) }
          val isDirty = editedContent != lastSavedContent

          Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.fillMaxSize()) {
            val codeTextStyle =
              MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
              )
            val lineCount = editedContent.count { it == '\n' } + 1
            val horizontalScrollState = rememberScrollState()
            Row(
              modifier =
                Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(codeScrollState)
            ) {
              Text(
                text = (1..lineCount).joinToString("\n"),
                style = codeTextStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                textAlign = TextAlign.End,
                modifier = Modifier.padding(top = 8.dp, bottom = 56.dp, end = 8.dp),
              )
              Box(modifier = Modifier.weight(1f).horizontalScroll(horizontalScrollState)) {
                BasicTextField(
                  value = editedContent,
                  onValueChange = {
                    editedContent = it
                    saveError = false
                  },
                  textStyle = codeTextStyle,
                  cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                  modifier = Modifier.widthIn(min = 280.dp).padding(top = 8.dp, bottom = 56.dp),
                )
              }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
              if (saveError) {
                Text(
                  text = "Save failed",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.error,
                  modifier = Modifier.align(Alignment.CenterVertically),
                )
              }
              IconButton(
                enabled = isDirty,
                onClick = {
                  val success =
                    ProjectFolderManager.writeTextFile(context, openedFile.uri, editedContent)
                  if (success) {
                    lastSavedContent = editedContent
                    saveError = false
                    onFileContentSaved(editedContent)
                    pendingApply = null
                  } else {
                    saveError = true
                  }
                },
                colors =
                  IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                  ),
              ) {
                Icon(Icons.Outlined.Save, contentDescription = "Save", modifier = Modifier.size(20.dp))
              }
              IconButton(
                onClick = {
                  scope.launch {
                    val clipData = ClipData.newPlainText("code", editedContent)
                    val clipEntry = ClipEntry(clipData = clipData)
                    clipboard.setClipEntry(clipEntry = clipEntry)
                  }
                },
                colors =
                  IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.primary,
                  ),
              ) {
                Icon(
                  Icons.Outlined.ContentCopy,
                  contentDescription = stringResource(R.string.cd_copy_to_clipboard_icon),
                  modifier = Modifier.size(20.dp),
                )
              }
            }
          }
        } else if (initializing) {
          Box(
            contentAlignment = Alignment.TopStart,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
          ) {
            MessageBodyLoading()
          }
        } else {
          // Message when response is empty.
          if (response.isEmpty()) {
            Row(
              modifier = Modifier.fillMaxSize(),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                stringResource(R.string.prompt_lab_response_placeholder),
                modifier = Modifier.alpha(0.5f),
                style = MaterialTheme.typography.labelMedium,
              )
            }
          }
          // Response markdown.
          else {
            // Stores if the list is at the scrollable area's bottom.
            //
            // It will only be updated when the state holds for at least 500ms to improve user
            // experience.
            var isAtBottom by remember { mutableStateOf(true) }
            LaunchedEffect(responseScrollState) {
              snapshotFlow {
                  // Read the raw scroll state here
                  !responseScrollState.canScrollForward
                }
                .collectLatest { rawAtBottom ->
                  if (!rawAtBottom) {
                    delay(500)
                  }
                  // Update the actual state that drives your AnimatedVisibility
                  isAtBottom = rawAtBottom
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
              ResponseActionsBar(
                response = response,
                inProgress = inProgress,
                openedFileUri = openedFile?.uri,
                onCopy = {
                  scope.launch {
                    clipboard.setClipEntry(
                      ClipEntry(clipData = ClipData.newPlainText("response", response))
                    )
                  }
                },
                onApplyToEditor = { code ->
                  openedFile?.let { pendingApply = Pair(it.uri, code) }
                  selectedOptionIndex = 1
                },
                onFileChanged = { uri, content ->
                  if (uri == openedFile?.uri) {
                    onFileContentSaved(content)
                    pendingApply = null
                  }
                },
              )
              Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(responseScrollState)) {
                  BufferedFadingMarkdownText(
                    text = response,
                    inProgress = uiState.inProgress,
                    modifier =
                      Modifier.padding(top = 8.dp, bottom = 40.dp).semantics {
                        // Only announce when message is complete.
                        if (!inProgress) {
                          liveRegion = LiveRegionMode.Polite
                        }
                      },
                  )
                }
                
                  colors =
                    IconButtonDefaults.iconButtonColors(
                      containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                      contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                  Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_to_clipboard_icon),
                    modifier = Modifier.size(20.dp),
                  )
                }

                // "Scroll to bottom" button, only shown when the list is not at the bottom.
                
                    modifier = Modifier.padding(end = 52.dp),
                    colors =
                      IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.primary,
                      ),
                  ) {
                    Icon(
                      Icons.Outlined.Check,
                      contentDescription = "Apply to file",
                      modifier = Modifier.size(20.dp),
                    )
                  }
                }
                Column(
                  modifier =
                    Modifier.align(alignment = Alignment.BottomCenter)
                      .fillMaxWidth()
                      .padding(bottom = 0.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  ScrollToBottomButton(
                    isAtBottom = isAtBottom,
                    onClick = {
                      scope.launch {
                        responseScrollState.animateScrollTo(
                          responseScrollState.maxValue,
                          animationSpec =
                            tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        )
                      }
                    },
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SegmentedTabButton(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier
        .clip(RoundedCornerShape(50))
        .background(
          if (selected) MaterialTheme.colorScheme.secondaryContainer
          else MaterialTheme.colorScheme.surfaceContainerLow
        )
        .border(
          width = 1.dp,
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(50),
        )
        .clickable(onClick = onClick)
        .height(36.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
private fun extractLargestCodeBlock(text: String): String? {
  val regex = Regex("```[^\\n`]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
  return regex
    .findAll(text)
    .map { it.groupValues[1].trimEnd('\n') + "\n" }
    .maxByOrNull { it.length }
    ?.takeIf { it.isNotBlank() }
}
private fun cleanForApply(block: String, fileName: String?): String {
  var lines = block.trimEnd('\n').lines()
  // The model sometimes echoes the "// fileName" header line.
  if (fileName != null && lines.isNotEmpty() && lines.first().trim().startsWith("// $fileName")) {
    lines = lines.drop(1)
  }
  // Strip "N | " line numbers if the model copied them.
  val numbered = Regex("^\\s*\\d+\\s*\\|\\s?(.*)$")
  val nonBlank = lines.filter { it.isNotBlank() }
  if (nonBlank.isNotEmpty() && nonBlank.all { numbered.matches(it) }) {
    lines = lines.map { numbered.matchEntire(it)?.groupValues?.get(1) ?: it }
  }
  return lines.joinToString("\n") + "\n"
}