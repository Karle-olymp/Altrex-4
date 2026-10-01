package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** Builds a compact view of the project folder to prepend to the prompt. */
object ProjectContextBuilder {
  private const val MAX_TREE_ENTRIES = 150
  private const val MAX_DEPTH = 4
  private const val MAX_EXTRA_FILES = 3
  private const val MAX_CHARS_PER_FILE = 6000

  private val IGNORED_DIRS =
    setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist", "__pycache__")

  private val TEXT_EXTENSIONS =
    setOf(
      "kt", "java", "kts", "gradle", "xml", "json", "md", "txt", "js", "ts", "tsx", "jsx",
      "html", "css", "py", "c", "cpp", "h", "yml", "yaml", "toml", "properties", "sh", "sql",
      "dart",
    )

  private class Entry(val path: String, val file: DocumentFile)

  fun build(context: Context, instruction: String, excludeUri: Uri?): String {
    val root = ProjectFolderManager.getSavedProjectFolder(context) ?: return ""
    val entries = mutableListOf<Entry>()
    collect(root, "", 0, entries)
    if (entries.isEmpty()) return ""

    val sb = StringBuilder()
    sb.append("[Projet : ${root.name ?: "projet"}]\n")
    sb.append("Arborescence (tu n'as pas le contenu des fichiers non listés ci-dessous).\n")
    sb.append("Si tu as besoin d'un autre fichier, demande-le avec son nom exact.\n")
    for (e in entries) {
      sb.append(e.path)
      if (e.file.isDirectory) sb.append("/")
      sb.append("\n")
    }
    if (entries.size >= MAX_TREE_ENTRIES) sb.append("... (arborescence tronquée)\n")

    val lowerInstruction = instruction.lowercase()
    val mentioned =
      entries
        .filter { e ->
          val name = e.file.name ?: return@filter false
          if (!e.file.isFile || e.file.uri == excludeUri) return@filter false
          val ext = name.substringAfterLast('.', "").lowercase()
          if (ext !in TEXT_EXTENSIONS) return@filter false
          val base = name.substringBeforeLast('.')
          lowerInstruction.contains(name.lowercase()) ||
            (base.length >= 4 && lowerInstruction.contains(base.lowercase()))
        }
        .take(MAX_EXTRA_FILES)

    for (e in mentioned) {
      val text = ProjectFolderManager.readTextFile(context, e.file.uri) ?: continue
      sb.append("\n// Fichier : ${e.path}\n")
      sb.append(text.take(MAX_CHARS_PER_FILE))
      if (text.length > MAX_CHARS_PER_FILE) sb.append("\n... (fichier tronqué)")
      sb.append("\n")
    }
    return sb.toString()
  }

  private fun collect(folder: DocumentFile, prefix: String, depth: Int, out: MutableList<Entry>) {
    if (depth >= MAX_DEPTH) return
    for (child in ProjectFolderManager.listChildren(folder)) {
      if (out.size >= MAX_TREE_ENTRIES) return
      val name = child.name ?: continue
      if (child.isDirectory) {
        if (name in IGNORED_DIRS) continue
        out.add(Entry("$prefix$name", child))
        collect(child, "$prefix$name/", depth + 1, out)
      } else {
        out.add(Entry("$prefix$name", child))
      }
    }
  }
}