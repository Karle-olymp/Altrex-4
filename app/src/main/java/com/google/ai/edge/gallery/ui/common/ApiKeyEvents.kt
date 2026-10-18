package com.google.ai.edge.gallery.ui.common

import androidx.compose.runtime.mutableIntStateOf

/**
 * [ALTREX] Tells the screens that show an API key status (web search bar, security bar) that a key
 * was added, changed or deleted in the Settings.
 */
object ApiKeyEvents {
  val version = mutableIntStateOf(0)

  fun changed() {
    version.intValue++
  }
}
