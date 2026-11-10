package com.google.ai.edge.gallery.forge.checkpoints

/**
 * [ALTREX] /undo and /diff over a [SnapshotStore] (docs/forge/FORGE.md §13, ideas of Cline and
 * Aider, rewritten):
 * - /undo puts back the files the agent changed in one turn (snapshot « avant » → « après »), but
 *   never a file the user changed himself since the end of that turn: those are left alone and
 *   listed;
 * - the restoration is transactional: a backup snapshot is taken first, and if anything fails the
 *   files already put back are restored from it;
 * - nothing ever touches the user's own Git history.
 */
class Checkpoints(private val store: SnapshotStore) {
  /** What /undo would do, computed before asking the user (nothing is changed yet). */
  data class UndoPlan(
    val turn: Int,
    val pre: String,
    /** Backup of the project as it is now (taken while planning). */
    val now: String,
    val restore: List<FileChange>,
    /** Files the agent changed in that turn, and the user since: left alone. */
    val conflicts: List<String>,
  )

  data class UndoResult(val restored: List<String>, val skipped: List<String>, val backup: String, val after: String)

  /**
   * [post] null: the turn ended without its « après » snapshot (stopped, app closed); the current
   * state stands for it, so changes made by the user since cannot be told apart.
   */
  suspend fun planUndo(turn: Int, pre: String, post: String?): UndoPlan {
    val now = store.snapshot("Forge : avant annulation du tour $turn")
    val end = post ?: now
    val agent = store.changes(pre, end)
    val user = if (post == null) emptySet() else store.changes(post, now).map { it.path }.toSet()
    return UndoPlan(turn, pre, now, agent.filter { it.path !in user }, agent.map { it.path }.filter { it in user })
  }

  suspend fun applyUndo(plan: UndoPlan): UndoResult {
    val paths = plan.restore.map { it.path }
    try {
      store.restore(plan.pre, paths)
    } catch (e: Exception) {
      // Transaction: back to the state of just before /undo.
      runCatching { store.restore(plan.now, paths) }
      throw IllegalStateException("Annulation impossible, rien n'a été changé : ${e.message ?: e.javaClass.simpleName}", e)
    }
    val after = store.snapshot("Forge : tour ${plan.turn} annulé")
    return UndoResult(paths, plan.conflicts, plan.now, after)
  }

  /** Files changed between [from] and the project now (a fresh snapshot), with their diff lines. */
  suspend fun diffToNow(from: String): Pair<String, List<Pair<FileChange, String>>> {
    val now = store.snapshot("Forge : /diff")
    return now to diff(from, now)
  }

  suspend fun diff(from: String, to: String): List<Pair<FileChange, String>> =
    store.changes(from, to).map { c -> c to if (c.binary) "…\t \tfichier binaire" else store.patch(from, to, c.path) }
}
