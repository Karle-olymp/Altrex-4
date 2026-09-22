/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile

private const val PREFS_NAME = "code_mode_project_prefs"
private const val KEY_PROJECT_FOLDER_URI = "project_folder_uri"

/** Manages the project folder picked by the user for Code Mode's file browser. */
/** A file opened from the project folder, kept separate from the user's typed instruction. */
data class OpenedFile(val fileName: String, val uri: Uri, val content: String)
object ProjectFolderManager {

  /** Persists the picked folder (via SAF) so it survives app restarts. */
  fun saveProjectFolder(context: Context, treeUri: Uri) {
    context.contentResolver.takePersistableUriPermission(
      treeUri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
      putString(KEY_PROJECT_FOLDER_URI, treeUri.toString())
    }
  }

  /** Returns the previously picked project folder, if any and still accessible. */
  fun getSavedProjectFolder(context: Context): DocumentFile? {
    val uriString =
      context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_PROJECT_FOLDER_URI, null) ?: return null
    val uri = Uri.parse(uriString)
    val hasPermission =
      context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    if (!hasPermission) return null
    return DocumentFile.fromTreeUri(context, uri)
  }

  fun clearProjectFolder(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
      remove(KEY_PROJECT_FOLDER_URI)
    }
  }

  /** Lists the immediate children of a folder, directories first, alphabetically. */
  fun listChildren(folder: DocumentFile): List<DocumentFile> {
    return folder.listFiles().sortedWith(
      compareBy({ !it.isDirectory }, { (it.name ?: "").lowercase() })
    )
  }
  /** Reads a text file's content. Returns null if it can't be read. */
  fun readTextFile(context: Context, uri: Uri): String? {
    return try {
      context.contentResolver.openInputStream(uri)?.use { input -> input.bufferedReader().readText() }
    } catch (e: Exception) {
      null
    }
  }
  /** Overwrites a text file's content. Returns true on success. */
  fun writeTextFile(context: Context, uri: Uri, content: String): Boolean {
    return try {
      context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
        output.write(content.toByteArray())
      }
      true
    } catch (e: Exception) {
      false
    }
  }
}