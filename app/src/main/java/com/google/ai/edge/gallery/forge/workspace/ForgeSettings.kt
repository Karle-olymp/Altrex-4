package com.google.ai.edge.gallery.forge.workspace

import com.google.ai.edge.gallery.forge.tools.FileTools
import java.io.File
import org.json.JSONObject

/**
 * [ALTREX] Forge's own settings (screen « Réglages de Forge »), in `files/forge/reglages.json`:
 * the light mode for slow local models and the limits of a turn. Null limits keep the defaults of
 * [com.google.ai.edge.gallery.forge.engine.ForgeLimits].
 */
data class ForgeSettings(
  val light: Light = Light.AUTO,
  val maxStepsCloud: Int? = null,
  val maxStepsLocal: Int? = null,
  val maxMinutes: Int? = null,
) {
  enum class Light(val label: String) {
    AUTO("Auto (modèles locaux lents)"),
    ON("Toujours (modèles locaux)"),
    OFF("Jamais"),
  }

  fun toJson(): JSONObject =
    JSONObject().put("light", light.name).also { o ->
      maxStepsCloud?.let { o.put("maxStepsCloud", it) }
      maxStepsLocal?.let { o.put("maxStepsLocal", it) }
      maxMinutes?.let { o.put("maxMinutes", it) }
    }

  companion object {
    const val MIN_STEPS = 3
    const val MAX_STEPS = 200
    const val MIN_MINUTES = 2
    const val MAX_MINUTES = 240

    fun file(workspace: ForgeWorkspace) = File(workspace.base, "reglages.json")

    fun load(workspace: ForgeWorkspace): ForgeSettings =
      runCatching {
        val f = file(workspace)
        if (!f.isFile) return ForgeSettings()
        val o = JSONObject(f.readText())
        fun opt(k: String, lo: Int, hi: Int) = if (o.has(k)) o.optInt(k).coerceIn(lo, hi) else null
        ForgeSettings(
          light = Light.entries.firstOrNull { it.name == o.optString("light") } ?: Light.AUTO,
          maxStepsCloud = opt("maxStepsCloud", MIN_STEPS, MAX_STEPS),
          maxStepsLocal = opt("maxStepsLocal", MIN_STEPS, MAX_STEPS),
          maxMinutes = opt("maxMinutes", MIN_MINUTES, MAX_MINUTES),
        )
      }.getOrDefault(ForgeSettings())

    fun save(workspace: ForgeWorkspace, s: ForgeSettings) {
      FileTools.writeAtomically(file(workspace), s.toJson().toString(2).toByteArray())
    }
  }
}
