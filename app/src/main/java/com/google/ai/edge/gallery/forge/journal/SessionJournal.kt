package com.google.ai.edge.gallery.forge.journal

import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * [ALTREX] A Forge session on disk: one JSON event per line, only ever appended to. Each event is
 * written and synced before the app goes on, so a crash or Android closing the app loses at most
 * the line being written (a damaged last line is skipped on load).
 *
 * The journals live in the app's own storage (not in the project): the model, which can run
 * commands in the project, cannot rewrite its own history or the user's approvals.
 */
class SessionJournal(val file: File) {
  private val lock = Any()

  /** Events in order, as loaded and then appended. */
  private val events = ArrayList<ForgeEvent>()

  val id: String
    get() = file.nameWithoutExtension

  fun snapshot(): List<ForgeEvent> = synchronized(lock) { events.toList() }

  fun append(event: ForgeEvent) {
    synchronized(lock) {
      val line = event.toJson().toString().replace("\n", "\\n") + "\n"
      file.parentFile?.mkdirs()
      FileOutputStream(file, true).use { out ->
        out.write(line.toByteArray(Charsets.UTF_8))
        out.fd.sync()
      }
      // Kept with the time actually written, as a reload would give it.
      events.add(ForgeEvent.fromJson(JSONObject(line)) ?: event)
    }
  }

  companion object {
    /**
     * Reads a journal. A turn left open (the app was closed during it) is closed here with an
     * « interrompu » end, and its tool calls without result get one saying they were not run
     * again: appended to the file, never by cutting it.
     */
    fun load(file: File): SessionJournal {
      val j = SessionJournal(file)
      if (file.isFile) {
        file.forEachLine(Charsets.UTF_8) { line ->
          if (line.isBlank()) return@forEachLine
          val e = runCatching { ForgeEvent.fromJson(JSONObject(line)) }.getOrNull() ?: return@forEachLine
          j.events.add(e)
        }
      }
      for (fix in repairs(j.events)) j.append(fix)
      return j
    }

    /** Events that close what a crash left open (see [load]); empty when all is closed. */
    fun repairs(events: List<ForgeEvent>): List<ForgeEvent> {
      val lastStart = events.filterIsInstance<ForgeEvent.TurnStart>().lastOrNull() ?: return emptyList()
      val ended = events.any { it is ForgeEvent.TurnEnd && it.turn == lastStart.turn }
      if (ended) return emptyList()
      val out = ArrayList<ForgeEvent>()
      val answered = events.filterIsInstance<ForgeEvent.ToolResult>().map { it.id }.toSet()
      for (c in events.filterIsInstance<ForgeEvent.ToolCall>()) {
        if (c.turn == lastStart.turn && c.id !in answered) {
          out.add(
            ForgeEvent.ToolResult(
              turn = c.turn,
              id = c.id,
              name = c.name,
              status = "error",
              modelText = """{"error":"Interrompu : l'appli a été fermée pendant cet appel. Il n'a pas été relancé ; vérifie son effet avant de le refaire.","status":"error"}""",
              summary = "interrompu",
            )
          )
        }
      }
      out.add(ForgeEvent.TurnEnd(lastStart.turn, "interrupted", "L'appli a été fermée pendant ce tour."))
      return out
    }
  }
}
