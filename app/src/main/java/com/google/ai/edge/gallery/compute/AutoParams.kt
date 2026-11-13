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

import java.util.Locale

/** [ALTREX] Memory figures of ActivityManager.MemoryInfo. */
data class MemorySnapshot(val totalBytes: Long, val availBytes: Long, val thresholdBytes: Long = 0L)

/** Context size and thread count chosen for a model, with the figures behind them. */
data class AutoParams(
  val contextTokens: Int,
  val threads: Int,
  /** False when even the smallest context does not fit in the free memory. */
  val fits: Boolean,
  /** Memory the model needs with [contextTokens] (weights, runtime, KV cache). */
  val neededBytes: Long,
  /** Memory kept free for Android and the other apps. */
  val safetyMarginBytes: Long,
)

/**
 * [ALTREX] Parameters chosen from the free memory, the model size and its maximal context.
 *
 * The KV cache grows with the context; the weights (memory-mapped) and the runtime buffers do not.
 * A safety margin stays free so that Android does not kill the app (low-memory killer): the
 * largest of 512 MB, 10 % of the RAM and the system's low-memory threshold.
 */
object AutoParamsCalculator {
  private const val MB = 1024L * 1024L
  const val RUNTIME_OVERHEAD_BYTES = 400L * MB
  const val MIN_CONTEXT = 1024
  /** Auto stops there even with RAM to spare: longer prompts make every answer slower. */
  const val PREFERRED_MAX_CONTEXT = 8192
  /** Context steps: the value is always a multiple of this. */
  const val CONTEXT_STEP = 1024

  /** Rough f16 KV cache cost per context token: ~32 KB for 1B models up to ~160 KB for 8B. */
  fun kvBytesPerToken(fileBytes: Long): Long = (fileBytes / 30_000L).coerceIn(32L * 1024L, 160L * 1024L)

  fun safetyMargin(mem: MemorySnapshot): Long =
    maxOf(512L * MB, mem.totalBytes / 10, mem.thresholdBytes)

  fun neededBytes(fileBytes: Long, contextTokens: Int): Long =
    fileBytes + RUNTIME_OVERHEAD_BYTES + contextTokens * kvBytesPerToken(fileBytes)

  /**
   * @param maxContext the model's maximal context (the largest value of its setting).
   * @param cpuMaxFreqs each core's cpuinfo_max_freq (kHz), empty when unreadable.
   */
  fun compute(
    mem: MemorySnapshot,
    fileBytes: Long,
    maxContext: Int,
    cpuCores: Int,
    cpuMaxFreqs: List<Long> = emptyList(),
    preferredMaxContext: Int = PREFERRED_MAX_CONTEXT,
  ): AutoParams {
    val margin = safetyMargin(mem)
    val kv = kvBytesPerToken(fileBytes)
    val upper = minOf(maxContext, preferredMaxContext).coerceAtLeast(MIN_CONTEXT)
    val forKv = mem.availBytes - margin - fileBytes - RUNTIME_OVERHEAD_BYTES
    val byRam = if (forKv <= 0) 0L else forKv / kv
    var context = ((minOf(byRam, upper.toLong()) / CONTEXT_STEP) * CONTEXT_STEP).toInt()
    if (context < MIN_CONTEXT) context = MIN_CONTEXT
    // Without the margin, the smallest context must still fit: else the load is refused.
    val fits = fileBytes <= 0 || mem.availBytes >= neededBytes(fileBytes, MIN_CONTEXT)
    return AutoParams(
      contextTokens = context,
      threads = autoThreads(cpuCores, cpuMaxFreqs),
      fits = fits,
      neededBytes = neededBytes(fileBytes, context),
      safetyMarginBytes = margin,
    )
  }

  /**
   * Threads on the fast cores, as the native auto mode does (jni.cpp): the cores whose maximal
   * frequency is above the slowest ones, at most 4; else 2 on a 4-core phone, up to 4 otherwise.
   */
  fun autoThreads(cpuCores: Int, cpuMaxFreqs: List<Long>): Int {
    val known = cpuMaxFreqs.filter { it > 0 }
    if (known.isNotEmpty()) {
      val slowest = known.min()
      val fast = known.count { it > slowest }
      if (known.max() != slowest && fast >= 2) return minOf(fast, 4)
    }
    val cores = cpuCores.coerceAtLeast(1)
    return if (cores == 4) 2 else minOf(4, cores)
  }

  /** "1,6 Go" / "820 Mo". */
  fun formatBytes(bytes: Long): String =
    if (bytes >= 1024L * MB) "%.1f Go".format(Locale.FRANCE, bytes / (1024.0 * MB))
    else "${bytes / MB} Mo"
}
