package com.google.ai.edge.gallery.forge.engine

import com.google.ai.edge.gallery.tools.AgentShell

/** [ALTREX] What a Forge tool wants to do, as seen by [PermissionPolicy]. */
sealed class ForgeAction {
  /**
   * Create or change a file. [path] is relative to the project root, already normalized ("src/a.js"),
   * or null when the requested path is outside the project.
   */
  data class WriteFile(val path: String?, val isNew: Boolean) : ForgeAction()

  /** A shell command; [justification] is set when the model asks again after a refusal. */
  data class RunCommand(val command: String, val justification: String? = null) : ForgeAction()

  /** Key used to remember a refusal for this exact action. */
  fun key(): String =
    when (this) {
      is WriteFile -> "write:" + (path ?: "?")
      is RunCommand -> "cmd:" + command.trim().replace(Regex("\\s+"), " ")
    }
}

/** What to do with a [ForgeAction]. */
sealed class Decision {
  data object Allow : Decision()

  /** Refused without asking; [reason] goes back to the model. */
  data class Deny(val reason: String) : Decision()

  /**
   * Ask the user. [grant] is what « Toujours » would cover for the rest of the session: a program
   * name ("npm"), [GRANT_EDITS] for file changes, or null when « Toujours » is not offered.
   */
  data class Ask(
    val title: String,
    val detail: String,
    val risky: Boolean = false,
    val escalation: Boolean = false,
    val justification: String? = null,
    val grant: String? = null,
  ) : Decision()

  companion object {
    const val GRANT_EDITS = "*fichiers*"
  }
}

/**
 * [ALTREX] What the user allowed or refused during one session (« Toujours », « Non »). Kept in the
 * session journal, so a resumed session remembers it.
 */
class SessionGrants {
  val programs = LinkedHashSet<String>()
  var edits = false
  /** Action key → reason of the user's refusal. */
  val refused = LinkedHashMap<String, String>()
  /** Actions already asked again once with a justification (the one allowed escalation). */
  val escalated = LinkedHashSet<String>()

  fun grant(grant: String) {
    if (grant == Decision.GRANT_EDITS) edits = true else programs.add(grant)
  }
}

/**
 * [ALTREX] The single place where Forge decides whether an action runs, is asked, or is refused.
 * Pure function of the mode, the session's grants and the action: unit tested, never asks the
 * model to judge what is "safe". Reads inside the project, read-only commands and the tools that
 * change nothing (TodoWrite, AskUserQuestion, ExitPlanMode) never come here.
 *
 * Rules (docs/forge/OUTILS.md §Autorisations):
 * - Plan (sandbox [Sandbox.LECTURE]): only `blueprint/` and `.altrex/plans/` may be written; any
 *   other write or command that is not read-only is refused, with a reason that points to
 *   ExitPlanMode. No escalation: leaving the plan goes through the plan's button.
 * - A path outside the project is refused (Bash is there for the rest of the Linux).
 * - `.git/` and `.altrex/` (but `.altrex/plans/`) are protected: always asked, even with « Tout
 *   autoriser ».
 * - Dangerous commands (rm -rf /, curl | sh, sudo, mkfs, git push --force…) are always asked.
 * - [Sandbox.PROJET]: PRoot cannot keep a command inside the project, so any command that is not
 *   read-only is asked, whatever the approval axis.
 * - A refused action is refused again without asking, unless the model gives a justification: then
 *   it is asked once more (the one escalation), never twice.
 */
object PermissionPolicy {
  private val PLAN_DIRS = listOf("blueprint/", ".altrex/plans/")
  private val PLAN_REASON =
    "Mode Plan : lecture seule. Tu peux seulement écrire dans blueprint/ ou .altrex/plans/. " +
      "Termine ton plan et présente-le avec ExitPlanMode ; l'utilisateur le validera."

  fun isPlanPath(path: String): Boolean = PLAN_DIRS.any { path == it.trimEnd('/') || path.startsWith(it) }

  fun isProtected(path: String): Boolean {
    val p = path.trimStart('/')
    if (isPlanPath(p)) return false
    return p == ".git" || p.startsWith(".git/") || p == ".altrex" || p.startsWith(".altrex/")
  }

  private val DANGEROUS =
    listOf(
      Regex("""(^|[\s;&|(])rm\s+(-[a-zA-Z]*[rR][a-zA-Z]*\s+|--recursive\s+)+(-[a-zA-Z]+\s+)*("?)(/|~|\*|/\*|/root/?|\.\.|\${'$'}HOME)("?)(\s|$|;|&|\|)"""),
      Regex("""(curl|wget)\b[^|;&]*\|\s*(sudo\s+)?(sh|bash|ash|zsh|python3?)\b"""),
      Regex("""(^|[\s;&|(])sudo\s"""),
      Regex("""(^|[\s;&|(])mkfs(\.\w+)?\s"""),
      Regex("""(^|[\s;&|(])dd\s[^;&|]*of=/dev/"""),
      Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""),
      Regex("""git\s+push\s+[^;&|]*(--force\b|-f\b)"""),
      Regex("""chmod\s+(-R\s+)?[0-7]*777\s+/(\s|$)"""),
      Regex(""">\s*/dev/(sd|block|mmc)"""),
    )

  /** Commands that may destroy data or run code from the network: always shown to the user. */
  fun isDangerous(command: String): Boolean = DANGEROUS.any { it.containsMatchIn(command) }

  /** A pipeline whose every program got « Toujours » this session (never a chained command). */
  private fun grantedProgram(command: String, grants: SessionGrants): Boolean {
    if (grants.programs.isEmpty() || !AgentShell.isSimple(command)) return false
    return command.split('|').all { seg ->
      AgentShell.bareProgram(seg.trim().split(Regex("\\s+")).firstOrNull())?.let { it in grants.programs } == true
    }
  }

  fun decide(mode: ForgeMode, grants: SessionGrants, action: ForgeAction): Decision {
    val key = action.key()
    val base = baseDecision(mode, grants, action)
    if (base !is Decision.Ask) return base
    // Refused before in this session: refused again, unless the model justifies it (once).
    val previous = grants.refused[key] ?: return base
    val justification = (action as? ForgeAction.RunCommand)?.justification?.trim().orEmpty()
    if (justification.isEmpty() || key in grants.escalated) {
      return Decision.Deny(
        "L'utilisateur a déjà refusé cette action dans cette session" +
          (if (previous.isNotBlank()) " (motif : $previous)" else "") +
          ". Ne la redemande pas ; change d'approche ou demande-lui ce qu'il préfère."
      )
    }
    return base.copy(escalation = true, justification = justification, grant = null)
  }

  private fun baseDecision(mode: ForgeMode, grants: SessionGrants, action: ForgeAction): Decision =
    when (action) {
      is ForgeAction.WriteFile -> decideWrite(mode, grants, action)
      is ForgeAction.RunCommand -> decideCommand(mode, grants, action)
    }

  private fun decideWrite(mode: ForgeMode, grants: SessionGrants, a: ForgeAction.WriteFile): Decision {
    val path = a.path ?: return Decision.Deny("Ce chemin est hors du projet : les outils de fichiers restent dans le dossier du projet.")
    val verb = if (a.isNew) "créer" else "modifier"
    if (mode.sandbox == Sandbox.LECTURE) {
      if (!isPlanPath(path)) return Decision.Deny(PLAN_REASON)
      return if (mode.approval == Approval.DEMANDER) Decision.Ask("Forge veut $verb $path", path, grant = Decision.GRANT_EDITS)
      else Decision.Allow
    }
    if (isProtected(path)) {
      return Decision.Ask(
        "Forge veut $verb $path (dossier protégé)",
        "$path fait partie d'un dossier protégé (.git ou .altrex) : il est toujours demandé.",
        risky = true,
      )
    }
    if (mode.approval == Approval.DEMANDER && !grants.edits) {
      return Decision.Ask("Forge veut $verb $path", path, grant = Decision.GRANT_EDITS)
    }
    return Decision.Allow
  }

  private fun decideCommand(mode: ForgeMode, grants: SessionGrants, a: ForgeAction.RunCommand): Decision {
    val cmd = a.command.trim()
    if (cmd.isEmpty()) return Decision.Deny("La commande est vide.")
    val dangerous = isDangerous(cmd)
    if (!dangerous && AgentShell.isReadOnly(cmd)) return Decision.Allow
    if (mode.sandbox == Sandbox.LECTURE) return Decision.Deny(PLAN_REASON)
    val program = AgentShell.programOf(cmd)?.takeIf { !dangerous }
    if (dangerous) {
      return Decision.Ask(
        "Commande à risque",
        cmd,
        risky = true,
        justification = a.justification,
      )
    }
    if (grantedProgram(cmd, grants)) return Decision.Allow
    val ask = Decision.Ask("Forge veut lancer une commande", cmd, justification = a.justification, grant = program)
    return when {
      mode.approval == Approval.DEMANDER -> ask
      mode.sandbox == Sandbox.TOTAL -> Decision.Allow
      // PROJET: a command cannot be kept inside the project by PRoot, so it is asked.
      else -> ask
    }
  }
}
