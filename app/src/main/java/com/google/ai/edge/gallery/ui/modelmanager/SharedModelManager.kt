package com.google.ai.edge.gallery.ui.modelmanager

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * [ALTREX] The model manager lives as long as the app process, not as long as one screen: the
 * chat (MainActivity) and the assistant panel (AskAltrexActivity) share the same list of models and
 * the same loaded model, which is never loaded twice in memory.
 */
private object AppViewModelStoreOwner : ViewModelStoreOwner {
  override val viewModelStore = ViewModelStore()
}

/** The app-wide [ModelManagerViewModel], created through Hilt from [activity] the first time. */
fun sharedModelManager(activity: ComponentActivity): ModelManagerViewModel =
  ViewModelProvider(
    AppViewModelStoreOwner.viewModelStore,
    activity.defaultViewModelProviderFactory,
    activity.defaultViewModelCreationExtras,
  )[ModelManagerViewModel::class.java]
