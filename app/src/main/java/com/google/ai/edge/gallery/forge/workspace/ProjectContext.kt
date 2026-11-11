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
    cachedLight = null
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

  private var cachedLight: String? = null

  override fun lightPrompt(): String =
    cachedLight
      ?: buildString {
          append(ForgePrompts.LIGHT_INSTRUCTIONS)
          append("\n")
          append(
            ForgePrompts.lightEnvironment(
              project = project,
              linuxPath = paths.linuxPath,
              model = modelName(),
              date = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(Date(clock())),
            )
          )
          memory.startText(workspace.info(project).trusted)?.let { m ->
            append("\n\n").append(if (m.length > LIGHT_MEMORY_CHARS) m.take(LIGHT_MEMORY_CHARS) + "\n[… mémoire coupée en mode léger …]" else m)
          }
        }
        .also { cachedLight = it }

  override fun onPathTouched(rel: String): String? = memory.onPathTouched(rel, workspace.info(project).trusted)

  companion object {
    const val LIGHT_MEMORY_CHARS = 2_500
  }
}
