package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ALTREX] Skills and tools of the main chat. They used to be a switch ("Skills et outils"); the chat
 * and the agent are now one screen, so they are always on. Kept as an object so that the callers
 * (model loading, chat screen) stay unchanged.
 */
object ChatTools {
  private val _enabled = MutableStateFlow<Boolean?>(true)

  fun isEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

  fun enabled(@Suppress("UNUSED_PARAMETER") context: Context): StateFlow<Boolean?> = _enabled.asStateFlow()
}

val LocalShowToolPickers = compositionLocalOf { false }
