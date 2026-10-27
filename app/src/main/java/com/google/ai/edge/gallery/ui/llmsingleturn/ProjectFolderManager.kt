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
import android.provider.DocumentsContract
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

  /**
   * Path of [uri] relative to the project folder ("src/app.js"), or null when it is not inside it
   * or the provider does not use path-like document ids.
   */
  fun relativePath(context: Context, uri: Uri): String? {
    val root = getSavedProjectFolder(context) ?: return null
    return try {
      val rootId = DocumentsContract.getTreeDocumentId(root.uri)
      val docId = DocumentsContract.getDocumentId(uri)
      docId.takeIf { it.startsWith("$rootId/") }?.removePrefix("$rootId/")
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  /** Lists the immediate children of a folder, directories first, alphabetically. */
  fun listChildren(folder: DocumentFile): List<DocumentFile> {
    return folder.listFiles().sortedWith(
      compareBy({ !it.isDirectory }, { (it.name ?: "").lowercase() })
    )
  }
  /** [ALTREX] Largest file opened in the editor. */
  const val MAX_EDITOR_BYTES = 2 * 1024 * 1024

  /** Result of [readTextFile]. */
  sealed class TextRead {
    data class Ok(val text: String) : TextRead()

    data object TooBig : TextRead()

    data object Binary : TextRead()

    data object Failed : TextRead()
  }

  /**
   * Reads a text file's content. [ALTREX] At most [MAX_EDITOR_BYTES]; a binary file (APK, video,
   * image…) is refused, since editing and saving it as text would corrupt it. Call it off the main
   * thread.
   */
  fun readTextFile(context: Context, uri: Uri): TextRead {
    return try {
      val bytes =
        context.contentResolver.openInputStream(uri)?.use { input ->
          val buffer = java.io.ByteArrayOutputStream()
          val chunk = ByteArray(64 * 1024)
          while (buffer.size() <= MAX_EDITOR_BYTES) {
            val n = input.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
          }
          buffer.toByteArray()
        } ?: return TextRead.Failed
      when {
        bytes.size > MAX_EDITOR_BYTES -> TextRead.TooBig
        looksBinary(bytes) -> TextRead.Binary
        else -> TextRead.Ok(String(bytes, Charsets.UTF_8))
      }
    } catch (e: Exception) {
      TextRead.Failed
    } catch (e: OutOfMemoryError) {
      TextRead.TooBig
    }
  }

  /** A NUL byte, or bytes that are not valid UTF-8, in the first 8 KB. */
  internal fun looksBinary(bytes: ByteArray): Boolean {
    val n = minOf(bytes.size, 8 * 1024)
    for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
    // A cut inside a multi-byte character is not an error: when cut, also try without the last
    // 1 to 3 bytes.
    val drops = if (n < bytes.size) 0..3 else 0..0
    return drops.none { drop -> isUtf8(bytes, n - drop) }
  }

  private fun isUtf8(bytes: ByteArray, length: Int): Boolean =
    try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes, 0, length))
      true
    } catch (e: java.nio.charset.CharacterCodingException) {
      false
    }
  /** Overwrites a text file's content. Returns true on success. */
  fun writeTextFile(context: Context, uri: Uri, content: String): Boolean {
    return try {
      // [ALTREX] No stream means nothing was written: report it (it was shown as saved).
      val output = context.contentResolver.openOutputStream(uri, "wt") ?: return false
      output.use { it.write(content.toByteArray()) }
      true
    } catch (e: Exception) {
      false
    }
  }

  /** Creates an empty file directly inside the given folder. Returns it, or null on failure. */
  fun createFile(parent: DocumentFile, fileName: String): DocumentFile? {
    return try {
      parent.createFile("application/octet-stream", fileName)
    } catch (e: Exception) {
      null
    }
  }
}