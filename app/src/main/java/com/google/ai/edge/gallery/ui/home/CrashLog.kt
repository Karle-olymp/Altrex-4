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
  private const val MAX_CHARS = 12_000

  fun install(context: Context) {
    val file = File(context.applicationContext.filesDir, FILE)
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      // A failed clean-up of an abandoned network call must not close the app (see below).
      if (isIgnorable(thread.name, error)) {
        Log.w("AGCrashLog", "Background error ignored on ${thread.name}", error)
        return@setDefaultUncaughtExceptionHandler
      }
      try {
        val text = "Fil : ${thread.name}\n" + Log.getStackTraceString(error)
        file.writeText(SecretRedactor.redact(text).take(MAX_CHARS))
      } catch (_: Throwable) {
        // Nothing more can be done while crashing.
      }
      previous?.uncaughtException(thread, error)
    }
  }

  /**
   * Errors thrown while a coroutine library cancels a call nobody waits for any more (typically an
   * MCP server that never answered: its 60 s timer cancels the network call, whose clean-up then
   * throws on the library's timer thread, where nothing can catch it). Nothing of ours is in a
   * broken state then, so the app keeps running; every other crash still goes through.
   */
  internal fun isIgnorable(threadName: String, error: Throwable): Boolean =
    threadName.startsWith("kotlinx.coroutines.DefaultExecutor") &&
      generateSequence(error) { it.cause }.take(8).any { it is kotlinx.coroutines.CompletionHandlerException }

  /**
   * The part of a trace worth reading: its first lines, then every "Caused by" with what follows,
   * since the real cause is at the bottom of the chain.
   */
  internal fun summarize(trace: String, headLines: Int = 12, maxLines: Int = 70): String {
    val lines = trace.lines()
    val keep = BooleanArray(lines.size)
    for (i in 0 until minOf(headLines, lines.size)) keep[i] = true
    for ((i, l) in lines.withIndex()) {
      if (l.startsWith("Caused by:")) for (j in i until minOf(i + 4, lines.size)) keep[j] = true
    }
    val out = ArrayList<String>()
    var skipped = false
    for ((i, l) in lines.withIndex()) {
      if (keep[i]) {
        if (skipped) out.add("\t[…]")
        skipped = false
        out.add(l)
      } else {
        skipped = true
      }
    }
    return out.take(maxLines).joinToString("\n")
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
