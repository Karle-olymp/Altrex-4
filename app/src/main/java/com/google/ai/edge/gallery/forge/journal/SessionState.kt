package com.google.ai.edge.gallery.forge.journal

import com.google.ai.edge.gallery.agent.AgentMsg
import com.google.ai.edge.gallery.agent.ToolCalling
import com.google.ai.edge.gallery.forge.engine.ForgeMode
import com.google.ai.edge.gallery.forge.engine.SessionGrants

/** [ALTREX] Applies the user's answer to the session's grants (live and when a journal is replayed). */
fun SessionGrants.apply(e: ForgeEvent.Approval) {
  e.grant?.let { grant(it) }
  if (e.actionKey.isEmpty()) return
  if (e.decision == "non" || e.decision == "sans_reponse" || e.decision == "arreter") {
    if (e.actionKey in refused) escalated.add(e.actionKey)
    refused[e.actionKey] = e.reason
  } else if (e.decision == "oui" && e.actionKey in refused) {
    escalated.add(e.actionKey)
  }
}

/** [ALTREX] One item of the Forge feed, rebuilt from the journal. */
sealed class FeedItem {
  abstract val turn: Int

  data class UserText(override val turn: Int, val text: String) : FeedItem()

  data class AssistantText(override val turn: Int, val text: String, val thinking: String) : FeedItem()

  /** A tool call and, once known, its result. */
  data class ToolCard(override val turn: Int, val id: String, val name: String, val args: String, val result: ForgeEvent.ToolResult?) : FeedItem()

  data class Note(override val turn: Int, val kind: String, val text: String) : FeedItem()

  data class Plan(override val turn: Int, val id: String, val markdown: String, val decision: ForgeEvent.PlanDecision?) : FeedItem()

  data class Question(override val turn: Int, val id: String, val question: String, val options: List<String>, val answer: String?) : FeedItem()

  data class TurnEnd(override val turn: Int, val reason: String, val detail: String) : FeedItem()
}

/**
 * [ALTREX] Everything a session is, computed from its journal alone (pure fold, unit tested):
 * what the screen shows, the model's history, the task list, the mode and the user's grants.
 */
data class SessionState(
  val id: String,
  val project: String,
  val model: String,
  val title: String,
  val feed: List<FeedItem>,
  val todos: List<ForgeEvent.Todo>,
  val mode: ForgeMode,
  val grants: SessionGrants,
  val lastTurn: Int,
  /** True when the last turn has no end yet (it is running now). */
  val turnOpen: Boolean,
  val lastEnd: String?,
  val updated: Long,
  /** Files changed by the agent in the last finished turn (« fichiers produits »). */
  val lastTurnFiles: List<String>,
) {
  companion object {
    fun fold(events: List<ForgeEvent>, defaultMode: ForgeMode = ForgeMode.DEFAULT): SessionState {
      var id = ""
      var project = ""
      var model = ""
      var title = ""
      var mode = defaultMode
      val grants = SessionGrants()
      var todos = emptyList<ForgeEvent.Todo>()
      val feed = ArrayList<FeedItem>()
      val cards = HashMap<String, Int>()
      val plans = HashMap<String, Int>()
      val questions = HashMap<String, Int>()
      var lastTurn = 0
      var open = false
      var lastEnd: String? = null
      var updated = 0L
      for (e in events) {
        if (e.ts > updated) updated = e.ts
        when (e) {
          is ForgeEvent.SessionStart -> {
            id = e.id
            project = e.project
            model = e.model
            title = e.title
            ForgeMode.parse(e.mode)?.let { mode = it }
          }
          is ForgeEvent.TurnStart -> {
            lastTurn = e.turn
            open = true
          }
          is ForgeEvent.User -> {
            feed.add(FeedItem.UserText(e.turn, e.text))
            if (title.isEmpty()) title = e.text.lineSequence().first().take(60)
          }
          is ForgeEvent.Assistant ->
            if (e.display.isNotBlank() || e.thinking.isNotBlank()) feed.add(FeedItem.AssistantText(e.turn, e.display, e.thinking))
          is ForgeEvent.ToolCall -> {
            cards[e.id] = feed.size
            feed.add(FeedItem.ToolCard(e.turn, e.id, e.name, e.args, null))
          }
          is ForgeEvent.ToolResult -> {
            val i = cards[e.id]
            if (i != null) feed[i] = (feed[i] as FeedItem.ToolCard).copy(result = e)
          }
          is ForgeEvent.Approval -> grants.apply(e)
          is ForgeEvent.Todos -> todos = e.items
          is ForgeEvent.Mode -> ForgeMode.parse(e.mode)?.let { mode = it }
          is ForgeEvent.Plan -> {
            plans[e.id] = feed.size
            feed.add(FeedItem.Plan(e.turn, e.id, e.markdown, null))
          }
          is ForgeEvent.PlanDecision -> {
            plans[e.id]?.let { i -> feed[i] = (feed[i] as FeedItem.Plan).copy(decision = e) }
            if (e.approved) e.mode?.let { ForgeMode.parse(it) }?.let { mode = it }
          }
          is ForgeEvent.Question -> {
            questions[e.id] = feed.size
            feed.add(FeedItem.Question(e.turn, e.id, e.question, e.options, null))
          }
          is ForgeEvent.Answer -> questions[e.id]?.let { i -> feed[i] = (feed[i] as FeedItem.Question).copy(answer = e.answer) }
          is ForgeEvent.Note -> if (e.shown) feed.add(FeedItem.Note(e.turn, e.kind, e.text))
          is ForgeEvent.TurnEnd -> {
            open = false
            lastEnd = e.reason
            if (e.reason != "done") feed.add(FeedItem.TurnEnd(e.turn, e.reason, e.detail))
          }
        }
      }
      val lastFiles =
        events.filterIsInstance<ForgeEvent.ToolResult>()
          .filter { it.turn == lastTurn && it.status == "ok" && it.path != null && (it.name == "Write" || it.name == "Edit") }
          .mapNotNull { it.path }
          .distinct()
      return SessionState(id, project, model, title, feed, todos, mode, grants, lastTurn, open, lastEnd, updated, lastFiles)
    }

    /**
     * The model's history: earlier turns condensed (request, the tools used in one line, the final
     * answer), the current turn [currentTurn] in full (each answer with its tool calls, then the
     * tool results). Notes for the model are added to the next message the model reads.
     */
    fun modelMessages(events: List<ForgeEvent>, currentTurn: Int): List<AgentMsg> {
      val out = ArrayList<AgentMsg>()
      val byTurn = events.groupBy { turnOf(it) }
      for (turn in byTurn.keys.filter { it in 1..currentTurn }.sorted()) {
        val ev = byTurn[turn].orEmpty()
        if (turn < currentTurn) condensed(ev, out) else full(ev, out)
      }
      return out
    }

    private fun turnOf(e: ForgeEvent): Int =
      when (e) {
        is ForgeEvent.TurnStart -> e.turn
        is ForgeEvent.User -> e.turn
        is ForgeEvent.Assistant -> e.turn
        is ForgeEvent.ToolCall -> e.turn
        is ForgeEvent.ToolResult -> e.turn
        is ForgeEvent.Approval -> e.turn
        is ForgeEvent.Todos -> e.turn
        is ForgeEvent.Plan -> e.turn
        is ForgeEvent.PlanDecision -> e.turn
        is ForgeEvent.Question -> e.turn
        is ForgeEvent.Answer -> e.turn
        is ForgeEvent.Note -> e.turn
        is ForgeEvent.TurnEnd -> e.turn
        else -> 0
      }

    private fun condensed(ev: List<ForgeEvent>, out: MutableList<AgentMsg>) {
      val user = ev.filterIsInstance<ForgeEvent.User>().joinToString("\n\n") { it.text }
      if (user.isEmpty()) return
      val notes = ev.filterIsInstance<ForgeEvent.Note>().filter { it.forModel && it.kind == "contexte" }.joinToString("\n\n") { it.text }
      out.add(AgentMsg("user", listOf(notes, user).filter { it.isNotBlank() }.joinToString("\n\n")))
      val tools =
        ev.filterIsInstance<ForgeEvent.ToolResult>().joinToString(", ") { r ->
          r.name + (r.path?.let { " $it" } ?: "") + (if (r.added + r.removed > 0) " (+${r.added} −${r.removed})" else "") +
            (if (r.status != "ok") " [${r.status}]" else "")
        }
      val answer = ev.filterIsInstance<ForgeEvent.Assistant>().lastOrNull { it.display.isNotBlank() }?.display.orEmpty()
      val end = ev.filterIsInstance<ForgeEvent.TurnEnd>().lastOrNull()
      val endNote = if (end != null && end.reason != "done") "[Tour terminé : ${end.reason}${if (end.detail.isNotEmpty()) " — " + end.detail else ""}]" else ""
      val text =
        listOf(if (tools.isNotEmpty()) "[Outils utilisés : ${tools.take(1_500)}]" else "", answer, endNote)
          .filter { it.isNotBlank() }
          .joinToString("\n")
      out.add(AgentMsg("assistant", text.ifEmpty { "…" }))
    }

    private fun full(ev: List<ForgeEvent>, out: MutableList<AgentMsg>) {
      val pendingNotes = ArrayList<String>()
      val results = ArrayList<String>()
      fun flushResults() {
        if (results.isEmpty() && pendingNotes.isEmpty()) return
        val msg =
          if (results.isNotEmpty()) ToolCalling.toolResponseMessage(results).content else ""
        val all = listOf(msg, pendingNotes.joinToString("\n\n")).filter { it.isNotBlank() }.joinToString("\n\n")
        val last = out.lastOrNull()
        if (last != null && last.role == "user") out[out.lastIndex] = last.copy(content = last.content + "\n\n" + all)
        else out.add(AgentMsg("user", all))
        results.clear()
        pendingNotes.clear()
      }
      for (e in ev) {
        when (e) {
          is ForgeEvent.User -> {
            flushResults()
            out.add(AgentMsg("user", e.text))
          }
          is ForgeEvent.Assistant -> {
            flushResults()
            out.add(AgentMsg("assistant", e.raw.ifBlank { "…" }))
          }
          is ForgeEvent.ToolResult -> results.add(e.modelText)
          is ForgeEvent.Note -> if (e.forModel) {
            if (results.isEmpty() && out.lastOrNull()?.role == "user") {
              val last = out.last()
              out[out.lastIndex] = last.copy(content = if (e.kind == "contexte") e.text + "\n\n" + last.content else last.content + "\n\n" + e.text)
            } else {
              pendingNotes.add(e.text)
            }
          }
          else -> Unit
        }
      }
      flushResults()
    }
  }
}
