package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.documentfile.provider.DocumentFile

/** A file proposed by the model: relative path + full content. */
data class FileAction(val path: String, val content: String)

data class FileActionResult(
  val ok: Boolean,
  val created: Boolean,
  val uri: Uri?,
  val message: String,
)

data class UndoResult(val message: String, val uri: Uri?, val restoredContent: String?)

/** Global signal used to refresh the file explorer after files were created or changed. */
object ProjectEvents {
  val tick = mutableIntStateOf(0)
}

object FileActions {
  private val FENCE = "`".repeat(3)

  private class Backup(val path: String, val uri: Uri, val previous: String?)

  private val backups = mutableListOf<Backup>()

  fun canUndo(): Boolean = synchronized(backups) { backups.isNotEmpty() }

  /** Finds the file blocks proposed by the model (format: file:path then the content). */
  fun parse(response: String): List<FileAction> {
    val regex = Regex(FENCE + "file:([^\\n`]+)\\n(.*?)" + FENCE, RegexOption.DOT_MATCHES_ALL)
    val result = LinkedHashMap<String, FileAction>()
    for (m in regex.findAll(response)) {
      val path = normalizePath(m.groupValues[1]) ?: continue
      result[path] = FileAction(path, cleanCode(m.groupValues[2]))
    }
    return result.values.toList()
  }

  /** Largest plain code block of a response, cleaned. */
  fun largestCodeBlock(response: String): String? {
    val regex = Regex(FENCE + "[^\\n`]*\\n(.*?)" + FENCE, RegexOption.DOT_MATCHES_ALL)
    return regex
      .findAll(response)
      .map { cleanCode(it.groupValues[1]) }
      .maxByOrNull { it.length }
      ?.takeIf { it.isNotBlank() }
  }

  private fun normalizePath(raw: String): String? {
    var p = raw.trim().trim('"', '\'', '`').replace('\\', '/')
    while (p.startsWith("./")) p = p.removePrefix("./")
    p = p.trimStart('/')
    val segments = p.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it == ".." || it == "." }) return null
    return segments.joinToString("/")
  }

  /** Removes the echoed header line and the "N | " line numbers if the model copied them. */
  fun cleanCode(block: String): String {
    var lines = block.trimEnd('\n').lines()
    if (lines.isNotEmpty() && lines.first().contains("sont ajoutés pour te repérer")) {
      lines = lines.drop(1)
    }
    // Strip "12 | " or "N | " prefixes when most lines have one.
    val numbered = Regex("^\\s*(?:\\d+|N)\\s*\\|\\s?(.*)$", RegexOption.IGNORE_CASE)
    val nonBlank = lines.filter { it.isNotBlank() }
    val matching = nonBlank.count { numbered.matches(it) }
    if (nonBlank.isNotEmpty() && matching * 10 >= nonBlank.size * 6) {
      lines = lines.map { numbered.matchEntire(it)?.groupValues?.get(1) ?: it }
    }
    lines = lines.dropLastWhile { it.isBlank() }
    return lines.joinToString("\n") + "\n"
  }

  fun exists(context: Context, path: String): Boolean {
    return try {
      var cur: DocumentFile = ProjectFolderManager.getSavedProjectFolder(context) ?: return false
      for (seg in path.split('/')) {
        cur = cur.findFile(seg) ?: return false
      }
      true
    } catch (e: Exception) {
      false
    }
  }

  private fun fail(message: String) = FileActionResult(false, false, null, message)

  private fun readAll(context: Context, uri: Uri): String? =
    try {
      context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    } catch (e: Exception) {
      null
    }

  /** Creates or overwrites the file inside the project folder (creating folders if needed). */
  fun apply(context: Context, action: FileAction): FileActionResult {
    try {
      val root =
        ProjectFolderManager.getSavedProjectFolder(context)
          ?: return fail("Aucun dossier de projet sélectionné")
      val segments = action.path.split('/')
      var dir: DocumentFile = root
      for (seg in segments.dropLast(1)) {
        val existingDir = dir.findFile(seg)
        dir =
          when {
            existingDir == null ->
              dir.createDirectory(seg) ?: return fail("Impossible de créer le dossier « $seg »")
            existingDir.isDirectory -> existingDir
            else -> return fail("« $seg » existe déjà et n'est pas un dossier")
          }
      }
      val name = segments.last()
      val existing = dir.findFile(name)
      if (existing != null && existing.isDirectory) return fail("« $name » est un dossier")
      val previous: String? =
        if (existing != null) {
          readAll(context, existing.uri) ?: return fail("Lecture de l'ancien contenu impossible")
        } else {
          null
        }
      val target =
        existing
          ?: dir.createFile("application/octet-stream", name)
          ?: return fail("Création impossible : $name")
      if (!ProjectFolderManager.writeTextFile(context, target.uri, action.content)) {
        if (existing == null) target.delete()
        return fail("Écriture impossible : $name")
      }
      synchronized(backups) {
        backups.add(Backup(action.path, target.uri, previous))
        while (backups.size > 20) backups.removeAt(0)
      }
      ProjectContextBuilder.invalidateCache()
      ProjectEvents.tick.intValue++
      return FileActionResult(
        ok = true,
        created = existing == null,
        uri = target.uri,
        message = if (existing == null) "Créé" else "Modifié",
      )
    } catch (e: Exception) {
      return fail(e.message ?: "Erreur inconnue")
    }
  }

  /** Reverts the last applied action (restores the old content, or deletes a created file). */
  fun undoLast(context: Context): UndoResult {
    val b =
      synchronized(backups) {
        if (backups.isEmpty()) null else backups.removeAt(backups.size - 1)
      } ?: return UndoResult("Rien à annuler", null, null)
    return try {
      val result =
        if (b.previous == null) {
          DocumentFile.fromSingleUri(context, b.uri)?.delete()
          UndoResult("Supprimé : ${b.path}", null, null)
        } else if (ProjectFolderManager.writeTextFile(context, b.uri, b.previous)) {
          UndoResult("Restauré : ${b.path}", b.uri, b.previous)
        } else {
          UndoResult("Échec de la restauration : ${b.path}", null, null)
        }
      ProjectContextBuilder.invalidateCache()
      ProjectEvents.tick.intValue++
      result
    } catch (e: Exception) {
      UndoResult("Échec : ${e.message}", null, null)
    }
  }
}