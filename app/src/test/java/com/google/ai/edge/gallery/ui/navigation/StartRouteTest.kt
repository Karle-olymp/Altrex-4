package com.google.ai.edge.gallery.ui.navigation

import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the app opens, in each case of the first launch, and the links to removed tasks. */
class StartRouteTest {
  private fun model(name: String) =
    Model(name = name, downloadFileName = "$name.gguf", url = "", sizeInBytes = 0L)

  private fun task(id: String, vararg models: Model) =
    Task(id = id, label = id, category = Category.LLM, description = "", models = models.toMutableList())

  private val downloaded = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)

  @Test
  fun waitsWhileTheListLoads() {
    assertEquals(
      StartDestination.Waiting,
      chooseStartDestination(emptyList(), emptyMap(), loadFailed = false, gaveUpWaiting = false),
    )
  }

  @Test
  fun welcomeWhenTheListFailsOrTakesTooLong() {
    assertEquals(
      StartDestination.Welcome,
      chooseStartDestination(emptyList(), emptyMap(), loadFailed = true, gaveUpWaiting = false),
    )
    assertEquals(
      StartDestination.Welcome,
      chooseStartDestination(emptyList(), emptyMap(), loadFailed = false, gaveUpWaiting = true),
    )
  }

  @Test
  fun welcomeWhenTheChatHasNoModel() {
    // Offline first launch: the list is empty, the tasks exist, no model at all.
    val tasks = listOf(task(BuiltInTaskId.LLM_CHAT), task(BuiltInTaskId.LLM_PROMPT_LAB))
    assertEquals(
      StartDestination.Welcome,
      chooseStartDestination(tasks, emptyMap(), loadFailed = true, gaveUpWaiting = false),
    )
  }

  @Test
  fun chatWithTheFirstDownloadedModel() {
    val tasks = listOf(task(BuiltInTaskId.LLM_CHAT, model("a"), model("b")))
    assertEquals(
      StartDestination.Chat(BuiltInTaskId.LLM_CHAT, "b"),
      chooseStartDestination(tasks, mapOf("b" to downloaded), loadFailed = false, gaveUpWaiting = false),
    )
  }

  @Test
  fun chatWithTheFirstModelWhenNoneIsDownloaded() {
    val tasks = listOf(task(BuiltInTaskId.LLM_CHAT, model("a"), model("b")))
    assertEquals(
      StartDestination.Chat(BuiltInTaskId.LLM_CHAT, "a"),
      chooseStartDestination(tasks, emptyMap(), loadFailed = false, gaveUpWaiting = false),
    )
  }

  @Test
  fun chatOpensTheLastUsedModelNotTheFirstOne() {
    // [ALTREX] The Argora Space (first, always "downloaded") no longer wins over the last model.
    val tasks = listOf(task(BuiltInTaskId.LLM_CHAT, model("space:argora"), model("qwen")))
    val both = mapOf("space:argora" to downloaded, "qwen" to downloaded)
    assertEquals(
      StartDestination.Chat(BuiltInTaskId.LLM_CHAT, "qwen"),
      chooseStartDestination(tasks, both, loadFailed = false, gaveUpWaiting = false, lastModelName = "qwen"),
    )
    // Last model deleted since: the first downloaded one.
    assertEquals(
      StartDestination.Chat(BuiltInTaskId.LLM_CHAT, "space:argora"),
      chooseStartDestination(tasks, both, loadFailed = false, gaveUpWaiting = false, lastModelName = "gone"),
    )
  }

  @Test
  fun removedTasksOpenTheirReplacement() {
    assertEquals(BuiltInTaskId.LLM_CHAT, resolveTaskAlias(BuiltInTaskId.LLM_AGENT_CHAT))
    assertEquals(BuiltInTaskId.LLM_CHAT, resolveTaskAlias(BuiltInTaskId.LLM_ASK_IMAGE))
    assertEquals(BuiltInTaskId.LLM_CHAT, resolveTaskAlias(BuiltInTaskId.LLM_ASK_AUDIO))
    assertEquals(BuiltInTaskId.LLM_PROMPT_LAB, resolveTaskAlias(BuiltInTaskId.LLM_SECURITY))
    assertEquals(BuiltInTaskId.LLM_CHAT, resolveTaskAlias(BuiltInTaskId.LLM_CHAT))
    assertEquals(BuiltInTaskId.LLM_PROMPT_LAB, resolveTaskAlias(BuiltInTaskId.LLM_PROMPT_LAB))
  }
}
