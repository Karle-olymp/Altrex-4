package com.google.ai.edge.gallery.forge.checkpoints

import java.io.File

/**
 * [ALTREX] Runs git on the SHADOW repository of one project: the real one sets GIT_DIR (the shadow
 * folder) and GIT_WORK_TREE (the project), so the project's own `.git` is never read nor written.
 */
fun interface GitRunner {
  data class Out(val code: Int, val text: String)

  suspend fun git(args: List<String>): Out
}

/**
 * [ALTREX] Snapshots as commits of a shadow repository (one per project, out of the project:
 * `/root/projets/.instantanes/<projet>.git`). Its own exclusions ([ProjectScan]) plus the project's
 * `.gitignore`; files over 2 MB are excluded at each snapshot. Never touches the user's branch.
 */
class GitSnapshotStore(private val runner: GitRunner, private val root: File, private val gitDirHost: File) : SnapshotStore {
  override val kind = "git"

  private var ready = false

  private suspend fun git(vararg args: String): GitRunner.Out = runner.git(BASE + args.toList())

  private suspend fun ok(vararg args: String): String {
    val r = git(*args)
    if (r.code != 0) throw IllegalStateException("git ${args.firstOrNull()} : ${r.text.trim().take(300)}")
    return r.text
  }

  private suspend fun ensure() {
    if (ready) return
    if (!File(gitDirHost, "HEAD").isFile) {
      gitDirHost.mkdirs()
      ok("init", "-q")
    }
    ready = true
  }

  private fun writeExclude(tooBig: List<String>) {
    val info = File(gitDirHost, "info").apply { mkdirs() }
    val lines = ArrayList<String>()
    lines.add("# Forge : exclusions du dépôt fantôme (réécrit à chaque instantané)")
    for (d in ProjectScan.SKIPPED_DIRS) if (d != ".altrex") lines.add("$d/")
    lines.add("/.altrex/*")
    for (k in ProjectScan.ALTREX_KEPT) lines.add("!/.altrex/$k/")
    lines.add("*.forge-*.tmp")
    for (b in tooBig) lines.add("/" + b.replace("[", "\\[").replace("*", "\\*").replace("?", "\\?"))
    File(info, "exclude").writeText(lines.joinToString("\n") + "\n")
  }

  override suspend fun snapshot(label: String): String {
    ensure()
    writeExclude(ProjectScan.scan(root).tooBig)
    ok("add", "-A", "--", ".")
    ok("commit", "-q", "--allow-empty", "--no-verify", "-m", label)
    return ok("rev-parse", "HEAD").trim()
  }

  override suspend fun changes(from: String, to: String): List<FileChange> {
    ensure()
    val kinds = LinkedHashMap<String, String>()
    val status = ok("diff", "--no-renames", "--name-status", "-z", from, to).split('\u0000')
    var i = 0
    while (i + 1 < status.size) {
      val k = status[i].trim()
      if (k.isEmpty()) {
        i++
        continue
      }
      kinds[status[i + 1]] = k.take(1)
      i += 2
    }
    val counts = HashMap<String, Triple<Int, Int, Boolean>>()
    for (rec in ok("diff", "--no-renames", "--numstat", "-z", from, to).split('\u0000')) {
      val parts = rec.trim('\n').split('\t')
      if (parts.size < 3) continue
      val bin = parts[0] == "-"
      counts[parts[2]] = Triple(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0, bin)
    }
    return kinds.map { (p, k) ->
      val c = counts[p]
      FileChange(p, if (k == "A" || k == "D") k else "M", c?.first ?: 0, c?.second ?: 0, c?.third ?: false)
    }
  }

  override suspend fun content(rev: String, path: String): ByteArray? {
    ensure()
    val r = git("cat-file", "-p", "$rev:$path")
    return if (r.code == 0) r.text.toByteArray(Charsets.UTF_8) else null
  }

  override suspend fun restore(rev: String, paths: List<String>) {
    if (paths.isEmpty()) return
    ensure()
    val present = ok("ls-tree", "-r", "--name-only", "-z", rev, "--", *paths.toTypedArray()).split('\u0000').filter { it.isNotEmpty() }.toSet()
    val back = paths.filter { it in present }
    for (chunk in back.chunked(100)) ok("checkout", rev, "--", *chunk.toTypedArray())
    for (p in paths - present) {
      val f = File(root, p)
      if (f.isFile && !f.delete()) throw java.io.IOException("Impossible de supprimer $p")
    }
  }

  companion object {
    /** Same identity and settings everywhere; nothing from the user's own git configuration. */
    val BASE =
      listOf(
        "-c", "user.name=Altrex Forge",
        "-c", "user.email=forge@altrex.invalid",
        "-c", "commit.gpgsign=false",
        "-c", "core.autocrlf=false",
        "-c", "core.quotepath=false",
        "-c", "core.hooksPath=/dev/null",
        "-c", "gc.auto=200",
      )
  }
}
