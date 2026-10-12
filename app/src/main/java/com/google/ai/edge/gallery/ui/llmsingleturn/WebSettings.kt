package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings of the web tools: the Brave Search API key (encrypted at rest, never sent to the model)
 * and a daily cap on searches, enforced by code, to protect the monthly Brave credit.
 */
object WebSettings {
  private const val FILE = "web_secure_prefs"
  private const val KEY_BRAVE = "brave_api_key"
  private const val KEY_DAY = "search_day"
  private const val KEY_COUNT = "search_count"

  /** Maximum number of web searches per day. */
  const val DAILY_LIMIT = 30

  /** Set by the search tool when no key is stored; observed by the UI. */
  val keyDialogRequest = mutableStateOf(false)

  private fun prefs(context: Context): SharedPreferences {
    val app = context.applicationContext
    val masterKey = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
      app,
      FILE,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  fun getBraveKey(context: Context): String? =
    try {
      prefs(context).getString(KEY_BRAVE, null)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
      null
    }

  fun saveBraveKey(context: Context, key: String): Boolean =
    try {
      prefs(context).edit().putString(KEY_BRAVE, key.trim()).apply()
      true
    } catch (e: Exception) {
      false
    }

  /** Counts one search for today. Returns false when the daily limit is already reached. */
  @Synchronized
  fun takeSearchQuota(context: Context): Boolean =
    try {
      val p = prefs(context)
      val today = java.time.LocalDate.now().toString()
      val count = if (p.getString(KEY_DAY, "") == today) p.getInt(KEY_COUNT, 0) else 0
      if (count >= DAILY_LIMIT) {
        false
      } else {
        p.edit().putString(KEY_DAY, today).putInt(KEY_COUNT, count + 1).apply()
        true
      }
    } catch (e: Exception) {
      false
    }
}
