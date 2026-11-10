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

package com.google.ai.edge.gallery.compute

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.RuntimeType

/**
 * [ALTREX] Compute choice of a local model, stored in ConfigKeys.ACCELERATOR with these labels
 * ("Auto", "CPU", "GPU", "NPU"; the older "TPU" label reads as NPU).
 */
enum class ComputeChoice(val label: String) {
  AUTO("Auto"),
  CPU("CPU"),
  GPU("GPU"),
  NPU("NPU");

  companion object {
    /** The stored label, AUTO when it is empty or unknown. */
    fun fromLabel(label: String?): ComputeChoice =
      when (label?.trim()?.uppercase()) {
        "CPU" -> CPU
        "GPU" -> GPU
        "NPU",
        "TPU" -> NPU
        else -> AUTO
      }
  }
}

/** Maker of the phone's chip, for the NPU's name. */
enum class SocKind {
  QUALCOMM,
  TENSOR,
  OTHER;

  companion object {
    /** From Build.SOC_MANUFACTURER and Build.SOC_MODEL (empty before Android 12). */
    fun from(manufacturer: String, model: String): SocKind {
      val m = manufacturer.lowercase()
      val s = model.lowercase()
      return when {
        m.contains("qti") || m.contains("qualcomm") || s.startsWith("sm") || s.startsWith("qcs") ->
          QUALCOMM
        m.contains("google") || s.contains("tensor") -> TENSOR
        else -> OTHER
      }
    }
  }
}

/** One line of the compute selector. */
data class ComputeOption(
  val choice: ComputeChoice,
  val title: String,
  val detail: String,
  val experimental: Boolean = false,
)

/**
 * Which compute backends each engine really has in this build.
 *
 * - llama.cpp (GGUF): the native library is compiled with the ggml CPU backend only (see
 *   app/src/main/cpp/CMakeLists.txt: no ggml-vulkan, ggml-opencl or ggml-hexagon sources), so CPU
 *   is the only choice; offering GPU would silently run on the CPU.
 * - LiteRT-LM: CPU, GPU (the engine picks OpenCL or WebGPU itself) and NPU (the model must be
 *   compiled for the phone's chip), within what the model declares.
 * - Remote, cloud and AICore models: no local compute, no selector.
 */
object ComputeOptions {
  const val LLAMA_CPP_NOTE =
    "llama.cpp est compilé ici pour le processeur seulement : pas de Vulkan, d'OpenCL ni de NPU " +
      "Hexagon pour les modèles GGUF dans cette version."

  /**
   * Backends to choose from, in a fixed order (CPU, GPU, NPU), without AUTO.
   *
   * @param modelAccelerators what the model's list or import declares (empty: CPU and GPU).
   * @param engineBackends what the model file says it supports (LiteRT-LM ModelInfo), null when
   *   unknown. It narrows the list but never empties it.
   */
  fun backendsFor(
    runtime: RuntimeType,
    modelAccelerators: List<Accelerator>,
    engineBackends: Set<ComputeChoice>? = null,
  ): List<ComputeChoice> {
    return when (runtime) {
      RuntimeType.LLAMA_CPP -> listOf(ComputeChoice.CPU)
      RuntimeType.LITERT_LM -> {
        val declared =
          modelAccelerators
            .map {
              when (it) {
                Accelerator.CPU -> ComputeChoice.CPU
                Accelerator.GPU -> ComputeChoice.GPU
                Accelerator.NPU,
                Accelerator.TPU -> ComputeChoice.NPU
              }
            }
            .distinct()
        val base = declared.ifEmpty { listOf(ComputeChoice.CPU, ComputeChoice.GPU) }
        val narrowed =
          if (engineBackends.isNullOrEmpty()) base
          else base.filter { it in engineBackends }.ifEmpty { base }
        ORDER.filter { it in narrowed }
      }
      else -> emptyList()
    }
  }

  /** The selector's lines: Auto first, then each backend; empty when the model has no choice. */
  fun optionsFor(
    runtime: RuntimeType,
    modelAccelerators: List<Accelerator>,
    soc: SocKind,
    engineBackends: Set<ComputeChoice>? = null,
  ): List<ComputeOption> {
    val backends = backendsFor(runtime, modelAccelerators, engineBackends)
    if (backends.isEmpty()) return emptyList()
    val auto =
      ComputeOption(
        choice = ComputeChoice.AUTO,
        title = "Auto",
        detail =
          if (backends.size == 1) "Un seul calcul possible ici : ${title(backends[0], soc)}."
          else
            "Compare les calculs au premier chargement, garde le plus rapide (le CPU à vitesse " +
              "proche) et s'en souvient pour ce modèle.",
      )
    return listOf(auto) + backends.map { option(it, runtime, soc) }
  }

  /** The stored choice when it is offered, else AUTO (a GPU choice left on a GGUF model, say). */
  fun resolve(storedLabel: String?, backends: List<ComputeChoice>): ComputeChoice {
    val choice = ComputeChoice.fromLabel(storedLabel)
    return if (choice == ComputeChoice.AUTO || choice in backends) choice else ComputeChoice.AUTO
  }

  fun title(choice: ComputeChoice, soc: SocKind): String =
    when (choice) {
      ComputeChoice.AUTO -> "Auto"
      ComputeChoice.CPU -> "CPU"
      ComputeChoice.GPU -> "GPU"
      ComputeChoice.NPU ->
        when (soc) {
          SocKind.QUALCOMM -> "NPU Hexagon"
          SocKind.TENSOR -> "TPU Tensor"
          SocKind.OTHER -> "NPU"
        }
    }

  private fun option(choice: ComputeChoice, runtime: RuntimeType, soc: SocKind): ComputeOption =
    when (choice) {
      ComputeChoice.CPU ->
        ComputeOption(
          choice,
          "CPU",
          if (runtime == RuntimeType.LLAMA_CPP) "Processeur, sur les cœurs rapides. Le plus stable."
          else "Processeur (XNNPACK). Le plus stable.",
        )
      ComputeChoice.GPU ->
        ComputeOption(
          choice,
          "GPU",
          "Processeur graphique par LiteRT, qui choisit lui-même OpenCL ou WebGPU (Vulkan).",
        )
      ComputeChoice.NPU ->
        ComputeOption(
          choice,
          title(choice, soc),
          (if (soc == SocKind.QUALCOMM) "Accélérateur Qualcomm, par LiteRT. "
          else "Accélérateur neuronal, par LiteRT. ") +
            "Le modèle doit être compilé pour cette puce.",
          experimental = true,
        )
      ComputeChoice.AUTO -> ComputeOption(choice, "Auto", "")
    }

  private val ORDER = listOf(ComputeChoice.CPU, ComputeChoice.GPU, ComputeChoice.NPU)
}
