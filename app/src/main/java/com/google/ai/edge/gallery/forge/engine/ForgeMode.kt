package com.google.ai.edge.gallery.forge.engine

/**
 * [ALTREX] Forge's permissions, on two separate axes (docs/forge/FORGE.md §4):
 * - [Sandbox]: what the agent may change by itself (read only, the project, everything);
 * - [Approval]: whether it asks before changing anything inside that sandbox.
 *
 * The four chips of the screen are [Preset]s of these two axes; any other pair shows as
 * « Personnalisé ». PRoot does not isolate anything: these rules are enforced by Forge's tools
 * themselves, before they act, and the screen says so.
 */
enum class Sandbox(val label: String) {
  LECTURE("lecture seule"),
  PROJET("écriture dans le projet"),
  TOTAL("accès total"),
}

enum class Approval(val label: String) {
  /** Ask before every change, even inside the sandbox. */
  DEMANDER("demander"),
  /** Never ask inside the sandbox; what goes beyond it is asked or refused (see [PermissionPolicy]). */
  JAMAIS("jamais"),
}

data class ForgeMode(val sandbox: Sandbox, val approval: Approval) {
  /** The chip this pair stands for, or null (« Personnalisé »). */
  val preset: Preset?
    get() = Preset.entries.firstOrNull { it.mode == this }

  /** Plan mode: read only, and the only way out is ExitPlanMode validated by the user's button. */
  val isPlan: Boolean
    get() = sandbox == Sandbox.LECTURE

  val label: String
    get() = preset?.label ?: "Personnalisé"

  /** Stored in the session journal: "PROJET/DEMANDER". */
  fun key(): String = "${sandbox.name}/${approval.name}"

  companion object {
    /** « Demander » (a getter: [Preset] builds ForgeMode values while it is initialized). */
    val DEFAULT: ForgeMode
      get() = ForgeMode(Sandbox.PROJET, Approval.DEMANDER)

    fun parse(key: String?): ForgeMode? {
      val parts = key?.split('/') ?: return null
      if (parts.size != 2) return null
      val s = Sandbox.entries.firstOrNull { it.name == parts[0] } ?: return null
      val a = Approval.entries.firstOrNull { it.name == parts[1] } ?: return null
      return ForgeMode(s, a)
    }
  }
}

/** The four chips (« Demander », « Éditions auto », « Plan », « Tout autoriser »). */
enum class Preset(val label: String, val description: String, val mode: ForgeMode) {
  DEMANDER("Demander", "Demande avant chaque modification.", ForgeMode(Sandbox.PROJET, Approval.DEMANDER)),
  EDITIONS_AUTO(
    "Éditions auto",
    "Modifie seul, demande pour les commandes.",
    ForgeMode(Sandbox.PROJET, Approval.JAMAIS),
  ),
  PLAN("Plan", "Lecture seule, puis un plan à valider.", ForgeMode(Sandbox.LECTURE, Approval.JAMAIS)),
  TOUT_AUTORISER("Tout autoriser", "Agit sans demander, dans le projet.", ForgeMode(Sandbox.TOTAL, Approval.JAMAIS)),
}
