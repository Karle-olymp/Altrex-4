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

import com.google.ai.edge.gallery.ui.theme.codeFontFamily
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
  onApplyToEditor: (String) -> Unit = {},
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val inProgress = uiState.inProgress
  val initializing = uiState.preparing
  val selectedPromptTemplateType = uiState.selectedPromptTemplateType
  val responseScrollState = rememberScrollState()
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val pagerState =
    rememberPagerState(initialPage = task.models.indexOf(model), pageCount = { task.models.size })
  val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
  val context = LocalContext.current

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
      Box(modifier = Modifier.weight(1f)) {
        if (initializing && response.isEmpty()) {
          Box(
            contentAlignment = Alignment.TopStart,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
          ) {
            MessageBodyLoading()
          }
        } else {
          // Message when response is empty.
          if (response.isEmpty()) {
            // [ALTREX] Altrex tile above the hint, instead of a bare grey line.
            androidx.compose.foundation.layout.Column(
              modifier = Modifier.fillMaxSize(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
              com.google.ai.edge.gallery.ui.common.AltrexAvatar(size = 40.dp)
              Text(
                stringResource(R.string.prompt_lab_response_placeholder),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
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
                onApplyToEditor = onApplyToEditor,
                onFileChanged = { uri, content ->
                  if (uri == openedFile?.uri) onFileContentSaved(content)
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
                // "Scroll to bottom" button, only shown when the list is not at the bottom.
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
