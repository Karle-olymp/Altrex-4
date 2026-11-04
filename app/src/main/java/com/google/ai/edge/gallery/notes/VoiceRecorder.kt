package com.google.ai.edge.gallery.notes

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where speech recognition stands, before recording. */
sealed class RecognizerAvailability {
  data object Checking : RecognizerAvailability()

  /** On-device recognition, language installed: nothing leaves the phone. */
  data object OnDevice : RecognizerAvailability()

  /** On-device recognition exists but this language must be downloaded first. */
  data class LanguageDownloadable(val language: String) : RecognizerAvailability()

  /** The language pack is being downloaded by the system. */
  data class LanguageDownloading(val language: String) : RecognizerAvailability()

  /** No on-device recognition for this language (or at all): only the system service is left. */
  data class OnDeviceUnavailable(val reason: String, val systemAvailable: Boolean) : RecognizerAvailability()
}

/** The recording, as the screen shows it. */
data class RecordingState(
  val recording: Boolean = false,
  /** Paused by the user (the text is kept; "Reprendre" goes on). */
  val paused: Boolean = false,
  /** Text recognized and final. */
  val committed: String = "",
  /** Words being recognized, not final yet. */
  val partial: String = "",
  /** Voice level, 0..1. */
  val level: Float = 0f,
  val elapsedMs: Long = 0L,
  /** A problem to show (in French), or null. */
  val message: String? = null,
  /** True when the system recognizer (maybe online) is used instead of the on-device one. */
  val usingSystemService: Boolean = false,
) {
  val fullText: String
    get() = join(committed, partial)

  companion object {
    fun join(a: String, b: String): String =
      when {
        a.isBlank() -> b.trim()
        b.isBlank() -> a.trim()
        else -> a.trimEnd() + " " + b.trim()
      }
  }
}

/**
 * [ALTREX] Continuous dictation for the voice notes, on Android's SpeechRecognizer.
 *
 * - On-device recognizer (createOnDeviceSpeechRecognizer) first, in the phone's language. When it
 *   is missing or lacks the language, the screen says so plainly; the system recognizer (which may
 *   send audio to its provider) is used only if the user explicitly picks it.
 * - Keeps listening through pauses: on Android 13+ a segmented session (one long session, a result
 *   per sentence); otherwise, and whenever a session ends or fails in a recoverable way, it is
 *   restarted, with a growing delay after repeated errors so it never spins.
 * - The audio is never stored, only the text, which [onTextChanged] receives as it grows (for the
 *   draft that protects the note from a crash).
 *
 * Main thread only (SpeechRecognizer's rule). Never logs what was said.
 */
class VoiceRecorder(
  private val context: Context,
  private val onTextChanged: (String) -> Unit = {},
) {
  private val main = Handler(Looper.getMainLooper())
  private val mainExecutor = Executor { main.post(it) }

  private val _availability = MutableStateFlow<RecognizerAvailability>(RecognizerAvailability.Checking)
  val availability: StateFlow<RecognizerAvailability> = _availability.asStateFlow()

  private val _state = MutableStateFlow(RecordingState())
  val state: StateFlow<RecordingState> = _state.asStateFlow()

  private var recognizer: SpeechRecognizer? = null
  private var useSystemService = false
  private var startedAt = 0L
  private var accumulatedMs = 0L
  private var consecutiveErrors = 0
  private var sessionHadSpeech = false
  private var restartPending = false

  /** Language of the recognition: the phone's (French if none). */
  val language: String =
    Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "fr-FR"

  private val ticker =
    object : Runnable {
      override fun run() {
        val s = _state.value
        if (s.recording && !s.paused) {
          _state.update { it.copy(elapsedMs = accumulatedMs + SystemClock.elapsedRealtime() - startedAt) }
          main.postDelayed(this, 500)
        }
      }
    }

  /** Checks what recognition is possible for [language], on the device first. */
  fun checkAvailability() {
    _availability.value = RecognizerAvailability.Checking
    val systemAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
      _availability.value =
        RecognizerAvailability.OnDeviceUnavailable(
          "La reconnaissance vocale sur l'appareil n'est pas disponible sur ce téléphone.",
          systemAvailable,
        )
      return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      // No way to ask which languages are installed: try, and report a language error if it comes.
      _availability.value = RecognizerAvailability.OnDevice
      return
    }
    val probe =
      try {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      } catch (e: Exception) {
        null
      }
    if (probe == null) {
      _availability.value = RecognizerAvailability.OnDevice
      return
    }
    try {
      probe.checkRecognitionSupport(
        intent(),
        mainExecutor,
        object : RecognitionSupportCallback {
          override fun onSupportResult(support: RecognitionSupport) {
            _availability.value = availabilityFrom(support, systemAvailable)
            probe.destroy()
          }

          override fun onError(error: Int) {
            // Unknown: assume it works; a language error during recognition is still reported.
            _availability.value = RecognizerAvailability.OnDevice
            probe.destroy()
          }
        },
      )
    } catch (e: Exception) {
      probe.destroy()
      _availability.value = RecognizerAvailability.OnDevice
    }
  }

  private fun availabilityFrom(support: RecognitionSupport, systemAvailable: Boolean): RecognizerAvailability {
    fun has(list: List<String>) = list.any { matchesLanguage(it, language) }
    return when {
      has(support.installedOnDeviceLanguages) -> RecognizerAvailability.OnDevice
      has(support.pendingOnDeviceLanguages) -> RecognizerAvailability.LanguageDownloading(displayLanguage())
      has(support.supportedOnDeviceLanguages) -> RecognizerAvailability.LanguageDownloadable(displayLanguage())
      else ->
        RecognizerAvailability.OnDeviceUnavailable(
          "La reconnaissance sur l'appareil ne prend pas en charge la langue « ${displayLanguage()} ».",
          systemAvailable,
        )
    }
  }

  /** Asks the system to download the language pack (Android 13+). */
  fun downloadLanguage() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    try {
      val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      r.triggerModelDownload(intent())
      _availability.value = RecognizerAvailability.LanguageDownloading(displayLanguage())
      main.postDelayed({ r.destroy() }, 2_000)
    } catch (e: Exception) {
      Log.w(TAG, "Language download could not be started: ${e.javaClass.simpleName}")
    }
  }

  /** The user accepted the system recognizer (which may go through its provider's servers). */
  fun allowSystemService() {
    useSystemService = true
  }

  fun start() {
    if (_state.value.recording) return
    _state.value = RecordingState(recording = true, usingSystemService = useSystemService)
    accumulatedMs = 0L
    startedAt = SystemClock.elapsedRealtime()
    consecutiveErrors = 0
    listen()
    main.post(ticker)
  }

  /** Resumes after a draft was recovered: the recognized text starts from [text]. */
  fun startWith(text: String) {
    start()
    _state.update { it.copy(committed = text) }
  }

  fun pause() {
    val s = _state.value
    if (!s.recording || s.paused) return
    accumulatedMs += SystemClock.elapsedRealtime() - startedAt
    // What was being said is kept as final text.
    _state.update { it.copy(paused = true, committed = it.fullText, partial = "", level = 0f) }
    stopRecognizer()
    onTextChanged(_state.value.committed)
  }

  fun resume() {
    val s = _state.value
    if (!s.recording || !s.paused) return
    startedAt = SystemClock.elapsedRealtime()
    consecutiveErrors = 0
    _state.update { it.copy(paused = false, message = null) }
    listen()
    main.post(ticker)
  }

  /** Stops and returns everything recognized (final text and words in progress). */
  fun finish(): String {
    val text = _state.value.fullText
    stopRecognizer()
    main.removeCallbacks(ticker)
    _state.value = RecordingState()
    return text
  }

  fun release() {
    main.removeCallbacksAndMessages(null)
    stopRecognizer()
    _state.value = RecordingState()
  }

  // --- Recognition loop ----------------------------------------------------------------------

  private fun intent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
      // Long pauses are part of thinking aloud: wait before calling it the end.
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
    }

  private fun createRecognizer(): SpeechRecognizer? =
    try {
      if (useSystemService) SpeechRecognizer.createSpeechRecognizer(context)
      else SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } catch (e: Exception) {
      Log.w(TAG, "Recognizer creation failed: ${e.javaClass.simpleName}")
      null
    }

  private fun listen() {
    restartPending = false
    val s = _state.value
    if (!s.recording || s.paused) return
    val r = recognizer ?: createRecognizer()?.also { it.setRecognitionListener(Listener(it)) }
    if (r == null) {
      _state.update { it.copy(message = "La reconnaissance vocale n'a pas pu démarrer.") }
      return
    }
    recognizer = r
    sessionHadSpeech = false
    val intent = intent()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !useSystemService) {
      // One long session that ends only when we stop it, with a result after each sentence.
      intent.putExtra(
        RecognizerIntent.EXTRA_SEGMENTED_SESSION,
        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
      )
      intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60L * 60 * 1000)
    }
    try {
      r.startListening(intent)
    } catch (e: Exception) {
      Log.w(TAG, "startListening failed: ${e.javaClass.simpleName}")
      scheduleRestart(recreate = true)
    }
  }

  private fun stopRecognizer() {
    restartPending = false
    val r = recognizer ?: return
    recognizer = null
    try {
      r.cancel()
    } catch (_: Exception) {}
    try {
      r.destroy()
    } catch (_: Exception) {}
  }

  private fun scheduleRestart(recreate: Boolean = false) {
    val s = _state.value
    if (!s.recording || s.paused || restartPending) return
    if (recreate) stopRecognizer()
    restartPending = true
    // Right away after a normal end; slower and slower after errors in a row.
    val delay = if (consecutiveErrors == 0) 120L else minOf(250L * (1 shl minOf(consecutiveErrors, 5)), 8_000L)
    main.postDelayed({ if (restartPending) listen() }, delay)
  }

  private var lastSegment = ""

  private fun commit(text: String?) {
    val t = text?.trim().orEmpty()
    lastSegment = t
    _state.update { it.copy(committed = RecordingState.join(it.committed, t), partial = "") }
    if (t.isNotEmpty()) onTextChanged(_state.value.committed)
  }

  /** Events of one recognizer; those of a recognizer already replaced are ignored. */
  private inner class Listener(private val owner: SpeechRecognizer) : RecognitionListener {
      private val stale: Boolean
        get() = owner !== recognizer

      override fun onReadyForSpeech(params: Bundle?) {
        if (stale) return
        _state.update { it.copy(message = null) }
      }

      override fun onBeginningOfSpeech() {
        if (stale) return
        sessionHadSpeech = true
      }

      override fun onRmsChanged(rmsdB: Float) {
        if (stale) return
        val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        _state.update { it.copy(level = it.level * 0.5f + level * 0.5f) }
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {}

      override fun onPartialResults(partialResults: Bundle?) {
        if (stale) return
        val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        _state.update { it.copy(partial = p) }
      }

      override fun onResults(results: Bundle?) {
        if (stale) return
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        // After a segmented session, some recognizers repeat the last segment here.
        if (text.isNullOrBlank() || text.trim() != lastSegment) commit(text) else commit(null)
        consecutiveErrors = 0
        scheduleRestart()
      }

      override fun onSegmentResults(segmentResults: Bundle) {
        if (stale) return
        commit(segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
        consecutiveErrors = 0
      }

      override fun onEndOfSegmentedSession() {
        if (stale) return
        commit(_state.value.partial)
        scheduleRestart()
      }

      override fun onError(error: Int) {
        if (stale) return
        // Words heard before the error are kept.
        if (_state.value.partial.isNotBlank()) commit(_state.value.partial)
        when (error) {
          SpeechRecognizer.ERROR_NO_MATCH,
          SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
            // Silence: normal while thinking. Keep listening.
            consecutiveErrors = if (sessionHadSpeech) 0 else minOf(consecutiveErrors + 1, 2)
            scheduleRestart()
          }
          SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
            _state.update { it.copy(message = "L'accès au micro a été refusé.") }
            pause()
          }
          SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
          SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
            _state.update {
              it.copy(message = "La langue « ${displayLanguage()} » n'est pas installée pour la reconnaissance sur l'appareil.")
            }
            _availability.value =
              RecognizerAvailability.OnDeviceUnavailable(
                "La langue « ${displayLanguage()} » n'est pas disponible pour la reconnaissance sur l'appareil.",
                SpeechRecognizer.isRecognitionAvailable(context),
              )
            pause()
          }
          SpeechRecognizer.ERROR_NETWORK,
          SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
            consecutiveErrors++
            _state.update { it.copy(message = "Connexion nécessaire au service de reconnaissance : nouvel essai…") }
            scheduleRestart()
          }
          else -> {
            // Busy, client, server disconnected, audio...: start over with a fresh recognizer.
            consecutiveErrors++
            if (consecutiveErrors >= MAX_ERRORS_IN_A_ROW) {
              _state.update { it.copy(message = "La reconnaissance s'est arrêtée (erreur $error). Ton texte est gardé.") }
              pause()
            } else {
              scheduleRestart(recreate = true)
            }
          }
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }

  private fun displayLanguage(): String =
    Locale.forLanguageTag(language).getDisplayLanguage(Locale.FRENCH).ifBlank { language }

  companion object {
    private const val TAG = "AGVoiceNotes"
    private const val MAX_ERRORS_IN_A_ROW = 8

    /** "fr-FR" matches "fr-FR", "fr" and "fr-CA" (the language decides, not the region). */
    fun matchesLanguage(available: String, wanted: String): Boolean {
      val a = available.lowercase().replace('_', '-')
      val w = wanted.lowercase().replace('_', '-')
      return a == w || a.substringBefore('-') == w.substringBefore('-')
    }
  }
}
