package com.google.ai.edge.gallery.ui.common.chat

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * [ALTREX] Content sent to Altrex from another app ("Demander à Altrex" on a text selection, or the
 * share sheet). It waits here until the chat screen takes it: the app opens the chat, then the input
 * field fills itself in (and sends it when [autoSend] is set).
 */
data class SharedRequest(
  val text: String,
  val document: AttachedDocument? = null,
  val images: List<Bitmap> = emptyList(),
  val autoSend: Boolean = false,
)

object SharedInbox {
  private val _pending = MutableStateFlow<SharedRequest?>(null)
  val pending: StateFlow<SharedRequest?> = _pending.asStateFlow()

  fun post(request: SharedRequest) {
    _pending.value = request
  }

  /** Returns the waiting request and clears it, so that it is used only once. */
  fun take(): SharedRequest? = _pending.getAndUpdate { null }
}
