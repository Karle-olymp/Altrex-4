package com.google.ai.edge.gallery.tools

/**
 * [ALTREX] French labels of the tool progress panel ("Chargement du skill « x »" while it runs,
 * "Skill « x » chargé" once done). The "done" form is derived from the running one.
 */
object ProgressLabels {
  private const val LOADING_SKILL = "Chargement du skill "
  private const val CALLING_SCRIPT = "Appel du script JS "
  private const val CALLING_MCP = "Appel de l'outil MCP "
  private const val RUNNING_ACTION = "Exécution de l'action "

  private fun quote(s: String) = "« $s »"

  fun loadingSkill(name: String) = LOADING_SKILL + quote(name)

  fun callingScript(skill: String, script: String) = CALLING_SCRIPT + quote("$skill/$script")

  fun callingMcpTool(name: String) = CALLING_MCP + quote(name)

  fun runningAction(intent: String) = RUNNING_ACTION + quote(intent)

  /** The label once the step is over; any other label is kept as it is. */
  fun done(label: String): String =
    when {
      label.startsWith(LOADING_SKILL) -> "Skill " + label.removePrefix(LOADING_SKILL) + " chargé"
      label.startsWith(CALLING_SCRIPT) -> "Script JS " + label.removePrefix(CALLING_SCRIPT) + " appelé"
      label.startsWith(CALLING_MCP) -> "Outil MCP " + label.removePrefix(CALLING_MCP) + " appelé"
      label.startsWith(RUNNING_ACTION) -> "Action " + label.removePrefix(RUNNING_ACTION) + " exécutée"
      else -> label
    }
}
