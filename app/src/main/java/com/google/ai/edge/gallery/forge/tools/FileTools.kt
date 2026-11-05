package com.google.ai.edge.gallery.forge.tools

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * [ALTREX] Versions of the files the model has read in one session. Write and Edit refuse a file
 * that exists but was not read, or that changed since (the user works in the same files from the
 * Terminal): the model must read it again instead of overwriting someone else's work.
 */
class FileTracker {
  private val versions = HashMap<String, String>()

  fun record(rel: String, bytes: ByteArray) {
    versions[rel] = digest(bytes)
  }

  fun forget(rel: String) {
    versions.remove(rel)
  }

  fun versionOf(rel: String): String? = versions[rel]

  /** Null when the write may go on, else the error for the model. */
  fun checkBeforeWrite(rel: String, current: ByteArray?): String? {
    if (current == null) return null
    val seen = versions[rel] ?: return "Le fichier $rel existe et tu ne l'as pas encore lu dans cette session. Lis-le d'abord avec Read."
    return if (seen != digest(current)) {
      "Le fichier $rel a changé depuis ta dernière lecture (modifié par l'utilisateur ou par une commande). Relis-le avec Read avant de le modifier."
    } else {
      null
    }
  }

  companion object {
    fun digest(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
  }
}

/**
 * [ALTREX] Read, Write and Edit of Forge, done by the app on the project folder (fast, and testable
 * on a computer), never through a shell line. A write goes to a temporary file in the same folder,
 * then replaces the target in one rename: a crash never leaves half a file. Write and Edit are in
 * two steps, [prepareWrite] / [prepareEdit] then [commit], so the engine can show the exact change
 * and ask for it in between.
 */
class FileTools(private val paths: ProjectPaths, private val tracker: FileTracker) {
  /** A change checked and ready, not yet on disk. */
  data class PendingWrite(
    val rel: String,
    val file: File,
    val newText: String,
    val isNew: Boolean,
    val diff: DiffLines.Result,
    val replacements: Int = 0,
    val tool: String,
  )

  sealed class Prepared {
    data class Ready(val write: PendingWrite) : Prepared()

    data class Failed(val outcome: ToolOutcome) : Prepared()
  }

  // ------------------------------------------------------------------------------------- Read

  fun read(rawPath: String, offset: Int?, limit: Int?): ToolOutcome {
    if (rawPath.isBlank()) return ToolOutcome.error("Read demande un « file_path ».")
    val r = paths.resolve(rawPath) ?: return outside(rawPath)
    val f = r.file
    if (!f.exists()) return notFound(r.rel)
    if (f.isDirectory) {
      return ToolOutcome.error("${shown(r.rel)} est un dossier. Utilise Glob pour lister les fichiers, ou Bash avec ls.")
    }
    if (f.length() > MAX_READ_BYTES) {
      return ToolOutcome.error(
        "${r.rel} est trop gros pour Read (${f.length() / 1024} Ko). Cherche ce qu'il te faut avec Grep, ou lis une partie avec Bash (sed -n '1,200p')."
      )
    }
    val bytes = f.readBytes()
    if (looksBinary(bytes)) return ToolOutcome.error("${r.rel} n'est pas un fichier texte (binaire).")
    tracker.record(r.rel, bytes)
    val lines = String(bytes, Charsets.UTF_8).let { if (it.endsWith("\n")) it.dropLast(1) else it }.let {
      if (it.isEmpty()) emptyList() else it.split('\n')
    }
    val total = lines.size
    val from = (offset ?: 1).coerceAtLeast(1)
    val count = (limit?.takeIf { it > 0 } ?: DEFAULT_READ_LINES).coerceAtMost(MAX_READ_LINES)
    val to = minOf(total, from + count - 1)
    val sb = StringBuilder()
    for (i in from..to) {
      val l = lines[i - 1].trimEnd('\r')
      sb.append(String.format("%6d\t", i)).append(if (l.length > MAX_LINE_CHARS) l.take(MAX_LINE_CHARS) + " […]" else l).append('\n')
    }
    val note =
      when {
        total == 0 -> "Le fichier est vide."
        from > total -> "Le fichier n'a que $total lignes."
        to < total -> "Lignes ${to + 1} à $total non affichées : rappelle Read avec offset=${to + 1}."
        else -> null
      }
    return ToolOutcome(
      ToolOutcome.Status.OK,
      ToolOutcome.json(
        "file" to r.rel,
        "lines" to if (total == 0 || from > total) "0/$total" else "$from-$to/$total",
        "content" to sb.toString().trimEnd('\n'),
        "note" to note,
        "status" to "ok",
      ),
      summary = "$total l.",
      path = r.rel,
    )
  }

  // ------------------------------------------------------------------------------------- Write

  fun prepareWrite(rawPath: String, content: String): Prepared {
    if (rawPath.isBlank()) return failed("Write demande un « file_path ».")
    val r = paths.resolve(rawPath) ?: return Prepared.Failed(outside(rawPath))
    if (r.rel.isEmpty() || r.file.isDirectory) return failed("${shown(r.rel)} est un dossier : donne le chemin d'un fichier.")
    val current = if (r.file.isFile) r.file.readBytes() else null
    tracker.checkBeforeWrite(r.rel, current)?.let { return failed(it) }
    val old = current?.toString(Charsets.UTF_8).orEmpty()
    return Prepared.Ready(
      PendingWrite(r.rel, r.file, content, isNew = current == null, diff = DiffLines.compute(old, content), tool = "Write")
    )
  }

  // ------------------------------------------------------------------------------------- Edit

  fun prepareEdit(rawPath: String, oldString: String, newString: String, replaceAll: Boolean): Prepared {
    if (rawPath.isBlank()) return failed("Edit demande un « file_path ».")
    val r = paths.resolve(rawPath) ?: return Prepared.Failed(outside(rawPath))
    if (!r.file.isFile) {
      return failed(
        if (r.file.isDirectory) "${shown(r.rel)} est un dossier." else "Le fichier ${r.rel} n'existe pas. Pour le créer, utilise Write."
      )
    }
    if (oldString.isEmpty()) return failed("old_string est vide. Pour remplir un fichier entier, utilise Write.")
    if (oldString == newString) return failed("old_string et new_string sont identiques : rien à changer.")
    val bytes = r.file.readBytes()
    if (tracker.versionOf(r.rel) == null) {
      return failed("Tu n'as pas encore lu ${r.rel} dans cette session. Lis-le d'abord avec Read, puis copie le passage exact.")
    }
    tracker.checkBeforeWrite(r.rel, bytes)?.let { return failed(it) }
    val text = bytes.toString(Charsets.UTF_8)
    return when (val m = EditMatcher.apply(text, oldString, newString, replaceAll)) {
      is EditMatcher.Result.Fail -> failed(m.message)
      is EditMatcher.Result.Ok ->
        Prepared.Ready(
          PendingWrite(r.rel, r.file, m.text, isNew = false, diff = DiffLines.compute(text, m.text), replacements = m.count, tool = "Edit")
        )
    }
  }

  // ------------------------------------------------------------------------------------- commit

  /** Writes [w] atomically; the new version counts as read. */
  fun commit(w: PendingWrite): ToolOutcome {
    try {
      writeAtomically(w.file, w.newText.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
      return ToolOutcome.error("Écriture impossible de ${w.rel} : ${e.message}")
    }
    tracker.record(w.rel, w.newText.toByteArray(Charsets.UTF_8))
    val summary = "+${w.diff.added} −${w.diff.removed}"
    val json =
      if (w.tool == "Edit") {
        ToolOutcome.json("file" to w.rel, "replacements" to w.replacements, "added" to w.diff.added, "removed" to w.diff.removed, "status" to "ok")
      } else {
        ToolOutcome.json(
          "file" to w.rel,
          "created" to w.isNew,
          "lines" to (if (w.newText.isEmpty()) 0 else w.newText.trimEnd('\n').split('\n').size),
          "added" to w.diff.added,
          "removed" to w.diff.removed,
          "status" to "ok",
        )
      }
    return ToolOutcome(ToolOutcome.Status.OK, json, summary = summary, detail = w.diff.text, added = w.diff.added, removed = w.diff.removed, path = w.rel)
  }

  private fun failed(message: String) = Prepared.Failed(ToolOutcome.error(message))

  private fun outside(raw: String) =
    ToolOutcome.error(
      "« $raw » est hors du projet (${paths.linuxPath}). Les outils de fichiers restent dans le projet ; pour le reste du Linux, utilise Bash."
    )

  private fun notFound(rel: String): ToolOutcome {
    val parent = rel.substringBeforeLast('/', "")
    val dir = paths.resolve(parent)?.file
    val near =
      dir?.listFiles()?.map { it.name }?.sorted()?.take(20)?.joinToString(", ")?.takeIf { it.isNotEmpty() }
    return ToolOutcome.error("Le fichier $rel n'existe pas." + (near?.let { " Contenu de ${shown(parent)} : $it." } ?: ""))
  }

  private fun shown(rel: String) = if (rel.isEmpty()) "La racine du projet" else rel

  companion object {
    const val DEFAULT_READ_LINES = 400
    const val MAX_READ_LINES = 2_000
    const val MAX_LINE_CHARS = 500
    const val MAX_READ_BYTES = 2L * 1024 * 1024

    /** A NUL byte in the first 8 KB. */
    fun looksBinary(bytes: ByteArray): Boolean {
      val n = minOf(bytes.size, 8 * 1024)
      for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
      return false
    }

    /**
     * Temporary file in the same folder, synced, then renamed over the target. The rename replaces
     * the directory entry itself: a symbolic link at [target] is replaced, never followed.
     */
    fun writeAtomically(target: File, bytes: ByteArray) {
      val dir = target.absoluteFile.parentFile ?: throw java.io.IOException("dossier introuvable")
      if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("impossible de créer ${dir.name}")
      val tmp = File(dir, ".${target.name}.forge-${System.nanoTime()}.tmp")
      try {
        FileOutputStream(tmp).use { out ->
          out.write(bytes)
          out.fd.sync()
        }
        if (target.isFile && target.canExecute()) tmp.setExecutable(true, false)
        try {
          Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
          Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
      } finally {
        if (tmp.exists()) tmp.delete()
      }
    }
  }
}
