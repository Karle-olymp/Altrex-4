package com.google.ai.edge.gallery.forge.checkpoints

import com.google.ai.edge.gallery.forge.tools.DiffLines
import com.google.ai.edge.gallery.forge.tools.FileTools
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** [ALTREX] One file that differs between two snapshots: "A" added, "M" modified, "D" deleted. */
data class FileChange(val path: String, val kind: String, val added: Int, val removed: Int, val binary: Boolean = false)

/**
 * [ALTREX] Snapshots of a Forge project, kept OUTSIDE the user's own Git history (a shadow store:
 * docs/forge/FORGE.md §13, « points de retour dans un dépôt fantôme »). Never a commit on the
 * user's branch, never a change to the project's `.git`.
 *
 * Two implementations: [GitSnapshotStore] (git of the Linux, a separate GIT_DIR) and, when git is
 * not there, [CopySnapshotStore] (copies made by the app, deduplicated by content).
 */
interface SnapshotStore {
  /** "git" or "copie" (shown by /checkpoints). */
  val kind: String

  /** Records the project as it is now; returns the snapshot's id. */
  suspend fun snapshot(label: String): String

  suspend fun changes(from: String, to: String): List<FileChange>

  /** The bytes of [path] in snapshot [rev], or null when it is not there. */
  suspend fun content(rev: String, path: String): ByteArray?

  /** Puts [paths] back as they are in [rev]: rewritten, or deleted when absent from it. */
  suspend fun restore(rev: String, paths: List<String>)
}

/** Diff lines ([DiffLines] format, the cards' one) of [path] between two snapshots. */
suspend fun SnapshotStore.patch(from: String, to: String, path: String): String {
  val a = content(from, path)
  val b = content(to, path)
  if ((a != null && FileTools.looksBinary(a)) || (b != null && FileTools.looksBinary(b))) return "…\t \tfichier binaire"
  return DiffLines.compute(a?.toString(Charsets.UTF_8).orEmpty(), b?.toString(Charsets.UTF_8).orEmpty(), maxShown = 400).text
}

/** [ALTREX] Which files of a project a snapshot covers: no heavy or generated folders, no huge file. */
object ProjectScan {
  /** Folders never snapshotted (dependencies, caches, Forge's own outputs). */
  val SKIPPED_DIRS = setOf(".git", "node_modules", ".gradle", "__pycache__", ".venv", "venv", ".next", ".cache", ".altrex")

  /** Inside `.altrex`, only these are snapshotted (the plans; not the outputs, not the attachments). */
  val ALTREX_KEPT = setOf("plans")

  const val MAX_FILE_BYTES = 2L * 1024 * 1024
  const val MAX_FILES = 5_000

  data class Result(val files: List<String>, val tooBig: List<String>, val truncated: Boolean)

  fun scan(root: File): Result {
    val files = ArrayList<String>()
    val big = ArrayList<String>()
    var truncated = false
    fun walk(dir: File, rel: String) {
      val list = dir.listFiles()?.sortedBy { it.name } ?: return
      for (f in list) {
        if (files.size >= MAX_FILES) {
          truncated = true
          return
        }
        val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
        if (f.isDirectory) {
          if (rel.isEmpty() && f.name == ".altrex") {
            for (k in ALTREX_KEPT) File(f, k).takeIf { it.isDirectory }?.let { walk(it, ".altrex/$k") }
            continue
          }
          if (f.name in SKIPPED_DIRS) continue
          walk(f, r)
        } else if (f.isFile) {
          if (f.name.contains(".forge-") && f.name.endsWith(".tmp")) continue
          if (f.length() > MAX_FILE_BYTES) big.add(r) else files.add(r)
        }
      }
    }
    walk(root, "")
    return Result(files, big, truncated)
  }

  fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  fun lineCounts(a: ByteArray?, b: ByteArray?): Pair<Int, Int> {
    if ((a != null && FileTools.looksBinary(a)) || (b != null && FileTools.looksBinary(b))) return 0 to 0
    val d = DiffLines.compute(a?.toString(Charsets.UTF_8).orEmpty(), b?.toString(Charsets.UTF_8).orEmpty(), maxShown = 0)
    return d.added to d.removed
  }
}

/**
 * [ALTREX] The fallback when the Linux has no git: each snapshot is a list path → content hash,
 * contents stored once (`objects/<sha>`), in the app's private storage (out of the project, so
 * neither the model's Bash nor the user's Git sees it). Files over 2 MB and the heavy folders of
 * [ProjectScan] are not covered.
 */
class CopySnapshotStore(private val root: File, private val dir: File, private val clock: () -> Long = System::currentTimeMillis) : SnapshotStore {
  override val kind = "copie"

  private val objects = File(dir, "objects")
  private val snaps = File(dir, "snaps")
  private var counter = 0

  /** Last known hash of each file, by size and modification time (avoids re-hashing everything). */
  private val cache = HashMap<String, Triple<Long, Long, String>>()

  override suspend fun snapshot(label: String): String {
    objects.mkdirs()
    snaps.mkdirs()
    val scan = ProjectScan.scan(root)
    val map = JSONObject()
    for (rel in scan.files) {
      val f = File(root, rel)
      val size = f.length()
      val mtime = f.lastModified()
      val known = cache[rel]
      val sha =
        if (known != null && known.first == size && known.second == mtime && File(objects, known.third).isFile) {
          known.third
        } else {
          val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
          val s = ProjectScan.sha(bytes)
          val obj = File(objects, s)
          if (!obj.isFile) FileTools.writeAtomically(obj, bytes)
          cache[rel] = Triple(size, mtime, s)
          s
        }
      map.put(rel, sha)
    }
    val id = "%013d-%03d".format(clock(), (counter++) % 1000)
    FileTools.writeAtomically(File(snaps, "$id.json"), JSONObject().put("label", label).put("files", map).toString().toByteArray())
    return id
  }

  private fun load(rev: String): Map<String, String> {
    val f = File(snaps, "$rev.json")
    if (!f.isFile) throw IllegalStateException("Instantané $rev introuvable.")
    val files = JSONObject(f.readText()).optJSONObject("files") ?: JSONObject()
    return files.keys().asSequence().associateWith { files.optString(it) }
  }

  override suspend fun changes(from: String, to: String): List<FileChange> {
    val a = load(from)
    val b = load(to)
    val out = ArrayList<FileChange>()
    for (p in (a.keys + b.keys).toSortedSet()) {
      val x = a[p]
      val y = b[p]
      if (x == y) continue
      val ca = x?.let { File(objects, it).takeIf { f -> f.isFile }?.readBytes() }
      val cb = y?.let { File(objects, it).takeIf { f -> f.isFile }?.readBytes() }
      val (add, rem) = ProjectScan.lineCounts(ca, cb)
      val bin = (ca != null && FileTools.looksBinary(ca)) || (cb != null && FileTools.looksBinary(cb))
      out.add(FileChange(p, if (x == null) "A" else if (y == null) "D" else "M", add, rem, bin))
    }
    return out
  }

  override suspend fun content(rev: String, path: String): ByteArray? = load(rev)[path]?.let { File(objects, it).takeIf { f -> f.isFile }?.readBytes() }

  override suspend fun restore(rev: String, paths: List<String>) {
    val map = load(rev)
    for (p in paths) {
      val target = File(root, p)
      val sha = map[p]
      if (sha == null) {
        if (target.isFile && !target.delete()) throw java.io.IOException("Impossible de supprimer $p")
      } else {
        val obj = File(objects, sha)
        if (!obj.isFile) throw java.io.IOException("Contenu de $p manquant dans l'instantané")
        FileTools.writeAtomically(target, obj.readBytes())
      }
    }
  }
}
