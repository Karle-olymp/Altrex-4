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

package org.nehuatl.llamacpp

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.ai.edge.gallery.data.HardwareOptimizer
import com.google.ai.edge.gallery.data.LlmHardwareProfile
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

class LlamaContext private constructor(private var contextPtr: Long) {

    private var isReleased = false

    fun release() {
        if (!isReleased && contextPtr != 0L) {
            freeContext(contextPtr)
            contextPtr = 0L
            isReleased = true
        }
    }

    protected fun finalize() {
        release()
    }

    companion object {
        init {
            System.loadLibrary("rnllama")
        }

        private const val TAG = "LlamaContext"

        /**
         * Crée et initialise un contexte LLM avec prise en compte du profil matériel (CPU / GPU Hybride).
         */
        fun create(
            context: Context,
            modelFile: File,
            embedding: Boolean = false,
            nCtx: Int = 2048,
            nBatch: Int = 512,
            nThreads: Int = 0,
            hardwareProfile: LlmHardwareProfile = LlmHardwareProfile.HYBRID_AUTO,
            useMlock: Boolean = true,
            useMmap: Boolean = true,
            vocabOnly: Boolean = false,
            lora: String = "",
            loraScaled: Float = 1.0f,
            ropeFreqBase: Float = 0.0f,
            ropeFreqScale: Float = 0.0f,
            mmprojFile: File? = null
        ): LlamaContext? {
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file does not exist: ${modelFile.absolutePath}")
                return null
            }

            val pfd = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val modelFd = pfd.fd

            var mmprojFd = -1
            if (mmprojFile != null && mmprojFile.exists()) {
                val mmprojPfd = ParcelFileDescriptor.open(mmprojFile, ParcelFileDescriptor.MODE_READ_ONLY)
                mmprojFd = mmprojPfd.fd
            }

            // [ALTREX] Calcul dynamique des couches GPU via l'optimiseur matériel
            // On attribue par défaut 33 couches (standard pour les modèles 3B/4B comme Qwen) si non spécifié
            val estimatedTotalLayers = 33 
            val calculatedGpuLayers = HardwareOptimizer.getOptimalGpuLayers(
                context = context,
                profile = hardwareProfile,
                totalModelLayers = estimatedTotalLayers
            )

            Log.i(TAG, "Initialisation JNI avec ${calculatedGpuLayers} couches sur le GPU (Profil: ${hardwareProfile.name})")

            val ptr = initContextWithFd(
                modelFd = modelFd,
                embedding = embedding,
                nCtx = nCtx,
                nBatch = nBatch,
                nThreads = nThreads,
                nGpuLayers = calculatedGpuLayers,
                useMlock = useMlock,
                useMmap = useMmap,
                vocabOnly = vocabOnly,
                loraStr = lora,
                loraScaled = loraScaled,
                ropeFreqBase = ropeFreqBase,
                ropeFreqScale = ropeFreqScale,
                mmprojFd = mmprojFd,
                imageFds = null
            )

            // Fermeture propre des descripteurs côté Kotlin (dupliqués en C++ via dup())
            try {
                pfd.close()
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de la fermeture du pfd du modèle", e)
            }

            if (ptr == 0L) {
                Log.e(TAG, "Échec de l'initialisation du contexte C++ (pointeur nul)")
                return null
            }

            return LlamaContext(ptr)
        }

        @JvmStatic
        private external fun initContextWithFd(
            modelFd: Int,
            embedding: Boolean,
            nCtx: Int,
            nBatch: Int,
            nThreads: Int,
            nGpuLayers: Int,
            useMlock: Boolean,
            useMmap: Boolean,
            vocabOnly: Boolean,
            loraStr: String,
            loraScaled: Float,
            ropeFreqBase: Float,
            ropeFreqScale: Float,
            mmprojFd: Int,
            imageFds: IntArray?
        ): Long

        @JvmStatic
        private external fun freeContext(contextPtr: Long)
    }

    external fun loadModelDetails(): Map<String, Any>?
    external fun loadSession(path: String): Map<String, Any>?
    external fun saveSession(path: String, size: Int): Int
    
    external fun doCompletion(
        prompt: String,
        grammar: String?,
        temperature: Float,
        nThreads: Int,
        nPredict: Int,
        nProbs: Int,
        penaltyLastN: Int,
        penaltyRepeat: Float,
        penaltyFreq: Float,
        penaltyPresent: Float,
        mirostat: Float,
        mirostatTau: Float,
        mirostatEta: Float,
        penalizeNl: Boolean,
        topK: Int,
        topP: Float,
        min_p: Float,
        xtcT: Float,
        xtcP: Float,
        tfsZ: Float,
        typicalP: Float,
        seed: Int,
        stop: Array<String>?,
        ignoreEos: Boolean,
        logitBias: Array<FloatArray>?,
        imageFds: IntArray?,
        callback: Any
    ): Map<String, Any>?

    external fun stopCompletion()
    external fun isPredicting(): Boolean
    external fun tokenize(text: String): IntArray?
    external fun detokenize(tokens: IntArray): String?
    external fun isEmbeddingEnabled(): Boolean
    external fun embedding(text: String): FloatArray?
    external fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String?
}
