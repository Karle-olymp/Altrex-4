package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** Agent mode switch (kept in memory, ON by default). */
object AgentSettings {
  var autoMode by mutableStateOf(true)
}

object FileActions {
  private val FENCE = "`".repeat(3)
  private const val MAX_BACKUP_CHARS = 300_000

  private class Backup(
    val path: String,
    val uri: Uri?,
    val previous: String?,
    val deleted: Boolean,
  )

  private val backups = mutableListOf<Backup>()

  fun canUndo(): Boolean = synchronized(backups) { backups.isNotEmpty() }

  /** Finds the file blocks proposed by the model (format: file:path then the content). */
  fun parse(response: String): List<FileAction> {
    val regex = Regex(FENCE + "file:([^\\n`]+)\\n(.*?)" + FENCE, RegexOption.DOT_MATCHES_ALL)
    val result = LinkedHashMap<String, FileAction>()
    for (m in regex.findAll(response)) {
      val path = normalize(m.groupValues[1]) ?: continue
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

  /** Normalizes a relative path. Returns null if it is empty or tries to leave the project. */
  fun normalize(raw: String): String? {
    var p = raw.trim().trim('"', '\'', '`', '*', '_').trim().replace('\\', '/')
    while (p.startsWith("./")) p = p.removePrefix("./")
    p = p.trimStart('/')
    val segments = p.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it == ".." || it == "." }) return null
    return segments.joinToString("/")
  }

  /** Like normalize, but "." / "/" / empty mean the project root (returned as ""). */
  fun normalizeDir(raw: String): String? {
    val t = raw.trim().trim('"', '\'', '`', '*', '_').trim()
    if (t.isEmpty() || t == "." || t == "./" || t == "/" || t == "*") return ""
    return normalize(t)
  }

  /** Removes the echoed header line and the "12 | " / "N | " prefixes if the model copied them. */
  fun cleanCode(block: String): String {
    var lines = block.trimEnd('\n').lines()
    if (lines.isNotEmpty() && lines.first().contains("sont ajoutés pour te repérer")) {
      lines = lines.drop(1)
    }
    val numbered = Regex("^\\s*(?:\\d+|N)\\s*\\|\\s?(.*)$", RegexOption.IGNORE_CASE)
    val nonBlank = lines.filter { it.isNotBlank() }
    val matching = nonBlank.count { numbered.matches(it) }
    if (nonBlank.isNotEmpty() && matching * 10 >= nonBlank.size * 6) {
      lines = lines.map { numbered.matchEntire(it)?.groupValues?.get(1) ?: it }
    }
    lines = lines.dropLastWhile { it.isBlank() }
    return lines.joinToString("\n") + "\n"
  }

  private fun resolve(context: Context, path: String): DocumentFile? {
    var cur: DocumentFile = ProjectFolderManager.getSavedProjectFolder(context) ?: return null
    if (path.isEmpty()) return cur
    for (seg in path.split('/')) {
      cur = cur.findFile(seg) ?: return null
    }
    return cur
  }

  fun exists(context: Context, path: String): Boolean {
    return try {
      resolve(context, path) != null
    } catch (e: Exception) {
      false
    }
  }

  fun fileExists(context: Context, uri: Uri): Boolean =
    try {
      DocumentFile.fromSingleUri(context, uri)?.exists() == true
    } catch (e: Exception) {
      false
    }

  /** Returns the new content if the file changed on disk compared to [current], else null. */
  fun readIfChanged(context: Context, uri: Uri, current: String): String? {
    val text = readAll(context, uri) ?: return null
    return if (text != current) text else null
  }

  private fun fail(message: String) = FileActionResult(false, false, null, message)

  private fun readAll(context: Context, uri: Uri): String? =
    try {
      context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    } catch (e: Exception) {
      null
    }

  private fun addBackup(backup: Backup) {
    synchronized(backups) {
      backups.add(backup)
      while (backups.size > 20) backups.removeAt(0)
    }
  }

  /** Reads a text file with numbered lines, truncated to maxChars. Null if not a readable file. */
  fun readNumbered(context: Context, path: String, maxChars: Int): String? {
    return try {
      val f = resolve(context, path) ?: return null
      if (!f.isFile) return null
      val text =
        context.contentResolver.openInputStream(f.uri)?.use { input ->
          input.bufferedReader().use { reader ->
            val buf = CharArray(maxChars + 1)
            val n = reader.read(buf)
            if (n <= 0) "" else String(buf, 0, n)
          }
        } ?: return null
      if (text.contains('\u0000')) return "[fichier binaire, non lisible]"
      val body =
        text
          .take(maxChars)
          .removeSuffix("\n")
          .lines()
          .mapIndexed { i, line -> "${i + 1} | $line" }
          .joinToString("\n")
      if (text.length > maxChars) body + "\n... (fichier tronqué)" else body
    } catch (e: Exception) {
      null
    }
  }

  /** Lists a folder (folders first, "/" suffix). Null if the folder does not exist. */
  fun listDir(context: Context, path: String): List<String>? {
    return try {
      val d = resolve(context, path) ?: return null
      if (!d.isDirectory) return null
      d.listFiles()
        .take(80)
        .mapNotNull { f ->
          val n = f.name ?: return@mapNotNull null
          if (f.isDirectory) "$n/" else n
        }
        .sortedWith(compareBy({ !it.endsWith("/") }, { it.lowercase() }))
    } catch (e: Exception) {
      null
    }
  }

  fun apply(context: Context, action: FileAction): FileActionResult =
    write(context, action.path, action.content, record = true)

  /** Creates or overwrites the file inside the project folder (creating folders if needed). */
  private fun write(context: Context, path: String, content: String, record: Boolean): FileActionResult {
    try {
      val root =
        ProjectFolderManager.getSavedProjectFolder(context)
          ?: return fail("Aucun dossier de projet sélectionné")
      val segments = path.split('/')
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
      if (previous != null && previous.length > MAX_BACKUP_CHARS) {
        return fail("Fichier trop gros pour être modifié en sécurité : $name")
      }
      val target =
        existing
          ?: dir.createFile("application/octet-stream", name)
          ?: return fail("Création impossible : $name")
      if (!ProjectFolderManager.writeTextFile(context, target.uri, content)) {
        if (existing == null) target.delete()
        return fail("Écriture impossible : $name")
      }
      if (record) addBackup(Backup(path, target.uri, previous, false))
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

  /** Deletes a text file (its content is kept so that the deletion can be undone). */
  fun delete(context: Context, path: String): FileActionResult {
    try {
      val f = resolve(context, path) ?: return fail("Introuvable : $path")
      if (f.isDirectory) return fail("« $path » est un dossier : suppression de dossier non autorisée")
      val previous =
        readAll(context, f.uri) ?: return fail("Lecture impossible, suppression annulée : $path")
      if (previous.length > MAX_BACKUP_CHARS || previous.contains('\u0000')) {
        return fail("Fichier trop gros ou binaire, non supprimé : $path")
      }
      if (!f.delete()) return fail("Suppression impossible : $path")
      addBackup(Backup(path, null, previous, true))
      ProjectContextBuilder.invalidateCache()
      ProjectEvents.tick.intValue++
      return FileActionResult(true, false, null, "Supprimé")
    } catch (e: Exception) {
      return fail(e.message ?: "Erreur inconnue")
    }
  }

  /** Reverts the last action (restores old content, deletes a created file, recreates a deleted one). */
  fun undoLast(context: Context): UndoResult {
    val b =
      synchronized(backups) {
        if (backups.isEmpty()) null else backups.removeAt(backups.size - 1)
      } ?: return UndoResult("Rien à annuler", null, null)
    return try {
      val result =
        if (b.deleted) {
          val content = b.previous ?: ""
          val r = write(context, b.path, content, record = false)
          if (r.ok) {
            UndoResult("Restauré : ${b.path}", r.uri, content)
          } else {
            UndoResult("Échec de la restauration : ${b.path}", null, null)
          }
        } else if (b.uri == null) {
          UndoResult("Échec : ${b.path}", null, null)
        } else if (b.previous == null) {
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
