package com.google.ai.edge.gallery.agent

import com.google.ai.edge.gallery.ui.llmsingleturn.AgentMsg
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGuardsTest {
  private fun call(name: String, json: String) = AgentToolCall(name, JSONObject(json), json)

  @Test
  fun repeatGuardEscalatesOnIdenticalCalls() {
    val guard = RepeatCallGuard()
    val verdicts = (1..8).map { guard.check(call("webSearch", """{"query":"nginx tls"}""")) }
    assertEquals(RepeatCallGuard.Verdict.RUN, verdicts[1])
    assertEquals(RepeatCallGuard.Verdict.RUN_WITH_WARNING, verdicts[2])
    assertEquals(RepeatCallGuard.Verdict.REFUSE, verdicts[4])
    assertEquals(RepeatCallGuard.Verdict.STOP, verdicts[7])
  }

  @Test
  fun repeatGuardIgnoresKeyOrderAndResetsOnNewCall() {
    val guard = RepeatCallGuard()
    guard.check(call("run_js", """{"a":1,"b":"x"}"""))
    guard.check(call("runJs", """{"b":"x","a":1}"""))
    assertEquals(RepeatCallGuard.Verdict.RUN_WITH_WARNING, guard.check(call("run_js", """{"b":"x","a":1}""")))
    assertEquals(RepeatCallGuard.Verdict.RUN, guard.check(call("run_js", """{"a":2}""")))
  }

  @Test
  fun longResultIsCutAndReadable() {
    val budget = ToolResultBudget(1_500)
    val full = (1..2_000).joinToString(" ") { "mot$it" }
    val cut = budget.fit(full)
    assertTrue(cut.length < 1_700)
    assertTrue(cut.contains("read_tool_output"))
    assertTrue(cut.endsWith(full.takeLast(100)))
    val offset = Regex("\"offset\": (\\d+)").find(cut)!!.groupValues[1].toInt()
    val page = budget.read("r1", offset)
    assertTrue(page.startsWith(full.substring(offset, offset + 50)))
    assertEquals("court", budget.fit("court"))
  }

  @Test
  fun fitterDropsOldStepsAsPairsAndKeepsRequest() {
    val messages = ArrayList<AgentMsg>()
    messages.add(AgentMsg("user", "ancienne question"))
    messages.add(AgentMsg("assistant", "ancienne réponse"))
    messages.add(AgentMsg("user", "la demande"))
    repeat(5) { i ->
      messages.add(AgentMsg("assistant", "<tool_call>appel $i</tool_call>"))
      messages.add(AgentMsg("user", "résultat $i " + "x".repeat(1_000)))
    }
    val fitted = AgentContextFitter.fit(messages, requestIndex = 2, budgetChars = 2_000)
    assertEquals("ancienne question", fitted[0].content)
    assertTrue(fitted[2].content.startsWith("la demande"))
    assertTrue(fitted[2].content.contains("omises"))
    // Steps stay whole pairs and the last result is complete.
    val steps = fitted.drop(3)
    assertEquals(0, steps.size % 2)
    assertEquals("assistant", steps.first().role)
    assertTrue(fitted.last().content.startsWith("résultat 4") && fitted.last().content.length > 1_000)
  }
}
