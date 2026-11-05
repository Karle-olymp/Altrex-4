package com.google.ai.edge.gallery.forge.tools

import com.google.gson.GsonBuilder

/**
 * [ALTREX] Result of one Forge tool call: [modelText] goes back to the model (a JSON object, keys
 * in a fixed order, see docs/forge/OUTILS.md), the rest draws the tool card on the screen.
 */
data class ToolOutcome(
  val status: Status,
  val modelText: String,
  /** Short French line on the card: "48 l.", "code 0", "3 fichiers". */
  val summary: String = "",
  /** What the card shows when opened: diff lines ([DiffLines]) or the end of an output. */
  val detail: String = "",
  val added: Int = 0,
  val removed: Int = 0,
  /** File the call worked on, relative to the project. */
  val path: String? = null,
  /** The tool itself asks the turn to end (the user pressed « Arrêter » in an approval). */
  val stopTurn: Boolean = false,
) {
  enum class Status { OK, ERROR, REFUSED }

  companion object {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /** A JSON object with the keys in this order (status last, as in Altrex's other tools). */
    fun json(vararg pairs: Pair<String, Any?>): String =
      gson.toJson(LinkedHashMap<String, Any?>().apply { for ((k, v) in pairs) if (v != null) put(k, v) })

    fun error(message: String, summary: String = "erreur"): ToolOutcome =
      ToolOutcome(Status.ERROR, json("error" to message, "status" to "error"), summary = summary, detail = message)

    fun refused(reason: String): ToolOutcome =
      ToolOutcome(
        Status.REFUSED,
        json(
          "refused" to reason,
          "note" to "Ne relance pas la même action telle quelle : adapte ton approche, ou explique la situation à l'utilisateur.",
          "status" to "refused",
        ),
        summary = "refusé",
        detail = reason,
      )
  }
}

/**
 * [ALTREX] A compact diff for the cards: common start and end are skipped, the changed block is
 * shown as removed then added lines, with their line numbers. Enough on a phone, and linear.
 */
object DiffLines {
  data class Result(val added: Int, val removed: Int, val text: String)

  /** One line of [Result.text]: "<number>\t<+|-| >\t<content>". */
  fun compute(old: String, new: String, maxShown: Int = 60): Result {
    val a = lines(old)
    val b = lines(new)
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start++
    var endA = a.size
    var endB = b.size
    while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
      endA--
      endB--
    }
    val removed = endA - start
    val added = endB - start
    val out = StringBuilder()
    var shown = 0
    for (i in start until endA) {
      if (shown++ >= maxShown) break
      out.append(i + 1).append("\t-\t").append(a[i].take(300)).append('\n')
    }
    for (i in start until endB) {
      if (shown++ >= maxShown) break
      out.append(i + 1).append("\t+\t").append(b[i].take(300)).append('\n')
    }
    val total = removed + added
    if (total > maxShown) out.append("…\t \t").append(total - maxShown).append(" lignes de plus\n")
    return Result(added, removed, out.toString().trimEnd('\n'))
  }

  /** The lines of [text]; a final line end does not make an extra empty line. */
  private fun lines(text: String): List<String> =
    if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')
}
