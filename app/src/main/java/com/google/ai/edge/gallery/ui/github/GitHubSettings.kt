package com.google.ai.edge.gallery.ui.github

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * [ALTREX] GitHub settings: the access token (encrypted at rest, never shown to a model). Forge
 * uses it to clone a private repository of github.com (« Cloner un dépôt »).
 */
object GitHubSettings {
  private const val FILE = "github_secure_prefs"
  private const val KEY_TOKEN = "token"

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

  fun getToken(context: Context): String? =
    try {
      prefs(context).getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
      null
    }

  fun saveToken(context: Context, token: String): Boolean =
    try {
      prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
      true
    } catch (e: Exception) {
      false
    }
}
