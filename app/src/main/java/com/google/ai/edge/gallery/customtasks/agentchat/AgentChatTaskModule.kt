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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.agent.AgentChatExecutor
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.DefaultAgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.PromptExpander
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.McpServers
import com.google.ai.edge.gallery.skills.SkillManager
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.skills.formatSelectedSkills
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val TAG = "AGAgentChatTask"

// [ALTREX] The agent's routing prompt. The original one only knew skills and MCP tools ("if no
// skill is found, stop"): models never used run_command or answered directly, and invented skill
// names instead ("yum"). Every path is listed now, plain questions first.
private const val AGENT_PROMPT_HEAD =
  """
  You are Altrex, an assistant running on the user's phone. Reply in the user's language, briefly.

  For each request, choose ONE path:
  - A normal question, an explanation, code to write or review, or a chat: answer directly, with no tool.
  - Running a shell command, installing a package, executing code or a script: call `run_command`. It runs in your own Alpine Linux: install packages with `apk add <package>` (not apt or yum), one short command per call, then read `exit_code` and `output`.
  - Recent information from the internet: call `webSearch`.
  - A task that matches a skill in --- SKILLS ---: call `load_skill` with its exact name, then follow its instructions.

  When the user asks you to install, run, check, clone or download something, do it yourself with `run_command`: never tell the user to type the command, and never state a result that no tool gave you.
  Examples:
  - "install python 3" → call `run_command` with {"command": "apk add python3"}
  - "quelle version de Linux ?" → call `run_command` with {"command": "uname -a"}
  - "clone github.com/user/projet" → call `run_command` with {"command": "apk add git && git clone https://github.com/user/projet"}
  """

private const val AGENT_PROMPT_MCP_PATH =
  """
  - A task that matches a tool in --- MCP TOOLS ---: call `runMcpTool` with `toolName` (the exact name from the list) and `input` (a JSON object matching its schema).
  """

private const val AGENT_PROMPT_RULES =
  """
  Only use skill and tool names that appear below or above; never invent one. If a tool fails or is refused, say so plainly instead of retrying it.
  After a tool result, give the final answer: one short sentence on what you did, then the result.

  --- SKILLS ---
  ___SKILLS___
  """

private const val AGENT_PROMPT_MCP_LIST =
  """

  --- MCP TOOLS ---
  ___TOOLS___
  """

// The default system prompt for the agent chat task (skills and MCP tools).
val DEFAULT_SYSTEM_PROMPT =
  AGENT_PROMPT_HEAD.trimIndent() + "\n" + AGENT_PROMPT_MCP_PATH.trimIndent() + "\n\n" +
    AGENT_PROMPT_RULES.trimIndent() + "\n\n" + AGENT_PROMPT_MCP_LIST.trimIndent()

val DEFAULT_SYSTEM_PROMPT_TRIMMED = DEFAULT_SYSTEM_PROMPT.trim()

// The default system prompt for the agent chat task without MCP tools.
val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY =
  AGENT_PROMPT_HEAD.trimIndent() + "\n\n" + AGENT_PROMPT_RULES.trimIndent()

val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED = DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY.trim()

class AgentChatTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val skillsProvider: SkillsProvider,
  private val agentTools: AgentTools,
  @AgentChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_AGENT_CHAT,
      label = context.getString(R.string.task_label_agent_skills),
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      newFeature = true,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_agent_skills),
      shortDescription = context.getString(R.string.task_short_desc_agent_skills),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT_TRIMMED,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    val initialSystemPrompt = systemInstruction?.toString() ?: task.defaultSystemPrompt
    coroutineScope.launch(Dispatchers.Default) {
      agentTools.toolsEnabled = true
      val finalSystemPrompt = buildAgentSystemPrompt(agentTools, skillsProvider, initialSystemPrompt)

      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          actionChannel = agentTools.sendActionChannel,
          supportImage = model.llmSupportImage,
          supportAudio = model.llmSupportAudio,
          enableConversationConstrainedDecoding = true,
          systemInstruction = finalSystemPrompt,
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
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AgentChatTaskModule {
  @Provides
  @Singleton
  fun provideAgentTools(skillManager: SkillManager): AgentTools {
    return AgentToolsImpl().apply { skillsProvider = skillManager }
  }

  @Provides
  @Singleton
  @AgentChatExecutor
  fun provideAgentChatExecutor(
    skillManager: SkillManager,
    agentTools: AgentTools,
  ): AgentRuntimeExecutor {
    return DefaultAgentRuntimeExecutor(
      skillsProvider = skillManager,
      toolsProvider = agentTools,
      toolDispatcher = RuntimeToolDispatcher(),
    )
  }

  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    skillManager: SkillManager,
    agentTools: AgentTools,
    @AgentChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return AgentChatTask(context, skillManager, agentTools, executor)
  }

  @Provides
  @Singleton
  fun provideMcpServersDataStore(@ApplicationContext context: Context): DataStore<McpServers> {
    return DataStoreFactory.create(
      serializer = McpServersSerializer,
      produceFile = { context.dataStoreFile("mcp_servers.pb") },
    )
  }
}

/**
 * [ALTREX] The agent's system prompt: [template] with the selected skills and the MCP tools put
 * in (the default template depends on whether MCP tools are on). Shared by the main chat with
 * "Skills et outils" on and the Agent Skills task.
 */
suspend fun buildAgentSystemPrompt(
  agentTools: AgentTools,
  skillsProvider: SkillsProvider,
  template: String,
): String = coroutineScope {
  val skillsJob = launch { agentTools.skillsProvider.loadSkills(SkillManagerViewModel.DEFAULT_DISABLED_SKILLS) }
  val mcpJob = launch { agentTools.mcpManagerViewModel.loadMcpServers() }
  skillsJob.join()
  mcpJob.join()
  val toolsPrompt = agentTools.mcpManagerViewModel.getToolsPrompt()
  val baseSystemPrompt = getEffectiveBaseSystemPrompt(template, toolsPrompt.isNotEmpty())
  // TODO: inject prompt expander as a dependency.
  PromptExpander()
    .formatSystemInstructions(
      template = baseSystemPrompt,
      substitutions =
        mapOf(
          "___SKILLS___" to formatSelectedSkills(skillsProvider.getAvailableSkills()),
          "___TOOLS___" to toolsPrompt,
        ),
    )
}

// Check whether the system prompt is the default one.
fun isDefaultSystemPrompt(prompt: String): Boolean {
  return prompt == DEFAULT_SYSTEM_PROMPT_TRIMMED ||
    prompt == DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
}

// Returns the effective default system prompt depending on whether MCP tools are enabled.
fun getEffectiveBaseSystemPrompt(currentPrompt: String, hasMcpTools: Boolean): String {
  return if (isDefaultSystemPrompt(currentPrompt)) {
    if (hasMcpTools) DEFAULT_SYSTEM_PROMPT_TRIMMED else DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
  } else {
    currentPrompt
  }
}
