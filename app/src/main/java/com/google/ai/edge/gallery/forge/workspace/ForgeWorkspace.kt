package com.google.ai.edge.gallery.forge.workspace

import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.journal.SessionJournal
import com.google.ai.edge.gallery.forge.journal.SessionState
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import java.io.File
import org.json.JSONObject

/**
 * [ALTREX] Forge's working folder, shared by both Linux environments (docs/forge/FORGE.md §1):
 * - [projectsDir] holds the projects; PRoot binds it as `/root/projets` in the user's terminal
 *   and in the model's Linux, so a file written by one is seen at once by the other;
 * - [metaDir] keeps what Forge knows of each project (origin, trusted or not);
 * - [sessionsDir] keeps the session journals, out of the projects (the model cannot edit them);
 * - [globalMemory] is the user's own ALTREX.md, read in every project.
 *
 * Plain files only, no Android: unit tested on the computer, and readable by a future PC version.
 */
class ForgeWorkspace(val base: File) {
  val projectsDir = File(base, "projets")
  val metaDir = File(base, "meta")
  val sessionsDir = File(base, "sessions")
  val globalMemory = File(base, "ALTREX.md")
  /** Copies of the projects for /undo when the Linux has no git ([com.google.ai.edge.gallery.forge.checkpoints.CopySnapshotStore]). */
  val snapshotsDir = File(base, "instantanes")

  data class ProjectInfo(
    val name: String,
    val dir: File,
    /** "Next.js", "Python", "Node"… guessed from the files, or "". */
    val kind: String,
    val branch: String?,
    val updated: Long,
    val trusted: Boolean,
    val origin: String,
  )

  data class SessionInfo(
    val project: String,
    val id: String,
    val title: String,
    val updated: Long,
    /** null (never ended), "done", "interrupted", "stopped"… */
    val lastEnd: String?,
    val todosDone: Int,
    val todosTotal: Int,
    val file: File,
  )

  fun ensure() {
    projectsDir.mkdirs()
    metaDir.mkdirs()
    sessionsDir.mkdirs()
  }

  fun paths(name: String): ProjectPaths = ProjectPaths(File(projectsDir, name), "$LINUX_ROOT/$name")

  fun exists(name: String): Boolean = File(projectsDir, name).isDirectory

  // ------------------------------------------------------------------------------------- projects

  /** A free, valid folder name close to [wanted] ("Mon Site!" → "mon-site", then "mon-site-2"). */
  fun freeName(wanted: String): String {
    val base = slug(wanted).ifEmpty { "projet" }
    if (!exists(base)) return base
    var i = 2
    while (exists("$base-$i")) i++
    return "$base-$i"
  }

  /** Creates an empty project (trusted: it is the user's). Returns its name, or an error message. */
  fun create(wanted: String, origin: String = ORIGIN_CREATED, trusted: Boolean = true): Result<String> {
    ensure()
    checkName(wanted)?.let { return Result.failure(IllegalArgumentException(it)) }
    val name = wanted.trim()
    val dir = File(projectsDir, name)
    if (dir.exists()) return Result.failure(IllegalArgumentException("Un projet « $name » existe déjà."))
    if (!dir.mkdirs()) return Result.failure(IllegalStateException("Impossible de créer le dossier du projet."))
    writeMeta(name, trusted, origin)
    return Result.success(name)
  }

  fun list(): List<ProjectInfo> =
    projectsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { info(it.name) }?.sortedByDescending { it.updated }.orEmpty()

  fun info(name: String): ProjectInfo {
    val dir = File(projectsDir, name)
    val meta = readMeta(name)
    val lastSession = sessionFiles(name).maxOfOrNull { it.lastModified() } ?: 0L
    return ProjectInfo(
      name = name,
      dir = dir,
      kind = detectKind(dir),
      branch = gitBranch(dir),
      updated = maxOf(dir.lastModified(), lastSession),
      trusted = meta?.optBoolean("trusted", true) ?: true,
      origin = meta?.optString("origin", ORIGIN_CREATED) ?: ORIGIN_CREATED,
    )
  }

  fun setTrusted(name: String, trusted: Boolean) {
    val m = readMeta(name)
    writeMeta(name, trusted, m?.optString("origin") ?: ORIGIN_CREATED)
  }

  /** Records a project added from outside (import, clone, migration): not trusted until the user says. */
  fun registerImported(name: String, origin: String) = writeMeta(name, trusted = false, origin = origin)

  /** Deletes the project folder, its sessions and its metadata. */
  fun delete(name: String): Boolean {
    if (checkName(name) != null) return false
    File(sessionsDir, name).deleteRecursively()
    File(metaDir, "$name.json").delete()
    File(snapshotsDir, name).deleteRecursively()
    File(File(projectsDir, SHADOW_DIR), "$name.git").deleteRecursively()
    return File(projectsDir, name).deleteRecursively()
  }

  private fun readMeta(name: String): JSONObject? =
    File(metaDir, "$name.json").takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

  private fun writeMeta(name: String, trusted: Boolean, origin: String) {
    metaDir.mkdirs()
    val o = JSONObject().put("name", name).put("trusted", trusted).put("origin", origin).put("updated", System.currentTimeMillis())
    com.google.ai.edge.gallery.forge.tools.FileTools.writeAtomically(File(metaDir, "$name.json"), o.toString(2).toByteArray())
  }

  // ------------------------------------------------------------------------------------- sessions

  private fun sessionFiles(project: String): List<File> =
    File(sessionsDir, project).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.toList().orEmpty()

  /** A new session journal, started with its first event. */
  fun newSession(project: String, model: String, mode: String, now: Long = System.currentTimeMillis()): SessionJournal {
    val id = "s" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(now)) +
      "-" + Integer.toHexString((now % 4096).toInt())
    val j = SessionJournal(File(File(sessionsDir, project), "$id.jsonl"))
    j.append(ForgeEvent.SessionStart(id, project, model, mode, ""))
    return j
  }

  fun openSession(project: String, id: String): SessionJournal? {
    val f = File(File(sessionsDir, project), "$id.jsonl")
    return if (f.isFile) SessionJournal.load(f) else null
  }

  private val infoCache = HashMap<String, Pair<Long, SessionInfo>>()

  /** Sessions of [project] (or of every project), most recent first. */
  fun sessions(project: String? = null): List<SessionInfo> {
    val names = if (project != null) listOf(project) else list().map { it.name }
    return names.flatMap { p -> sessionFiles(p).mapNotNull { sessionInfo(p, it) } }.sortedByDescending { it.updated }
  }

  private fun sessionInfo(project: String, f: File): SessionInfo? {
    val key = f.path
    infoCache[key]?.let { (stamp, info) -> if (stamp == f.lastModified()) return info }
    val events =
      runCatching {
          f.readLines().mapNotNull { line -> runCatching { ForgeEvent.fromJson(JSONObject(line)) }.getOrNull() }
        }
        .getOrNull() ?: return null
    if (events.none { it is ForgeEvent.User }) return null
    val s = SessionState.fold(events)
    val end = if (s.turnOpen) null else s.lastEnd
    val info =
      SessionInfo(project, f.nameWithoutExtension, s.title.ifEmpty { "Session" }, maxOf(s.updated, f.lastModified()), end,
        s.todos.count { it.status == "completed" }, s.todos.size, f)
    infoCache[key] = f.lastModified() to info
    return info
  }

  companion object {
    const val LINUX_ROOT = "/root/projets"
    /** Shadow Git repositories of the projects (/undo, /diff), next to them: `/root/projets/.instantanes/<projet>.git`. */
    const val SHADOW_DIR = ".instantanes"
    const val ORIGIN_CREATED = "créé"
    const val ORIGIN_IMPORTED = "importé"
    const val ORIGIN_CLONED = "cloné"
    const val ORIGIN_MIGRATED = "ancien Code"

    private val NAME = Regex("^[a-z0-9][a-z0-9._-]{0,39}$")

    /** Null when [name] is a valid project name, else why not (in French). */
    fun checkName(name: String): String? {
      val n = name.trim()
      return when {
        n.isEmpty() -> "Donne un nom au projet."
        n.length > 40 -> "Le nom fait au plus 40 caractères."
        n == "." || n == ".." || n.contains("..") -> "Ce nom n'est pas permis."
        !NAME.matches(n) -> "Lettres minuscules sans accent, chiffres, « - », « _ » ou « . » seulement (ex. mon-site)."
        else -> null
      }
    }

    /** "Mon Site Vitrine !" → "mon-site-vitrine". */
    fun slug(text: String): String {
      val noAccents = java.text.Normalizer.normalize(text.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
      return noAccents.replace(Regex("[^a-z0-9._-]+"), "-").replace(Regex("-{2,}"), "-").trim('-', '.', '_').take(40).trimEnd('-', '.', '_')
    }

    /** The current Git branch, read from .git/HEAD (no Git needed). */
    fun gitBranch(dir: File): String? {
      val head = File(dir, ".git/HEAD").takeIf { it.isFile } ?: return null
      val text = runCatching { head.readText().trim() }.getOrNull() ?: return null
      return if (text.startsWith("ref: refs/heads/")) text.removePrefix("ref: refs/heads/") else text.take(7).ifEmpty { null }
    }

    /** A short label of the project's stack, guessed from its files. */
    fun detectKind(dir: File): String {
      fun has(name: String) = File(dir, name).exists()
      if (has("package.json")) {
        val pkg = runCatching { File(dir, "package.json").readText() }.getOrDefault("")
        return when {
          pkg.contains("\"next\"") -> "Next.js"
          pkg.contains("\"react\"") -> "React"
          pkg.contains("\"vue\"") -> "Vue"
          pkg.contains("\"svelte\"") -> "Svelte"
          else -> "Node"
        }
      }
      return when {
        has("pyproject.toml") || has("requirements.txt") || has("main.py") -> "Python"
        has("Cargo.toml") -> "Rust"
        has("go.mod") -> "Go"
        has("build.gradle.kts") || has("build.gradle") -> "Kotlin"
        has("index.html") -> "Web"
        else -> ""
      }
    }
  }
}
