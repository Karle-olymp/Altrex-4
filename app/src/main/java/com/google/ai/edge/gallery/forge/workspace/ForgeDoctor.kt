package com.google.ai.edge.gallery.forge.workspace

import android.content.Context
import com.google.ai.edge.gallery.terminal.LinuxEnv

/**
 * [ALTREX] `/doctor`: really runs each tool (`--version`, with a delay) instead of looking for its
 * file, so it tells « absent » from « cassé » and « trop lent » (precious under PRoot), gives the
 * repair to do, and writes a summary the model reads at its next turn. It only checks: installing
 * stays the user's decision.
 */
object ForgeDoctor {
  data class Check(val name: String, val command: String, val fix: String)

  enum class State(val label: String) { OK("ok"), ABSENT("absent"), CASSE("cassé"), LENT("trop lent") }

  data class Line(val check: Check, val state: State, val detail: String)

  val CHECKS =
    listOf(
      Check("shell", "echo ok", "Réinstalle le Linux du modèle (Réglages → Linux)."),
      Check("git", "git --version", "apk add git (ou altrex-setup)"),
      Check("node", "node --version", "apk add nodejs (ou altrex-setup)"),
      Check("npm", "npm --version", "apk add npm (ou altrex-setup)"),
      Check("python3", "python3 --version", "apk add python3 (ou altrex-setup)"),
      Check("pip", "pip3 --version", "apk add py3-pip (ou altrex-setup)"),
      Check("curl", "curl --version", "apk add curl (ou altrex-setup)"),
      Check("gcc", "gcc --version", "altrex-setup --dev (compilateurs)"),
    )

  const val TIMEOUT_MS = 15_000L

  /** What a run says about a tool. */
  fun classify(exitCode: Int, output: String, timedOut: Boolean): State =
    when {
      timedOut -> State.LENT
      exitCode == 127 || output.contains("not found", ignoreCase = true) || output.contains("No such file") -> State.ABSENT
      exitCode != 0 -> State.CASSE
      else -> State.OK
    }

  /** First meaningful line of a version output. */
  fun version(output: String): String = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(60)

  /** The report shown in the feed (Markdown) and given to the model. */
  fun report(linux: String, lines: List<Line>, model: String, budgetChars: Int, protocol: String, projectVisible: Boolean?): String {
    val sb = StringBuilder("**/doctor**\n\n")
    sb.append("- Linux : ").append(linux).append('\n')
    projectVisible?.let { sb.append("- Dossier du projet dans le Linux : ").append(if (it) "visible" else "INVISIBLE (montage /root/projets)").append('\n') }
    sb.append("- Modèle : ").append(model).append(" · ").append(protocol).append(" · contexte ≈ ").append(budgetChars / 3 / 1000).append(" k jetons\n\n")
    for (l in lines) {
      sb.append("- `").append(l.check.name).append("` : ").append(l.state.label)
      if (l.detail.isNotEmpty()) sb.append(" (").append(l.detail).append(')')
      if (l.state != State.OK) sb.append(" → ").append(l.check.fix)
      sb.append('\n')
    }
    val missing = lines.filter { it.state != State.OK }
    sb.append('\n')
    sb.append(
      if (missing.isEmpty()) "Tout est prêt."
      else "À réparer : ${missing.joinToString(", ") { it.check.name }}. Forge n'installe rien sans ton accord."
    )
    return sb.toString()
  }

  /** Runs every check in the model's Linux (installed only if it already is). */
  suspend fun run(context: Context, project: String, model: String, budgetChars: Int, protocol: String): String {
    val env = LinuxEnv.of(context, LinuxEnv.AGENT)
    if (!env.isSupported) return report("indisponible sur cet appareil (arm64 requis)", emptyList(), model, budgetChars, protocol, null)
    if (!env.isInstalled) {
      return report("pas encore installé (il le sera à la première commande de l'agent)", emptyList(), model, budgetChars, protocol, null)
    }
    val lines =
      CHECKS.map { c ->
        val r = runCatching { env.exec(listOf("/bin/sh", "-lc", c.command + " 2>&1"), null, TIMEOUT_MS) }.getOrNull()
        if (r == null) Line(c, State.CASSE, "lancement impossible")
        else {
          val s = classify(r.exitCode, r.output, r.timedOut)
          Line(c, s, if (s == State.OK) version(r.output) else if (s == State.CASSE) "code ${r.exitCode}" else "")
        }
      }
    val visible =
      runCatching { env.exec(listOf("/bin/sh", "-lc", "test -d \"\$1\"", "sh", "${LinuxEnv.PROJECTS_PATH}/$project"), null, TIMEOUT_MS).exitCode == 0 }
        .getOrNull()
    return report("Alpine ${LinuxEnv.ALPINE_VERSION} (PRoot), installé", lines, model, budgetChars, protocol, visible)
  }
}
