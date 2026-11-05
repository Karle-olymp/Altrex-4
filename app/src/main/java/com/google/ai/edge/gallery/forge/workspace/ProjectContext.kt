package com.google.ai.edge.gallery.forge.workspace

import com.google.ai.edge.gallery.forge.engine.ForgeContext
import com.google.ai.edge.gallery.forge.engine.ForgePrompts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [ALTREX] What the model knows of the project at the start of a session: Forge's instructions,
 * the environment, then the project memory (ALTREX.md, see [MemoryChain]). Built once per
 * session so the prompt stays the same from one step to the next.
 */
class ProjectContext(
  private val workspace: ForgeWorkspace,
  private val project: String,
  private val modelName: () -> String,
  private val clock: () -> Long = System::currentTimeMillis,
) : ForgeContext {
  private val paths = workspace.paths(project)
  val memory = MemoryChain(workspace, project)
  private var cached: String? = null

  /** Forget the built prompt (ALTREX.md written by /init, trust changed, other model). */
  fun invalidate() {
    cached = null
  }

  override fun systemPrompt(): String =
    cached
      ?: buildString {
          append(ForgePrompts.INSTRUCTIONS)
          append("\n\n")
          append(
            ForgePrompts.environment(
              project = project,
              linuxPath = paths.linuxPath,
              model = modelName(),
              date = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(Date(clock())),
              gitBranch = ForgeWorkspace.gitBranch(paths.root),
            )
          )
          memory.startText(workspace.info(project).trusted)?.let { append("\n\n").append(it) }
        }
        .also { cached = it }

  override fun onPathTouched(rel: String): String? = memory.onPathTouched(rel, workspace.info(project).trusted)
}
