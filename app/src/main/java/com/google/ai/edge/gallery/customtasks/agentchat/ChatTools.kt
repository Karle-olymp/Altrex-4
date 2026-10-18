package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ALTREX] "Skills et outils" in the main chat. Off by default: a small model answers better and
 * faster without ~600 tokens of tool descriptions in its prompt. On, the chat becomes the agent
 * (skills, MCP servers, phone actions, web search).
 */
object ChatTools {
  private const val PREFS = "altrex_chat_tools"
  private const val KEY_ENABLED = "enabled"

  private val _enabled = MutableStateFlow<Boolean?>(null)

  fun isEnabled(context: Context): Boolean =
    _enabled.value
      ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false).also {
        _enabled.value = it
      }

  fun enabled(context: Context): StateFlow<Boolean?> {
    isEnabled(context)
    return _enabled.asStateFlow()
  }

  fun setEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    _enabled.value = enabled
  }
}

/** Whether the chat input shows the skills and MCP buttons. */
val LocalShowToolPickers = compositionLocalOf { false }
