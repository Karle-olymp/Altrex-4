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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mms
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AgentChatExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.customtasks.agentchat.AgentChatScreen
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.ChatTools
import com.google.ai.edge.gallery.customtasks.agentchat.DEFAULT_SYSTEM_PROMPT_TRIMMED
import com.google.ai.edge.gallery.customtasks.agentchat.buildAgentSystemPrompt
import com.google.ai.edge.gallery.skills.SkillManager
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

////////////////////////////////////////////////////////////////////////////////////////////////////
// AI Chat.

/**
 * [ALTREX] "Discuter": the main chat. It runs on the agent engine; "Skills et outils" (off by
 * default, see ChatTools) decides whether the model is offered skills, MCP tools and phone actions.
 * Off, it is a plain chat: the chat's own system prompt and no tool in the prompt.
 */
class LlmChatTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val skillsProvider: SkillsProvider,
  private val agentTools: AgentTools,
  @AgentChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_CHAT,
      label = context.getString(R.string.task_label_ai_chat),
      category = Category.LLM,
      icon = Icons.Outlined.Forum,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_ai_chat),
      shortDescription = context.getString(R.string.task_short_desc_ai_chat),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      // The assistant panel also loads this model, without a chat screen: no tools then.
      val tools = ChatTools.isEnabled(context) && agentTools.isReady()
      agentTools.toolsEnabled = tools
      val prompt =
        if (tools) buildAgentSystemPrompt(agentTools, skillsProvider, DEFAULT_SYSTEM_PROMPT_TRIMMED)
        else systemInstruction?.toString()
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          actionChannel = if (tools) agentTools.sendActionChannel else null,
          supportImage = model.llmSupportImage,
          supportAudio = model.llmSupportAudio,
          enableConversationConstrainedDecoding = tools,
          systemInstruction = prompt,
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUpModel(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      onMenuClicked = myData.onMenuClicked,
      initialQuery = myData.initialQuery,
      optionalTools = true,
    )
  }
}

/** Empty state of the main chat (without skills): what the loaded model can take as input. */
@Composable
fun AiChatEmptyState(model: Model) {
  Box(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier =
        Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.aichat_emptystate_title), style = emptyStateTitle)
      Text(
        stringResource(R.string.aichat_emptystate_content),
        style = emptyStateContent,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      val multimodalRes =
        when {
          model.llmSupportImage && model.llmSupportAudio -> {
            if (model.runtimeType == RuntimeType.AICORE) {
              R.string.aichat_emptystate_support_image_aicore_audio
            } else {
              R.string.aichat_emptystate_support_image_audio
            }
          }
          model.llmSupportImage -> {
            if (model.runtimeType == RuntimeType.AICORE) {
              R.string.aichat_emptystate_support_image_aicore
            } else {
              R.string.aichat_emptystate_support_image
            }
          }
          model.llmSupportAudio -> R.string.aichat_emptystate_support_audio
          else -> null
        }

      if (multimodalRes != null) {
        Text(
          stringResource(multimodalRes),
          style = emptyStateContent,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    skillManager: SkillManager,
    agentTools: AgentTools,
    @AgentChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return LlmChatTask(context, skillManager, agentTools, executor)
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask image.

class LlmAskImageTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  @AiChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_ASK_IMAGE,
      label = context.getString(R.string.task_label_ask_image),
      category = Category.LLM,
      icon = Icons.Outlined.Mms,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_ask_image),
      shortDescription = context.getString(R.string.task_short_desc_ask_image),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          supportImage = true,
          supportAudio = false,
          systemInstruction = systemInstruction?.toString(),
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUpModel(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmAskImageViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmAskImageScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      onMenuClicked = myData.onMenuClicked,
      viewModel = viewModel,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmAskImageModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    @AiChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return LlmAskImageTask(context, executor)
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask audio.

class LlmAskAudioTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  @AiChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_ASK_AUDIO,
      label = context.getString(R.string.task_label_audio_scribe),
      category = Category.LLM,
      icon = Icons.Outlined.Mic,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_audio_scribe),
      shortDescription = context.getString(R.string.task_short_desc_audio_scribe),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          supportImage = false,
          supportAudio = true,
          systemInstruction = systemInstruction?.toString(),
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUpModel(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmAskAudioViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmAskAudioScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      onMenuClicked = myData.onMenuClicked,
      viewModel = viewModel,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmAskAudioModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    @AiChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return LlmAskAudioTask(context, executor)
  }
}
