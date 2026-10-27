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

package com.google.ai.edge.gallery.agent

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.ARGORA_MODE_KEY
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.awaitInitialization
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.tools.ToolDispatcher
import com.google.ai.edge.gallery.tools.ToolExecutionContext
import com.google.ai.edge.gallery.tools.ToolsProvider
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentMsg
import com.google.ai.edge.gallery.ui.llmsingleturn.StreamSplitter
import com.google.ai.edge.gallery.ui.llmsingleturn.ToolCalling
import com.google.ai.edge.litertlm.Contents
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

private const val TAG = "AGDefaultAgentRuntimeExecutor"

/** Maximum number of tool-calling steps for local llama.cpp models before giving up. */
private const val MAX_LLAMA_AGENT_STEPS = 6

private const val FINAL_ANSWER_NUDGE =
  "N'appelle plus d'outil. Réponds maintenant à l'utilisateur avec ce que tu as déjà trouvé."

private const val EMPTY_ANSWER_NUDGE =
  "Tu n'as rien écrit pour l'utilisateur. Réponds-lui maintenant, directement."

/** Messages kept in the llama.cpp conversation memory (user + assistant). */
private const val MAX_LLAMA_HISTORY = 20

/**
 * Default implementation of [AgentRuntimeExecutor] orchestrating context assembly, execution
 * context injection, LiteRT-LM inference turns, and event streaming.
 *
 * This implementation is thread-safe and lock-free. Internal session state such as the active model
 * and tool execution context is encapsulated within an immutable structure managed by an atomic
 * reference. This allows lifecycle operations ([initialize], [executeStream], [interrupt], and
 * [cleanUp]) to be invoked safely across concurrent threads or coroutines without contention, race
 * conditions, or deadlocks.
 */
open class DefaultAgentRuntimeExecutor(
  val skillsProvider: SkillsProvider,
  val toolsProvider: ToolsProvider,
  val toolDispatcher: ToolDispatcher,
) : AgentRuntimeExecutor {

  /** Active session state for the current conversation. */
  private data class ActiveSession(
    val model: Model,
    val toolExecutionContext: ToolExecutionContext,
    val systemInstruction: String?,
  )

  /** Atomic reference to the active session. */
  private val activeSession = AtomicReference<ActiveSession?>(null)

  /** [ALTREX] The configuration of the last initialize / resetSession, for [restartConversation]. */
  private val lastConfig = AtomicReference<AgentRuntimeConfig?>(null)

  /** Set when the user interrupts an in-flight generation; reset at the start of each request. */
  private val interrupted = AtomicBoolean(false)

  /**
   * [ALTREX] Conversation so far for local llama.cpp models (user questions and final answers), so
   * the model remembers the previous turns. LiteRT-LM keeps its own conversation natively.
   */
  private val llamaHistory = ArrayList<AgentMsg>()

  // [ALTREX] Bumped by Stop / reset: a running llama.cpp loop compares it with the value it started
  // with, so a later request can no longer "un-stop" it (a shared boolean was reset by the next
  // call). sessionGen only changes on reset, so an old loop never writes into a new chat's memory.
  private val stopGen = java.util.concurrent.atomic.AtomicInteger(0)
  private val sessionGen = java.util.concurrent.atomic.AtomicInteger(0)

  override suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (String) -> Unit,
  ) {
    val systemInstruction = Contents.of(config.systemInstruction ?: "")
    lastConfig.set(config)
    val executionContext =
      ToolExecutionContext(taskId = config.taskId, actionChannel = config.actionChannel)

    interrupted.set(false)
    stopGen.incrementAndGet()
    sessionGen.incrementAndGet()
    synchronized(llamaHistory) {
      llamaHistory.clear()
      // A reloaded past chat (or the chat so far after a Stop): the model remembers it too.
      for (msg in config.initialMessages) {
        val text = msg.contents.toString().trim()
        if (text.isEmpty()) continue
        llamaHistory.add(
          AgentMsg(if (msg.role == com.google.ai.edge.litertlm.Role.USER) "user" else "assistant", text)
        )
      }
      while (llamaHistory.size > MAX_LLAMA_HISTORY) llamaHistory.removeAt(0)
    }
    activeSession.set(
      ActiveSession(
        model = config.model,
        toolExecutionContext = executionContext,
        systemInstruction = config.systemInstruction,
      )
    )

    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = executionContext,
    )

    config.model.runtimeHelper.initialize(
      context = context,
      model = config.model,
      taskId = config.taskId,
      supportImage = config.supportImage,
      supportAudio = config.supportAudio,
      onDone = onDone,
      systemInstruction = systemInstruction,
      tools = toolsProvider.getLiteRtToolProviders(),
      enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
    )
  }

  private fun ProducerScope<AgentEvent>.emitEvent(event: AgentEvent) {
    trySend(event).onFailure { Log.w(TAG, "Failed to emit event: $event", it) }
  }

  override fun executeStream(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): Flow<AgentEvent> = callbackFlow {
    emitEvent(AgentEvent.LoopInitiated(request = request))

    // [ALTREX] No session (the model was unloaded): an error event, not an exception that crashed
    // the collecting screen.
    val session = activeSession.get()
    if (session == null) {
      emitEvent(AgentEvent.Error("Le modèle n'est pas chargé. Recharge-le puis renvoie ta demande."))
      close()
      return@callbackFlow
    }
    val curModel = session.model

    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = session.toolExecutionContext,
    )

    val images = request.attachments.filterIsInstance<Attachment.ImageBitmap>().map { it.bitmap }
    val audioClips =
      request.attachments.filterIsInstance<Attachment.AudioBytes>().map { it.audioBytes }

    val finalResponse = StringBuilder()
    val extraContext =
      (request.metadata[AgentRequest.LITERTLM_EXTRA_CONTEXT] as? Map<*, *>)
        ?.entries
        ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }
        ?.toMap()
        ?.ifEmpty { null }
    if (curModel.instance == null) {
      try {
        curModel.awaitInitialization()
      } catch (e: Exception) {
        emitEvent(AgentEvent.Error("Model initialization failed: ${e.message}"))
        close()
        return@callbackFlow
      }
    }
    if (curModel.instance == null) {
      emitEvent(AgentEvent.Error("Model not initialized."))
      close()
      return@callbackFlow
    }

    // Local llama.cpp models have no native tool calling: run the ReAct loop here, driving the
    // same tool definitions through the ChatML / Hermes protocol. LiteRT-LM models keep using the
    // engine's built-in automatic tool calling (single runInference call below).
    if (curModel.runtimeType == RuntimeType.LLAMA_CPP) {
      val producer = this
      val loopJob =
        launch(Dispatchers.IO) {
          try {
            val finalResponse =
              runLlamaCppAgentLoop(
                model = curModel,
                request = request,
                systemInstruction = session.systemInstruction,
                enableThinking = extraContext?.get("enable_thinking") == "true",
                argoraModeId = extraContext?.get(ARGORA_MODE_KEY),
                emit = { producer.emitEvent(it) },
              )
            if (finalResponse == null) {
              producer.emitEvent(AgentEvent.LoopCancelled)
            } else {
              producer.emitEvent(AgentEvent.LoopTerminated(finalResponse = finalResponse))
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Error in llama.cpp agent loop", e)
            producer.emitEvent(AgentEvent.Error(errorMessage = e.message ?: "Agent loop failed."))
          } finally {
            producer.close()
          }
        }
      awaitClose { loopJob.cancel() }
      return@callbackFlow
    }

    curModel.runtimeHelper.runInference(
      model = curModel,
      input = request.query,
      images = images,
      audioClips = audioClips,
      extraContext = extraContext,
      resultListener = { partialResult, done, partialThinking ->
        if (!partialResult.startsWith("<ctrl")) {
          if (partialResult.isNotEmpty() || !partialThinking.isNullOrEmpty() || done) {
            emitEvent(
              AgentEvent.StreamToken(token = partialResult, thinking = partialThinking, done = done)
            )
          }
          if (partialResult.isNotEmpty()) {
            finalResponse.append(partialResult)
          }
        }
        if (done) {
          emitEvent(AgentEvent.LoopTerminated(finalResponse = finalResponse.toString()))
          close()
        }
      },
      cleanUpListener = {
        // Clean up completed
        emitEvent(AgentEvent.LoopCancelled)
        close()
      },
      onError = { errorMsg ->
        Log.e(TAG, "Error in AgentLoop inference: $errorMsg")
        emitEvent(AgentEvent.Error(errorMessage = errorMsg))
        close()
      },
    )

    awaitClose {
      // Clean up when flow collection is cancelled
    }
  }

  override suspend fun execute(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): AgentResponse {
    var finalOutput = ""
    var isSuccess = true

    executeStream(context, request).collect { event ->
      when (event) {
        is AgentEvent.LoopTerminated -> {
          finalOutput = event.finalResponse
        }
        is AgentEvent.Error -> {
          isSuccess = false
          finalOutput = event.errorMessage
        }
        else -> {}
      }
    }
    return AgentResponse(output = finalOutput, isSuccessful = isSuccess)
  }

  override fun interrupt() {
    val curModel = activeSession.get()?.model ?: return
    Log.d(TAG, "Interrupting session for model: ${curModel.name}")
    interrupted.set(true)
    stopGen.incrementAndGet()
    // TODO: we need to reset the conversation in executeStream if user sends a new request.
    curModel.runtimeHelper.stopResponse(curModel)
  }

  override suspend fun resetSession(config: AgentRuntimeConfig) {
    val systemInstruction = Contents.of(config.systemInstruction ?: "")
    lastConfig.set(config)
    val executionContext =
      ToolExecutionContext(taskId = config.taskId, actionChannel = config.actionChannel)

    interrupted.set(false)
    stopGen.incrementAndGet()
    sessionGen.incrementAndGet()
    synchronized(llamaHistory) {
      llamaHistory.clear()
      // A reloaded past chat (or the chat so far after a Stop): the model remembers it too.
      for (msg in config.initialMessages) {
        val text = msg.contents.toString().trim()
        if (text.isEmpty()) continue
        llamaHistory.add(
          AgentMsg(if (msg.role == com.google.ai.edge.litertlm.Role.USER) "user" else "assistant", text)
        )
      }
      while (llamaHistory.size > MAX_LLAMA_HISTORY) llamaHistory.removeAt(0)
    }
    activeSession.set(
      ActiveSession(
        model = config.model,
        toolExecutionContext = executionContext,
        systemInstruction = config.systemInstruction,
      )
    )

    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = executionContext,
    )
    val curModel = config.model
    val maxRetries = 5
    val retryDelayMs = 200L

    for (attempt in 1..maxRetries) {
      try {
        curModel.runtimeHelper.resetConversation(
          model = curModel,
          supportImage = config.supportImage,
          supportAudio = config.supportAudio,
          systemInstruction = systemInstruction,
          tools = toolsProvider.getLiteRtToolProviders(),
          enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
          initialMessages = config.initialMessages,
        )
        return
      } catch (e: Exception) {
        if (attempt == maxRetries) {
          Log.e(
            TAG,
            "Failed to reset session for model: ${curModel.name} after $maxRetries attempts.",
            e,
          )
          return
        }
        Log.d(
          TAG,
          "Failed to reset session for model: ${curModel.name} (attempt $attempt/$maxRetries). Retrying...",
          e,
        )
        delay(retryDelayMs)
      }
    }
  }

  override suspend fun restartConversation(fallback: AgentRuntimeConfig) {
    val last = lastConfig.get()
    if (last != null && last.model.name == fallback.model.name) {
      resetSession(last.copy(model = fallback.model, initialMessages = fallback.initialMessages))
    } else {
      resetSession(fallback)
    }
  }

  override fun cleanUpModel(model: Model, onDone: () -> Unit) {
    val session = activeSession.get()
    if (session != null && session.model.name == model.name) {
      cleanUp(onDone)
    } else {
      model.runtimeHelper.cleanUp(model = model, onDone = onDone)
    }
  }

  override fun cleanUp(onDone: () -> Unit) {
    val session = activeSession.getAndSet(null)
    if (session == null) {
      onDone()
      return
    }
    session.model.runtimeHelper.cleanUp(model = session.model, onDone = onDone)
  }

  /**
   * ReAct loop for local llama.cpp models. Builds a full ChatML conversation (system prompt + tool
   * definitions, then the running message list), runs one generation per step, parses any tool call,
   * executes it through the shared [ToolDefinition]s and feeds the result back until the model
   * answers without calling a tool.
   */
  private suspend fun runLlamaCppAgentLoop(
    model: Model,
    request: AgentRequest,
    systemInstruction: String?,
    enableThinking: Boolean,
    argoraModeId: String? = null,
    emit: (AgentEvent) -> Unit,
  ): String? {
    // Triggers the ViewModel's "first token" handling so the loading state clears even when the
    // model's first turn is a silent tool call.
    val myStop = stopGen.get()
    val mySession = sessionGen.get()
    val stopped = { stopGen.get() != myStop }

    val specs = LlamaCppToolBridge.availableSpecs(toolsProvider)
    // [ALTREX] Without tools, an Argora model gets the exact system prompt it was trained with (plus
    // the suffix of the mode in use). With tools it keeps the agent prompt and its skill list: Argora
    // is trained on that exact format (argora docs/FORMAT_OUTILS.md § 4.5).
    val manifest = model.argoraManifest
    val argoraMode =
      manifest?.let { m ->
        argoraModeId?.let { m.modes[it] } ?: if (enableThinking) m.thinkingMode else m.defaultModeSettings
      }
    val thinking = argoraMode?.thinking ?: enableThinking
    val instructions =
      manifest?.takeIf { specs.isEmpty() }?.systemPromptFor(argoraMode)
        ?: systemInstruction?.takeIf { it.isNotBlank() }
        ?: org.nehuatl.llamacpp.LlamaHelper.getDefaultSystemPrompt()
    // No tools in this screen: a plain system prompt and a single turn.
    val system = if (specs.isEmpty()) instructions else ToolCalling.systemPrompt(specs, instructions)
    val messages = ArrayList<AgentMsg>()
    messages.addAll(fitHistory(model, system, request.query))
    messages.add(AgentMsg("user", request.query))
    val requestIndex = messages.lastIndex
    val shown = StringBuilder()

    // [ALTREX] Guards for small models: repeated calls, oversized results, a context that fills up
    // during the loop, an empty answer, and too many steps.
    val contextTokens = contextLength(model)
    val repeatGuard = RepeatCallGuard()
    val resultBudget = ToolResultBudget.forContext(contextTokens)
    // About 3 characters per token; half of the context stays free for the answer.
    val loopBudgetChars = (contextTokens * 3 / 2 - system.length).coerceAtLeast(2_000)
    var forceAnswer = false
    var nudgedEmpty = false
    var step = 0

    while (true) {
      if (stopped()) {
        return null
      }
      if (step >= MAX_LLAMA_AGENT_STEPS && !forceAnswer) {
        forceAnswer = true
        addNudge(messages, FINAL_ANSWER_NUDGE)
      }
      step++
      val prompt = ToolCalling.buildChatRequest(system, AgentContextFitter.fit(messages, requestIndex, loopBudgetChars))
      // [ALTREX] Stream the answer as it is written; tool calls stay hidden, reasoning goes to the
      // thinking area.
      val splitter = StreamSplitter()
      if (shown.isNotEmpty()) {
        emit(AgentEvent.StreamToken(token = "\n\n", done = false))
      }
      val response =
        runLlamaCppTurn(
          model = model,
          prompt = prompt,
          enableThinking = thinking,
          argoraModeId = argoraMode?.id,
        ) { chunk ->
          // The native callback thread and this one (after a Stop) can both get here.
          synchronized(splitter) { emitSplit(splitter.feed(chunk), shown, emit) }
        }
      synchronized(splitter) { emitSplit(splitter.finish(), shown, emit) }

      // Tool calls written inside the reasoning are not real calls.
      val calls =
        if (specs.isEmpty() || forceAnswer) emptyList() else ToolCalling.parseToolCalls(stripThinking(response))

      if (calls.isEmpty() || stopped()) {
        // Nothing for the user (only reasoning, or nothing at all): ask once for the answer.
        if (!stopped() && !forceAnswer && !nudgedEmpty && ToolCalling.displayText(response).isBlank()) {
          nudgedEmpty = true
          messages.add(AgentMsg("assistant", stripThinking(response).trim().ifEmpty { "…" }))
          addNudge(messages, EMPTY_ANSWER_NUDGE)
          continue
        }
        if (!stopped() && shown.isBlank()) {
          val fallback = "Je n'ai pas réussi à formuler de réponse. Reformule ta demande ou réessaie."
          shown.append(fallback)
          emit(AgentEvent.StreamToken(token = fallback, done = false))
        }
        emit(AgentEvent.StreamToken(token = "", done = true))
        if (sessionGen.get() == mySession) rememberTurn(request.query, shown.toString().trim())
        // Stopped by the user: the loop ends here, callers must not treat it as a finished answer.
        return if (stopped()) null else shown.toString().trim()
      }

      val results = ArrayList<String>()
      var stuck = false
      for (call in calls.take(4)) {
        val verdict = repeatGuard.check(call)
        results.add(
          when (verdict) {
            RepeatCallGuard.Verdict.STOP -> {
              stuck = true
              toolNote("error", RepeatCallGuard.REFUSAL)
            }
            RepeatCallGuard.Verdict.REFUSE -> toolNote("error", RepeatCallGuard.REFUSAL)
            else -> {
              val raw =
                if (call.name.lowercase().replace("_", "") == "readtooloutput") {
                  resultBudget.read(call.arguments.optString("id"), call.arguments.optInt("offset", 0))
                } else {
                  resultBudget.fit(LlamaCppToolBridge.dispatch(call = call, toolsProvider = toolsProvider))
                }
              val result = frameToolResult(call.name, raw)
              if (verdict == RepeatCallGuard.Verdict.RUN_WITH_WARNING) {
                result + "\n" + toolNote("warning", RepeatCallGuard.WARNING)
              } else {
                result
              }
            }
          }
        )
      }
      messages.add(AgentMsg("assistant", stripThinking(response).trim()))
      messages.add(ToolCalling.toolResponseMessage(results))
      if (stuck) {
        forceAnswer = true
        addNudge(messages, FINAL_ANSWER_NUDGE)
      }
    }
  }

  /**
   * [ALTREX] Results that may carry outside text (web, MCP server, script) are framed as data, so
   * that an injected "ignore your instructions…" is not obeyed. A loaded skill and the app's own
   * intent results are trusted: a skill's text is meant to be followed.
   */
  private fun frameToolResult(toolName: String, result: String): String =
    when (toolName.lowercase().replace("_", "")) {
      "loadskill", "runintent" -> result
      else -> com.google.ai.edge.gallery.security.UntrustedContent.frame("résultat de l'outil $toolName", result)
    }

  /** Adds an instruction to the last user message (tool results are user messages). */
  private fun addNudge(messages: MutableList<AgentMsg>, nudge: String) {
    val last = messages.lastOrNull()
    if (last != null && last.role == "user") {
      messages[messages.lastIndex] = last.copy(content = last.content + "\n\n" + nudge)
    } else {
      messages.add(AgentMsg("user", nudge))
    }
  }

  private fun toolNote(key: String, message: String): String = org.json.JSONObject().put(key, message).toString()

  private fun contextLength(model: Model): Int =
    (model.instance as? com.google.ai.edge.gallery.runtime.LlamaCppModelInstance)?.contextLength
      ?: model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)

  // The chat screen handles a token carrying both thinking and text as thinking only: send the
  // two parts as separate events.
  private fun emitSplit(out: StreamSplitter.Out, shown: StringBuilder, emit: (AgentEvent) -> Unit) {
    if (out.thinking.isNotEmpty()) {
      emit(AgentEvent.StreamToken(token = "", thinking = out.thinking, done = false))
    }
    if (out.text.isNotEmpty()) {
      shown.append(out.text)
      emit(AgentEvent.StreamToken(token = out.text, done = false))
    }
  }

  /** Drops reasoning blocks from a raw answer (they must not be fed back to the model). */
  private fun stripThinking(text: String): String = ToolCalling.stripThinking(text)

  /** Keeps the most recent turns that fit in about half of the context window. */
  private fun fitHistory(model: Model, system: String, query: String): List<AgentMsg> {
    // The context really used by the engine (it can be smaller than the configured one).
    val maxTokens =
      (model.instance as? com.google.ai.edge.gallery.runtime.LlamaCppModelInstance)?.contextLength
        ?: model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    // About 3 characters per token; half of the context stays free for the answer.
    var budget = maxTokens * 3 / 2 - system.length - query.length
    val kept = ArrayList<AgentMsg>()
    synchronized(llamaHistory) {
      for (msg in llamaHistory.asReversed()) {
        budget -= msg.content.length + 16
        if (budget < 0) break
        kept.add(0, msg)
      }
    }
    // Start on a user turn.
    while (kept.isNotEmpty() && kept.first().role != "user") kept.removeAt(0)
    return kept
  }

  private fun rememberTurn(query: String, answer: String) {
    if (answer.isEmpty()) return
    synchronized(llamaHistory) {
      llamaHistory.add(AgentMsg("user", query))
      llamaHistory.add(AgentMsg("assistant", answer))
      while (llamaHistory.size > MAX_LLAMA_HISTORY) llamaHistory.removeAt(0)
    }
  }

  /** Runs a single generation on a local llama.cpp model and suspends until it completes. */
  private suspend fun runLlamaCppTurn(
    model: Model,
    prompt: String,
    enableThinking: Boolean = false,
    argoraModeId: String? = null,
    onChunk: (String) -> Unit = {},
  ): String {
    val buffer = StringBuilder()
    val completion = CompletableDeferred<String>()
    model.runtimeHelper.runInference(
      model = model,
      input = prompt,
      extraContext =
        buildMap {
            if (enableThinking) put("enable_thinking", "true")
            if (argoraModeId != null) put(ARGORA_MODE_KEY, argoraModeId)
          }
          .ifEmpty { null },
      resultListener = { partialResult, done, _ ->
        if (partialResult.isNotEmpty() && !completion.isCompleted) {
          buffer.append(partialResult)
          onChunk(partialResult)
        }
        if (done && !completion.isCompleted) {
          completion.complete(buffer.toString())
        }
      },
      cleanUpListener = {
        if (!completion.isCompleted) {
          completion.complete(buffer.toString())
        }
      },
      onError = { errorMsg ->
        if (!completion.isCompleted) {
          completion.completeExceptionally(RuntimeException(errorMsg))
        }
      },
    )
    return try {
      completion.await()
    } catch (e: CancellationException) {
      // The underlying generation runs in its own scope, so stop it explicitly on cancellation.
      model.runtimeHelper.stopResponse(model)
      throw e
    }
  }
}
