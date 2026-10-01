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

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.awaitInitialization
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGLlmSingleTurnVM"

private const val MAX_HISTORY_TURNS = 4
private const val MAX_USER_CHARS = 1000
private const val MAX_ASSISTANT_CHARS = 2500

private data class ChatTurn(val user: String, val assistant: String)

data class LlmSingleTurnUiState(
  /** Indicates whether the runtime is currently processing a message. */
  val inProgress: Boolean = false,

  /**
   * Indicates whether the model is preparing (before outputting any result and after initializing).
   */
  val preparing: Boolean = false,

  // model -> <template label -> response>
  val responsesByModel: Map<String, Map<String, String>>,

  /** Selected prompt template type. */
  val selectedPromptTemplateType: PromptTemplateType = PromptTemplateType.entries[0],
)

@HiltViewModel
class LlmSingleTurnViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(createUiState())
  val uiState = _uiState.asStateFlow()

  // model name -> recent turns, replayed in each prompt because the conversation is reset.
  private val history = mutableMapOf<String, MutableList<ChatTurn>>()

  @Synchronized
  private fun buildPromptWithHistory(model: Model, input: String): String {
    val turns = history[model.name].orEmpty()
    if (turns.isEmpty()) return input
    return buildString {
      append("Conversation précédente :\n")
      for (t in turns) {
        append("Utilisateur : ${t.user}\n")
        append("Assistant : ${t.assistant}\n\n")
      }
      append("Nouvelle demande :\n")
      append(input)
    }
  }

  @Synchronized
  private fun addTurn(model: Model, user: String, assistant: String) {
    if (assistant.isBlank()) return
    val list = history.getOrPut(model.name) { mutableListOf() }
    list.add(ChatTurn(user.take(MAX_USER_CHARS), assistant.take(MAX_ASSISTANT_CHARS)))
    while (list.size > MAX_HISTORY_TURNS) list.removeAt(0)
  }

  @Synchronized
  fun clearHistory(model: Model) {
    history.remove(model.name)
  }

  fun generateResponse(task: Task, model: Model, input: String, instruction: String = input) {
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Wait for instance to be initialized.
      if (model.instance == null) {
        try {
          model.awaitInitialization()
        } catch (e: Exception) {
          setPreparing(false)
          setInProgress(false)
          return@launch
        }
      }
      if (model.instance == null) {
        setPreparing(false)
        setInProgress(false)
        return@launch
      }

      val supportImage =
        model.llmSupportImage &&
          task.id == com.google.ai.edge.gallery.data.BuiltInTaskId.LLM_ASK_IMAGE
      val supportAudio =
        model.llmSupportAudio &&
          task.id == com.google.ai.edge.gallery.data.BuiltInTaskId.LLM_ASK_AUDIO
      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = supportImage,
        supportAudio = supportAudio,
      )
      delay(500)

      val promptToRun = buildPromptWithHistory(model = model, input = input)

      // Run inference.
      var firstRun = true
      var response = ""
      model.runtimeHelper.runInference(