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

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the screens show about a model's compute: the line under its name. */
data class ComputeStatus(
  /** Backend the engine runs on now, null when the model is not loaded. */
  val backend: ComputeChoice? = null,
  /** "Auto : GPU, 14 tok/s" or "CPU" (manual choice). */
  val line: String = "",
  /** Parameters in use, as "contexte 8192 (auto) · 4 threads (auto)". */
  val params: String = "",
  /** Step of a load in progress ("Mesure du GPU…"), empty otherwise. */
  val phase: String = "",
)

/**
 * [ALTREX] Glue between the pure compute logic (ComputeOptions, AutoSelector, AutoMemory,
 * AutoParamsCalculator, LastModelStore) and Android: preferences, battery, memory, CPU.
 */
object ComputeRuntime {
  private const val PREFS = "altrex_compute"

  private val _status = MutableStateFlow<Map<String, ComputeStatus>>(emptyMap())
  val status: StateFlow<Map<String, ComputeStatus>> = _status.asStateFlow()

  /** Models whose next load may measure the backends (a manual "Charger" outside a conversation). */
  private val benchmarkPermits = ConcurrentHashMap<String, Boolean>()

  // ---- Stores -------------------------------------------------------------------------------

  private class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
      // commit(): the crash guard's mark must be on disk before a driver can kill the process.
      if (value == null) prefs.edit().remove(key).commit() else prefs.edit().putString(key, value).commit()
    }
  }

  fun store(context: Context): KeyValueStore = PrefsStore(context)

  fun deviceId(): String =
    listOf(Build.FINGERPRINT ?: "", BuildConfig.VERSION_CODE.toString()).joinToString("#")

  fun memory(context: Context): AutoMemory = AutoMemory(store(context), deviceId())

  fun lastModel(context: Context): LastModelStore = LastModelStore(store(context))

  fun soc(): SocKind =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      SocKind.from(Build.SOC_MANUFACTURER ?: "", Build.SOC_MODEL ?: "")
    } else {
      SocKind.from(Build.HARDWARE ?: "", Build.BOARD ?: "")
    }

  fun fingerprint(context: Context, model: Model): ModelFingerprint {
    val file = File(model.getPath(context))
    return ModelFingerprint(
      modelName = model.name,
      fileBytes = if (file.exists()) file.length() else model.sizeInBytes,
      fileModifiedMs = if (file.exists()) file.lastModified() else 0L,
      modelVersion = model.version,
    )
  }

  // ---- Options --------------------------------------------------------------------------------

  /** Backends of [model] (no AUTO), from its engine and what it declares. */
  fun backends(model: Model): List<ComputeChoice> =
    ComputeOptions.backendsFor(model.runtimeType, model.accelerators)

  fun options(model: Model): List<ComputeOption> =
    ComputeOptions.optionsFor(model.runtimeType, model.accelerators, soc())

  fun hasLocalCompute(model: Model): Boolean =
    model.runtimeType == RuntimeType.LLAMA_CPP || model.runtimeType == RuntimeType.LITERT_LM

  /** The user's choice for [model] (AUTO when unset or no longer offered). */
  fun choice(model: Model): ComputeChoice =
    ComputeOptions.resolve(
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = ComputeChoice.AUTO.label),
      backends(model),
    )

  fun title(choice: ComputeChoice): String = ComputeOptions.title(choice, soc())

  // ---- Device state -----------------------------------------------------------------------------

  fun deviceState(context: Context): DeviceState {
    val battery =
      try {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      } catch (e: Exception) {
        null
      }
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return DeviceState(
      batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
      charging = plugged,
      powerSave = pm?.isPowerSaveMode == true,
    )
  }

  fun memorySnapshot(context: Context): MemorySnapshot {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return MemorySnapshot(info.totalMem, info.availMem, info.threshold)
  }

  fun cpuMaxFreqs(): List<Long> {
    val cores = Runtime.getRuntime().availableProcessors()
    return (0 until cores).map { i ->
      try {
        File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
      } catch (e: Exception) {
        0L
      }
    }
  }

  /** The maximal context of [model]: the largest value of its context setting. */
  fun maxContext(model: Model): Int {
    val config: Config? = model.configs.firstOrNull { it.key == ConfigKeys.MAX_TOKENS }
    return (config as? NumberSliderConfig)?.sliderMax?.toInt()
      ?: model.llmMaxToken.takeIf { it > 0 }
      ?: AutoParamsCalculator.PREFERRED_MAX_CONTEXT
  }

  /** Auto parameters for [model] now. */
  fun autoParams(context: Context, model: Model): AutoParams {
    val file = File(model.getPath(context))
    val bytes = if (file.exists()) file.length() else model.sizeInBytes
    return AutoParamsCalculator.compute(
      mem = memorySnapshot(context),
      fileBytes = bytes,
      maxContext = maxContext(model),
      cpuCores = Runtime.getRuntime().availableProcessors(),
      cpuMaxFreqs = cpuMaxFreqs(),
    )
  }

  // ---- Benchmark permission ---------------------------------------------------------------------

  /** A manual load: the next initialization of [modelName] may measure the backends. */
  fun permitBenchmark(modelName: String, conversationActive: Boolean) {
    if (conversationActive) benchmarkPermits.remove(modelName) else benchmarkPermits[modelName] = true
  }

  /** Consumed by the engine helper: true once after [permitBenchmark] outside a conversation. */
  fun takeBenchmarkPermit(modelName: String): Boolean = benchmarkPermits.remove(modelName) == true

  // ---- Status ---------------------------------------------------------------------------------

  fun setPhase(modelName: String, phase: String) {
    _status.update { it + (modelName to (it[modelName] ?: ComputeStatus()).copy(phase = phase)) }
  }

  fun setLoaded(modelName: String, backend: ComputeChoice, line: String, params: String) {
    _status.update { it + (modelName to ComputeStatus(backend = backend, line = line, params = params)) }
  }

  fun setUnloaded(modelName: String) {
    _status.update { cur ->
      val prev = cur[modelName] ?: return@update cur
      // The line ("Auto : GPU, 14 tok/s") stays: it says what the next load will use.
      cur + (modelName to prev.copy(backend = null, phase = "", params = ""))
    }
  }

  /** Label of the backend in use for the chat's stats ("GPU"), or the stored choice. */
  fun activeLabel(model: Model): String =
    _status.value[model.name]?.backend?.let { title(it) }
      ?: model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")

  /** The line for a model that is not loaded: what the next load will do. */
  fun previewLine(context: Context, model: Model): String {
    if (!hasLocalCompute(model)) return ""
    val choice = choice(model)
    if (choice != ComputeChoice.AUTO) return "Calcul : ${title(choice)}"
    val backends = backends(model)
    if (backends.size == 1) return "Auto : ${title(backends[0])}"
    val rec =
      try {
        memory(context).recall(fingerprint(context, model))
      } catch (e: Exception) {
        null
      }
    return if (rec != null) AutoSelector.explain(title(rec.backend), rec.tokensPerSecond)
    else "Auto : mesure au prochain chargement"
  }

  // ---- Per-model settings (kept between app runs) -------------------------------------------------

  private val PERSISTED_KEYS =
    listOf(ConfigKeys.ACCELERATOR, ConfigKeys.CONTEXT_AUTO, ConfigKeys.MAX_TOKENS, ConfigKeys.NUM_THREADS)

  fun saveModelSettings(context: Context, model: Model) {
    if (!hasLocalCompute(model)) return
    val s = store(context)
    for (key in PERSISTED_KEYS) {
      if (model.configs.none { it.key == key }) continue
      val v = model.configValues[key.label] ?: continue
      s.put("cfg|${model.name}|${key.label}", v.toString())
    }
  }

  /** Puts the saved settings back into [model]'s values (after preProcess reset them). */
  fun restoreModelSettings(context: Context, model: Model) {
    if (!hasLocalCompute(model)) return
    val s = store(context)
    val values = model.configValues.toMutableMap()
    for (key in PERSISTED_KEYS) {
      val config = model.configs.firstOrNull { it.key == key } ?: continue
      val raw = s.get("cfg|${model.name}|${key.label}") ?: continue
      val v: Any? =
        when (config.defaultValue) {
          is Boolean -> raw.toBooleanStrictOrNull()
          is Float -> raw.toFloatOrNull()
          is Int -> raw.toFloatOrNull()?.toInt()
          is String -> raw
          else -> null
        }
      if (v != null) values[key.label] = v
    }
    // A choice no longer offered (GPU left on a GGUF model) reads as Auto.
    if (model.configs.any { it.key == ConfigKeys.ACCELERATOR }) {
      val resolved = ComputeOptions.resolve(values[ConfigKeys.ACCELERATOR.label]?.toString(), backends(model))
      values[ConfigKeys.ACCELERATOR.label] = resolved.label
    }
    model.configValues = values
  }
}
