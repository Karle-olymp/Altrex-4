package com.google.ai.edge.gallery.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * [ALTREX] Keeps the screen on while [active] (an answer being generated), and only then: the app
 * used to keep it on all the time, which drained the battery.
 */
@Composable
fun KeepScreenOn(active: Boolean) {
  val view = LocalView.current
  DisposableEffect(view, active) {
    if (active) view.keepScreenOn = true
    onDispose { if (active) view.keepScreenOn = false }
  }
}
