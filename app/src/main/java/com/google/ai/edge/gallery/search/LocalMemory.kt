package com.google.ai.edge.gallery.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.google.ai.edge.gallery.security.UntrustedContent
import com.google.ai.edge.gallery.ui.common.chat.DocumentReader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the local memory is doing. */
sealed class MemoryStatus {
  data object NoFolder : MemoryStatus()

  data class Indexing(val done: Int, val total: Int, val current: String) : MemoryStatus()

  /** Search by meaning: vectors being computed (the keyword search already works). */
  data class Embedding(val done: Int, val total: Int) : MemoryStatus()

  data class Ready(val files: Int, val passages: Int, val indexedAt: Long, val truncated: Boolean) :
    MemoryStatus()

  data class Failed(val message: String) : MemoryStatus()
}

/**
 * [ALTREX] Local memory (local RAG): a folder chosen by the user (notes, docs, configs, code,
 * PDF) that the model can draw on. Everything stays on the phone: the folder is read through the
 * Android file picker permission, indexed in the app's private storage, and searched by keywords
 * (BM25). Each chat question gets the most relevant passages, with the file they come from.
 */
object LocalMemory {
  private const val TAG = "AGLocalMemory"
  private const val PREFS = "altrex_memory"
  private const val KEY_FOLDER = "folder_uri"
  private const val KEY_ENABLED = "enabled"
  private const val KEY_INDEXED_AT = "indexed_at"
  private const val KEY_SEMANTIC = "semantic"
  /** Vectors are saved every so many pieces, so an interrupted computation resumes. */
  private const val VECTOR_CHECKPOINT = 200

  private const val MAX_FILES = 2_000
  private const val MAX_TEXT_BYTES = 2L * 1024 * 1024
  private const val MAX_PDF_BYTES = 40L * 1024 * 1024
  private const val MAX_TOTAL_CHARS = 6_000_000
  private const val MAX_DEPTH = 8
  /** An index older than this is refreshed in the background (only changed files are read again). */
  private const val REFRESH_AFTER_MS = 30 * 60 * 1000L

  private val TEXT_EXTENSIONS =
    setOf(
      "txt", "md", "markdown", "rst", "org", "csv", "tsv", "json", "jsonl", "yaml", "yml", "xml",
      "html", "htm", "conf", "cfg", "ini", "toml", "properties", "log", "env", "kt", "kts", "java",
      "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "sh", "bash", "zsh",
      "ps1", "sql", "php", "rb", "swift", "dart", "lua", "gradle", "dockerfile", "tf", "nginx",
    )
  private val SKIPPED_DIRS =
    setOf("node_modules", "build", "dist", "out", "target", "__pycache__", "venv", ".venv", "vendor")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var job: Job? = null

  private val _status = MutableStateFlow<MemoryStatus>(MemoryStatus.NoFolder)
  val status: StateFlow<MemoryStatus> = _status.asStateFlow()

  @Volatile private var index: MemoryIndex? = null
  @Volatile private var vectors: VectorStore? = null

  private val _download = MutableStateFlow<Long?>(null)
  /** Bytes of the embedding model received so far, while it downloads. */
  val download: StateFlow<Long?> = _download.asStateFlow()

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun indexFile(context: Context) = File(context.filesDir, "memory/index.jsonl")

  private fun vectorsFile(context: Context) = File(context.filesDir, "memory/vectors.bin")

  /** Search by meaning: on by choice, and possible once the embedding model is downloaded. */
  fun isSemanticEnabled(context: Context): Boolean =
    prefs(context).getBoolean(KEY_SEMANTIC, false) && Embedder.isDownloaded(context)

  fun setSemanticEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_SEMANTIC, enabled).apply()
    if (enabled) reindex(context) else Embedder.release()
  }

  /** Downloads the embedding model, then computes the vectors of the memory. */
  fun downloadSemanticModel(context: Context, onError: (String) -> Unit) {
    val app = context.applicationContext
    if (_download.value != null) return
    _download.value = 0L
    scope.launch {
      val error = Embedder.download(app) { _download.value = it }
      _download.value = null
      if (error != null) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { onError(error) }
      } else {
        setSemanticEnabled(app, true)
      }
    }
  }

  /** Deletes the embedding model and the vectors (the keyword search stays). */
  fun deleteSemanticModel(context: Context) {
    val app = context.applicationContext
    prefs(app).edit().putBoolean(KEY_SEMANTIC, false).apply()
    Embedder.delete(app)
    vectorsFile(app).delete()
    vectors = null
  }

  fun folderUri(context: Context): Uri? =
    prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)?.takeIf { uri ->
      context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

  /** Name of the chosen folder, for display. */
  fun folderName(context: Context): String? =
    folderUri(context)?.let { DocumentsContract.getTreeDocumentId(it).substringAfterLast(':').substringAfterLast('/') }
      ?.ifEmpty { "Stockage" }

  fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

  fun setEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
  }

  /** Used by the chat: a folder is chosen, the memory is on, and its index is loaded. */
  fun isActive(context: Context): Boolean = isEnabled(context) && index != null && folderUri(context) != null

  /** Keeps [treeUri] (read only) as the memory folder and indexes it. */
  fun chooseFolder(context: Context, treeUri: Uri) {
    val app = context.applicationContext
    folderUri(app)?.let { old ->
      if (old != treeUri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
      }
    }
    app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    prefs(app).edit().putString(KEY_FOLDER, treeUri.toString()).putBoolean(KEY_ENABLED, true).apply()
    index = null
    vectors = null
    indexFile(app).delete()
    vectorsFile(app).delete()
    reindex(app)
  }

  /** Forgets the folder and deletes the index (the files themselves are not touched). */
  fun forget(context: Context) {
    val app = context.applicationContext
    job?.cancel()
    folderUri(app)?.let {
      runCatching { app.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    val semantic = prefs(app).getBoolean(KEY_SEMANTIC, false)
    prefs(app).edit().clear().putBoolean(KEY_SEMANTIC, semantic).apply()
    indexFile(app).delete()
    vectorsFile(app).delete()
    index = null
    vectors = null
    _status.value = MemoryStatus.NoFolder
  }

  /**
   * At app start: loads the saved index, then refreshes it in the background when it is old. Cheap
   * when nothing changed (only the folder listing is read).
   */
  fun warmUp(context: Context) {
    val app = context.applicationContext
    if (folderUri(app) == null) return
    scope.launch {
      if (index == null) loadFromDisk(app)
      val age = System.currentTimeMillis() - prefs(app).getLong(KEY_INDEXED_AT, 0L)
      val vectorsMissing = isSemanticEnabled(app) && vectors?.let { it.computed < it.count } != false
      if (index == null || age > REFRESH_AFTER_MS || vectorsMissing) reindex(app)
    }
  }

  /** Reads the folder again; files that did not change keep their pieces. */
  fun reindex(context: Context) {
    val app = context.applicationContext
    if (job?.isActive == true) return
    val root = folderUri(app) ?: run {
      _status.value = MemoryStatus.NoFolder
      return
    }
    job =
      scope.launch {
        try {
          if (index == null) loadFromDisk(app)
          build(app, root)
        } catch (e: Exception) {
          Log.e(TAG, "Indexing failed", e)
          _status.value = MemoryStatus.Failed(e.message ?: "Indexation impossible.")
        }
      }
  }

  /**
   * Passages for [question], within [budgetChars]; empty when the memory is off or has nothing.
   * With the search by meaning, the question's vector is computed first (about 0.1 s).
   */
  suspend fun search(context: Context, question: String, budgetChars: Int): List<MemoryHit> {
    if (!isEnabled(context)) return emptyList()
    val current = index ?: return emptyList()
    val semantic =
      vectors
        ?.takeIf { isSemanticEnabled(context) && it.count == current.chunks.size && it.model == Embedder.MODEL_NAME }
        ?.let { store -> Embedder.embedQuery(context, question)?.let { MemoryIndex.Semantic(store, it, Embedder.MIN_COSINE) } }
    return runCatching { current.search(question, budgetChars, semantic = semantic) }.getOrDefault(emptyList())
  }

  /** The request sent to the model: the passages (framed as data, with their source), then the question. */
  fun prompt(context: Context, hits: List<MemoryHit>, question: String): String {
    val body =
      hits.joinToString("\n\n") { hit ->
        val where = if (hit.page > 0) "${hit.path}, p. ${hit.page}" else hit.path
        "[$where]\n${hit.text}"
      }
    return "Extraits de ma mémoire locale (dossier « ${folderName(context) ?: "mémoire"} ») qui peuvent aider :\n" +
      UntrustedContent.frame("mémoire locale", body) +
      "\n\nSi ces extraits répondent à la question, appuie-toi dessus et cite le fichier entre crochets ; " +
      "sinon, réponds normalement.\n\nQuestion : $question"
  }

  /** Short list of the sources, for the line shown in the chat. */
  fun sourcesLabel(hits: List<MemoryHit>): String =
    hits
      .map { if (it.page > 0) "${it.path.substringAfterLast('/')} (p. ${it.page})" else it.path.substringAfterLast('/') }
      .distinct()
      .joinToString(", ")

  // ---- Indexing ----

  private data class Entry(val uri: Uri, val path: String, val name: String, val modified: Long, val size: Long)

  private suspend fun build(context: Context, root: Uri) {
    _status.value = MemoryStatus.Indexing(0, 0, "Lecture du dossier…")
    val entries = ArrayList<Entry>()
    val rootId = DocumentsContract.getTreeDocumentId(root)
    listTree(context, root, rootId, "", 0, entries)

    val previous = index
    val previousFiles = previous?.files?.withIndex()?.associate { (i, f) -> f.uri to (i to f) }.orEmpty()
    // Old file -> global positions of its pieces, so that their vectors can be kept.
    val previousChunks = previous?.chunks?.indices?.groupBy { previous.chunks[it].file }.orEmpty()
    val oldPosition = ArrayList<Int>() // for each new piece, its position in the old index or -1

    val files = ArrayList<MemoryFile>()
    val chunks = ArrayList<MemoryChunk>()
    var totalChars = 0L
    var truncated = entries.size > MAX_FILES
    for ((n, e) in entries.take(MAX_FILES).withIndex()) {
      if (totalChars >= MAX_TOTAL_CHARS) {
        truncated = true
        break
      }
      _status.value = MemoryStatus.Indexing(n, minOf(entries.size, MAX_FILES), e.path)
      val fileIndex = files.size
      val old = previousFiles[e.uri.toString()]
      val reused = old != null && old.second.modified == e.modified && old.second.size == e.size
      val oldIds = if (reused) previousChunks[old!!.first].orEmpty() else emptyList()
      val pieces: List<MemoryChunk> =
        if (reused) oldIds.map { previous!!.chunks[it].copy(file = fileIndex) } else readFile(context, e, fileIndex)
      if (pieces.isEmpty()) continue
      files.add(MemoryFile(e.uri.toString(), e.path, e.modified, e.size))
      chunks.addAll(pieces)
      if (reused) oldPosition.addAll(oldIds) else repeat(pieces.size) { oldPosition.add(-1) }
      totalChars += pieces.sumOf { it.text.length }
    }

    val built = MemoryIndex(files, chunks)
    val out = indexFile(context)
    out.parentFile?.mkdirs()
    val tmp = File(out.parentFile, "index.jsonl.tmp")
    tmp.bufferedWriter().use { built.write(it) }
    if (!tmp.renameTo(out)) {
      out.delete()
      tmp.renameTo(out)
    }
    // Vectors of the pieces kept (same model), the others still to compute.
    val oldVectors = vectors?.takeIf { it.model == Embedder.MODEL_NAME && it.dim == Embedder.DIM }
    val newVectors = VectorStore(Embedder.DIM, chunks.size, Embedder.MODEL_NAME)
    if (oldVectors != null) {
      for ((i, from) in oldPosition.withIndex()) {
        if (from >= 0 && from < oldVectors.count && oldVectors.has(from)) newVectors.copyFrom(oldVectors, from, i)
      }
    }
    index = built
    vectors = newVectors
    val now = System.currentTimeMillis()
    prefs(context).edit().putLong(KEY_INDEXED_AT, now).apply()
    _status.value = MemoryStatus.Ready(files.size, chunks.size, now, truncated)
    if (isSemanticEnabled(context)) {
      computeVectors(context, built, newVectors)
      _status.value = MemoryStatus.Ready(files.size, chunks.size, now, truncated)
    } else {
      saveVectors(context, newVectors)
    }
  }

  /** Computes the missing vectors, pausing while the chat writes an answer (same CPU). */
  private suspend fun computeVectors(context: Context, built: MemoryIndex, store: VectorStore) {
    val missing = (0 until store.count).filter { !store.has(it) }
    for ((n, i) in missing.withIndex()) {
      if (com.google.ai.edge.gallery.runtime.SharedModelSession.chatGenerating.value) {
        com.google.ai.edge.gallery.runtime.SharedModelSession.chatGenerating.first { !it }
      }
      _status.value = MemoryStatus.Embedding(n, missing.size)
      val chunk = built.chunks[i]
      val title = built.files[chunk.file].path.substringAfterLast('/')
      Embedder.embedPassage(context, title, chunk.text)?.let { store.set(i, it) }
      if ((n + 1) % VECTOR_CHECKPOINT == 0) saveVectors(context, store)
    }
    saveVectors(context, store)
  }

  private fun saveVectors(context: Context, store: VectorStore) {
    val out = vectorsFile(context)
    out.parentFile?.mkdirs()
    val tmp = File(out.parentFile, "vectors.bin.tmp")
    java.io.DataOutputStream(tmp.outputStream().buffered()).use { store.write(it) }
    if (!tmp.renameTo(out)) {
      out.delete()
      tmp.renameTo(out)
    }
  }

  private fun loadFromDisk(context: Context) {
    val file = indexFile(context)
    if (!file.exists()) return
    index = runCatching { file.bufferedReader().use { MemoryIndex.read(it) } }.getOrNull()
    vectors =
      runCatching {
          java.io.DataInputStream(vectorsFile(context).inputStream().buffered()).use { VectorStore.read(it) }
        }
        .getOrNull()
        ?.takeIf { it.count == index?.chunks?.size }
    index?.let {
      _status.value =
        MemoryStatus.Ready(it.files.size, it.chunks.size, prefs(context).getLong(KEY_INDEXED_AT, 0L), false)
    }
  }

  /** Lists the readable files under a folder (fast: one query per folder, no file opened). */
  private fun listTree(
    context: Context,
    tree: Uri,
    parentId: String,
    parentPath: String,
    depth: Int,
    out: MutableList<Entry>,
  ) {
    if (depth > MAX_DEPTH || out.size > MAX_FILES) return
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val columns =
      arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
      )
    context.contentResolver.query(children, columns, null, null, null)?.use { c ->
      while (c.moveToNext()) {
        val id = c.getString(0) ?: continue
        val name = c.getString(1) ?: continue
        val mime = c.getString(2).orEmpty()
        val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
          if (name.startsWith(".") || name.lowercase() in SKIPPED_DIRS) continue
          listTree(context, tree, id, path, depth + 1, out)
        } else if (isSupported(name)) {
          out.add(Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), path, name, c.getLong(3), c.getLong(4)))
        }
      }
    }
  }

  private fun isSupported(name: String): Boolean {
    val lower = name.lowercase()
    if (lower == "dockerfile" || lower == "makefile" || lower == ".env") return true
    if (lower.startsWith(".")) return false
    val ext = lower.substringAfterLast('.', "")
    return ext == "pdf" || ext in TEXT_EXTENSIONS
  }

  private suspend fun readFile(context: Context, e: Entry, fileIndex: Int): List<MemoryChunk> {
    val isPdf = e.name.lowercase().endsWith(".pdf")
    if (e.size > (if (isPdf) MAX_PDF_BYTES else MAX_TEXT_BYTES)) return emptyList()
    return try {
      val doc = DocumentReader.read(context, e.uri)
      val out = ArrayList<MemoryChunk>()
      for ((p, text) in doc.pageTexts.withIndex()) {
        out.addAll(MemoryIndex.chunkText(text, fileIndex, if (doc.isPdf) p + 1 else 0, out.size))
      }
      out
    } catch (ex: Exception) {
      // Unreadable (scanned PDF, binary, encrypted…): skipped.
      Log.w(TAG, "Skipped ${e.path}: ${ex.message}")
      emptyList()
    }
  }
}
