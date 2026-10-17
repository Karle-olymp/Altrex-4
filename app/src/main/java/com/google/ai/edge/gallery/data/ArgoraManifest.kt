package com.google.ai.edge.gallery.data

import org.json.JSONObject

/** [ALTREX] extraContext key naming the Argora mode (manifest "modes") of a generation. */
const val ARGORA_MODE_KEY = "argora_mode"

/**
 * [ALTREX] argora-manifest.json (schema 1), shipped next to the GGUF files of an Argora version by
 * the training pipeline (dépôt karle-olymp/argora, scripts/make_manifest.py). It gives the exact
 * system prompt, the settings of each mode and the ready-made tasks.
 */
data class ArgoraManifest(
  val name: String,
  val version: String,
  val systemPrompt: String,
  val defaultMode: String,
  val modes: Map<String, ArgoraMode>,
  val tasks: List<ArgoraTask>,
  val recommendedContext: Int,
  val maxContext: Int,
  val fileNames: List<String>,
) {
  val defaultModeSettings: ArgoraMode?
    get() = modes[defaultMode] ?: modes.values.firstOrNull()

  /** Mode used when the user turns thinking on in the chat. */
  val thinkingMode: ArgoraMode?
    get() = modes["reflexion"] ?: modes.values.firstOrNull { it.thinking }

  fun systemPromptFor(mode: ArgoraMode?): String =
    if (mode?.systemSuffix.isNullOrBlank()) systemPrompt else "$systemPrompt\n\n${mode!!.systemSuffix}"

  companion object {
    const val SUPPORTED_SCHEMA = 1

    /** Returns null when [json] is not a manifest this version understands. */
    fun parse(json: String): ArgoraManifest? {
      if (json.isBlank()) return null
      return try {
        val root = JSONObject(json)
        if (root.optInt("schema", 0) != SUPPORTED_SCHEMA) return null
        val systemPrompt = root.optString("system_prompt")
        if (systemPrompt.isBlank()) return null
        val modes = LinkedHashMap<String, ArgoraMode>()
        root.optJSONObject("modes")?.let { obj ->
          for (id in obj.keys()) {
            val m = obj.optJSONObject(id) ?: continue
            modes[id] =
              ArgoraMode(
                id = id,
                label = m.optString("label", id),
                thinking = m.optBoolean("thinking", false),
                temperature = m.optDouble("temperature", 0.7).toFloat(),
                topP = m.optDouble("top_p", 0.8).toFloat(),
                topK = m.optInt("top_k", 20),
                repeatPenalty = m.optDouble("repeat_penalty", 1.1).toFloat(),
                thinkingBudget = m.optInt("thinking_budget", 0),
                systemSuffix = m.optString("system_suffix", ""),
              )
          }
        }
        val tasks = ArrayList<ArgoraTask>()
        root.optJSONArray("tasks")?.let { arr ->
          for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val prompt = t.optString("prompt")
            if (prompt.isBlank()) continue
            val extensions = ArrayList<String>()
            t.optJSONArray("extensions")?.let { e ->
              for (j in 0 until e.length()) extensions.add(e.optString(j))
            }
            tasks.add(
              ArgoraTask(
                id = t.optString("id", "task_$i"),
                label = t.optString("label", prompt),
                mode = t.optString("mode", "analyse"),
                extensions = extensions,
                prompt = prompt,
              )
            )
          }
        }
        val fileNames = ArrayList<String>()
        root.optJSONArray("files")?.let { arr ->
          for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("file")?.let(fileNames::add)
        }
        val context = root.optJSONObject("context")
        ArgoraManifest(
          name = root.optString("name", "Argora"),
          version = root.optString("version", ""),
          systemPrompt = systemPrompt,
          defaultMode = root.optString("default_mode", "normal"),
          modes = modes,
          tasks = tasks,
          recommendedContext = context?.optInt("recommended", 8192) ?: 8192,
          maxContext = context?.optInt("max", 16384) ?: 16384,
          fileNames = fileNames,
        )
      } catch (e: Exception) {
        null
      }
    }
  }
}

data class ArgoraMode(
  val id: String,
  val label: String,
  val thinking: Boolean,
  val temperature: Float,
  val topP: Float,
  val topK: Int,
  val repeatPenalty: Float,
  val thinkingBudget: Int,
  val systemSuffix: String,
)

data class ArgoraTask(
  val id: String,
  val label: String,
  val mode: String,
  val extensions: List<String>,
  val prompt: String,
)
