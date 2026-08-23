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
import kotlinx.coroutines.launch

class LlamaHelper(
    val contentResolver: ContentResolver,
    val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    val sharedFlow: MutableSharedFlow<LLMEvent>
) {

    private val llama by lazy { LlamaAndroid(contentResolver) }
    private var loadJob: Job? = null
    private var completionJob: Job? = null
    private var currentContext: Int? = null
    private var tokenCount = 0
    private var allText = ""

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
                        sharedFlow.tryEmit(LLMEvent.Ongoing(it, tokenCount))
                    }
                } catch (e: Exception) {
                    Log.e("LlamaHelper", "Engine start failed", e)
                    null
                }

                if (result == null) {
                    sharedFlow.tryEmit(LLMEvent.Error("Model initialization failed"))
                    return@launch
                }

                val id = result["contextId"] ?: throw Exception("contextId not found in result map")
                currentContext = (id as Number).toInt()

                Log.d("LlamaHelper", ">>> Context loaded successfully with ID: $currentContext")
                sharedFlow.tryEmit(LLMEvent.Loaded(path))
                loaded(currentContext!!.toLong())
            }
        } catch (e: Exception) {
            Log.e("LlamaHelper", "Failed to prepare model loading", e)
            sharedFlow.tryEmit(LLMEvent.Error("Failed to open files: ${e.message}"))
        }
    }

    fun predict(prompt: String, imagePath: String? = null, partialCompletion: Boolean = true) {
        val context = currentContext ?: throw Exception("Model was not loaded yet")
        val startTime = System.currentTimeMillis()
        tokenCount = 0
        allText = ""
        
        val params = mutableMapOf<String, Any>(
            "prompt" to prompt,
            "n_predict" to 512,
            "stop" to listOf("<|im_end|>", "<|endoftext|>"),
            "emit_partial_completion" to partialCompletion,
        )
        
        imagePath?.let {
            try {
                val imgUri = Uri.parse(it)
                Log.d("LlamaHelper", ">>> Opening image FD for URI: $imgUri")
                contentResolver.openFileDescriptor(imgUri, "r")?.use { pfd ->
                    // Since we want to pass the FD to JNI, we should detach it if needed,
                    // but here we might be able to just pass the FD number if it stays open
                    // for the duration of the call.
                    // Actually, detachFd() is safer.
                    val imgFd = pfd.detachFd()
                    params["image_fds"] = listOf(imgFd)
                    Log.d("LlamaHelper", ">>> Image FD added to params: $imgFd")
                }
            } catch (e: Exception) {
                Log.e("LlamaHelper", "Failed to open image FD", e)
            }
        }

        completionJob = scope.launch {
            val formattedPrompt = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
            params["prompt"] = formattedPrompt
            sharedFlow.tryEmit(LLMEvent.Started(prompt))
            llama.launchCompletion(
                id = context,
                params = params
            )
            val duration = System.currentTimeMillis() - startTime
            sharedFlow.tryEmit(LLMEvent.Done(allText, tokenCount, duration))
        }
    }

    fun stopPrediction() {
        val id = currentContext ?: return
        scope.launch {
            llama.stopCompletion(id)
        }
        completionJob?.cancel()
    }

    fun release() {
        currentContext?.let { id ->
            llama.releaseContext(id)
        }
        currentContext = null
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
}
