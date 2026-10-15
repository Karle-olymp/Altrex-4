package org.nehuatl.llamacpp

import java.io.File
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class LlamaHelper(
    val contentResolver: ContentResolver,
    val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    val sharedFlow: MutableSharedFlow<LLMEvent>
) {

    private val llama by lazy { LlamaAndroid(contentResolver) }
    private var loadJob: Job? = null
    private var completionJob: Job? = null
    @Volatile var currentContext: Int? = null
    private var tokenCount = 0
    private var allText = ""

    // Timing of the last generation (read by the agent to show where the time goes).
    @Volatile private var predictStartMs = 0L
    @Volatile var lastPrefillMs = 0L
    @Volatile var lastGenMs = 0L
    @Volatile var lastTokens = 0

    private fun getParcelFileDescriptor(pathOrUri: String): android.os.ParcelFileDescriptor? {
        val file = File(pathOrUri)
        if (file.exists() && file.isFile) {
            return try {
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                null
            }
        }
        val uri = if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
            Uri.parse(pathOrUri)
        } else {
            Uri.fromFile(file)
        }
        return try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
    }

    fun load(
        path: String,
        contextLength: Int,
        mmprojPath: String? = null,
        onError: ((String) -> Unit)? = null,
        loaded: (Long) -> Unit
    ) {
        currentContext?.let { id -> llama.releaseContext(id) }

        try {
            val modelUri = if (path.startsWith("content://") || path.startsWith("file://")) {
                Uri.parse(path)
            } else {
                Uri.fromFile(File(path))
            }
            Log.d("LlamaHelper", ">>> Opening model FD for URI: $modelUri (original path: $path)")

            val modelPfd = getParcelFileDescriptor(path)
                ?: throw IllegalArgumentException("Cannot open model file: $path")
            val modelFd = modelPfd.detachFd()
            Log.d("LlamaHelper", ">>> Model FD: $modelFd")

            val config = mutableMapOf<String, Any>(
                "model" to modelUri.toString(),
                "model_fd" to modelFd,
                "use_mmap" to true,
                "use_mlock" to false,
                "n_ctx" to contextLength,
                "embedding" to false,
                "n_batch" to 512,
                "n_threads" to 0,
                "n_gpu_layers" to 0,
                "vocab_only" to false,
                "lora" to "",
                "lora_scaled" to 1.0,
                "rope_freq_base" to 0.0,
                "rope_freq_scale" to 0.0
            )

            mmprojPath?.let {
                val mmPfd = getParcelFileDescriptor(it)
                if (mmPfd != null) {
                    val mmFd = mmPfd.detachFd()
                    config["mmproj_fd"] = mmFd
                    Log.d("LlamaHelper", ">>> Mmproj FD: $mmFd")
                }
            }

            loadJob = scope.launch {
                Log.d("LlamaHelper", ">>> will start llama context with config: $config")
                val result = try {
                    llama.startEngine(config) {
                        allText += it
                        tokenCount++
                        if (tokenCount == 1) {
                            lastPrefillMs = System.currentTimeMillis() - predictStartMs
                        }
                        tokenListener?.invoke(it)
                        sharedFlow.tryEmit(LLMEvent.Ongoing(it, tokenCount))
                    }
                } catch (e: Exception) {
                    Log.e("LlamaHelper", "Engine start failed", e)
                    null
                }

                val id = result?.get("contextId") as? Number
                if (id == null) {
                    // [ALTREX] Reported directly: the shared flow has no replay, so an event sent
                    // before anyone listens was lost and the model stayed "loading" forever.
                    sharedFlow.tryEmit(LLMEvent.Error("Model initialization failed"))
                    onError?.invoke("Impossible de charger ce modèle (fichier GGUF illisible ou non pris en charge).")
                    return@launch
                }
                currentContext = id.toInt()

                Log.d("LlamaHelper", ">>> Context loaded successfully with ID: $currentContext")
                sharedFlow.tryEmit(LLMEvent.Loaded(path))
                loaded(currentContext!!.toLong())
            }
        } catch (e: Exception) {
            Log.e("LlamaHelper", "Failed to prepare model loading", e)
            sharedFlow.tryEmit(LLMEvent.Error("Failed to open files: ${e.message}"))
            onError?.invoke("Impossible d'ouvrir le fichier du modèle : ${e.message}")
        }
    }

    /** Sampling settings for one generation (from the model's config dialog). */
    data class Sampling(
        val temperature: Float = 0.7f,
        val topK: Int = 40,
        val topP: Float = 0.95f,
        val minP: Float = 0.05f,
        val repeatPenalty: Float = 1.05f,
        val maxOutputTokens: Int = 2048,
    )

    // [ALTREX] One generation at a time: a new request waits until the previous native call has
    // really returned, so a late Stop can no longer kill the next answer and "Context is busy"
    // cannot happen.
    private val predictMutex = Mutex()
    @Volatile private var tokenListener: ((String) -> Unit)? = null
    // Bumped by every Stop: a request still waiting for its turn when Stop is pressed is dropped.
    @Volatile private var stopEpoch = 0

    fun predict(
        prompt: String,
        imagePath: String? = null,
        partialCompletion: Boolean = true,
        sampling: Sampling = Sampling(),
        onToken: ((String) -> Unit)? = null,
        onDone: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        val context = currentContext ?: throw Exception("Model was not loaded yet")

        val imageFds = ArrayList<Int>()
        imagePath?.let {
            try {
                val imgUri = Uri.parse(it)
                contentResolver.openFileDescriptor(imgUri, "r")?.use { pfd -> imageFds.add(pfd.detachFd()) }
            } catch (e: Exception) {
                Log.e("LlamaHelper", "Failed to open image FD", e)
            }
        }

        val epoch = stopEpoch
        completionJob = scope.launch {
            predictMutex.withLock {
                if (stopEpoch != epoch) {
                    onDone?.invoke("")
                    return@withLock
                }
                // The previous native call may still be finishing after a Stop.
                var waited = 0
                while (llama.isPredicting(context) && waited < 10_000) {
                    delay(20)
                    waited += 20
                }
                if (llama.isPredicting(context)) {
                    onError?.invoke("Le moteur est bloqué sur la génération précédente. Recharge le modèle.")
                    return@withLock
                }

                val startTime = System.currentTimeMillis()
                tokenCount = 0
                allText = ""
                predictStartMs = startTime
                lastPrefillMs = 0L

                val formatted = formatPrompt(context, prompt)
                val params = mutableMapOf<String, Any>(
                    "prompt" to formatted.prompt,
                    "n_predict" to sampling.maxOutputTokens,
                    "stop" to formatted.stops,
                    "temperature" to sampling.temperature.toDouble(),
                    "top_k" to sampling.topK,
                    "top_p" to sampling.topP.toDouble(),
                    "min_p" to sampling.minP.toDouble(),
                    "penalty_repeat" to sampling.repeatPenalty.toDouble(),
                    "penalty_last_n" to 128,
                    "emit_partial_completion" to partialCompletion,
                )
                if (imageFds.isNotEmpty()) params["image_fds"] = imageFds

                tokenListener = onToken
                sharedFlow.tryEmit(LLMEvent.Started(prompt))
                // [ALTREX] The native side clears its stop flag when a generation starts: a Stop
                // pressed just before that would be lost, so keep re-applying it while it runs.
                val stopWatcher = scope.launch {
                    while (true) {
                        if (stopEpoch != epoch) {
                            llama.stopCompletionNow(context)
                            break
                        }
                        delay(50)
                    }
                }
                val result = try {
                    llama.launchCompletion(id = context, params = params)
                } finally {
                    tokenListener = null
                    stopWatcher.cancel()
                }
                val duration = System.currentTimeMillis() - startTime
                lastGenMs = (duration - lastPrefillMs).coerceAtLeast(0L)
                lastTokens = tokenCount
                if (result == null && allText.isEmpty()) {
                    onError?.invoke("La génération a échoué.")
                } else if (allText.isEmpty() && result?.get("context_full") == true) {
                    onError?.invoke(
                        "La conversation est trop longue pour le contexte du modèle. " +
                            "Commence une nouvelle conversation ou augmente « Tokens max »."
                    )
                } else {
                    onDone?.invoke(allText)
                }
                sharedFlow.tryEmit(LLMEvent.Done(allText, tokenCount, duration))
            }
        }
    }

    private data class FormattedPrompt(val prompt: String, val stops: List<String>)

    /**
     * Turns the request into the model's own prompt format. Three inputs are accepted:
     * - a chat request built by [chatRequest] (system + messages): formatted with the GGUF chat
     *   template, so Gemma, Llama, Mistral, Phi... get their real format, with thinking off;
     * - a ready-made ChatML prompt (starts with <|im_start|>): used as is;
     * - plain text: wrapped as one user message with the default system prompt.
     */
    private fun formatPrompt(context: Int, input: String): FormattedPrompt {
        if (input.startsWith("<|im_start|>")) return FormattedPrompt(input, CHATML_STOPS)
        val messages: JSONArray =
            if (input.startsWith(CHAT_REQUEST_PREFIX)) {
                try {
                    JSONArray(input.removePrefix(CHAT_REQUEST_PREFIX))
                } catch (e: Exception) {
                    Log.e("LlamaHelper", "Invalid chat request", e)
                    JSONArray()
                }
            } else {
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", getDefaultSystemPrompt()))
                    .put(JSONObject().put("role", "user").put("content", input))
            }

        val native = llama.formatChat(context, messages.toString(), enableThinking = false)
        if (native != null) {
            try {
                val obj = JSONObject(native)
                val prompt = obj.optString("prompt")
                if (prompt.isNotBlank()) {
                    val stops = LinkedHashSet<String>()
                    obj.optJSONArray("stops")?.let { arr ->
                        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let(stops::add)
                    }
                    // End-of-turn markers of the common formats, in case the vocabulary does not flag
                    // them as end-of-generation.
                    if (prompt.contains("<|im_start|>")) stops.addAll(CHATML_STOPS)
                    if (prompt.contains("<start_of_turn>")) stops.add("<end_of_turn>")
                    if (prompt.contains("<|start_header_id|>")) stops.add("<|eot_id|>")
                    Log.i("LlamaHelper", "Prompt formatted with the model's chat template (jinja=${obj.optBoolean("jinja")})")
                    return FormattedPrompt(prompt, stops.toList())
                }
            } catch (e: Exception) {
                Log.e("LlamaHelper", "Bad formatChat result", e)
            }
        }

        // No usable template in the GGUF: ChatML, the most common format, with thinking off.
        Log.w("LlamaHelper", "No chat template in the model, falling back to ChatML")
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            sb.append("<|im_start|>").append(m.optString("role")).append("\n")
                .append(m.optString("content")).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n").append(THINK_OFF)
        return FormattedPrompt(sb.toString(), CHATML_STOPS)
    }

    fun stopPrediction() {
        stopEpoch++
        val id = currentContext ?: return
        // Synchronous: the flag is set before any new request can start.
        llama.stopCompletionNow(id)
    }

    /**
     * Frees the native context. [ALTREX] Waits for a running generation to end first: freeing it
     * while llama_decode is still running on another thread is a use-after-free (native crash).
     */
    fun release(onReleased: () -> Unit = {}) {
        val id = currentContext
        currentContext = null
        if (id == null) {
            onReleased()
            return
        }
        stopEpoch++
        llama.stopCompletionNow(id)
        scope.launch {
            predictMutex.withLock {
                var waited = 0
                while (llama.isPredicting(id) && waited < 15_000) {
                    delay(20)
                    waited += 20
                }
                try {
                    llama.releaseContext(id)
                } catch (e: Exception) {
                    Log.e("LlamaHelper", "Failed to release context", e)
                }
            }
            onReleased()
        }
    }

    fun abort() {
        loadJob?.cancel()
        stopPrediction()
    }

    sealed class LLMEvent {
        data class Loaded(val path: String) : LLMEvent()
        data class Started(val prompt: String) : LLMEvent()
        data class Ongoing(val word: String, val tokenCount: Int) : LLMEvent()
        data class Done(val fullText: String, val tokenCount: Int, val duration: Long) : LLMEvent()
        data class Error(val message: String) : LLMEvent()
    }

    companion object {
        const val DEFAULT_CONTEXT_SIZE = 2048

        /** Marks a chat request: the prefix is followed by an OpenAI-style JSON message array. */
        const val CHAT_REQUEST_PREFIX = "\u0000altrex-chat\u0000"

        private val THINK_OFF = "<" + "think>\n\n<" + "/think>\n\n"
        private val CHATML_STOPS = listOf("<|im_end|>", "<|endoftext|>")

        /** Builds a chat request for [predict] from (role, content) pairs. */
        fun chatRequest(messages: List<Pair<String, String>>): String {
            val arr = JSONArray()
            for ((role, content) in messages) {
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            return CHAT_REQUEST_PREFIX + arr.toString()
        }

        fun getDefaultSystemPrompt(): String {
            return "Tu es Altrex, un assistant IA qui fonctionne entièrement sur le téléphone. Réponds en français (sauf si on te parle dans une autre langue), clairement et de façon concise."
        }
    }
}