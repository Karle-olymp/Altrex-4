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

import com.google.ai.edge.gallery.tools.ToolDefinition
import com.google.ai.edge.gallery.tools.ToolsProvider
import com.google.ai.edge.gallery.tools.WebSearchTool
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the bridge that lets local llama.cpp models call the Gallery tools. */
class LlamaCppToolBridgeTest {

  private class FakeToolsProvider(private val tools: List<ToolDefinition>) : ToolsProvider {
    override fun getAvailableTools(): List<ToolDefinition> = tools

    override fun registerTool(tool: ToolDefinition) {}

    override fun unregisterTool(tool: ToolDefinition) {}
  }

  @Test
  fun specs_exposeExpectedToolNames() {
    val names = LlamaCppToolBridge.specs.map { it.name }.toSet()

    assertEquals(
      setOf("load_skill", "run_js", "run_intent", "runMcpTool", "webSearch"),
      names,
    )
  }

  @Test
  fun specs_webSearchRequiresAQuery() {
    val spec = LlamaCppToolBridge.specs.first { it.name == "webSearch" }

    assertEquals(1, spec.params.size)
    assertEquals("query", spec.params[0].name)
    assertTrue(spec.params[0].required)
  }

  @Test
  fun dispatch_malformedCallReturnsError() = runBlocking {
    val out =
      LlamaCppToolBridge.dispatch(AgentToolCall("", JSONObject(), ""), FakeToolsProvider(emptyList()))

    assertTrue(out.contains("\"error\""))
    assertTrue(out.contains("failed"))
  }

  @Test
  fun dispatch_unknownToolReturnsError() = runBlocking {
    val out =
      LlamaCppToolBridge.dispatch(
        AgentToolCall("doesNotExist", JSONObject(), "doesNotExist"),
        FakeToolsProvider(emptyList()),
      )

    assertTrue(out.contains("Unknown tool"))
  }

  @Test
  fun dispatch_webSearchWithoutTheToolReportsNotAvailable() = runBlocking {
    val call = AgentToolCall("webSearch", JSONObject().put("query", "kotlin"), "kotlin")

    val out = LlamaCppToolBridge.dispatch(call, FakeToolsProvider(emptyList()))

    assertTrue(out.contains("not available"))
  }

  @Test
  fun dispatch_webSearchWithEmptyQueryReturnsErrorBeforeAnyNetworkCall() = runBlocking {
    val call = AgentToolCall("webSearch", JSONObject(), "")

    val out = LlamaCppToolBridge.dispatch(call, FakeToolsProvider(listOf(WebSearchTool())))

    assertTrue(out.contains("query"))
  }

  @Test
  fun dispatch_nameMatchingNormalizesCaseAndUnderscores() = runBlocking {
    val call = AgentToolCall("WEB_SEARCH", JSONObject(), "")

    val out = LlamaCppToolBridge.dispatch(call, FakeToolsProvider(listOf(WebSearchTool())))

    assertTrue(out.contains("query"))
  }
}
