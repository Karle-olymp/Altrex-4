package com.google.ai.edge.gallery.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.ai.edge.gallery.data.Model

/**
 * [ALTREX] Diagnostic report for a GGUF model: device, native build in use, model settings and the
 * llama.cpp log of the recent loads. Shared as text so it can be sent for analysis.
 */
object LlamaDiagnostics {
  private const val MAX_CHARS = 120_000

  fun buildReport(context: Context, model: Model): String {
    val nativeLog =
      try {
        org.nehuatl.llamacpp.LlamaContext.getNativeLog()
      } catch (t: Throwable) {
        "(journal natif indisponible : ${t.message})"
      }
    val file = runCatching { java.io.File(model.getPath(context)) }.getOrNull()
    val header = buildString {
      appendLine("=== Altrex : journal llama.cpp ===")
      appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) appendLine("SoC : ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
      appendLine("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABI ${Build.SUPPORTED_ABIS.joinToString()}")
      appendLine("Version native chargée : ${org.nehuatl.llamacpp.LlamaContext.loadedVariant.ifEmpty { "(aucune)" }}")
      appendLine("Mode compatibilité processeur actif : ${org.nehuatl.llamacpp.NativeCpuOptions.forceBasicCpu}")
      appendLine("Modèle : ${model.name}")
      if (file != null) appendLine("Fichier : ${file.length()} octets")
      appendLine("Réglages : ${model.configValues}")
      model.argoraManifest?.let { appendLine("Manifeste : ${it.name} ${it.version}") }
      appendLine()
    }
    val log = if (nativeLog.length > MAX_CHARS) nativeLog.takeLast(MAX_CHARS) else nativeLog
    return header + log
  }

  fun share(context: Context, model: Model) {
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Journal llama.cpp – ${model.name}")
        putExtra(Intent.EXTRA_TEXT, buildReport(context, model))
      }
    context.startActivity(
      Intent.createChooser(intent, "Exporter le journal llama.cpp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }
}
