package com.google.ai.edge.gallery.ui.navigation

import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task

/**
 * [ALTREX] Task ids that no longer exist but may still arrive in a link or a saved reminder
 * (com.google.ai.edge.gallery://llm_agent_chat/?query=…): they open the space that replaced them.
 */
fun resolveTaskAlias(taskId: String): String =
  when (taskId) {
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO -> BuiltInTaskId.LLM_CHAT
    BuiltInTaskId.LLM_SECURITY -> BuiltInTaskId.LLM_PROMPT_LAB
    else -> taskId
  }

/** Where the start route goes. */
sealed interface StartDestination {
  /** The model list is still loading: show a neutral loader. */
  data object Waiting : StartDestination

  /** No model to chat with (or no list at all): show the welcome screen. */
  data object Welcome : StartDestination

  /** Open Discuter with this model. */
  data class Chat(val taskId: String, val modelName: String) : StartDestination
}

/**
 * Chooses the start destination. Discuter opens with its first downloaded model, or else its first
 * model (whose page offers the download). With no model at all, or when loading failed or took too
 * long ([gaveUpWaiting]), it is the welcome screen: never a blank screen.
 */
fun chooseStartDestination(
  tasks: List<Task>,
  downloadStatus: Map<String, ModelDownloadStatus>,
  loadFailed: Boolean,
  gaveUpWaiting: Boolean,
  // [ALTREX] The last model the user loaded: opened first (selected, not loaded).
  lastModelName: String? = null,
): StartDestination {
  if (tasks.isEmpty()) {
    return if (loadFailed || gaveUpWaiting) StartDestination.Welcome else StartDestination.Waiting
  }
  val chatTask = tasks.find { it.id == BuiltInTaskId.LLM_CHAT } ?: return StartDestination.Welcome
  val model =
    com.google.ai.edge.gallery.compute.ModelOrdering.preferred(chatTask.models, lastModelName, { it.name }) {
      downloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
    } ?: return StartDestination.Welcome
  return StartDestination.Chat(chatTask.id, model.name)
}
