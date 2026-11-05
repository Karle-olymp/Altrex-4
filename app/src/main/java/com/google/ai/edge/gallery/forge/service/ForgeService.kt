package com.google.ai.edge.gallery.forge.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.ai.edge.gallery.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * [ALTREX] Keeps the app in the foreground while Forge works (docs/forge/FORGE.md §4 and §10):
 * Android may otherwise close a long turn when the screen is off. Started when a turn starts,
 * stopped when none runs. Its notification says what Forge does and offers « Arrêter ». Type
 * dataSync (the permission the app already has); Android 15 limits it to 6 h a day, far more than
 * a turn.
 */
class ForgeService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var watcher: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      ForgeRuntime.stopAll()
      return START_NOT_STICKY
    }
    ForgeNotifications.ensureChannels(this)
    val n = ForgeNotifications.working(this, ForgeRuntime.status.value)
    try {
      startForeground(ForgeNotifications.ID_WORKING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } catch (e: Exception) {
      // Not allowed now (started from the background): the turn still runs, without the shield.
      stopSelf()
      return START_NOT_STICKY
    }
    if (watcher == null) {
      watcher =
        scope.launch {
          ForgeRuntime.status.collect { s ->
            if (s.running == 0) {
              stopForeground(STOP_FOREGROUND_REMOVE)
              stopSelf()
            } else {
              ForgeNotifications.update(this@ForgeService, s)
            }
          }
        }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  companion object {
    const val ACTION_STOP = "com.aistudio.altrex.forge.STOP"

    fun start(context: Context) {
      runCatching { ContextCompat.startForegroundService(context, Intent(context, ForgeService::class.java)) }
    }
  }
}

/** [ALTREX] Forge's notifications: working (foreground), waiting for the user, finished. */
object ForgeNotifications {
  private const val CHANNEL_WORK = "forge_travail"
  private const val CHANNEL_ALERT = "forge_alertes"
  const val ID_WORKING = 7301
  private const val ID_WAITING = 7302
  private const val ID_DONE = 7303

  fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(CHANNEL_WORK) == null) {
      nm.createNotificationChannel(NotificationChannel(CHANNEL_WORK, "Forge au travail", NotificationManager.IMPORTANCE_LOW))
    }
    if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ALERT, "Forge : accord demandé, travail terminé", NotificationManager.IMPORTANCE_HIGH)
      )
    }
  }

  private fun openApp(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  fun working(context: Context, s: ForgeRuntime.Status): Notification {
    val stop =
      PendingIntent.getService(
        context,
        1,
        Intent(context, ForgeService::class.java).setAction(ForgeService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val text =
      when {
        s.waiting -> "En attente de ton accord"
        s.step.isNotEmpty() -> "Outil : ${s.step}"
        else -> "L'agent travaille…"
      }
    return NotificationCompat.Builder(context, CHANNEL_WORK)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle("Forge · ${s.project.ifEmpty { "projet" }}")
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openApp(context))
      .addAction(0, "Arrêter", stop)
      .build()
  }

  fun update(context: Context, s: ForgeRuntime.Status) {
    if (!canPost(context)) return
    runCatching { NotificationManagerCompat.from(context).notify(ID_WORKING, working(context, s)) }
  }

  private fun canPost(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  private fun inBackground(): Boolean =
    runCatching { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)

  /** Forge waits for an answer: an alert, only when the app is not on screen. */
  fun waiting(context: Context, project: String) {
    if (!canPost(context) || !inBackground()) return
    ensureChannels(context)
    val n =
      NotificationCompat.Builder(context, CHANNEL_ALERT)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Forge attend ton accord")
        .setContentText("Projet ${project.ifEmpty { "en cours" }} : sans réponse dans 5 min, c'est un refus.")
        .setAutoCancel(true)
        .setContentIntent(openApp(context))
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(ID_WAITING, n) }
  }

  /** A turn ended while the app was not on screen. */
  fun turnFinished(context: Context, project: String, end: String?) {
    runCatching { NotificationManagerCompat.from(context).cancel(ID_WAITING) }
    if (!canPost(context) || !inBackground()) return
    ensureChannels(context)
    val text =
      when (end) {
        "done" -> "Terminé."
        "stopped" -> "Arrêté."
        "limit" -> "Limite atteinte : vois où l'agent en est."
        "blocked" -> "L'agent était bloqué : il s'est arrêté."
        "error" -> "Erreur : ouvre Forge pour la voir."
        else -> "Tour fini."
      }
    val n =
      NotificationCompat.Builder(context, CHANNEL_ALERT)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Forge · $project")
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(openApp(context))
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(ID_DONE, n) }
  }
}
