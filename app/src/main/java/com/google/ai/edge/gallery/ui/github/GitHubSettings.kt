package com.google.ai.edge.gallery.ui.github

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.ai.edge.gallery.ui.llmsingleturn.ProjectFolderManager

/**
 * [ALTREX] GitHub connector settings: the access token (encrypted at rest, only ever sent to
 * api.github.com) and the folders of the project that were imported from GitHub. Those hold
 * someone else's code: the Code agent treats what it reads there as untrusted content.
 */
object GitHubSettings {
  private const val FILE = "github_secure_prefs"
  private const val KEY_TOKEN = "token"
  private const val KEY_IMPORTED = "imported_folders"

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

  private fun projectId(context: Context): String? =
    ProjectFolderManager.getSavedProjectFolder(context)?.uri?.toString()

  /** Folders of the current project imported from GitHub (paths relative to the project). */
  fun importedFolders(context: Context): Set<String> {
    val id = projectId(context) ?: return emptySet()
    return try {
      (prefs(context).getStringSet(KEY_IMPORTED, emptySet()) ?: emptySet())
        .filter { it.startsWith("$id|") }
        .map { it.substringAfter('|') }
        .toSet()
    } catch (e: Exception) {
      emptySet()
    }
  }

  fun addImportedFolder(context: Context, folder: String) {
    val id = projectId(context) ?: return
    try {
      val p = prefs(context)
      val set = HashSet(p.getStringSet(KEY_IMPORTED, emptySet()) ?: emptySet())
      set.add("$id|$folder")
      p.edit().putStringSet(KEY_IMPORTED, set).apply()
    } catch (e: Exception) {
      // Not recorded: the folder simply will not be marked as untrusted.
    }
  }

  /** True when the project [path] lies in a folder imported from GitHub. */
  fun isImported(context: Context, path: String): Boolean =
    GitHubPaths.isInside(path, importedFolders(context))
}
