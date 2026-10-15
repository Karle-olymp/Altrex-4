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
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
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
    val modelPath = model.getPath(context = context)

    // Memory guard. A big model (for example an 8B, about 5 GB) next to Android and the other apps
    // can get the process killed without any error message, so check first and shrink the context.
    val fileBytes = java.io.File(modelPath).length()
    val memInfo = android.app.ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
      .getMemoryInfo(memInfo)
    val megabyte = 1024L * 1024L
    // The KV cache grows with the context: cap it for big models.
    val contextLength = if (fileBytes > 4_000L * megabyte) minOf(maxTokens, 4096) else maxTokens
    Log.i(
      TAG,
      "Loading '${model.name}': file=${fileBytes / megabyte} MB, available RAM=${memInfo.availMem / megabyte} MB, " +
        "context=$contextLength (asked $maxTokens)",
    )
    if (fileBytes > 0 && memInfo.availMem < fileBytes + 400L * megabyte) {
      val msg =
        "Mémoire insuffisante pour charger ce modèle : environ ${(fileBytes + 400L * megabyte) / megabyte} Mo " +
          "nécessaires, ${memInfo.availMem / megabyte} Mo disponibles. Ferme d'autres applications " +
          "ou choisis un modèle plus petit."
      model.markInitializationFailed(msg)
      onDone(msg)
      return
    }

    var hasCompletedInit = false

    scope.launch {
      sharedFlow.collect { event ->
        when (event) {
          is LlamaHelper.LLMEvent.Loaded -> {
            if (!hasCompletedInit) {
              hasCompletedInit = true
              model.instance =
                LlamaCppModelInstance(llamaHelper = llamaHelper, sharedFlow = sharedFlow)
              model.markInitialized()
              onDone("")
            }
          }
          is LlamaHelper.LLMEvent.Error -> {
            if (!hasCompletedInit) {
              hasCompletedInit = true
              val errorMsg = event.message
              model.markInitializationFailed(errorMsg)
              onDone(errorMsg)
            }
          }
          else -> {}
        }
      }
    }

    try {
      llamaHelper.load(
        path = modelPath,
        contextLength = contextLength,
        mmprojPath = null,
      ) { contextId ->
        Log.d(TAG, "LlamaHelper loaded callback with contextId=$contextId")
        Log.i(TAG, "Modèle chargé avec contexte: $contextLength tokens")

        if (!hasCompletedInit) {
          hasCompletedInit = true
          model.instance =
            LlamaCppModelInstance(llamaHelper = llamaHelper, sharedFlow = sharedFlow)
          model.markInitialized()
          onDone("")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load llama model: ${e.message}", e)
      val errorMsg = e.message ?: "Unknown initialization error"
      model.markInitializationFailed(errorMsg)
      onDone(errorMsg)
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
    if (instance != null) {
      try {
        instance.inferenceJob?.cancel()
        instance.llamaHelper.stopPrediction()
        instance.llamaHelper.release()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to release llama instance: ${e.message}", e)
      }
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.resetInitialization()
    onDone()
    Log.d(TAG, "Llama clean up done for model '${model.name}'")
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

    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    // [ALTREX] Direct callbacks (no shared flow: events sent before the collector subscribed were
    // lost, and "done" could arrive twice). "done" is reported exactly once.
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    val finish: (String?) -> Unit = { error ->
      if (finished.compareAndSet(false, true)) {
        if (error == null) resultListener("", true, null) else onError(error)
      }
    }
    instance.finishCurrent = { finish(null) }

    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val sampling =
      LlamaHelper.Sampling(
        temperature =
          model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE),
        topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK),
        topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP),
        // Leave room for the prompt: at most half the context, between 1024 and 8192 tokens.
        maxOutputTokens = (maxTokens / 2).coerceIn(1024, 8192),
      )

    try {
      instance.llamaHelper.predict(
        prompt = input,
        imagePath = null,
        partialCompletion = true,
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
