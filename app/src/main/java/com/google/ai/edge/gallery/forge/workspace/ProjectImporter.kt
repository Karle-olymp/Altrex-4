package com.google.ai.edge.gallery.forge.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.ai.edge.gallery.terminal.LinuxEnv
import java.io.File
import java.io.InputStream

/**
 * [ALTREX] Brings projects into Forge's folder: a folder of the phone (copied, the original is not
 * touched), the project of the former Code screen (migration), or a Git repository (cloned in the
 * model's Linux). Every project that comes from outside starts « non fiable »: its ALTREX.md is
 * not given to the model until the user allows it.
 */
object ProjectImporter {
  /** A folder to copy, from the phone's storage (SAF) or, in tests, from plain files. */
  interface Node {
    val name: String
    val isDirectory: Boolean
    val size: Long

    fun children(): List<Node>

    fun open(): InputStream?
  }

  data class Report(val files: Int, val bytes: Long, val skipped: Int, val truncated: Boolean)

  /** Folders not copied: rebuilt by their tools (npm install, gradle…). */
  val SKIPPED_DIRS = setOf("node_modules", ".gradle", "build", "dist", ".next", "__pycache__", ".venv", "venv", ".cache")

  const val MAX_FILES = 20_000
  const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
  const val MAX_FILE_BYTES = 100L * 1024 * 1024

  /** Copies [source] into [target] (created). Stops at the limits; never follows a path out of [target]. */
  fun copyTree(source: Node, target: File): Report {
    target.mkdirs()
    var files = 0
    var bytes = 0L
    var skipped = 0
    var truncated = false
    val targetRoot = target.canonicalFile

    fun visit(node: Node, dir: File, depth: Int) {
      if (truncated) return
      for (c in node.children()) {
        if (truncated) return
        val name = c.name
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\u0000')) {
          skipped++
          continue
        }
        val out = File(dir, name)
        if (!out.canonicalFile.path.startsWith(targetRoot.path)) {
          skipped++
          continue
        }
        if (c.isDirectory) {
          if (name in SKIPPED_DIRS || depth > 30) {
            skipped++
            continue
          }
          out.mkdirs()
          visit(c, out, depth + 1)
        } else {
          if (files >= MAX_FILES || bytes + c.size > MAX_TOTAL_BYTES) {
            truncated = true
            return
          }
          if (c.size > MAX_FILE_BYTES) {
            skipped++
            continue
          }
          val input = runCatching { c.open() }.getOrNull()
          if (input == null) {
            skipped++
            continue
          }
          val n = input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
          files++
          bytes += n
        }
      }
    }
    visit(source, target, 0)
    return Report(files, bytes, skipped, truncated)
  }

  class FileNode(private val f: File) : Node {
    override val name: String = f.name
    override val isDirectory: Boolean = f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())
    override val size: Long = f.length()

    override fun children(): List<Node> = if (isDirectory) f.listFiles()?.sortedBy { it.name }?.map { FileNode(it) }.orEmpty() else emptyList()

    override fun open(): InputStream? = if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) f.inputStream() else null
  }

  private class DocNode(private val context: Context, private val d: DocumentFile) : Node {
    override val name: String = d.name.orEmpty()
    override val isDirectory: Boolean = d.isDirectory
    override val size: Long = d.length()

    override fun children(): List<Node> = d.listFiles().map { DocNode(context, it) }

    override fun open(): InputStream? = context.contentResolver.openInputStream(d.uri)
  }

  /** Copies the folder [treeUri] (picked by the user) as a new project. Returns its name. */
  fun importFolder(context: Context, workspace: ForgeWorkspace, treeUri: Uri, origin: String = ForgeWorkspace.ORIGIN_IMPORTED): Result<Pair<String, Report>> =
    runCatching {
      val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Dossier illisible.")
      val name = workspace.freeName(root.name ?: "projet")
      workspace.create(name, origin = origin, trusted = false).getOrThrow()
      val report = copyTree(DocNode(context, root), File(workspace.projectsDir, name))
      workspace.registerImported(name, origin)
      name to report
    }

  // ------------------------------------------------------------------------------------- migration

  private const val OLD_PREFS = "code_mode_project_prefs"
  private const val OLD_KEY = "project_folder_uri"
  private const val MIGRATED_KEY = "forge_migrated_uri"

  /** The project folder of the former Code screen, when it is still readable and not yet brought in. */
  fun pendingOldProject(context: Context): Pair<Uri, String>? {
    val p = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
    val raw = p.getString(OLD_KEY, null) ?: return null
    if (p.getString(MIGRATED_KEY, null) == raw) return null
    val uri = Uri.parse(raw)
    val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    if (!granted) return null
    val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() ?: "projet"
    return uri to name
  }

  /** Copies the former Code project into Forge; the original folder stays where it was. */
  fun migrateOldProject(context: Context, workspace: ForgeWorkspace): Result<Pair<String, Report>> {
    val (uri, _) = pendingOldProject(context) ?: return Result.failure(IllegalStateException("Aucun ancien projet Code à importer."))
    val r = importFolder(context, workspace, uri, ForgeWorkspace.ORIGIN_MIGRATED)
    if (r.isSuccess) dismissOldProject(context)
    return r
  }

  /** « Plus tard / non » : the card is not shown again for this folder. */
  fun dismissOldProject(context: Context) {
    val p = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
    p.getString(OLD_KEY, null)?.let { p.edit().putString(MIGRATED_KEY, it).apply() }
  }

  // ------------------------------------------------------------------------------------- clone

  private val REPO_URL = Regex("^https://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9._~/-]+$")

  /** Null when [url] can be cloned (https, no options, no spaces), else why not. */
  fun checkRepoUrl(url: String): String? {
    val u = url.trim()
    return when {
      u.isEmpty() -> "Colle l'adresse du dépôt (https://…)."
      !u.startsWith("https://") -> "Seules les adresses https:// sont acceptées."
      !REPO_URL.matches(u) -> "Adresse de dépôt invalide."
      else -> null
    }
  }

  /** "https://github.com/moi/mon-site.git" → "mon-site". */
  fun repoName(url: String): String = url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git")

  /**
   * Clones [url] into a new project with Git in the model's Linux (installed with apk when missing).
   * The URL and the folder are arguments of the script, never pasted in it.
   */
  suspend fun clone(context: Context, workspace: ForgeWorkspace, url: String, onProgress: (String) -> Unit): Result<String> {
    checkRepoUrl(url)?.let { return Result.failure(IllegalArgumentException(it)) }
    workspace.ensure()
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    if (!env.isSupported) return Result.failure(IllegalStateException("Le Linux n'est pas disponible sur cet appareil."))
    if (!env.isInstalled) {
      onProgress("Installation du Linux…")
      env.install { onProgress(it) }.exceptionOrNull()?.let { return Result.failure(it) }
    }
    val name = workspace.freeName(repoName(url))
    onProgress("Clonage de ${repoName(url)}…")
    val script =
      "command -v git >/dev/null 2>&1 || apk add --no-progress git >/dev/null 2>&1 || { echo 'git introuvable et impossible à installer'; exit 3; }\n" +
        "exec git clone --depth 50 -- \"\$1\" \"\$2\" 2>&1\n"
    val r = env.exec(listOf("/bin/sh", "-lc", script, "sh", url.trim(), "${LinuxEnv.PROJECTS_PATH}/$name"), null, 600_000) { chunk ->
      chunk.trim().lineSequence().lastOrNull()?.takeIf { it.isNotBlank() }?.let { onProgress(it.take(80)) }
    }
    if (r.exitCode != 0 || !workspace.exists(name)) {
      File(workspace.projectsDir, name).deleteRecursively()
      return Result.failure(IllegalStateException("Le clonage a échoué : " + r.output.trim().lines().takeLast(3).joinToString(" ").take(300)))
    }
    workspace.registerImported(name, ForgeWorkspace.ORIGIN_CLONED)
    return Result.success(name)
  }
}
