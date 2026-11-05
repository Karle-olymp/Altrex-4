package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.forge.engine.Preset
import com.google.ai.edge.gallery.forge.journal.FeedItem
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.journal.SessionJournal
import com.google.ai.edge.gallery.forge.journal.SessionState
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTest {
  private fun newFile(): File = File(Files.createTempDirectory("forge-journal").toFile(), "s1.jsonl")

  @Test
  fun appendOnly_roundTrip() {
    val f = newFile()
    val j = SessionJournal(f)
    j.append(ForgeEvent.SessionStart("s1", "demo", "Qwen", Preset.DEMANDER.mode.key(), ""))
    j.append(ForgeEvent.TurnStart(1))
    j.append(ForgeEvent.User(1, "ajoute un formulaire\navec \"validation\""))
    j.append(ForgeEvent.ToolCall(1, "c1-1", "Read", "{\"file_path\":\"a.js\"}"))
    j.append(ForgeEvent.ToolResult(1, "c1-1", "Read", "ok", "{\"status\":\"ok\"}", "12 l.", path = "a.js"))
    j.append(ForgeEvent.TurnEnd(1, "done"))
    assertEquals(6, f.readLines().size)
    val loaded = SessionJournal.load(f).snapshot()
    assertEquals(6, loaded.size)
    assertEquals("ajoute un formulaire\navec \"validation\"", (loaded[2] as ForgeEvent.User).text)
    assertEquals("a.js", (loaded[4] as ForgeEvent.ToolResult).path)
    // Nothing was open: nothing appended.
    assertEquals(6, f.readLines().size)
  }

  @Test
  fun openTurn_isMarkedInterrupted_notCut() {
    val f = newFile()
    val j = SessionJournal(f)
    j.append(ForgeEvent.SessionStart("s1", "demo", "m", "", ""))
    j.append(ForgeEvent.TurnStart(1))
    j.append(ForgeEvent.User(1, "lance les tests"))
    j.append(ForgeEvent.ToolCall(1, "c1-1", "Bash", "{\"command\":\"npm test\"}"))
    // The app is killed here; a half-written line is left.
    f.appendText("{\"t\":\"assistant\",\"turn\":1,\"raw\":\"coup")
    val loaded = SessionJournal.load(f)
    val ev = loaded.snapshot()
    val result = ev.filterIsInstance<ForgeEvent.ToolResult>().single()
    assertEquals("c1-1", result.id)
    assertEquals("error", result.status)
    assertTrue(result.modelText.contains("Interrompu"))
    assertEquals("interrupted", ev.filterIsInstance<ForgeEvent.TurnEnd>().single().reason)
    val state = SessionState.fold(ev)
    assertFalse(state.turnOpen)
    assertTrue(state.feed.last() is FeedItem.TurnEnd)
    // The original lines are all still there.
    assertTrue(f.readText().contains("lance les tests"))
    // Loading again adds nothing.
    val before = f.readLines().size
    SessionJournal.load(f)
    assertEquals(before, f.readLines().size)
  }

  @Test
  fun fold_rebuildsTodosModeGrantsAndFeed() {
    val events =
      listOf(
        ForgeEvent.SessionStart("s1", "demo", "m", Preset.DEMANDER.mode.key(), ""),
        ForgeEvent.TurnStart(1),
        ForgeEvent.User(1, "fais un plan"),
        ForgeEvent.Mode(Preset.PLAN.mode.key()),
        ForgeEvent.Todos(1, listOf(ForgeEvent.Todo("a", "completed"), ForgeEvent.Todo("b", "in_progress"))),
        ForgeEvent.ToolCall(1, "c1-1", "Bash", "{}"),
        ForgeEvent.Approval(1, "c1-1", "toujours", grant = "npm", actionKey = "cmd:npm i"),
        ForgeEvent.ToolResult(1, "c1-1", "Bash", "ok", "{}", "code 0"),
        ForgeEvent.Plan(1, "c1-2", "# Plan\n1. a"),
        ForgeEvent.PlanDecision(1, "c1-2", approved = true, mode = Preset.EDITIONS_AUTO.mode.key()),
        ForgeEvent.ToolCall(1, "c1-3", "Write", "{}"),
        ForgeEvent.ToolResult(1, "c1-3", "Write", "ok", "{}", "+3 −0", added = 3, path = "src/a.js"),
        ForgeEvent.TurnEnd(1, "done"),
      )
    val s = SessionState.fold(events)
    assertEquals("fais un plan", s.title)
    assertEquals(2, s.todos.size)
    assertEquals(Preset.EDITIONS_AUTO.mode, s.mode)
    assertTrue("npm" in s.grants.programs)
    assertEquals(listOf("src/a.js"), s.lastTurnFiles)
    val card = s.feed.filterIsInstance<FeedItem.ToolCard>().first()
    assertEquals("code 0", card.result!!.summary)
    assertTrue(s.feed.filterIsInstance<FeedItem.Plan>().single().decision!!.approved)
  }

  @Test
  fun modelMessages_condenseEarlierTurns_keepCurrentInFull() {
    val events =
      listOf(
        ForgeEvent.TurnStart(1),
        ForgeEvent.User(1, "lis a.js"),
        ForgeEvent.Assistant(1, "<tool_call>\n{\"name\":\"Read\"}\n</tool_call>", ""),
        ForgeEvent.ToolCall(1, "c1-1", "Read", "{}"),
        ForgeEvent.ToolResult(1, "c1-1", "Read", "ok", "{\"content\":\"…\"}", path = "a.js"),
        ForgeEvent.Assistant(1, "C'est un fichier vide.", "C'est un fichier vide."),
        ForgeEvent.TurnEnd(1, "done"),
        ForgeEvent.TurnStart(2),
        ForgeEvent.User(2, "corrige-le"),
        ForgeEvent.Note(2, "mode", "<rappel-forge>Mode Plan</rappel-forge>", forModel = true, shown = false),
        ForgeEvent.Assistant(2, "<tool_call>\n{\"name\":\"Edit\"}\n</tool_call>", ""),
        ForgeEvent.ToolCall(2, "c2-1", "Edit", "{}"),
        ForgeEvent.ToolResult(2, "c2-1", "Edit", "refused", "{\"refused\":\"Mode Plan\"}"),
        ForgeEvent.Note(2, "blocage", "Rappel de Forge : tu sembles bloqué", forModel = true),
      )
    val m = SessionState.modelMessages(events, 2)
    assertEquals(listOf("user", "assistant", "user", "assistant", "user"), m.map { it.role })
    assertEquals("lis a.js", m[0].content)
    assertTrue(m[1].content.contains("[Outils utilisés : Read a.js]"))
    assertTrue(m[1].content.contains("C'est un fichier vide."))
    assertTrue(m[2].content.startsWith("corrige-le"))
    assertTrue(m[2].content.contains("Mode Plan"))
    assertTrue(m[4].content.contains("<tool_response>"))
    assertTrue(m[4].content.contains("Mode Plan\"}"))
    assertTrue(m[4].content.endsWith("tu sembles bloqué"))
  }
}
