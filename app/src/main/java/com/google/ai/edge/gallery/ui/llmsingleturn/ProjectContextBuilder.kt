package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/** Builds a compact view of the project folder to prepend to the prompt. */
object ProjectContextBuilder {
  private const val TAG = "ProjectContextBuilder"
  private const val MAX_TREE_ENTRIES = 150
  private const val MAX_TREE_SHOWN = 60
  private const val MAX_DEPTH = 4
  private const val MAX_EXTRA_FILES = 3
  private const val MAX_CHARS_PER_FILE = 6000
  private const val CACHE_MS = 60_000L

  private val IGNORED_DIRS =
    setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist", "__pycache__")

  private val BINARY_EXTENSIONS =
    setOf(
      "png", "jpg", "jpeg", "gif", "webp", "ico", "zip", "jar", "aar", "apk", "so", "bin", "pdf",
      "ttf", "otf", "woff", "woff2", "mp3", "mp4", "gguf", "class", "dex", "keystore",
    )

  private class Node(val path: String, val name: String, val uri: Uri, val isDir: Boolean)

  private class Child(val docId: String, val name: String, val isDir: Boolean)

  @Volatile private var cacheKey: String? = null
  @Volatile private var cacheTime = 0L
  @Volatile private var cacheNodes: List<Node> = emptyList()

  /** Header + numbered content of the opened file, as sent to the model. */
  fun fileBlock(fileName: String, content: String): String {
    val numbered =
      content
        .removeSuffix("\n")
        .lines()
        .mapIndexed { i, line -> "${i + 1} | $line" }
        .joinToString("\n")
    return "// $fileName (chaque ligne est précédée de son numéro et de « | » : ces préfixes " +
      "sont ajoutés pour te repérer, ils ne font pas partie du fichier)\n$numbered\n\n"
  }

  fun invalidateCache() {
    cacheKey = null
  }
  fun build(
    context: Context,
    instruction: String,
    excludeUri: Uri?,
    maxChars: Int = Int.MAX_VALUE,
    includeTree: Boolean = true,
  ): String {
    return try {
      buildInternal(context, instruction, excludeUri, maxChars, includeTree)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to build project context", e)
      ""
    }
  }

  private fun buildInternal(
    context: Context,
    instruction: String,
    excludeUri: Uri?,
    maxChars: Int,
    includeTree: Boolean,
  ): String {
    val root = ProjectFolderManager.getSavedProjectFolder(context) ?: return ""
    val nodes = getNodes(context, root.uri)
    if (nodes.isEmpty()) return ""

    val sb = StringBuilder()
    if (includeTree) {
      sb.append("[Projet : ${root.name ?: "projet"}]\n")
      sb.append("Arborescence (tu n'as pas le contenu des fichiers non listés ci-dessous).\n")
      for (n in nodes.take(MAX_TREE_SHOWN)) {
        sb.append(n.path)
        if (n.isDir) sb.append("/")
        sb.append("\n")
      }
      if (nodes.size > MAX_TREE_SHOWN) sb.append("... (arborescence tronquée)\n")
    }

    val lowerInstruction = instruction.lowercase()
    val mentioned =
      nodes
        .filter { n ->
          if (n.isDir || n.uri == excludeUri) return@filter false
          val ext = n.name.substringAfterLast('.', "").lowercase()
          if (ext in BINARY_EXTENSIONS) return@filter false
          val base = n.name.substringBeforeLast('.')
          lowerInstruction.contains(n.name.lowercase()) ||
            (base.length >= 4 && lowerInstruction.contains(base.lowercase()))
        }
        .take(MAX_EXTRA_FILES)

    for (n in mentioned) {
      val text = readCapped(context, n.uri) ?: continue
      sb.append("\n// Fichier : ${n.path}\n")
      sb.append(text.take(MAX_CHARS_PER_FILE))
      if (text.length > MAX_CHARS_PER_FILE) sb.append("\n... (fichier tronqué)")
      sb.append("\n")
    }
    return sb.toString().take(maxChars)
  }

  private fun getNodes(context: Context, rootUri: Uri): List<Node> {
    val key = rootUri.toString()
    val now = System.currentTimeMillis()
    if (key == cacheKey && now - cacheTime < CACHE_MS) return cacheNodes

    val out = mutableListOf<Node>()
    val queue = ArrayDeque<Triple<String, String, Int>>()
    queue.add(Triple(DocumentsContract.getDocumentId(rootUri), "", 0))
    while (queue.isNotEmpty() && out.size < MAX_TREE_ENTRIES) {
      val (docId, prefix, depth) = queue.removeFirst()
      for (child in listChildren(context, rootUri, docId)) {
        if (out.size >= MAX_TREE_ENTRIES) break
        if (child.isDir && child.name in IGNORED_DIRS) continue
        val uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, child.docId)
        out.add(Node("$prefix${child.name}", child.name, uri, child.isDir))
        if (child.isDir && depth + 1 < MAX_DEPTH) {
          queue.add(Triple(child.docId, "$prefix${child.name}/", depth + 1))
        }
      }
    }
    cacheKey = key
    cacheTime = now
    cacheNodes = out
    return out
  }

  private fun listChildren(context: Context, rootUri: Uri, parentDocId: String): List<Child> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
    val result = mutableListOf<Child>()
    context.contentResolver
      .query(
        childrenUri,
        arrayOf(
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
      )
      ?.use { c ->
        while (c.moveToNext()) {
          val id = c.getString(0) ?: continue
          val name = c.getString(1) ?: continue
          val mime = c.getString(2)
          result.add(Child(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR))
        }
      }
    return result.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
  }

  /** Reads at most MAX_CHARS_PER_FILE + 1 characters, to avoid loading huge files. */
  private fun readCapped(context: Context, uri: Uri): String? {
    return try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        input.bufferedReader().use { reader ->
          val buf = CharArray(MAX_CHARS_PER_FILE + 1)
          val n = reader.read(buf)
          if (n <= 0) "" else String(buf, 0, n)
        }
      }
    } catch (e: Exception) {
      null
    }
  }
}