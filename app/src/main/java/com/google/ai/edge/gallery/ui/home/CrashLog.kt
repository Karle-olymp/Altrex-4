package com.google.ai.edge.gallery.ui.home

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.security.SecretRedactor
import java.io.File

/**
 * [ALTREX] Keeps the stack trace of the last crash of the app (Kotlin/Java exceptions), so that the
 * next launch can show it ([LastExitDialog]) and the user can copy it. Android itself only says "it
 * crashed". The file stays in the app's private storage; secrets are masked before writing.
 */
object CrashLog {
  private const val FILE = "last_crash.txt"
  private const val MAX_CHARS = 6_000

  fun install(context: Context) {
    val file = File(context.applicationContext.filesDir, FILE)
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      try {
        val text = "Fil : ${thread.name}\n" + Log.getStackTraceString(error)
        file.writeText(SecretRedactor.redact(text).take(MAX_CHARS))
      } catch (_: Throwable) {
        // Nothing more can be done while crashing.
      }
      previous?.uncaughtException(thread, error)
    }
  }

  /** The saved trace and when it was written, or null; [consume] deletes it. */
  fun read(context: Context, consume: Boolean): Pair<String, Long>? {
    val file = File(context.filesDir, FILE)
    if (!file.exists()) return null
    val result = runCatching { file.readText() to file.lastModified() }.getOrNull()
    if (consume) file.delete()
    return result
  }
}
