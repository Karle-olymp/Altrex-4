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

package com.google.ai.edge.gallery.ui.llmchat

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import com.google.ai.edge.gallery.ui.common.chat.SendMessageTrigger
import com.google.ai.edge.gallery.ui.common.chat.convertToLitertMessage
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@Composable
fun LlmChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onMenuClicked: (() -> Unit)? = null,
  taskId: String = BuiltInTaskId.LLM_CHAT,
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  onSkillClicked: () -> Unit = {},
  onMcpClicked: () -> Unit = {},
  onResetSessionClickedOverride: ((Task, Model, List<ChatMessage>, Boolean, () -> Unit) -> Unit)? =
    null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  viewModel: LlmChatViewModel = hiltViewModel(),
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  emptyStateComposable: @Composable (Model) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  skillCount: Int = 0,
  mcpCount: Int = 0,
  topContent: (@Composable () -> Unit)? = null,
) {
  val chatMode by viewModel.chatMode.collectAsState()
  val task = modelManagerViewModel.getTaskById(id = taskId)
  // [ALTREX] No General/Code switch above the chat any more: it took space for little use
  // (the Code space has its own screen). The chat stays in general mode.
  val defaultTopContent: @Composable () -> Unit = {}

  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = taskId,
    navigateUp = navigateUp,
    modifier = modifier,
    onMenuClicked = onMenuClicked,
    onSkillClicked = onSkillClicked,
    onMcpClicked = onMcpClicked,
    onFirstToken = onFirstToken,
    onGenerateResponseDone = onGenerateResponseDone,
    onResetSessionClickedOverride = onResetSessionClickedOverride,
    composableBelowMessageList = composableBelowMessageList,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    skillCount = skillCount,
    mcpCount = mcpCount,
    onSystemPromptChanged = onSystemPromptChanged,
    emptyStateComposable = emptyStateComposable,
    sendMessageTrigger = sendMessageTrigger,
    showImagePicker = showImagePicker,
    showAudioPicker = showAudioPicker,
    topContent = topContent ?: defaultTopContent,
  )
}

@Composable
fun ChatViewWrapper(
  viewModel: LlmChatViewModelBase,
  modelManagerViewModel: ModelManagerViewModel,
  taskId: String,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onMenuClicked: (() -> Unit)? = null,
  onSkillClicked: () -> Unit = {},
  onMcpClicked: () -> Unit = {},
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  onResetSessionClickedOverride: ((Task, Model, List<ChatMessage>, Boolean, () -> Unit) -> Unit)? =
    null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  emptyStateComposable: @Composable (Model) -> Unit = {},
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  skillCount: Int = 0,
  mcpCount: Int = 0,
  topContent: (@Composable () -> Unit)? = null,
) {
  val context = LocalContext.current
  val task = modelManagerViewModel.getTaskById(id = taskId)!!
  val scope = rememberCoroutineScope()

  ChatView(
    task = task,
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    onMenuClicked = onMenuClicked,
    onSendMessage = { model, messages ->
      for (message in messages) {
        viewModel.addMessage(model = model, message = message)
      }

      var text = ""
      val images: MutableList<Bitmap> = mutableListOf()
      val audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
      var chatMessageText: ChatMessageText? = null
      for (message in messages) {
        if (message is ChatMessageText) {
          chatMessageText = message
          text = message.content
        } else if (message is ChatMessageImage) {
          images.addAll(message.bitmaps)
        } else if (message is ChatMessageAudioClip) {
          audioMessages.add(message)
        }
      }
      if ((text.isNotEmpty() && chatMessageText != null) || audioMessages.isNotEmpty()) {
        if (text.isNotEmpty()) {
          modelManagerViewModel.addTextInputHistory(text)
        }
        // [ALTREX] Attached document: its text goes before the question, cut to fit the context.
        val doc = chatMessageText?.data as? com.google.ai.edge.gallery.ui.common.chat.AttachedDocument
        var input = text
        var longDocument: ((onError: (String) -> Unit) -> Unit)? = null
        // [ALTREX] An Argora model analyses an attached file in its "analyse" mode, with the
        // message form it was trained on.
        val argora = model.argoraManifest
        val argoraMode = if (doc != null && argora?.modes?.containsKey("analyse") == true) "analyse" else null
        if (doc != null) {
          val contextTokens =
            (model.instance as? com.google.ai.edge.gallery.runtime.LlamaCppModelInstance)?.contextLength
              ?: model.getIntConfigValue(
                com.google.ai.edge.gallery.data.ConfigKeys.MAX_TOKENS,
                com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN,
              )
          // About 3 characters per token; a third of the context stays for the history and answer.
          val budgetChars = contextTokens * 2
          when (
            val plan =
              com.google.ai.edge.gallery.ui.common.chat.DocumentPlanner.plan(
                doc = doc,
                question = text,
                budgetChars = budgetChars,
                argoraFormat = argora != null,
              )
          ) {
            is com.google.ai.edge.gallery.ui.common.chat.DocumentPlan.Direct -> input = plan.prompt
            is com.google.ai.edge.gallery.ui.common.chat.DocumentPlan.Passages -> {
              input = plan.prompt
              android.widget.Toast.makeText(
                  context,
                  "Document long : Altrex a gardé les passages liés à ta question" +
                    (if (plan.pages.isEmpty()) "." else " (pages ${plan.pages.joinToString(", ")})."),
                  android.widget.Toast.LENGTH_LONG,
                )
                .show()
            }
            is com.google.ai.edge.gallery.ui.common.chat.DocumentPlan.Sections -> {
              // A request about the whole document: read part by part, then answer from the notes.
              longDocument = { onError ->
                viewModel.generateResponseForLongDocument(
                  model = model,
                  doc = doc,
                  plan = plan,
                  question = text,
                  budgetChars = budgetChars,
                  systemPrompt =
                    argora?.systemPrompt
                      ?: curSystemPrompt.ifBlank { org.nehuatl.llamacpp.LlamaHelper.getDefaultSystemPrompt() },
                  onFirstToken = onFirstToken,
                  onDone = { onGenerateResponseDone(model) },
                  onError = onError,
                  allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
                  argoraMode = argoraMode,
                )
              }
            }
          }
        }
        val onError: (String) -> Unit = { errorMessage ->
          viewModel.handleError(
            context = context,
            task = task,
            model = model,
            errorMessage = errorMessage,
            modelManagerViewModel = modelManagerViewModel,
          )
        }
        // [ALTREX] Local memory: passages of the user's chosen folder that match the question, about
        // a third of the context at most; the chat shows which files were used. The search (and the
        // question's meaning vector) runs off the main thread, then the answer starts.
        val useMemory =
          doc == null && text.isNotBlank() && com.google.ai.edge.gallery.search.LocalMemory.isActive(context)
        val baseInput = input
        val readLongDocument = longDocument
        scope.launch {
          // [ALTREX] Sending to a model that is not loaded loads it first (an explicit request;
          // the Auto mode does not measure during a conversation).
          val loadError = modelManagerViewModel.ensureLoaded(context, task, model)
          if (loadError != null) {
            viewModel.addMessage(
              model = model,
              message = com.google.ai.edge.gallery.ui.common.chat.ChatMessageError(content = loadError),
            )
            return@launch
          }
          var finalInput = baseInput
          if (useMemory) {
            val contextTokens =
              (model.instance as? com.google.ai.edge.gallery.runtime.LlamaCppModelInstance)?.contextLength
                ?: model.getIntConfigValue(
                  com.google.ai.edge.gallery.data.ConfigKeys.MAX_TOKENS,
                  com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN,
                )
            val hits = com.google.ai.edge.gallery.search.LocalMemory.search(context, text, budgetChars = contextTokens)
            if (hits.isNotEmpty()) {
              finalInput = com.google.ai.edge.gallery.search.LocalMemory.prompt(context, hits, text)
              viewModel.addMessage(
                model = model,
                message =
                  com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo(
                    "Mémoire locale : " + com.google.ai.edge.gallery.search.LocalMemory.sourcesLabel(hits)
                  ),
              )
            }
          }
          if (readLongDocument != null) {
            readLongDocument(onError)
          } else {
            viewModel.generateResponse(
              model = model,
              input = finalInput,
              images = images,
              audioMessages = audioMessages,
              onFirstToken = onFirstToken,
              onDone = { onGenerateResponseDone(model) },
              onError = onError,
              allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
              argoraMode = argoraMode,
            )
          }
        }
      }
    },
    onRunAgainClicked = { model, message ->
      if (message is ChatMessageText) {
        viewModel.runAgain(
          model = model,
          message = message,
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              errorMessage = errorMessage,
              modelManagerViewModel = modelManagerViewModel,
            )
          },
          allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
        )
      }
    },
    onBenchmarkClicked = { _, _, _, _ -> },
    onResetSessionClicked = { model, chatMessages, clearHistory, onDone ->
      val litertMessages = chatMessages.mapNotNull { convertToLitertMessage(it) }
      if (onResetSessionClickedOverride != null) {
        onResetSessionClickedOverride(task, model, chatMessages, clearHistory, onDone)
      } else {
        viewModel.resetSession(
          task = task,
          model = model,
          systemInstruction = curSystemPrompt,
          supportImage = showImagePicker,
          supportAudio = showAudioPicker,
          initialMessages = litertMessages,
          onDone = onDone,
          clearHistory = clearHistory,
        )
      }
    },
    showStopButtonInInputWhenInProgress = true,
    onStopButtonClicked = { model -> viewModel.stopResponse(model = model) },
    onSkillClicked = onSkillClicked,
    onMcpClicked = onMcpClicked,
    navigateUp = navigateUp,
    skillCount = skillCount,
    mcpCount = mcpCount,
    modifier = modifier,
    composableBelowMessageList = composableBelowMessageList,
    showImagePicker = showImagePicker,
    emptyStateComposable = emptyStateComposable,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    sendMessageTrigger = sendMessageTrigger,
    showAudioPicker = showAudioPicker,
    topContent = topContent,
  )
}
