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

private const val MAX_HISTORY_TURNS = 2
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
  private fun buildPromptWithHistory(model: Model, input: String, maxChars: Int): String {
    val turns = history[model.name].orEmpty()
    val intro = "Conversation précédente :\n"
    val outro =
      "Réponds uniquement à la nouvelle demande, sans écrire « Assistant : ».\nNouvelle demande :\n"
    var remaining = maxChars - input.length - intro.length - outro.length
    val kept = mutableListOf<ChatTurn>()
    for (t in turns.asReversed()) {
      val cost = t.user.length + t.assistant.length + 30
      if (cost > remaining) break
      kept.add(0, t)
      remaining -= cost
    }
    if (kept.isEmpty()) return input
    return buildString {
      append(intro)
      for (t in kept) {
        append("Utilisateur : ${t.user}\n")
        append("Assistant : ${t.assistant}\n\n")
      }
      append(outro)
      append(input)
    }
  }

  @Synchronized
  private fun addTurn(model: Model, user: String, assistant: String) {
    if (assistant.isBlank()) return
    // Do not remember degenerate outputs (e.g. endless repeated characters).
    if (Regex("(.)\\1{30,}").containsMatchIn(assistant)) return
    val list = history.getOrPut(model.name) { mutableListOf() }
    list.add(ChatTurn(user.take(MAX_USER_CHARS), assistant.take(MAX_ASSISTANT_CHARS)))
    while (list.size > MAX_HISTORY_TURNS) list.removeAt(0)
  }

  @Synchronized
  fun clearHistory(model: Model) {
    history.remove(model.name)
  }

  fun generateResponse(
    task: Task,
    model: Model,
    input: String,
    instruction: String = input,
    maxPromptChars: Int = Int.MAX_VALUE,
    responsePrefix: String = "",
    onDone: ((String) -> Boolean)? = null,
  ) {
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

      val promptToRun =
        buildPromptWithHistory(model = model, input = input, maxChars = maxPromptChars)

      // Run inference.
      var firstRun = true
      var response = ""
      model.runtimeHelper.runInference(model = model,
        input = promptToRun,
        resultListener = { partialResult: String, done: Boolean, partialThinkingResult: String? ->
          if (firstRun) {
            setPreparing(false)
            firstRun = false
          }

          // Incrementally update the streamed partial results.
          response = processLlmResponse(response = "$response$partialResult")

          // Update response.
          updateResponse(
            model = model,
            promptTemplateType = uiState.value.selectedPromptTemplateType,
            response = responsePrefix + response,
          )

          if (done) {
            addTurn(model = model, user = instruction, assistant = response)
            // onDone returns true when it starts another generation (agent loop).
            val continued = onDone?.invoke(response) ?: false
            if (!continued) {
              setInProgress(false)
            }
          }
        },
        cleanUpListener = {
          setPreparing(false)
          setInProgress(false)
        },
        onError = { _: String ->
          setPreparing(false)
          setInProgress(false)
        },
        coroutineScope = viewModelScope,
      )
    }
  }

  fun currentResponse(model: Model): String =
    uiState.value.responsesByModel[model.name]?.get(uiState.value.selectedPromptTemplateType.name)
      ?: ""

  fun appendToResponse(model: Model, text: String) {
    updateResponse(
      model = model,
      promptTemplateType = uiState.value.selectedPromptTemplateType,
      response = currentResponse(model) + text,
    )
  }

  fun selectPromptTemplate(model: Model, promptTemplateType: PromptTemplateType) {
    Log.d(TAG, "selecting prompt template: ${promptTemplateType.name}")

    // Clear response.
    updateResponse(model = model, promptTemplateType = promptTemplateType, response = "")

    this._uiState.update {
      this.uiState.value.copy(selectedPromptTemplateType = promptTemplateType)
    }
  }

  fun setInProgress(inProgress: Boolean) {
    _uiState.update { _uiState.value.copy(inProgress = inProgress) }
  }

  fun setPreparing(preparing: Boolean) {
    _uiState.update { _uiState.value.copy(preparing = preparing) }
  }

  fun updateResponse(model: Model, promptTemplateType: PromptTemplateType, response: String) {
    _uiState.update { currentState ->
      val currentResponses = currentState.responsesByModel
      val modelResponses = currentResponses[model.name]?.toMutableMap() ?: mutableMapOf()
      modelResponses[promptTemplateType.name] = response
      val newResponses = currentResponses.toMutableMap()
      newResponses[model.name] = modelResponses
      currentState.copy(responsesByModel = newResponses)
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(false)
      model.runtimeHelper.stopResponse(model)
    }
  }

  private fun createUiState(): LlmSingleTurnUiState {
    val responsesByModel: MutableMap<String, Map<String, String>> = mutableMapOf()
    return LlmSingleTurnUiState(responsesByModel = responsesByModel)
  }
}