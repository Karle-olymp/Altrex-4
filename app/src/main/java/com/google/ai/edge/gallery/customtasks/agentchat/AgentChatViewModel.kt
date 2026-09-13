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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.agent.AgentChatExecutor
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.LogMessage
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/** Maximum number of self-correction attempts before giving up on a single goal. */
private const val MAX_SELF_CORRECTION_ITERATIONS = 3

/** Exact keyword the model must use to signal that the goal is fully achieved. */
private const val SATISFACTORY_KEYWORD = "SATISFAIT"

@HiltViewModel
class AgentChatViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  @AgentChatExecutor runtimeExecutor: AgentRuntimeExecutor,
) : LlmChatViewModel(systemPromptRepository, userDataDataStore, runtimeExecutor) {

  /**
   * When enabled, every call to [generateResponse] is followed by an automatic self-check: the
   * model is asked whether its own result fully satisfies the original goal, and if not, it
   * retries with the correction it identified, up to [MAX_SELF_CORRECTION_ITERATIONS] times.
   *
   * Defaults to `false` so existing behavior (a single turn per user message) is unchanged
   * unless a person explicitly turns this on.
   */
  val autonomousModeEnabled = MutableStateFlow(false)

  /** Turns the self-correcting autonomous loop on or off for this session. */
  fun setAutonomousMode(enabled: Boolean) {
    autonomousModeEnabled.value = enabled
  }

  override fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap>,
    audioMessages: List<ChatMessageAudioClip>,
    onFirstToken: (Model) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
    allowThinking: Boolean,
  ) {
    if (!autonomousModeEnabled.value) {
      super.generateResponse(
        model = model,
        input = input,
        images = images,
        audioMessages = audioMessages,
        onFirstToken = onFirstToken,
        onDone = onDone,
        onError = onError,
        allowThinking = allowThinking,
      )
      return
    }

    updateCollapsableProgressPanelMessage(
      model = model,
      title = "Autonomous mode",
      inProgress = true,
      doneIcon = Icons.Filled.CheckCircle,
      addItemTitle = "Attempt 1/$MAX_SELF_CORRECTION_ITERATIONS",
      addItemDescription = "Working on: $input",
    )

    runAttempt(
      model = model,
      goal = input,
      turnInput = input,
      images = images,
      audioMessages = audioMessages,
      onFirstToken = onFirstToken,
      onDone = onDone,
      onError = onError,
      allowThinking = allowThinking,
      iteration = 1,
    )
  }

  private fun runAttempt(
    model: Model,
    goal: String,
    turnInput: String,
    images: List<Bitmap>,
    audioMessages: List<ChatMessageAudioClip>,
    onFirstToken: (Model) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
    allowThinking: Boolean,
    iteration: Int,
  ) {
    super.generateResponse(
      model = model,
      input = turnInput,
      images = images,
      audioMessages = audioMessages,
      onFirstToken = onFirstToken,
      onDone = {
        val resultText =
          (getLastMessageWithType(model = model, type = ChatMessageType.TEXT) as? ChatMessageText)
            ?.content ?: ""

        if (iteration >= MAX_SELF_CORRECTION_ITERATIONS) {
          addLogMessageToLastCollapsableProgressPanel(
            model = model,
            logMessage =
              LogMessage(
                message = "Reached the max of $MAX_SELF_CORRECTION_ITERATIONS attempts, stopping."
              ),
          )
          onDone()
          return@generateResponse
        }

        runSelfCheck(
          model = model,
          goal = goal,
          resultText = resultText,
          onError = onError,
          allowThinking = allowThinking,
        ) { isSatisfactory, critique ->
          if (isSatisfactory) {
            addLogMessageToLastCollapsableProgressPanel(
              model = model,
              logMessage = LogMessage(message = "Self-check passed: goal achieved."),
            )
            onDone()
          } else {
            addLogMessageToLastCollapsableProgressPanel(
              model = model,
              logMessage = LogMessage(message = "Self-check found an issue: $critique"),
            )
            updateCollapsableProgressPanelMessage(
              model = model,
              title = "Autonomous mode",
              inProgress = true,
              doneIcon = Icons.Filled.CheckCircle,
              addItemTitle = "Attempt ${iteration + 1}/$MAX_SELF_CORRECTION_ITERATIONS",
              addItemDescription = "Fixing: $critique",
            )
            runAttempt(
              model = model,
              goal = goal,
              turnInput =
                "Continue working on the original goal below. Fix what you identified as " +
                  "still needing correction, then give your best complete answer.\n\n" +
                  "Original goal: $goal\nWhat needs fixing: $critique",
              images = listOf(),
              audioMessages = listOf(),
              onFirstToken = {},
              onDone = onDone,
              onError = onError,
              allowThinking = allowThinking,
              iteration = iteration + 1,
            )
          }
        }
      },
      onError = onError,
      allowThinking = allowThinking,
    )
  }

  /**
   * Asks the model, as a follow-up turn, whether its own last response fully satisfies the
   * original goal. This turn is a normal chat turn (visible like any other), kept intentionally
   * simple for a first version rather than hidden from the conversation.
   */
  private fun runSelfCheck(
    model: Model,
    goal: String,
    resultText: String,
    onError: (String) -> Unit,
    allowThinking: Boolean,
    onVerdict: (isSatisfactory: Boolean, critique: String) -> Unit,
  ) {
    val critiquePrompt =
      "You just attempted this goal: \"$goal\".\n" +
        "Your result was: \"$resultText\".\n" +
        "Is the goal fully and correctly achieved? " +
        "If yes, reply with exactly one word: $SATISFACTORY_KEYWORD. " +
        "If no, reply with a short, precise description of what still needs to be fixed."

    super.generateResponse(
      model = model,
      input = critiquePrompt,
      images = listOf(),
      audioMessages = listOf(),
      onFirstToken = {},
      onDone = {
        val critiqueText =
          (getLastMessageWithType(model = model, type = ChatMessageType.TEXT) as? ChatMessageText)
            ?.content
            ?.trim() ?: ""
        val isSatisfactory = critiqueText.uppercase().contains(SATISFACTORY_KEYWORD)
        onVerdict(isSatisfactory, critiqueText)
      },
      onError = onError,
      allowThinking = allowThinking,
    )
  }
}
