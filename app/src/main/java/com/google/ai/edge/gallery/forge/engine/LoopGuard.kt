package com.google.ai.edge.gallery.forge.engine

import com.google.ai.edge.gallery.agent.AgentToolCall
import com.google.ai.edge.gallery.agent.RepeatCallGuard

/**
 * [ALTREX] Keeps a Forge turn from going round in circles (docs/forge/FORGE.md §4, « Limites et
 * boucles »), which small local models (and Argora) do easily.
 *
 * 1. Anti-loop reminder: the same tool with the same arguments 3, 5 then 8 times in a row: a
 *    reminder, then the call is not run, then the turn stops with an explanation. TodoWrite does
 *    not count (rewriting the list is normal).
 * 2. Stall counter: goes up when the agent does not move forward (error, refusal, a call already
 *    made a little earlier, two actions alternating A B A B, the same text written again), down
 *    when a call brings something new, back to 0 when a task is completed. Past [STALL_LIMIT] the
 *    model is asked to stop, say why, and plan again; the second time, the turn stops.
 */
class LoopGuard {
  enum class Verdict { RUN, RUN_WITH_REMINDER, REFUSE, STOP }

  data class Check(val verdict: Verdict, val message: String?)

  private var lastKey: String? = null
  private var sameInARow = 0
  private val recentKeys = ArrayDeque<String>()
  private var lastText: String? = null
  var stalls = 0
    private set
  var replans = 0
    private set

  /** Before running [call]. */
  fun beforeCall(call: AgentToolCall): Check {
    if (call.name == "TodoWrite") return Check(Verdict.RUN, null)
    val key = RepeatCallGuard.key(call)
    sameInARow = if (key == lastKey) sameInARow + 1 else 1
    lastKey = key
    return when {
      sameInARow >= STOP_AT -> Check(Verdict.STOP, stopText(call.name))
      sameInARow >= REFUSE_AT -> Check(Verdict.REFUSE, refuseText(call.name, sameInARow))
      sameInARow >= REMIND_AT -> Check(Verdict.RUN_WITH_REMINDER, reminderText(call.name, sameInARow))
      else -> Check(Verdict.RUN, null)
    }
  }

  /** Outcome of a stall check after a call: null, a replan request, or the end of the turn. */
  sealed class StallAction {
    data class Replan(val note: String) : StallAction()

    data class Stop(val reason: String) : StallAction()
  }

  /**
   * After [call] ran (or was refused). [ok]: it succeeded; [completedTask]: TodoWrite marked a task
   * done.
   */
  fun afterCall(call: AgentToolCall, ok: Boolean, completedTask: Boolean = false): StallAction? {
    if (call.name == "TodoWrite") {
      if (completedTask) stalls = 0
      return null
    }
    val key = RepeatCallGuard.key(call)
    val seenRecently = key in recentKeys
    val alternating =
      recentKeys.size >= 3 && recentKeys[recentKeys.size - 2] == key && recentKeys.last() == recentKeys[recentKeys.size - 3] &&
        recentKeys.last() != key
    recentKeys.addLast(key)
    while (recentKeys.size > RECENT) recentKeys.removeFirst()
    val why =
      when {
        !ok -> "des appels qui échouent ou sont refusés"
        alternating -> "deux actions qui alternent sans avancer"
        seenRecently -> "des appels déjà faits juste avant"
        else -> null
      }
    if (why == null) {
      if (stalls > 0) stalls--
      return null
    }
    return bump(why)
  }

  /** After an answer of the model: the same text twice in a row is a monologue. */
  fun afterText(text: String): StallAction? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val same = t == lastText
    lastText = t
    return if (same) bump("la même réponse écrite deux fois") else null
  }

  private fun bump(why: String): StallAction? {
    stalls++
    if (stalls <= STALL_LIMIT) return null
    stalls = 0
    replans++
    return if (replans > MAX_REPLANS) {
      StallAction.Stop("Forge s'arrête : l'agent reste bloqué malgré un nouveau plan ($why).")
    } else {
      StallAction.Replan(
        "Rappel de Forge : tu sembles bloqué ($why). Arrête-toi. Explique en une phrase ce qui ne marche pas, " +
          "puis refais un plan court et différent avec TodoWrite avant de continuer. Si tu ne peux pas avancer, demande à l'utilisateur."
      )
    }
  }

  companion object {
    const val REMIND_AT = 3
    const val REFUSE_AT = 5
    const val STOP_AT = 8
    const val STALL_LIMIT = 3
    const val MAX_REPLANS = 1
    private const val RECENT = 6

    fun reminderText(tool: String, n: Int) =
      "Rappel de Forge : c'est la ${n}e fois de suite que tu appelles $tool avec exactement les mêmes arguments. " +
        "Le résultat ne change pas : sers-toi de celui que tu as déjà."

    fun refuseText(tool: String, n: Int) =
      "Appel non exécuté : $n appels identiques de suite à $tool. Change d'approche (autre fichier, autre commande, " +
        "autre argument) ou réponds à l'utilisateur avec ce que tu sais."

    fun stopText(tool: String) =
      "Forge arrête ce tour : $STOP_AT appels identiques de suite à $tool. L'agent tournait en rond."
  }
}
