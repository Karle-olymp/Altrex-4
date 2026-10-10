package com.google.ai.edge.gallery.ui.home

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS = "altrex_exit_reports"
private const val KEY_LAST_SHOWN = "last_shown_timestamp"

/**
 * When the previous run of the app ended badly (killed for lack of memory, native crash...), say so
 * once at the next launch. Android keeps the exact reason of the last process deaths, which is the
 * quickest way to tell a memory kill from a real bug without any debugging tool.
 */
@Composable
fun LastExitDialog() {
  val context = LocalContext.current
  var report by remember { mutableStateOf<Pair<String, Long>?>(null) }

  LaunchedEffect(Unit) {
    report = withContext(Dispatchers.IO) { describeLastAbnormalExit(context) }
  }

  val current = report
  if (current != null) {
    val dismiss = {
      context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_LAST_SHOWN, current.second)
        .apply()
      report = null
    }
    AlertDialog(
      onDismissRequest = { dismiss() },
      title = { Text("Altrex s'est arrêté") },
      text = { Text(current.first) },
      confirmButton = { TextButton(onClick = { dismiss() }) { Text("OK") } },
    )
  }
}

private fun describeLastAbnormalExit(context: Context): Pair<String, Long>? {
  return try {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
      ?: return null
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (info.timestamp <= prefs.getLong(KEY_LAST_SHOWN, 0L)) return null

    val rssMb = info.rss / 1024L
    val detail = info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
    val text =
      when (info.reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY ->
          "Android a fermé l'application faute de mémoire (environ $rssMb Mo utilisés). Le modèle " +
            "chargé est trop lourd pour la RAM disponible : choisis un modèle plus petit ou un " +
            "contexte plus court."
        ApplicationExitInfo.REASON_CRASH_NATIVE ->
          "Plantage dans le moteur natif (llama.cpp ou LiteRT)$detail. Ce n'est pas un manque de " +
            "mémoire : envoie-moi ce message."
        ApplicationExitInfo.REASON_CRASH -> "Plantage de l'application$detail."
        ApplicationExitInfo.REASON_ANR -> "L'application ne répondait plus$detail."
        ApplicationExitInfo.REASON_SIGNALED ->
          "L'application a été arrêtée par le système (signal ${info.status})$detail. Si cela " +
            "s'est produit pendant le chargement d'un gros modèle, c'est très probablement un " +
            "manque de mémoire."
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
          "Le système a arrêté l'application pour usage excessif de ressources$detail."
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
          "Échec du démarrage de l'application$detail."
        else -> null
      }
    if (text == null) {
      // Normal exits (user swipe, update...) are not reported: just remember we saw this one.
      prefs.edit().putLong(KEY_LAST_SHOWN, info.timestamp).apply()
      null
    } else {
      text to info.timestamp
    }
  } catch (e: Exception) {
    null
  }
}
