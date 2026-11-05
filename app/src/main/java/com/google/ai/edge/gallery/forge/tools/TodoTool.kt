package com.google.ai.edge.gallery.forge.tools

import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ALTREX] TodoWrite: the model gives the whole list each time (it replaces the previous one).
 * Checked strictly so the pinned list stays readable: known statuses only, no empty or duplicate
 * task, at most one task in progress, at most [MAX_ITEMS].
 */
object TodoTool {
  const val MAX_ITEMS = 20
  private val STATUSES = setOf("pending", "in_progress", "completed")

  sealed class Parsed {
    data class Ok(val items: List<ForgeEvent.Todo>) : Parsed()

    data class Fail(val message: String) : Parsed()
  }

  fun parse(arr: JSONArray?): Parsed {
    if (arr == null) {
      return Parsed.Fail("TodoWrite demande « todos » : un tableau d'objets {content, status, activeForm}.")
    }
    if (arr.length() > MAX_ITEMS) return Parsed.Fail("Trop de tâches (${arr.length()}) : $MAX_ITEMS au plus. Regroupe-les.")
    val out = ArrayList<ForgeEvent.Todo>()
    val seen = HashSet<String>()
    for (i in 0 until arr.length()) {
      val o: JSONObject =
        when (val v = arr.opt(i)) {
          is JSONObject -> v
          is String -> JSONObject().put("content", v).put("status", "pending")
          else -> return Parsed.Fail("La tâche n°${i + 1} n'est pas un objet {content, status}.")
        }
      val content = o.optString("content").ifEmpty { o.optString("task") }.trim()
      if (content.isEmpty()) return Parsed.Fail("La tâche n°${i + 1} n'a pas de « content ».")
      val status = o.optString("status", "pending").trim().lowercase().replace(' ', '_').ifEmpty { "pending" }
      if (status !in STATUSES) return Parsed.Fail("Statut inconnu « $status » (tâche n°${i + 1}) : pending, in_progress ou completed.")
      if (!seen.add(content.lowercase())) return Parsed.Fail("La tâche « $content » apparaît deux fois.")
      out.add(ForgeEvent.Todo(content.take(200), status, o.optString("activeForm").trim().take(200)))
    }
    if (out.count { it.status == "in_progress" } > 1) return Parsed.Fail("Une seule tâche peut être in_progress à la fois.")
    return Parsed.Ok(out)
  }

  /** Whether [next] marks as completed a task that was not completed in [previous]. */
  fun completedOne(previous: List<ForgeEvent.Todo>, next: List<ForgeEvent.Todo>): Boolean {
    val done = previous.filter { it.status == "completed" }.map { it.content }.toSet()
    return next.any { it.status == "completed" && it.content !in done }
  }

  fun result(items: List<ForgeEvent.Todo>): String {
    val done = items.count { it.status == "completed" }
    return ToolOutcome.json(
      "todos" to "$done/${items.size} terminées",
      "note" to "Liste enregistrée. Garde-la à jour au fil du travail.",
      "status" to "ok",
    )
  }
}
