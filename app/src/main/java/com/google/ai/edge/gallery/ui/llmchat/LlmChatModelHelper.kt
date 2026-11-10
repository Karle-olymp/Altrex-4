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
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.compute.AutoMemory
import com.google.ai.edge.gallery.compute.AutoPlan
import com.google.ai.edge.gallery.compute.AutoSelector
import com.google.ai.edge.gallery.compute.BenchSample
import com.google.ai.edge.gallery.compute.BenchmarkGate
import com.google.ai.edge.gallery.compute.ComputeChoice
import com.google.ai.edge.gallery.compute.ComputeOptions
import com.google.ai.edge.gallery.compute.ComputeRuntime
import com.google.ai.edge.gallery.compute.ModelFingerprint
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.DEFAULT_VISION_ACCELERATOR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.THOUGHT_CHANNEL
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGLlmChatModelHelper"

data class LlmModelInstance(val engine: Engine, var conversation: Conversation)

object LlmChatModelHelper : LlmModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
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
      Log.d(TAG, "Model '${model.name}' already initialized in LlmChatModelHelper. Skipping.")
      model.markInitialized()
      onDone("")
      return
    }
    model.markInitializationStarted()
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val visionAccelerator =
      model.getStringConfigValue(
        key = ConfigKeys.VISION_ACCELERATOR,
        defaultValue = DEFAULT_VISION_ACCELERATOR.label,
      )
    val visionBackend =
      when (visionAccelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.GPU()
      }
    val shouldEnableImage = supportImage
    val shouldEnableAudio = supportAudio
    val modelPath = model.getPath(context = context)

    // [ALTREX] Compute: Auto (measured once per model file, app and phone, see AutoSelector) or
    // the user's choice, then the CPU when the engine refuses to start. An engine start that killed
    // the app last time (a GPU or NPU driver crash) is recorded and not retried: no crash loop.
    val candidates =
      ComputeOptions.backendsFor(RuntimeType.LITERT_LM, model.accelerators, supportedBackends(modelPath))
    val choice = ComputeOptions.resolve(accelerator, candidates)
    val memory = ComputeRuntime.memory(context)
    val fp = ComputeRuntime.fingerprint(context, model)
    val crashed = memory.takeCrashedAttempt(fp)
    if (crashed != null) {
      showToast(
        context,
        "${ComputeRuntime.title(crashed)} s'est arrêté brutalement au dernier chargement de ce " +
          "modèle : il ne sera plus essayé automatiquement.",
      )
    }
    val failed = memory.failedOf(fp)
    val mayMeasure = ComputeRuntime.takeBenchmarkPermit(model.name)
    val cpuOk = ComputeChoice.CPU in candidates
    val primary: ComputeChoice
    var primaryTps: Double? = null
    var autoReason = ""
    if (choice == ComputeChoice.AUTO) {
      val refusal =
        BenchmarkGate.refusal(
          ComputeRuntime.deviceState(context),
          conversationActive = false,
          explicitLoad = mayMeasure,
        )
      when (val plan = AutoSelector.plan(candidates, memory.recall(fp), failed, refusal)) {
        is AutoPlan.Use -> {
          primary = plan.backend
          primaryTps = plan.tokensPerSecond
          autoReason = plan.reason
        }
        is AutoPlan.Measure -> {
          val samples =
            plan.backends.map { b ->
              ComputeRuntime.setPhase(model.name, "Mesure du calcul ${ComputeRuntime.title(b)}…")
              BenchSample(b, measure(context, modelPath, b, fp, memory))
            }
          Log.i(TAG, "Auto measurement for '${model.name}': $samples")
          for (sample in samples) if (sample.tokensPerSecond == null) memory.markFailed(fp, sample.backend)
          val pick = AutoSelector.pickFastest(samples)
          primary = pick ?: if (cpuOk) ComputeChoice.CPU else candidates.first()
          primaryTps = samples.firstOrNull { it.backend == primary }?.tokensPerSecond
          if (pick != null) memory.remember(fp, pick, primaryTps, System.currentTimeMillis())
          autoReason = "mesuré"
        }
      }
    } else {
      // A backend that failed for this file runs on the CPU until the user picks it again.
      primary = if (choice in failed && cpuOk) ComputeChoice.CPU else choice
    }
    val ordered = (listOf(primary) + if (cpuOk) listOf(ComputeChoice.CPU) else emptyList()).distinct()
    ComputeRuntime.setPhase(model.name, "Démarrage sur ${ComputeRuntime.title(primary)}…")
    val cpuThreads = ComputeRuntime.autoParams(context, model).threads

    // Check if the model file supports speculative decoding.
    var supportsSpeculativeDecoding = false
    // Check if the model file supports speculative decoding.
    try {
      // [ALTREX] LiteRT-LM 0.18: Capabilities became ModelInfo + LlmCapability.
      com.google.ai.edge.litertlm.ModelInfo.from(modelPath).use {
        supportsSpeculativeDecoding =
          (it as? com.google.ai.edge.litertlm.LlmCapability)?.hasSpeculativeDecodingSupport() == true
      }
    } catch (e: Exception) {
      // Ignore exceptions and assume not supported.
    }

    var firstError: String? = null
    for (attempt in ordered) {
      val preferredBackend = backendFor(context, attempt, cpuThreads)
      // A fallback away from the NPU also moves the image encoder off the NPU.
      val visionForAttempt =
        if (attempt != ComputeChoice.NPU && visionBackend is Backend.NPU) Backend.GPU() else visionBackend
      Log.d(TAG, "Trying backend $attempt (asked: $accelerator)")
      val engineConfig =
        EngineConfig(
          modelPath = modelPath,
          backend = preferredBackend,
          visionBackend = if (shouldEnableImage) visionForAttempt else null, // must be GPU for Gemma 3n
          audioBackend = if (shouldEnableAudio) Backend.CPU() else null, // must be CPU for Gemma 3n
          maxNumTokens = maxTokens,
          cacheDir =
            if (modelPath.startsWith("/data/local/tmp"))
              context.getExternalFilesDir(null)?.absolutePath
            else null,
        )

      // Create an instance of LiteRT LM engine and conversation.
      var engine: Engine? = null
      try {
        var speculativeDecoding = false
        // Check if the model supports speculative decoding for the given task type and if the
        // speculative decoding is enabled in the settings.
        if (
          supportsSpeculativeDecoding &&
            model.capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING]?.contains(taskId) ==
              true
        ) {
          speculativeDecoding =
            model.getBooleanConfigValue(
              key = ConfigKeys.ENABLE_SPECULATIVE_DECODING,
              defaultValue = false,
            )
        }
        ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
        Log.d(TAG, "Speculative decoding enabled: $speculativeDecoding")
        val newEngine = Engine(engineConfig)
        engine = newEngine
        memory.beginAttempt(fp, attempt)
        newEngine.initialize()
        ExperimentalFlags.enableSpeculativeDecoding = false

        ExperimentalFlags.enableConversationConstrainedDecoding =
          enableConversationConstrainedDecoding
        val conversation =
          newEngine.createConversation(
            ConversationConfig(
              samplerConfig =
                if (preferredBackend is Backend.NPU) {
                  null
                } else {
                  SamplerConfig(
                    topK = topK,
                    topP = topP.toDouble(),
                    temperature = temperature.toDouble(),
                  )
                },
              systemInstruction = systemInstruction,
              tools = tools,
            )
          )
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        model.instance = LlmModelInstance(engine = newEngine, conversation = conversation)
        memory.endAttempt(fp)
      } catch (e: Exception) {
        memory.endAttempt(fp)
        ExperimentalFlags.enableSpeculativeDecoding = false
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        try {
          engine?.close()
        } catch (closeError: Exception) {
          Log.w(TAG, "Failed to close the engine after a failed start: ${closeError.message}")
        }
        val errorMsg = cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error")
        Log.w(TAG, "Backend $attempt failed to start: $errorMsg")
        if (firstError == null) firstError = errorMsg
        if (attempt != ComputeChoice.CPU) memory.markFailed(fp, attempt)
        continue
      }

      val fellBack = attempt != primary
      if (fellBack) {
        Log.w(TAG, "Backend $primary unavailable for '${model.name}', using $attempt")
        showToast(
          context,
          "${ComputeRuntime.title(primary)} indisponible pour ce modèle : ${ComputeRuntime.title(attempt)} utilisé à la place.",
        )
        if (choice == ComputeChoice.AUTO) memory.remember(fp, attempt, null, System.currentTimeMillis())
      }
      val used = ComputeRuntime.title(attempt)
      val line =
        when {
          choice == ComputeChoice.AUTO && fellBack ->
            "Auto : $used (repli : ${ComputeRuntime.title(primary)} a échoué)"
          choice == ComputeChoice.AUTO -> AutoSelector.explain(used, primaryTps, autoReason)
          attempt != choice -> "Calcul : $used (${ComputeRuntime.title(choice)} a échoué ; rechoisis-le pour réessayer)"
          else -> "Calcul : $used"
        }
      val params =
        "contexte $maxTokens" + if (attempt == ComputeChoice.CPU) " · $cpuThreads threads (auto)" else ""
      ComputeRuntime.setLoaded(model.name, attempt, line, params)
      model.markInitialized()
      onDone("")
      return
    }

    val errorMsg = firstError ?: "Unknown error"
    model.markInitializationFailed(errorMsg)
    onDone(errorMsg)
  }

  private fun backendFor(context: Context, choice: ComputeChoice, cpuThreads: Int? = null): Backend =
    when (choice) {
      ComputeChoice.GPU -> Backend.GPU()
      ComputeChoice.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
      else -> Backend.CPU(cpuThreads)
    }

  /** Backends the model file declares (LiteRT-LM metadata), null when unknown. */
  private fun supportedBackends(modelPath: String): Set<ComputeChoice>? =
    try {
      com.google.ai.edge.litertlm.ModelInfo.from(modelPath).use { info ->
        info
          .supportedBackends(com.google.ai.edge.litertlm.Modality.TEXT)
          .mapNotNull {
            when (it) {
              com.google.ai.edge.litertlm.BackendType.CPU -> ComputeChoice.CPU
              com.google.ai.edge.litertlm.BackendType.GPU -> ComputeChoice.GPU
              com.google.ai.edge.litertlm.BackendType.NPU -> ComputeChoice.NPU
              else -> null
            }
          }
          .toSet()
          .ifEmpty { null }
      }
    } catch (e: Throwable) {
      null
    }

  /**
   * [ALTREX] Micro-benchmark of one backend: LiteRT-LM's benchmark (a fixed synthetic prompt of 64
   * tokens, then 32 generated tokens). Decode speed in tokens per second, null on failure. The
   * crash guard marks the attempt: if a driver kills the app here, Auto never tries it again.
   */
  @OptIn(ExperimentalApi::class)
  private fun measure(
    context: Context,
    modelPath: String,
    backend: ComputeChoice,
    fp: ModelFingerprint,
    memory: AutoMemory,
  ): Double? {
    memory.beginAttempt(fp, backend)
    return try {
      val dir = java.io.File(context.cacheDir, "litert_bench").apply { mkdirs() }
      val info =
        com.google.ai.edge.litertlm.benchmark(
          modelPath = modelPath,
          backend = backendFor(context, backend),
          prefillTokens = 64,
          decodeTokens = 32,
          cacheDir = dir.absolutePath,
        )
      info.lastDecodeTokensPerSecond.takeIf { it > 0.0 }
    } catch (e: Throwable) {
      Log.w(TAG, "Measurement on $backend failed: ${e.message}")
      null
    } finally {
      memory.endAttempt(fp)
    }
  }

  private fun showToast(context: Context, message: String) {
    val appContext = context.applicationContext
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    try {
      Log.d(TAG, "Resetting conversation for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val engine = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val shouldEnableImage = supportImage
      val shouldEnableAudio = supportAudio
      Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")

      // [ALTREX] The backend really in use (Auto or a fallback may differ from the setting).
      val onNpu = ComputeRuntime.status.value[model.name]?.backend == ComputeChoice.NPU
      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val newConversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (onNpu) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
            initialMessages = initialMessages,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.conversation = newConversation

      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset conversation", e)
    }
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as LlmModelInstance

    try {
      instance.conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }

    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the engine: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.resetInitialization()

    onDone()
    Log.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? LlmModelInstance ?: return
    try {
      instance.conversation.cancelProcess()
    } catch (e: IllegalStateException) {
      Log.w(TAG, "Conversation is not alive, cannot cancel process", e)
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
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      onError("LlmModelInstance is not initialized.")
      return
    }

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val conversation = instance.conversation

    val contents = mutableListOf<Content>()
    for (image in images) {
      contents.add(Content.ImageBytes(image.toPngByteArray()))
    }
    for (audioClip in audioClips) {
      contents.add(Content.AudioBytes(audioClip))
    }
    // add the text after image and audio for the accurate last token
    if (input.trim().isNotEmpty()) {
      contents.add(Content.Text(input))
    }

    // Set enable_thinking to false by default using boolean literals for proper JSON serialization.
    val enableThinking = extraContext?.get("enable_thinking") == "true"
    val finalExtraContext: Map<String, Any> =
      (extraContext ?: emptyMap()) + ("enable_thinking" to enableThinking)

    conversation.sendMessageAsync(
      Contents.of(contents),
      object : MessageCallback {
        override fun onMessage(message: Message) {
          resultListener(message.toString(), false, message.channels[THOUGHT_CHANNEL])
        }

        override fun onDone() {
          resultListener("", true, null)
        }

        override fun onError(throwable: Throwable) {
          if (throwable is CancellationException) {
            Log.i(TAG, "The inference is cancelled.")
            resultListener("", true, null)
          } else {
            Log.e(TAG, "onError", throwable)
            onError("Error: ${throwable.message}")
          }
        }
      },
      finalExtraContext,
    )
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
