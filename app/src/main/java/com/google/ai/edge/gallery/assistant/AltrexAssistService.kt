package com.google.ai.edge.gallery.assistant

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.google.ai.edge.gallery.AskAltrexActivity

/**
 * [ALTREX] Makes Altrex selectable as the phone's digital assistant (Settings > Apps > Default apps >
 * Digital assistant app; on Samsung, Side key > Press and hold). The assistant opens the Altrex sheet
 * over the current app, with the text shown on screen when the user allows it.
 */
class AltrexAssistService : VoiceInteractionService()

class AltrexAssistSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession = AltrexAssistSession(this)
}

class AltrexAssistSession(context: Context) : VoiceInteractionSession(context) {
  private var launched = false
  private val handler = Handler(Looper.getMainLooper())

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    launched = false
    // Without screen context (not allowed in the assistant settings), open right away; otherwise
    // wait for onHandleAssist, which brings the screen text.
    if (showFlags and SHOW_WITH_ASSIST == 0) {
      launchSheet(screenText = "")
    } else {
      // Some phones never deliver the screen content (setting off, secure app): open anyway.
      handler.postDelayed({ launchSheet(screenText = "") }, SCREEN_WAIT_MS)
    }
  }

  override fun onHide() {
    handler.removeCallbacksAndMessages(null)
    super.onHide()
  }

  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    if (launched) return
    launchSheet(screenText = state.assistStructure?.let(::collectText).orEmpty())
  }

  private fun launchSheet(screenText: String) {
    if (launched) return
    launched = true
    handler.removeCallbacksAndMessages(null)
    startAssistantActivity(
      Intent(context, AskAltrexActivity::class.java)
        .setAction(AskAltrexActivity.ACTION_ASSIST_SHEET)
        .putExtra(AskAltrexActivity.EXTRA_SCREEN_TEXT, screenText)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    hide()
  }

  /** Visible text of the app under the assistant, in reading order, capped. */
  private fun collectText(structure: AssistStructure): String {
    val out = StringBuilder()
    fun walk(node: AssistStructure.ViewNode) {
      if (out.length >= MAX_SCREEN_CHARS) return
      node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append('\n') }
      for (i in 0 until node.childCount) walk(node.getChildAt(i))
    }
    for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)
    return out.toString().take(MAX_SCREEN_CHARS).trim()
  }

  private companion object {
    const val MAX_SCREEN_CHARS = 8_000
    const val SCREEN_WAIT_MS = 1_200L
  }
}

/**
 * A voice interaction service must declare a speech recognizer. Altrex has no voice input of its
 * own yet: this one reports that recognition is not available.
 */
class AltrexRecognitionService : RecognitionService() {
  override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
    listener?.error(SpeechRecognizer.ERROR_CLIENT)
  }

  override fun onCancel(listener: Callback?) {}

  override fun onStopListening(listener: Callback?) {}
}
