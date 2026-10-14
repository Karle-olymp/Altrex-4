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

package com.google.ai.edge.gallery.data

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Définit la stratégie d'allocation matérielle pour l'inférence des modèles GGUF.
 */
enum class LlmHardwareProfile(val label: String) {
    CPU_ONLY("Économie d'énergie (100% CPU)"),
    HYBRID_AUTO("Auto (CPU + GPU équilibré)"),
    GPU_MAX("Performance Max (100% GPU)")
}

/**
 * Optimiseur proactif qui calcule la répartition de la charge (layers)
 * entre le CPU et le GPU en fonction de la mémoire RAM réellement disponible sur l'appareil.
 */
object HardwareOptimizer {
    private const val TAG = "HardwareOptimizer"

    /**
     * Calcule le nombre de couches GPU optimal.
     * À passer en paramètre (n_gpu_layers) lors de l'appel JNI initContextWithFd.
     *
     * @param context Le contexte Android pour accéder à l'ActivityManager.
     * @param profile Le profil matériel choisi par l'utilisateur.
     * @param totalModelLayers Le nombre total de couches du modèle (ex: 32 pour Llama-3). Si inconnu, utiliser 99.
     */
    fun getOptimalGpuLayers(
        context: Context,
        profile: LlmHardwareProfile,
        totalModelLayers: Int = 99
    ): Int {
        return when (profile) {
            LlmHardwareProfile.CPU_ONLY -> {
                Log.i(TAG, "Profil CPU_ONLY sélectionné : 0 couche GPU allouée.")
                0
            }
            LlmHardwareProfile.GPU_MAX -> {
                Log.i(TAG, "Profil GPU_MAX sélectionné : Forçage de toutes les couches sur GPU.")
                9999 // Convention llama.cpp pour forcer le maximum
            }
            LlmHardwareProfile.HYBRID_AUTO -> {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)

                val availableRamGb = memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0)

                // Logique de prévention OOM (Out Of Memory)
                // Le système Android tue les apps si la RAM est saturée. L'allocation GPU partagée
                // consomme énormément de mémoire globale.
                val optimalLayers = when {
                    availableRamGb > 4.5 -> {
                        // Téléphone haut de gamme (ex: S22 Ultra avec RAM libre), on charge ~80% sur GPU
                        (totalModelLayers * 0.80).toInt()
                    }
                    availableRamGb > 3.0 -> {
                        // Utilisation moyenne, on équilibre ~50%
                        (totalModelLayers * 0.50).toInt()
                    }
                    else -> {
                        // Mode survie (peu de RAM), on soulage le GPU pour éviter le crash
                        (totalModelLayers * 0.20).toInt()
                    }
                }
                
                Log.i(TAG, "Profil HYBRID_AUTO: RAM disponible = ${"%.2f".format(availableRamGb)} GB. Allocation de $optimalLayers/$totalModelLayers couches au GPU.")
                optimalLayers
            }
        }
    }
}
