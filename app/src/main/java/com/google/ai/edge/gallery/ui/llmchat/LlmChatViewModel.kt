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

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.agent.AgentEvent
import com.google.ai.edge.gallery.agent.AgentExecutionContext
import com.google.ai.edge.gallery.agent.AgentRequest
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.agent.Attachment
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.data.ARGORA_MODE_KEY
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.awaitInitialization
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.ai.edge.gallery.runtime.QuietCompletion
import com.google.ai.edge.gallery.runtime.SharedModelSession
import com.google.ai.edge.gallery.ui.common.chat.AttachedDocument
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.chat.DocumentPlan
import com.google.ai.edge.gallery.ui.common.chat.DocumentPlanner
import com.google.ai.edge.gallery.ui.common.chat.convertToLitertMessage
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ChatMode {
  GENERAL,
  CODE,
}

private const val TAG = "AGLlmChatViewModel"

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase(
  private val systemPromptRepository: SystemPromptRepository? = null,
  userDataDataStore: DataStore<UserData>? = null,
  private val modelFeedbackRepository: Any? = null,
  val runtimeExecutor: AgentRuntimeExecutor,
) : ChatViewModel(userDataDataStore) {
  private val _uiSystemPrompt = MutableStateFlow("")
  val uiSystemPrompt = _uiSystemPrompt.asStateFlow()
  private val _chatMode = MutableStateFlow(ChatMode.GENERAL)
  val chatMode = _chatMode.asStateFlow()

  companion object {
    const val CODE_MODE_SYSTEM_PROMPT =
      "You are an expert software engineer and coding assistant. You excel at writing clean, efficient, and well-documented code, explaining technical concepts clearly, and systematically debugging issues. Always provide clear explanations, best practices, and production-ready code snippets."
  }
  // Map to track if the session was stopped by the model for a given model name.
  private val sessionStoppedByModel = mutableMapOf<String, Boolean>()
  // The current task ID for the session.
  private var currentTaskId: String = ""

  fun setChatMode(mode: ChatMode, task: Task) {
    _chatMode.value = mode
    when (mode) {
      ChatMode.GENERAL -> {
        loadSystemPrompt(task)
      }
      ChatMode.CODE -> {
        _uiSystemPrompt.value = CODE_MODE_SYSTEM_PROMPT
      }
    }
  }

  /**
   * Sets the system prompt in the UI.
   *
   * This method updates the UI system prompt without saving it to the repository or resetting the
   * session. It is primarily used for initializing the UI system prompt.
   *
   * @param systemPrompt The new system prompt to set in the UI.
   */
  fun setUISystemPrompt(systemPrompt: String) {
    _uiSystemPrompt.value = systemPrompt
  }

  /**
   * Loads the system prompt for the given [task] from the repository.
   *
   * @param task The task to load the system prompt for.
   */
  fun loadSystemPrompt(task: Task) {
    currentTaskId = task.id
    if (_chatMode.value == ChatMode.CODE) {
      _uiSystemPrompt.value = CODE_MODE_SYSTEM_PROMPT
      return
    }
    viewModelScope.launch {
      val effectivePrompt =
        SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      _uiSystemPrompt.value = effectivePrompt
    }
  }

  /**
   * Applies a system prompt change to the given [task] and [model].
   *
   * This method updates the UI system prompt, saves the new prompt to the repository, and resets
   * the session with the new prompt.
   *
   * @param task The task to apply the system prompt change to.
   * @param model The model to apply the system prompt change to.
   * @param newPrompt The new system prompt to apply.
   * @param systemPromptUpdatedMessage The message to add to the chat after the system prompt is
   *   updated.
   */
  fun applySystemPromptChange(
    task: Task,
    model: Model,
    newPrompt: String,
    systemPromptUpdatedMessage: String,
  ) {
    _uiSystemPrompt.value = newPrompt
    viewModelScope.launch {
      systemPromptRepository?.updateSystemPrompt(task.id, newPrompt)
      resetSession(
        task = task,
        model = model,
        systemInstruction = newPrompt,
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        onDone = { addMessage(model, ChatMessageInfo(content = systemPromptUpdatedMessage)) },
      )
    }
  }

  open fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
    argoraMode: String? = null,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      val attachments = mutableListOf<Attachment>()
      for (image in images) {
        attachments.add(Attachment.ImageBitmap(image))
      }
      for (audioMessage in audioMessages) {
        attachments.add(Attachment.AudioBytes(audioMessage.genByteArrayForWav()))
      }

      val enableThinking =
        allowThinking &&
          model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
      val extraContext = buildMap {
        if (enableThinking) put("enable_thinking", "true")
        if (argoraMode != null) put(ARGORA_MODE_KEY, argoraMode)
      }
      val metadata =
        if (extraContext.isNotEmpty()) {
          mapOf(AgentRequest.LITERTLM_EXTRA_CONTEXT to extraContext)
        } else {
          emptyMap()
        }

      val request = AgentRequest(query = input, attachments = attachments, metadata = metadata)

      val context = AgentExecutionContext()

      var firstRun = true
      val start = System.currentTimeMillis()
      // [ALTREX] Streamed chunks and time of the first one, for the tokens/s shown under the answer.
      var streamedChunks = 0
      var firstChunkMs = 0L

      // [ALTREX] Also when a request made outside the chat replaced the model's conversation.
      val replaced = SharedModelSession.consumeConversationReplaced(model.name)
      if (sessionStoppedByModel[model.name] == true || replaced) {
        sessionStoppedByModel[model.name] = false
        val initialMessages =
          (uiState.value.messagesByModel[model.name] ?: emptyList())
            .filterIsInstance<ChatMessageText>()
            .dropLast(1)
            .mapNotNull { convertToLitertMessage(it) }
        val config =
          AgentRuntimeConfig(
            model = model,
            taskId = currentTaskId,
            supportImage = model.llmSupportImage,
            supportAudio = model.llmSupportAudio,
            systemInstruction = _uiSystemPrompt.value.ifEmpty { null },
            initialMessages = initialMessages,
          )
        runtimeExecutor.restartConversation(fallback = config)
      }

      // Run inference.
      // [ALTREX] An exception from the engine ends the request with an error message instead of
      // crashing the app (the collector runs in viewModelScope).
      try {
        runtimeExecutor.executeStream(context = context, request = request).collect { event ->
          when (event) {
            is AgentEvent.LoopInitiated -> {}
            is AgentEvent.StreamToken -> {
              val lastMessage = getLastMessage(model = model)
              val wasLoading = lastMessage?.type == ChatMessageType.LOADING
              // Remove the last message if it is a "loading" message.
              // This will only be done once.
              if (wasLoading) {
                removeLastMessage(model = model)
              }

              val thinkingText = event.thinking
              val isThinking = !thinkingText.isNullOrEmpty()
              var currentLastMessage = getLastMessage(model = model)

              // If thinking is enabled, add a thinking message.
              if (isThinking) {
                if (currentLastMessage?.type != ChatMessageType.THINKING) {
                  addMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = "",
                        inProgress = true,
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                      ),
                  )
                }
                updateLastThinkingMessageContentIncrementally(
                  model = model,
                  partialContent = thinkingText!!,
                )
              } else {
                if (currentLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = currentLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                currentLastMessage = getLastMessage(model = model)
                if (
                  currentLastMessage?.type != ChatMessageType.TEXT ||
                    currentLastMessage.side != ChatSide.AGENT
                ) {
                  // Add an empty message that will receive streaming results.
                  addMessage(
                    model = model,
                    message =
                      ChatMessageText(
                        content = "",
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                            currentLastMessage?.type == ChatMessageType.THINKING,
                      ),
                  )
                }

                // Incrementally update the streamed partial results.
                val latencyMs: Long = if (event.done) System.currentTimeMillis() - start else -1
                if (event.token.isNotEmpty()) {
                  if (streamedChunks == 0) firstChunkMs = System.currentTimeMillis()
                  streamedChunks++
                }
                val genMs = System.currentTimeMillis() - firstChunkMs
                // [ALTREX] llama.cpp: the engine's own token count and generation time (stream events
                // are merged chunks, not tokens).
                val llamaHelper =
                  (model.instance as? com.google.ai.edge.gallery.runtime.LlamaCppModelInstance)?.llamaHelper
                val tokensPerSecond =
                  if (event.done && llamaHelper != null) {
                    if (llamaHelper.lastGenMs > 0 && llamaHelper.lastTokens > 1) {
                      (llamaHelper.lastTokens - 1) * 1000f / llamaHelper.lastGenMs
                    } else {
                      -1f
                    }
                  } else if (event.done && streamedChunks > 1 && genMs > 0) {
                    (streamedChunks - 1) * 1000f / genMs
                  } else {
                    -1f
                  }
                if (event.token.isNotEmpty() || wasLoading || event.done) {
                  updateLastTextMessageContentIncrementally(
                    model = model,
                    partialContent = event.token,
                    latencyMs = latencyMs.toFloat(),
                    tokensPerSecond = tokensPerSecond,
                  )
                }
              }

              if (firstRun) {
                firstRun = false
                setPreparing(false)
                onFirstToken(model)
              }
            }
            is AgentEvent.LoopTerminated -> {
              val finalLastMessage = getLastMessage(model = model)
              if (finalLastMessage?.type == ChatMessageType.THINKING) {
                val thinkingMsg = finalLastMessage as ChatMessageThinking
                if (thinkingMsg.inProgress) {
                  replaceLastMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = thinkingMsg.content,
                        inProgress = false,
                        side = thinkingMsg.side,
                        accelerator = thinkingMsg.accelerator,
                        hideSenderLabel = thinkingMsg.hideSenderLabel,
                      ),
                    type = ChatMessageType.THINKING,
                  )
                }
              }
              setInProgress(false)
              setPreparing(false)
              onDone()
            }
            is AgentEvent.Error -> {
              Log.e(TAG, "Error occurred while running inference: ${event.errorMessage}")
              finishThinkingMessage(model)
              setInProgress(false)
              setPreparing(false)
              onError(event.errorMessage)
            }
            is AgentEvent.LoopCancelled -> {
              finishThinkingMessage(model)
              setInProgress(false)
              setPreparing(false)
            }
          }
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Inference failed", e)
        finishThinkingMessage(model)
        setInProgress(false)
        setPreparing(false)
        onError(e.message ?: e.toString())
      }
    }
  }

  /** [ALTREX] Ends a reasoning panel still marked in progress (Stop, error or cancelled loop). */
  private fun finishThinkingMessage(model: Model) {
    val last = getLastMessage(model = model)
    if (last is ChatMessageThinking && last.inProgress) {
      replaceLastMessage(
        model = model,
        message =
          ChatMessageThinking(
            content = last.content,
            inProgress = false,
            side = last.side,
            accelerator = last.accelerator,
            hideSenderLabel = last.hideSenderLabel,
          ),
        type = ChatMessageType.THINKING,
      )
    }
  }

  /** [ALTREX] Set by the stop button while a long document is read part by part. */
  @Volatile private var documentReadingStopped = false

  /**
   * [ALTREX] A document too long for the context, with a request about all of it: each part is
   * read on its own and summed up in notes (shown as progress), then the answer is written from
   * the notes through the usual [generateResponse].
   */
  fun generateResponseForLongDocument(
    model: Model,
    doc: AttachedDocument,
    plan: DocumentPlan.Sections,
    question: String,
    budgetChars: Int,
    systemPrompt: String,
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
    argoraMode: String? = null,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    documentReadingStopped = false
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      val count = plan.sections.size
      fun progress(label: String) {
        val last = getLastMessage(model = model)
        if (last is ChatMessageLoading) removeLastMessage(model = model)
        addMessage(model = model, message = ChatMessageLoading(extraProgressLabel = label, accelerator = accelerator))
      }
      val notes = ArrayList<String>()
      for ((index, section) in plan.sections.withIndex()) {
        if (documentReadingStopped) break
        progress("Lecture du document : partie ${index + 1} sur $count…")
        val result =
          QuietCompletion.run(
            model = model,
            messages =
              listOf(
                "system" to systemPrompt,
                "user" to DocumentPlanner.notesPrompt(doc, section, index, count, question),
              ),
          )
        val note = result.getOrNull()
        if (note == null) {
          if (getLastMessage(model = model) is ChatMessageLoading) removeLastMessage(model = model)
          setInProgress(false)
          onError(result.exceptionOrNull()?.message ?: "Lecture du document interrompue.")
          return@launch
        }
        notes.add(note)
      }
      if (getLastMessage(model = model) is ChatMessageLoading) removeLastMessage(model = model)
      if (documentReadingStopped || notes.isEmpty()) {
        setInProgress(false)
        onDone()
        return@launch
      }
      generateResponse(
        model = model,
        input = DocumentPlanner.finalPrompt(doc, plan, notes, question, budgetChars),
        onFirstToken = onFirstToken,
        onDone = onDone,
        onError = onError,
        allowThinking = allowThinking,
        argoraMode = argoraMode,
      )
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    documentReadingStopped = true
    QuietCompletion.stop(model)
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    } else {
      sessionStoppedByModel[model.name] = true
    }
    setInProgress(false)
    runtimeExecutor.interrupt()
    Log.d(TAG, "Done stopping response")
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: String? = null,
    actionChannel: SendChannel<ToolAction>? = null,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message> = listOf(),
    clearHistory: Boolean = true,
  ) {
    currentTaskId = task.id
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      if (clearHistory) {
        clearAllMessages(model = model)
      }
      stopResponse(model = model)
      sessionStoppedByModel[model.name] = false

      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          actionChannel = actionChannel,
          supportImage = supportImage,
          supportAudio = supportAudio,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          systemInstruction = systemInstruction,
          initialMessages = initialMessages,
        )
      runtimeExecutor.resetSession(config = config)

      setIsResettingSession(false)
      onDone()
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      if (model.instance == null) {
        try {
          model.awaitInitialization()
        } catch (e: Exception) {
          onError("Model initialization failed: ${e.message}")
          return@launch
        }
      }
      if (model.instance == null) {
        onError("Model not initialized.")
        return@launch
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(
            context = context,
            task = task,
            model = model,
            onDone = {
              // Add a warning message for re-initializing the session.
              addMessage(
                model = model,
                message = ChatMessageWarning(content = "Session re-initialized"),
              )
            },
            onError = {
              addMessage(
                model = model,
                message =
                  ChatMessageError(
                    content = "Failed to re-initialize session, please restart the app"
                  ),
              )
            },
          )
        },
      )
    }
  }
}

@HiltViewModel
open class LlmChatViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
) :
LlmChatViewModelBase(systemPromptRepository, userDataDataStore, null, runtimeExecutor)

@HiltViewModel
class LlmAskImageViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
) :
LlmChatViewModelBase(systemPromptRepository, userDataDataStore, null, runtimeExecutor)

@HiltViewModel
class LlmAskAudioViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
) :
LlmChatViewModelBase(systemPromptRepository, userDataDataStore, null, runtimeExecutor)
