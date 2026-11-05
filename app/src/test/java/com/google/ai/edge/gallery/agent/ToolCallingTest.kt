/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the ChatML/Hermes tool-calling protocol used by local llama.cpp models. */
class ToolCallingTest {

  @Test
  fun bareJsonCall_isParsedAndHidden() {
    val raw = "{ \"name\": \"run_command\", \"arguments\": { \"command\": \"altrex-setup -dev\" } }"
    val calls = ToolCalling.parseToolCalls(raw)
    assertEquals(1, calls.size)
    assertEquals("run_command", calls[0].name)
    assertEquals("altrex-setup -dev", calls[0].arguments.getString("command"))
    assertEquals("", ToolCalling.displayText(raw))
  }

  @Test
  fun ordinaryJsonAnswer_isNotACall() {
    val raw = "{\"name\": \"Alice\", \"age\": 3}"
    assertTrue(ToolCalling.parseToolCalls(raw).isEmpty())
    assertEquals(raw, ToolCalling.displayText(raw))
  }

  @Test
  fun splitter_dropsABareCallButShowsOtherJson() {
    fun run(vararg chunks: String): String {
      val sp = StreamSplitter()
      val sb = StringBuilder()
      for (c in chunks) sb.append(sp.feed(c).text)
      sb.append(sp.finish().text)
      return sb.toString()
    }
    assertEquals("", run("{ \"na", "me\": \"run_command\", \"argu", "ments\": {\"command\": \"ls\"} }"))
    assertEquals("{\"foo\": 1}", run("{\"fo", "o\": 1}"))
    assertEquals("Bonjour", run("Bon", "jour"))
  }

  private val webSearch =
    AgentToolSpec(
      name = "webSearch",
      description = "Search the web.",
      params = listOf(AgentToolParam("query", "string", "The search query.")),
    )

  @Test
  fun systemPrompt_includesInstructionsAndToolSignatures() {
    val prompt = ToolCalling.systemPrompt(listOf(webSearch), "SYS")

    assertTrue(prompt.startsWith("SYS"))
    assertTrue(prompt.contains("<tools>"))
    assertTrue(prompt.contains("</tools>"))
    assertTrue(prompt.contains("\"name\":\"webSearch\""))
    assertTrue(prompt.contains("<tool_call>"))
    assertTrue(prompt.contains("</tool_call>"))
  }

  @Test
  fun buildPrompt_usesChatmlAndDisablesThinking() {
    val prompt = ToolCalling.buildPrompt("SYS", listOf(AgentMsg("user", "hi")))

    assertTrue(prompt.contains("<|im_start|>system\nSYS<|im_end|>"))
    assertTrue(prompt.contains("<|im_start|>user\nhi<|im_end|>"))
    val thinkOff = "<" + "think>\n\n<" + "/think>\n\n"
    assertTrue(prompt.endsWith("<|im_start|>assistant\n" + thinkOff))
  }

  @Test
  fun parseToolCalls_extractsNameAndArguments() {
    val text =
      "Let me look that up.\n" +
        "<tool_call>\n{\"name\": \"webSearch\", \"arguments\": {\"query\": \"kotlin\"}}\n</tool_call>"

    val calls = ToolCalling.parseToolCalls(text)

    assertEquals(1, calls.size)
    assertEquals("webSearch", calls[0].name)
    assertEquals("kotlin", calls[0].arguments.getString("query"))
  }

  @Test
  fun parseToolCalls_handlesMultipleCalls() {
    val text =
      "<tool_call>{\"name\":\"load_skill\",\"arguments\":{\"skillName\":\"web-search\"}}</tool_call>" +
        "<tool_call>{\"name\":\"webSearch\",\"arguments\":{\"query\":\"news\"}}</tool_call>"

    val calls = ToolCalling.parseToolCalls(text)

    assertEquals(2, calls.size)
    assertEquals("load_skill", calls[0].name)
    assertEquals("web-search", calls[0].arguments.getString("skillName"))
    assertEquals("webSearch", calls[1].name)
    assertEquals("news", calls[1].arguments.getString("query"))
  }

  @Test
  fun parseToolCalls_acceptsArgumentsAsJsonString() {
    val text =
      "<tool_call>{\"name\":\"webSearch\",\"arguments\":\"{\\\"query\\\":\\\"android\\\"}\"}</tool_call>"

    val calls = ToolCalling.parseToolCalls(text)

    assertEquals(1, calls.size)
    assertEquals("webSearch", calls[0].name)
    assertEquals("android", calls[0].arguments.getString("query"))
  }

  @Test
  fun parseToolCalls_acceptsParametersAlias() {
    val text = "<tool_call>{\"name\":\"webSearch\",\"parameters\":{\"query\":\"x\"}}</tool_call>"

    val calls = ToolCalling.parseToolCalls(text)

    assertEquals(1, calls.size)
    assertEquals("x", calls[0].arguments.getString("query"))
  }

  @Test
  fun parseToolCalls_malformedJsonYieldsCallWithEmptyName() {
    val calls = ToolCalling.parseToolCalls("<tool_call>not json at all</tool_call>")

    assertEquals(1, calls.size)
    assertEquals("", calls[0].name)
  }

  @Test
  fun parseToolCalls_unterminatedBlockIsStillParsed() {
    val calls = ToolCalling.parseToolCalls("<tool_call>{\"name\":\"webSearch\",\"arguments\":{}}")

    assertEquals(1, calls.size)
    assertEquals("webSearch", calls[0].name)
  }

  @Test
  fun stripToolCalls_removesBlocksAndKeepsProse() {
    val text =
      "Here you go.<tool_call>{\"name\":\"webSearch\",\"arguments\":{\"query\":\"k\"}}" +
        "</tool_call> Done."

    val stripped = ToolCalling.stripToolCalls(text)

    assertTrue(stripped.startsWith("Here you go."))
    assertTrue(stripped.endsWith("Done."))
    assertTrue(!stripped.contains("tool_call"))
  }

  @Test
  fun toolResponseMessage_wrapsEachResultAsUserMessage() {
    val msg = ToolCalling.toolResponseMessage(listOf("{\"a\":1}", "plain"))

    assertEquals("user", msg.role)
    assertTrue(msg.content.contains("<tool_response>"))
    assertTrue(msg.content.contains("</tool_response>"))
    assertTrue(msg.content.contains("{\"a\":1}"))
    assertTrue(msg.content.contains("plain"))
  }
}
