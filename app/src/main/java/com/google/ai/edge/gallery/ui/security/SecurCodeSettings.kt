package com.google.ai.edge.gallery.ui.security

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** A pending request from the scan tool: the user must approve sending this project. */
data class ConsentRequest(val projectId: String, val label: String)

/**
 * Settings of the SecurCode integration: the API key (encrypted at rest) and the per-project
 * consent to send source code to SecurCode. Consent is enforced by code, never by the model.
 */
object SecurCodeSettings {
  private const val FILE = "securcode_secure_prefs"
  private const val KEY_API = "api_key"
  private const val KEY_CONSENT = "consented_projects"

  /** Set by the scan tool when the project has not been approved yet; observed by the UI. */
  val pendingConsent = mutableStateOf<ConsentRequest?>(null)

  /** Set by the scan tool when no API key is stored; observed by the UI. */
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

  fun getApiKey(context: Context): String? =
    try {
      prefs(context).getString(KEY_API, null)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
      null
    }

  fun saveApiKey(context: Context, key: String): Boolean =
    try {
      prefs(context).edit().putString(KEY_API, key.trim()).apply()
      true
    } catch (e: Exception) {
      false
    }

  fun isConsented(context: Context, projectId: String): Boolean =
    try {
      prefs(context).getStringSet(KEY_CONSENT, emptySet())?.contains(projectId) == true
    } catch (e: Exception) {
      false
    }

  fun grantConsent(context: Context, projectId: String): Boolean =
    try {
      val p = prefs(context)
      val set = HashSet<String>(p.getStringSet(KEY_CONSENT, emptySet()) ?: emptySet())
      set.add(projectId)
      p.edit().putStringSet(KEY_CONSENT, set).apply()
      true
    } catch (e: Exception) {
      false
    }
}
