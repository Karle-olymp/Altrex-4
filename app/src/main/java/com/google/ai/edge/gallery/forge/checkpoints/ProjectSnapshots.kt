package com.google.ai.edge.gallery.forge.checkpoints

import android.content.Context
import com.google.ai.edge.gallery.forge.workspace.ForgeWorkspace
import com.google.ai.edge.gallery.terminal.LinuxEnv
import java.io.File

/** [ALTREX] git in the model's Linux, on the shadow repository of [project]. */
class LinuxGitRunner(private val context: Context, project: String) : GitRunner {
  private val gitDir = "${LinuxEnv.PROJECTS_PATH}/${ForgeWorkspace.SHADOW_DIR}/$project.git"
  private val work = "${LinuxEnv.PROJECTS_PATH}/$project"

  override suspend fun git(args: List<String>): GitRunner.Out {
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    val argv = listOf("env", "GIT_DIR=$gitDir", "GIT_WORK_TREE=$work", "GIT_TERMINAL_PROMPT=0", "LC_ALL=C", "git", "-C", work) + args
    val r = env.exec(argv, null, 120_000)
    return GitRunner.Out(if (r.timedOut) -1 else r.exitCode, r.output)
  }

  /** True when the Linux is installed and its git answers. */
  suspend fun available(): Boolean {
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    if (!env.isSupported || !env.isInstalled) return false
    return runCatching { env.exec(listOf("git", "--version"), null, 20_000).exitCode == 0 }.getOrDefault(false)
  }
}

/**
 * [ALTREX] The snapshot store of a project: git's shadow repository when the Linux has git, else
 * the app's copies ([CopySnapshotStore], the documented fallback: same /undo and /diff, files over
 * 2 MB not covered). The choice is made at the first snapshot; ids tell the two kinds apart (a git
 * id is 40 hex digits), so older snapshots of the other kind still work.
 */
class AutoSnapshotStore(
  private val git: SnapshotStore,
  private val copy: SnapshotStore,
  private val gitAvailable: suspend () -> Boolean,
) : SnapshotStore {
  @Volatile private var useGit: Boolean? = null

  override val kind: String
    get() = when (useGit) {
      true -> git.kind
      false -> copy.kind
      null -> "?"
    }

  private suspend fun current(): SnapshotStore {
    val g = useGit ?: gitAvailable().also { useGit = it }
    return if (g) git else copy
  }

  private fun of(rev: String): SnapshotStore = if (GIT_ID.matches(rev)) git else copy

  override suspend fun snapshot(label: String): String =
    try {
      current().snapshot(label)
    } catch (e: Exception) {
      // git there but failing (rights, full disk under PRoot): the copies take over.
      if (useGit == true) {
        useGit = false
        copy.snapshot(label)
      } else {
        throw e
      }
    }

  override suspend fun changes(from: String, to: String): List<FileChange> {
    val a = of(from)
    if (a !== of(to)) throw IllegalStateException("Ces deux instantanés ne sont pas de la même sorte (git et copie).")
    return a.changes(from, to)
  }

  override suspend fun content(rev: String, path: String): ByteArray? = of(rev).content(rev, path)

  override suspend fun restore(rev: String, paths: List<String>) = of(rev).restore(rev, paths)

  companion object {
    private val GIT_ID = Regex("^[0-9a-f]{40}$")

    fun forProject(context: Context, workspace: ForgeWorkspace, project: String): AutoSnapshotStore {
      val root = File(workspace.projectsDir, project)
      val runner = LinuxGitRunner(context.applicationContext, project)
      return AutoSnapshotStore(
        GitSnapshotStore(runner, root, File(File(workspace.projectsDir, ForgeWorkspace.SHADOW_DIR), "$project.git")),
        CopySnapshotStore(root, File(workspace.snapshotsDir, project)),
        runner::available,
      )
    }
  }
}
