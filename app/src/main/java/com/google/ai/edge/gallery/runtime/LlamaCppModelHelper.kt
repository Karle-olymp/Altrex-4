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

package com.google.ai.edge.gallery.runtime

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.compute.AutoParamsCalculator
import com.google.ai.edge.gallery.compute.AutoSelector
import com.google.ai.edge.gallery.compute.ComputeChoice
import com.google.ai.edge.gallery.compute.ComputeRuntime
import com.google.ai.edge.gallery.data.ARGORA_MODE_KEY
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_REPEAT_PENALTY
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.nehuatl.llamacpp.LlamaHelper

private const val TAG = "LlamaCppModelHelper"

data class LlamaCppModelInstance(
  val llamaHelper: LlamaHelper,
  val sharedFlow: MutableSharedFlow<LlamaHelper.LLMEvent>,
  var inferenceJob: Job? = null,
  /** Context size really used by the engine (may be smaller than the configured one). */
  val contextLength: Int = 4096,
  /** Ends the request in flight (reports "done" once). */
  @Volatile var finishCurrent: (() -> Unit)? = null,
)

object LlamaCppModelHelper : LlmModelHelper {
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  override fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    if (model.instance != null) {
      Log.d(TAG, "Model '${model.name}' already initialized in LlamaCppModelHelper. Skipping.")
      model.markInitialized()
      onDone("")
      return
    }

    model.markInitializationStarted()
    val scope = coroutineScope ?: CoroutineScope(Dispatchers.IO)
    val sharedFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 64)
    val llamaHelper =
      LlamaHelper(
        contentResolver = context.contentResolver,
        scope = scope,
        sharedFlow = sharedFlow,
      )

    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    // [ALTREX] The native build is chosen when the library loads: remember the choice for the next
    // app start.
    context
      .getSharedPreferences(org.nehuatl.llamacpp.NativeCpuOptions.PREFS, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(
        org.nehuatl.llamacpp.NativeCpuOptions.KEY_FORCE_BASIC_CPU,
        model.getBooleanConfigValue(key = ConfigKeys.BASIC_CPU, defaultValue = false),
      )
      .apply()
    val modelPath = model.getPath(context = context)

    // Memory guard. A big model next to Android and the other apps can get the process killed
    // without any error message. [ALTREX] Context and threads are chosen from the free memory, the
    // model size and its maximal context (AutoParamsCalculator) unless the user set them; a manual
    // context is still halved when it does not fit.
    val megabyte = 1024L * 1024L
    val auto = ComputeRuntime.autoParams(context, model)
    val mem = ComputeRuntime.memorySnapshot(context)
    val fileBytes = java.io.File(modelPath).length()
    val autoContext =
      model.getBooleanConfigValue(key = ConfigKeys.CONTEXT_AUTO, defaultValue = false)
    var contextLength: Int
    if (autoContext) {
      contextLength = auto.contextTokens
    } else {
      contextLength = if (fileBytes > 4_000L * megabyte) minOf(maxTokens, 4096) else maxTokens
      while (
        contextLength > 2048 &&
          fileBytes > 0 &&
          mem.availBytes < AutoParamsCalculator.neededBytes(fileBytes, contextLength)
      ) {
        contextLength /= 2
      }
    }
    val needed = AutoParamsCalculator.neededBytes(fileBytes, contextLength)
    val threadsSetting = model.getIntConfigValue(key = ConfigKeys.NUM_THREADS, defaultValue = 0)
    Log.i(
      TAG,
      "Loading '${model.name}': file=${fileBytes / megabyte} MB, available RAM=${mem.availBytes / megabyte} MB, " +
        "context=$contextLength (auto=$autoContext, asked $maxTokens), threads=${if (threadsSetting > 0) threadsSetting else "auto " + auto.threads}",
    )
    if (fileBytes > 0 && mem.availBytes < needed) {
      val msg =
        "Mémoire insuffisante pour charger ce modèle : environ ${needed / megabyte} Mo " +
          "nécessaires, ${mem.availBytes / megabyte} Mo disponibles. Ferme d'autres applications " +
          "ou choisis un modèle plus petit."
      model.markInitializationFailed(msg)
      onDone(msg)
      return
    }
    // llama.cpp has the CPU backend only in this build: Auto and CPU both run there.
    val computeLine =
      if (ComputeRuntime.choice(model) == ComputeChoice.AUTO) {
        AutoSelector.explain("CPU", null, "seul calcul de llama.cpp ici")
      } else {
        "Calcul : CPU"
      }
    val paramsLine =
      "contexte $contextLength" + (if (autoContext) " (auto)" else "") + " · " +
        (if (threadsSetting > 0) "$threadsSetting threads" else "${auto.threads} threads (auto)")

    // [ALTREX] One completion path, reported exactly once (the shared-flow collector could miss
    // the events and both paths could fire).
    val initDone = java.util.concurrent.atomic.AtomicBoolean(false)
    val effectiveContext = contextLength
    try {
      llamaHelper.load(
        path = modelPath,
        contextLength = contextLength,
        mmprojPath = null,
        // [ALTREX] MTP speculative decoding when the user turned on speculative decoding.
        useMtp =
          model.getBooleanConfigValue(key = ConfigKeys.ENABLE_SPECULATIVE_DECODING, defaultValue = false),
        nThreads = model.getIntConfigValue(key = ConfigKeys.NUM_THREADS, defaultValue = 0),
        kvCacheType = model.getStringConfigValue(key = ConfigKeys.KV_CACHE_TYPE, defaultValue = "f16"),
        flashAttn =
          when (model.getStringConfigValue(key = ConfigKeys.FLASH_ATTENTION, defaultValue = "auto")) {
            "off" -> 0
            "on" -> 1
            else -> -1
          },
        onError = { errorMsg ->
          if (initDone.compareAndSet(false, true)) {
            Log.e(TAG, "Failed to load '${model.name}': $errorMsg")
            model.markInitializationFailed(errorMsg)
            onDone(errorMsg)
          }
        },
      ) { contextId ->
        Log.i(
          TAG,
          "Model loaded, context id=$contextId, $effectiveContext tokens, native build " +
            org.nehuatl.llamacpp.LlamaContext.loadedVariant,
        )
        if (initDone.compareAndSet(false, true)) {
          model.instance =
            LlamaCppModelInstance(
              llamaHelper = llamaHelper,
              sharedFlow = sharedFlow,
              contextLength = effectiveContext,
            )
          ComputeRuntime.setLoaded(model.name, ComputeChoice.CPU, computeLine, paramsLine)
          model.markInitialized()
          onDone("")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load llama model: ${e.message}", e)
      if (initDone.compareAndSet(false, true)) {
        val errorMsg = e.message ?: "Unknown initialization error"
        model.markInitializationFailed(errorMsg)
        onDone(errorMsg)
      }
    }
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    Log.d(TAG, "Resetting conversation for model '${model.name}'")
    stopResponse(model)
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    val instance = model.instance as? LlamaCppModelInstance
    val onCleanUp = cleanUpListeners.remove(model.name)
    val finishCleanUp = {
      onCleanUp?.invoke()
      model.resetInitialization()
      onDone()
      Log.d(TAG, "Llama clean up done for model '${model.name}'")
    }
    if (instance == null) {
      finishCleanUp()
      return
    }
    try {
      instance.finishCurrent?.invoke()
      // [ALTREX] Frees the native context only once the running generation has returned.
      instance.llamaHelper.release { finishCleanUp() }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to release llama instance: ${e.message}", e)
      finishCleanUp()
    }
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? LlamaCppModelInstance ?: return
    try {
      instance.llamaHelper.stopPrediction()
      // The native call returns once the flag is seen; report the end now so the UI never waits.
      instance.finishCurrent?.invoke()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to stop llama prediction", e)
    }
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? LlamaCppModelInstance
    if (instance == null) {
      onError("LlamaCppModelInstance is not initialized.")
      return
    }

    cleanUpListeners[model.name] = cleanUpListener

    // [ALTREX] Direct callbacks (no shared flow: events sent before the collector subscribed were
    // lost, and "done" could arrive twice). "done" is reported exactly once.
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    val finish: (String?) -> Unit = { error ->
      if (finished.compareAndSet(false, true)) {
        if (error == null) resultListener("", true, null) else onError(error)
      }
    }
    instance.finishCurrent = { finish(null) }

    val maxTokens = instance.contextLength
    // [ALTREX] A task or a mode of an Argora model brings its own settings (e.g. "analyse": low
    // temperature for stable file audits).
    val argoraMode = extraContext?.get(ARGORA_MODE_KEY)?.let { model.argoraManifest?.modes?.get(it) }
    val baseSampling =
      LlamaHelper.Sampling(
        temperature =
          model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE),
        topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK),
        topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP),
        repeatPenalty =
          model.getFloatConfigValue(
            key = ConfigKeys.REPEAT_PENALTY,
            defaultValue = DEFAULT_REPEAT_PENALTY,
          ),
        // Leave room for the prompt: at most half the context, between 1024 and 8192 tokens.
        maxOutputTokens = (maxTokens / 2).coerceIn(1024, 8192),
        thinkingBudget = model.getIntConfigValue(key = ConfigKeys.THINKING_BUDGET, defaultValue = 0),
      )
    val sampling =
      argoraMode?.let {
        baseSampling.copy(
          temperature = it.temperature,
          topK = it.topK,
          topP = it.topP,
          repeatPenalty = it.repeatPenalty,
          thinkingBudget = it.thinkingBudget,
        )
      } ?: baseSampling

    try {
      instance.llamaHelper.predict(
        prompt = input,
        imagePath = null,
        partialCompletion = true,
        // [ALTREX] Qwen3/3.5 enable_thinking: the reasoning streams between think tags and is
        // shown in the thinking area.
        enableThinking = extraContext?.get("enable_thinking") == "true",
        sampling = sampling,
        onToken = { token -> if (!finished.get()) resultListener(token, false, null) },
        onDone = { finish(null) },
        onError = { message ->
          Log.e(TAG, "Error during inference: $message")
          finish(message)
        },
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start predict: ${e.message}", e)
      finish(e.message ?: "Failed to start inference")
    }
  }
}
