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

/** [ALTREX] A string store: SharedPreferences in the app, a map in the tests. */
interface KeyValueStore {
  fun get(key: String): String?

  /** Null removes the key. */
  fun put(key: String, value: String?)
}

class MapKeyValueStore : KeyValueStore {
  private val map = mutableMapOf<String, String>()

  override fun get(key: String): String? = map[key]

  override fun put(key: String, value: String?) {
    if (value == null) map.remove(key) else map[key] = value
  }
}

/** Decode speed measured on one backend; null when it failed to start or to answer. */
data class BenchSample(val backend: ComputeChoice, val tokensPerSecond: Double?)

/** The phone's state that decides whether a measurement may run now. */
data class DeviceState(val batteryPercent: Int?, val charging: Boolean, val powerSave: Boolean)

/** When the Auto mode may compare backends. */
object BenchmarkGate {
  /** Below this charge (and unplugged), no measurement. */
  const val MIN_BATTERY_PERCENT = 20

  /** Null when the measurement may run, else why it waits (shown to the user). */
  fun refusal(device: DeviceState, conversationActive: Boolean, explicitLoad: Boolean): String? =
    when {
      !explicitLoad -> "mesure à faire au prochain chargement manuel"
      conversationActive -> "mesure reportée : conversation en cours"
      device.powerSave -> "mesure reportée : économie d'énergie"
      !device.charging && (device.batteryPercent ?: 100) < MIN_BATTERY_PERCENT ->
        "mesure reportée : batterie faible"
      else -> null
    }

  /**
   * A background measurement starts a second engine beside the loaded one: it needs about the
   * model's size again in free memory, plus a margin.
   */
  fun memoryRefusal(availBytes: Long, fileBytes: Long): String? =
    if (availBytes < fileBytes + fileBytes / 4 + MEMORY_MARGIN_BYTES) "mesure reportée : mémoire libre insuffisante" else null

  const val MEMORY_MARGIN_BYTES = 600L * 1024 * 1024
}

/** What the Auto mode does for this load. */
sealed class AutoPlan {
  /** Load on [backend]; [reason] completes the line shown to the user. */
  data class Use(val backend: ComputeChoice, val reason: String, val tokensPerSecond: Double? = null) :
    AutoPlan()

  /** Measure these backends first, then load on the fastest. */
  data class Measure(val backends: List<ComputeChoice>) : AutoPlan()
}

object AutoSelector {
  /** At a speed within 10 % of the fastest, the CPU wins: it is the most stable. */
  const val CPU_PREFERENCE_MARGIN = 0.10

  /** The backend to keep from the samples, null when none answered. */
  fun pickFastest(samples: List<BenchSample>, margin: Double = CPU_PREFERENCE_MARGIN): ComputeChoice? {
    val ok = samples.filter { (it.tokensPerSecond ?: 0.0) > 0.0 }
    if (ok.isEmpty()) return null
    val best = ok.maxBy { it.tokensPerSecond!! }
    val cpu = ok.firstOrNull { it.backend == ComputeChoice.CPU }
    if (cpu != null && cpu.tokensPerSecond!! > best.tokensPerSecond!! * (1.0 - margin)) {
      return ComputeChoice.CPU
    }
    return best.backend
  }

  /**
   * Decides the Auto load.
   *
   * @param candidates backends offered for the model (no AUTO).
   * @param remembered the stored result for this model, file, app and phone (null: none or stale).
   * @param excluded backends that failed or crashed before (never tried again by Auto).
   * @param gateRefusal why a measurement may not run now, null when it may.
   */
  fun plan(
    candidates: List<ComputeChoice>,
    remembered: AutoRecord?,
    excluded: Set<ComputeChoice>,
    gateRefusal: String?,
  ): AutoPlan {
    val usable = candidates.filter { it !in excluded }
    if (usable.isEmpty()) {
      val fallback = if (ComputeChoice.CPU in candidates) ComputeChoice.CPU else candidates.firstOrNull()
      return AutoPlan.Use(fallback ?: ComputeChoice.CPU, "repli après un échec")
    }
    if (remembered != null && remembered.backend in usable) {
      return AutoPlan.Use(remembered.backend, "mesure gardée", remembered.tokensPerSecond)
    }
    if (usable.size == 1) return AutoPlan.Use(usable[0], "seul calcul disponible")
    if (gateRefusal != null) {
      val stable = if (ComputeChoice.CPU in usable) ComputeChoice.CPU else usable[0]
      return AutoPlan.Use(stable, gateRefusal)
    }
    return AutoPlan.Measure(usable)
  }

  /**
   * [ALTREX] The backend to load on NOW, without measuring anything (the measurement runs later,
   * in the background, see [AutoBenchmark]): the measured choice if there is one, else the last
   * backend that started well, else the one the model recommends, else the CPU.
   *
   * @param excluded backends that failed, crashed or were too slow before.
   */
  fun immediate(
    candidates: List<ComputeChoice>,
    remembered: AutoRecord?,
    lastGood: ComputeChoice?,
    recommended: ComputeChoice?,
    excluded: Set<ComputeChoice>,
  ): AutoPlan.Use {
    val usable = candidates.filter { it !in excluded }
    if (usable.isEmpty()) {
      val fallback = if (ComputeChoice.CPU in candidates) ComputeChoice.CPU else candidates.firstOrNull()
      return AutoPlan.Use(fallback ?: ComputeChoice.CPU, "repli après un échec")
    }
    if (remembered != null && remembered.backend in usable) {
      return AutoPlan.Use(remembered.backend, "mesure gardée", remembered.tokensPerSecond)
    }
    if (usable.size == 1) return AutoPlan.Use(usable[0], "seul calcul disponible")
    if (lastGood != null && lastGood in usable) return AutoPlan.Use(lastGood, "dernier choix qui a fonctionné")
    if (recommended != null && recommended in usable) return AutoPlan.Use(recommended, "recommandé par le modèle")
    return AutoPlan.Use(if (ComputeChoice.CPU in usable) ComputeChoice.CPU else usable[0], "choix sûr")
  }

  /** The one line shown to the user, as "Auto : GPU, 14 tok/s". */
  fun explain(backendTitle: String, tokensPerSecond: Double?, reason: String? = null): String {
    val speed = tokensPerSecond?.takeIf { it > 0.0 }?.let { ", ${formatSpeed(it)} tok/s" } ?: ""
    val why = reason?.takeIf { it.isNotBlank() && speed.isEmpty() }?.let { " ($it)" } ?: ""
    return "Auto : $backendTitle$speed$why"
  }

  fun formatSpeed(tokensPerSecond: Double): String =
    if (tokensPerSecond >= 10.0) "%.0f".format(Locale.ROOT, tokensPerSecond)
    else "%.1f".format(Locale.FRANCE, tokensPerSecond)
}

/** Identity of a model file: a change of any field makes the stored measurement stale. */
data class ModelFingerprint(
  val modelName: String,
  val fileBytes: Long,
  val fileModifiedMs: Long,
  val modelVersion: String,
) {
  fun signature(deviceId: String): String =
    listOf(fileBytes, fileModifiedMs, modelVersion, deviceId).joinToString("|").replace(";", ",")
}

/** The Auto result kept for one model. */
data class AutoRecord(
  val backend: ComputeChoice,
  val tokensPerSecond: Double?,
  val measuredAtMs: Long,
  val failed: Set<ComputeChoice> = emptySet(),
)

/**
 * [ALTREX] Remembers the Auto choice per model and phone, and guards against crash loops.
 *
 * @param deviceId the phone and the app build (Build.FINGERPRINT, app version): a system or app
 *   update makes the stored measurement stale, since engines and drivers change with them.
 */
class AutoMemory(private val store: KeyValueStore, private val deviceId: String) {
  /** The stored result, null when there is none or it belongs to another file, app or phone. */
  fun recall(fp: ModelFingerprint): AutoRecord? {
    val raw = store.get(recordKey(fp.modelName)) ?: return null
    val fields = parse(raw)
    if (fields["sig"] != fp.signature(deviceId)) return null
    val backend = fields["b"]?.let { ComputeChoice.fromLabel(it) }
    return AutoRecord(
      backend = backend?.takeIf { it != ComputeChoice.AUTO } ?: return null,
      tokensPerSecond = fields["t"]?.toDoubleOrNull(),
      measuredAtMs = fields["at"]?.toLongOrNull() ?: 0L,
      failed = failedOf(fp),
    )
  }

  fun remember(fp: ModelFingerprint, backend: ComputeChoice, tokensPerSecond: Double?, nowMs: Long) {
    val fields =
      buildMap {
        put("sig", fp.signature(deviceId))
        put("b", backend.label)
        if (tokensPerSecond != null) put("t", "%.3f".format(Locale.ROOT, tokensPerSecond))
        put("at", nowMs.toString())
      }
    store.put(recordKey(fp.modelName), format(fields))
  }

  /** Forgets the measurement (the next manual load measures again), not the failures. */
  fun forget(modelName: String) {
    store.put(recordKey(modelName), null)
  }

  /** Backends that failed or crashed for this file on this phone. */
  fun failedOf(fp: ModelFingerprint): Set<ComputeChoice> {
    val raw = store.get(failedKey(fp.modelName)) ?: return emptySet()
    val fields = parse(raw)
    if (fields["sig"] != fp.signature(deviceId)) return emptySet()
    return fields["f"]
      .orEmpty()
      .split(',')
      .filter { it.isNotBlank() }
      .map { ComputeChoice.fromLabel(it) }
      .filter { it != ComputeChoice.AUTO }
      .toSet()
  }

  fun markFailed(fp: ModelFingerprint, backend: ComputeChoice) {
    if (backend == ComputeChoice.AUTO) return
    val failed = failedOf(fp) + backend
    store.put(
      failedKey(fp.modelName),
      format(mapOf("sig" to fp.signature(deviceId), "f" to failed.joinToString(",") { it.label })),
    )
    // A remembered choice that now fails is no longer valid.
    if (recall(fp)?.backend == backend) forget(fp.modelName)
    if (lastSuccess(fp) == backend) store.put(okKey(fp.modelName), null)
  }

  /** The user chose a backend again by hand: give it a new chance. */
  fun clearFailures(modelName: String) {
    store.put(failedKey(modelName), null)
  }

  /** Backends whose background measurement went over its time limit (not retried by itself). */
  fun slowOf(fp: ModelFingerprint): Set<ComputeChoice> = listed(fp, slowKey(fp.modelName))

  fun markSlow(fp: ModelFingerprint, backend: ComputeChoice) {
    if (backend == ComputeChoice.AUTO) return
    val slow = slowOf(fp) + backend
    store.put(slowKey(fp.modelName), format(mapOf("sig" to fp.signature(deviceId), "f" to slow.joinToString(",") { it.label })))
    if (recall(fp)?.backend == backend) forget(fp.modelName)
  }

  /** « Relancer la mesure »: the slow ones get a new chance (crashes and failures do not). */
  fun clearSlow(modelName: String) {
    store.put(slowKey(modelName), null)
  }

  /** Everything Auto must not try by itself: failed, crashed, too slow. */
  fun excludedOf(fp: ModelFingerprint): Set<ComputeChoice> = failedOf(fp) + slowOf(fp)

  /** The backend the engine last started well on, for this file, app and phone. */
  fun lastSuccess(fp: ModelFingerprint): ComputeChoice? {
    val fields = parse(store.get(okKey(fp.modelName)) ?: return null)
    if (fields["sig"] != fp.signature(deviceId)) return null
    return ComputeChoice.fromLabel(fields["b"]).takeIf { it != ComputeChoice.AUTO }
  }

  fun rememberSuccess(fp: ModelFingerprint, backend: ComputeChoice) {
    if (backend == ComputeChoice.AUTO) return
    store.put(okKey(fp.modelName), format(mapOf("sig" to fp.signature(deviceId), "b" to backend.label)))
  }

  private fun listed(fp: ModelFingerprint, key: String): Set<ComputeChoice> {
    val fields = parse(store.get(key) ?: return emptySet())
    if (fields["sig"] != fp.signature(deviceId)) return emptySet()
    return fields["f"].orEmpty().split(',').filter { it.isNotBlank() }.map { ComputeChoice.fromLabel(it) }.filter { it != ComputeChoice.AUTO }.toSet()
  }

  /**
   * Called before an engine starts on [backend]. If the process dies there (a native crash of a
   * GPU or NPU driver), the mark stays and [takeCrashedAttempt] reports it at the next load.
   */
  fun beginAttempt(fp: ModelFingerprint, backend: ComputeChoice) {
    store.put(pendingKey(fp.modelName), format(mapOf("sig" to fp.signature(deviceId), "b" to backend.label)))
  }

  fun endAttempt(fp: ModelFingerprint) {
    store.put(pendingKey(fp.modelName), null)
  }

  /**
   * The backend of an attempt that never ended (the app was killed while it started), now recorded
   * as failed; null when the last attempt ended normally. Reported once.
   */
  fun takeCrashedAttempt(fp: ModelFingerprint): ComputeChoice? {
    val raw = store.get(pendingKey(fp.modelName)) ?: return null
    store.put(pendingKey(fp.modelName), null)
    val fields = parse(raw)
    if (fields["sig"] != fp.signature(deviceId)) return null
    val backend = ComputeChoice.fromLabel(fields["b"])
    if (backend == ComputeChoice.AUTO) return null
    markFailed(fp, backend)
    return backend
  }

  private fun recordKey(name: String) = "auto|$name"

  private fun failedKey(name: String) = "failed|$name"

  private fun pendingKey(name: String) = "pending|$name"

  private fun slowKey(name: String) = "slow|$name"

  private fun okKey(name: String) = "ok|$name"

  private fun parse(raw: String): Map<String, String> =
    raw.split(';').mapNotNull { part ->
      val i = part.indexOf('=')
      if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
    }.toMap()

  private fun format(fields: Map<String, String>): String =
    fields.entries.joinToString(";") { (k, v) -> "$k=${v.replace(";", ",")}" }
}
