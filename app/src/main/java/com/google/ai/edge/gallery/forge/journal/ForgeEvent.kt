package com.google.ai.edge.gallery.forge.journal

import org.json.JSONObject

/**
 * [ALTREX] One line of a Forge session journal (JSON Lines, append only). The session is this
 * journal: the screen, the model's history, the task list, the mode and the user's grants are all
 * rebuilt from it ([SessionState.fold]). The format is documented in docs/forge/OUTILS.md so that a
 * future PC version can read the same sessions.
 */
sealed class ForgeEvent {
  abstract val type: String
  /** Milliseconds since 1970, set when written. */
  open val ts: Long = 0

  data class SessionStart(val id: String, val project: String, val model: String, val mode: String, val title: String, override val ts: Long = 0) : ForgeEvent() {
    override val type = "session"
  }

  data class TurnStart(val turn: Int, override val ts: Long = 0) : ForgeEvent() {
    override val type = "turn_start"
  }

  /** What the user typed (or the expanded text of a / command). */
  data class User(val turn: Int, val text: String, override val ts: Long = 0) : ForgeEvent() {
    override val type = "user"
  }

  /**
   * One answer of the model in a turn. [raw] is what goes back to the model (reasoning removed,
   * tool calls kept); [display] is what the user sees.
   */
  data class Assistant(val turn: Int, val raw: String, val display: String, val thinking: String = "", override val ts: Long = 0) : ForgeEvent() {
    override val type = "assistant"
  }

  data class ToolCall(val turn: Int, val id: String, val name: String, val args: String, override val ts: Long = 0) : ForgeEvent() {
    override val type = "tool_call"
  }

  data class ToolResult(
    val turn: Int,
    val id: String,
    val name: String,
    /** "ok", "error" or "refused". */
    val status: String,
    val modelText: String,
    val summary: String = "",
    val detail: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val path: String? = null,
    override val ts: Long = 0,
  ) : ForgeEvent() {
    override val type = "tool_result"
  }

  /** The user's answer to an approval: "oui", "toujours", "non", "arreter", "sans_reponse". */
  data class Approval(val turn: Int, val id: String, val decision: String, val reason: String = "", val grant: String? = null, val actionKey: String = "", override val ts: Long = 0) : ForgeEvent() {
    override val type = "approval"
  }

  /** The whole task list (TodoWrite gives the full list each time). */
  data class Todos(val turn: Int, val items: List<Todo>, override val ts: Long = 0) : ForgeEvent() {
    override val type = "todos"
  }

  data class Mode(val mode: String, override val ts: Long = 0) : ForgeEvent() {
    override val type = "mode"
  }

  /** A plan given by ExitPlanMode, then the user's decision on it. */
  data class Plan(val turn: Int, val id: String, val markdown: String, override val ts: Long = 0) : ForgeEvent() {
    override val type = "plan"
  }

  data class PlanDecision(val turn: Int, val id: String, val approved: Boolean, val feedback: String = "", val mode: String? = null, override val ts: Long = 0) : ForgeEvent() {
    override val type = "plan_decision"
  }

  data class Question(val turn: Int, val id: String, val question: String, val options: List<String>, override val ts: Long = 0) : ForgeEvent() {
    override val type = "question"
  }

  data class Answer(val turn: Int, val id: String, val answer: String, override val ts: Long = 0) : ForgeEvent() {
    override val type = "answer"
  }

  /**
   * A note of the app: anti-loop reminder, limit reached, /doctor summary. [forModel] notes are
   * added to the model's next message; [shown] ones appear in the feed.
   */
  data class Note(val turn: Int, val kind: String, val text: String, val forModel: Boolean, val shown: Boolean = true, override val ts: Long = 0) : ForgeEvent() {
    override val type = "note"
  }

  /** "done", "stopped", "limit", "error", "interrupted", "blocked". */
  data class TurnEnd(val turn: Int, val reason: String, val detail: String = "", override val ts: Long = 0) : ForgeEvent() {
    override val type = "turn_end"
  }

  /**
   * A snapshot of the project in the shadow store ([com.google.ai.edge.gallery.forge.checkpoints]):
   * "avant" the agent's first change of a turn, "apres" at its end ([files] = what changed).
   */
  data class Checkpoint(val turn: Int, val phase: String, val rev: String, val store: String, val files: List<String> = emptyList(), override val ts: Long = 0) : ForgeEvent() {
    override val type = "checkpoint"
  }

  /** /undo of turn [turn]: files put back, files left alone (changed by the user since), backup snapshot. */
  data class Undo(val turn: Int, val restored: List<String>, val skipped: List<String>, val backup: String, val after: String, override val ts: Long = 0) : ForgeEvent() {
    override val type = "undo"
  }

  /** Level 1 compaction: these tool results are replaced by a tombstone in the model's history. */
  data class Compaction(val turn: Int, val ids: List<String>, override val ts: Long = 0) : ForgeEvent() {
    override val type = "compaction"
  }

  /** Level 2 compaction: [text] replaces, for the model, the first [upto] events of the journal. */
  data class Summary(val turn: Int, val upto: Int, val text: String, override val ts: Long = 0) : ForgeEvent() {
    override val type = "summary"
  }

  data class Todo(val content: String, val status: String, val activeForm: String = "")

  fun toJson(now: Long = ts): JSONObject {
    val o = JSONObject().put("t", type).put("ts", if (now > 0) now else System.currentTimeMillis())
    when (this) {
      is SessionStart -> o.put("v", FORMAT_VERSION).put("id", id).put("project", project).put("model", model).put("mode", mode).put("title", title)
      is TurnStart -> o.put("turn", turn)
      is User -> o.put("turn", turn).put("text", text)
      is Assistant -> o.put("turn", turn).put("raw", raw).put("display", display).also { if (thinking.isNotEmpty()) it.put("thinking", thinking) }
      is ToolCall -> o.put("turn", turn).put("id", id).put("name", name).put("args", args)
      is ToolResult -> {
        o.put("turn", turn).put("id", id).put("name", name).put("status", status).put("model", modelText)
        if (summary.isNotEmpty()) o.put("summary", summary)
        if (detail.isNotEmpty()) o.put("detail", detail)
        if (added != 0) o.put("added", added)
        if (removed != 0) o.put("removed", removed)
        path?.let { o.put("path", it) }
      }
      is Approval -> {
        o.put("turn", turn).put("id", id).put("decision", decision)
        if (reason.isNotEmpty()) o.put("reason", reason)
        grant?.let { o.put("grant", it) }
        if (actionKey.isNotEmpty()) o.put("key", actionKey)
      }
      is Todos ->
        o.put("turn", turn).put(
          "items",
          org.json.JSONArray().apply {
            for (t in items) put(JSONObject().put("content", t.content).put("status", t.status).put("activeForm", t.activeForm))
          },
        )
      is Mode -> o.put("mode", mode)
      is Plan -> o.put("turn", turn).put("id", id).put("markdown", markdown)
      is PlanDecision -> o.put("turn", turn).put("id", id).put("approved", approved).put("feedback", feedback).also { m -> mode?.let { m.put("mode", it) } }
      is Question -> o.put("turn", turn).put("id", id).put("question", question).put("options", org.json.JSONArray(options))
      is Answer -> o.put("turn", turn).put("id", id).put("answer", answer)
      is Note -> o.put("turn", turn).put("kind", kind).put("text", text).put("forModel", forModel).put("shown", shown)
      is TurnEnd -> o.put("turn", turn).put("reason", reason).also { if (detail.isNotEmpty()) it.put("detail", detail) }
      is Checkpoint -> o.put("turn", turn).put("phase", phase).put("rev", rev).put("store", store).put("files", org.json.JSONArray(files))
      is Undo -> o.put("turn", turn).put("restored", org.json.JSONArray(restored)).put("skipped", org.json.JSONArray(skipped)).put("backup", backup).put("after", after)
      is Compaction -> o.put("turn", turn).put("ids", org.json.JSONArray(ids))
      is Summary -> o.put("turn", turn).put("upto", upto).put("text", text)
    }
    return o
  }

  companion object {
    const val FORMAT_VERSION = 1

    private fun strings(o: JSONObject, key: String): List<String> {
      val arr = o.optJSONArray(key) ?: return emptyList()
      return (0 until arr.length()).map { arr.optString(it) }
    }

    /** Null for an unknown or damaged line (a crash in the middle of a write). */
    fun fromJson(o: JSONObject): ForgeEvent? {
      val ts = o.optLong("ts", 0)
      val turn = o.optInt("turn", 0)
      return when (o.optString("t")) {
        "session" -> SessionStart(o.optString("id"), o.optString("project"), o.optString("model"), o.optString("mode"), o.optString("title"), ts)
        "turn_start" -> TurnStart(turn, ts)
        "user" -> User(turn, o.optString("text"), ts)
        "assistant" -> Assistant(turn, o.optString("raw"), o.optString("display"), o.optString("thinking"), ts)
        "tool_call" -> ToolCall(turn, o.optString("id"), o.optString("name"), o.optString("args"), ts)
        "tool_result" ->
          ToolResult(
            turn, o.optString("id"), o.optString("name"), o.optString("status"), o.optString("model"),
            o.optString("summary"), o.optString("detail"), o.optInt("added"), o.optInt("removed"),
            if (o.has("path")) o.optString("path") else null, ts,
          )
        "approval" -> Approval(turn, o.optString("id"), o.optString("decision"), o.optString("reason"), if (o.has("grant")) o.optString("grant") else null, o.optString("key"), ts)
        "todos" -> {
          val arr = o.optJSONArray("items") ?: org.json.JSONArray()
          Todos(turn, (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Todo(it.optString("content"), it.optString("status"), it.optString("activeForm")) } }, ts)
        }
        "mode" -> Mode(o.optString("mode"), ts)
        "plan" -> Plan(turn, o.optString("id"), o.optString("markdown"), ts)
        "plan_decision" -> PlanDecision(turn, o.optString("id"), o.optBoolean("approved"), o.optString("feedback"), if (o.has("mode")) o.optString("mode") else null, ts)
        "question" -> {
          val arr = o.optJSONArray("options") ?: org.json.JSONArray()
          Question(turn, o.optString("id"), o.optString("question"), (0 until arr.length()).map { arr.optString(it) }, ts)
        }
        "answer" -> Answer(turn, o.optString("id"), o.optString("answer"), ts)
        "note" -> Note(turn, o.optString("kind"), o.optString("text"), o.optBoolean("forModel"), o.optBoolean("shown", true), ts)
        "turn_end" -> TurnEnd(turn, o.optString("reason"), o.optString("detail"), ts)
        "checkpoint" -> Checkpoint(turn, o.optString("phase"), o.optString("rev"), o.optString("store"), strings(o, "files"), ts)
        "undo" -> Undo(turn, strings(o, "restored"), strings(o, "skipped"), o.optString("backup"), o.optString("after"), ts)
        "compaction" -> Compaction(turn, strings(o, "ids"), ts)
        "summary" -> Summary(turn, o.optInt("upto"), o.optString("text"), ts)
        else -> null
      }
    }
  }
}
