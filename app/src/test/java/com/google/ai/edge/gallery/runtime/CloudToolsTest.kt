package com.google.ai.edge.gallery.runtime

import com.google.ai.edge.gallery.agent.LlamaCppToolBridge
import com.google.ai.edge.gallery.runtime.CloudApi.Provider
import com.google.ai.edge.gallery.ui.llmsingleturn.ToolCalling
import java.io.BufferedReader
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [ALTREX] Native tool calling for cloud models: text protocol <-> provider function calling. */
class CloudToolsTest {
  private val instructions = "Tu es Altrex, un agent.\n\nCompétences disponibles :\n- meteo : la météo"
  private val system = ToolCalling.systemPrompt(LlamaCppToolBridge.specs, instructions)

  private fun msgs(vararg pairs: Pair<String, String>) =
    JSONArray().apply { for ((r, c) in pairs) put(JSONObject().put("role", r).put("content", c)) }

  private fun call(name: String, args: JSONObject) = ToolCalling.callMessage(name, args).content

  private fun results(vararg r: String) = ToolCalling.toolResponseMessage(r.toList()).content

  private fun sse(fixture: String, onEvent: (String, String) -> Unit) =
    CloudApi.readSseLines(BufferedReader(StringReader(fixture.trimIndent() + "\n")), { false }, onEvent)

  private fun openAiStream(fixture: String, native: Boolean = true, texts: MutableList<String> = ArrayList()): String {
    val s = CloudApi.OpenAiStream(Provider.GROQ, native) { texts.add(it) }
    sse(fixture) { _, d -> s.onData(d) }
    return s.finish()
  }

  private fun anthropicStream(fixture: String, texts: MutableList<String> = ArrayList()): String {
    val s = CloudApi.AnthropicStream(Provider.ANTHROPIC, true) { texts.add(it) }
    sse(fixture, s::onEvent)
    return s.finish()
  }

  /** What the agent loop does with a model answer. */
  private fun loopCalls(answer: String) = ToolCalling.parseToolCalls(ToolCalling.stripThinking(answer))

  @After
  fun tearDown() = CloudTools.forgetAll()

  // ---- Tool definitions.

  @Test
  fun extractTools_realSystemPrompt() {
    val (defs, cleaned) = CloudTools.extractTools(system)!!
    assertEquals(LlamaCppToolBridge.specs.map { it.name }, defs.map { it.name })
    assertEquals(instructions, cleaned)
    assertFalse(cleaned.contains("# Tools"))
    assertFalse(cleaned.contains("tool_call"))
    val spec = LlamaCppToolBridge.specs.first { it.name == "webSearch" }
    val def = defs.first { it.name == "webSearch" }
    assertEquals(spec.description, def.description)
    assertEquals("object", def.parameters.getString("type"))
    assertTrue(def.parameters.getJSONObject("properties").has(spec.params.first().name))
    assertEquals(
      spec.params.filter { it.required }.map { it.name },
      def.parameters.getJSONArray("required").let { a -> (0 until a.length()).map { a.getString(it) } },
    )
  }

  @Test
  fun prepare_noTools_requestUnchanged() {
    assertNull(CloudTools.prepare(msgs("system" to "Tu es Altrex.", "user" to "salut")))
    assertNull(CloudTools.prepare(msgs("user" to "salut")))
    // A broken tools section is left alone too (sent in the text protocol).
    assertNull(CloudTools.prepare(msgs("system" to system.replace("\"type\":\"function\"", "\"type\":\"x\""), "user" to "a")))
    val body = CloudApi.openAiBody(Provider.GROQ, "m", msgs("system" to system, "user" to "a"), 100)
    assertFalse(body.has("tools"))
    assertEquals(system, body.getJSONArray("messages").getJSONObject(0).getString("content"))
  }

  @Test
  fun openAiBody_native_toolsAndCleanSystem() {
    val p = CloudTools.prepare(msgs("system" to system, "user" to "a"))!!
    val body = CloudApi.openAiBody(Provider.GROQ, "openai/gpt-oss-120b", JSONArray(), 1000, p)
    val tools = body.getJSONArray("tools")
    assertEquals(LlamaCppToolBridge.specs.size, tools.length())
    assertEquals("function", tools.getJSONObject(0).getString("type"))
    assertEquals("load_skill", tools.getJSONObject(0).getJSONObject("function").getString("name"))
    assertFalse(body.has("tool_choice"))
    assertEquals(instructions, body.getJSONArray("messages").getJSONObject(0).getString("content"))
    assertEquals(1000, body.getInt("max_tokens"))
  }

  @Test
  fun anthropicBody_native_toolsNoChoiceNoSampling() {
    val p = CloudTools.prepare(msgs("system" to system, "user" to "a"))!!
    val body = CloudApi.anthropicBody("claude-opus-5-5", JSONArray(), thinking = true, maxTokens = 8192, fallbacks = true, native = p)
    val t = body.getJSONArray("tools").getJSONObject(0)
    assertEquals("load_skill", t.getString("name"))
    assertEquals("object", t.getJSONObject("input_schema").getString("type"))
    assertFalse(body.has("tool_choice"))
    assertFalse(body.has("temperature"))
    assertEquals(instructions, body.getString("system"))
    assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
    assertEquals("default", body.getString("fallbacks"))
  }

  // ---- History, OpenAI.

  @Test
  fun openAi_singleCall_resultAndFinalAnswer() {
    val p =
      CloudTools.prepare(
        msgs(
          "system" to system,
          "user" to "Météo à Lyon ?",
          "assistant" to "Je cherche.\n" + call("webSearch", JSONObject().put("query", "météo Lyon")),
          "user" to results("{\"result\":\"12°C\"}"),
          "assistant" to "Il fait 12°C.",
          "user" to "Merci",
        )
      )!!
    val m = CloudTools.openAiMessages(p, gemini = false)
    assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "user"), (0 until m.length()).map { m.getJSONObject(it).getString("role") })
    val a = m.getJSONObject(2)
    assertEquals("Je cherche.", a.getString("content"))
    val tc = a.getJSONArray("tool_calls").getJSONObject(0)
    assertEquals("tc000x000", tc.getString("id"))
    assertEquals(9, tc.getString("id").length)
    assertEquals("function", tc.getString("type"))
    assertEquals("webSearch", tc.getJSONObject("function").getString("name"))
    assertEquals("météo Lyon", JSONObject(tc.getJSONObject("function").getString("arguments")).getString("query"))
    assertFalse(tc.has("extra_content"))
    assertEquals("tc000x000", m.getJSONObject(3).getString("tool_call_id"))
    assertEquals("{\"result\":\"12°C\"}", m.getJSONObject(3).getString("content"))
  }

  @Test
  fun openAi_parallelCalls_idsInOrder_geminiSignature() {
    val p =
      CloudTools.prepare(
        msgs(
          "system" to system,
          "user" to "a",
          "assistant" to call("webSearch", JSONObject().put("query", "x")) + "\n" + call("read_file", JSONObject().put("path", "/a")),
          "user" to results("R1", "R2"),
        )
      )!!
    val m = CloudTools.openAiMessages(p, gemini = true)
    val a = m.getJSONObject(2)
    assertTrue(a.isNull("content"))
    val calls = a.getJSONArray("tool_calls")
    assertEquals(2, calls.length())
    assertEquals("skip_thought_signature_validator", calls.getJSONObject(0).getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
    assertEquals("tc000x001", m.getJSONObject(4).getString("tool_call_id"))
    assertEquals("R2", m.getJSONObject(4).getString("content"))
    assertEquals(5, m.length())
  }

  @Test
  fun openAi_moreCallsThanResults_restStaysText() {
    val third = call("check_job", JSONObject().put("id", "j1"))
    val p =
      CloudTools.prepare(
        msgs(
          "system" to system,
          "user" to "a",
          "assistant" to call("webSearch", JSONObject().put("query", "x")) + "\n" + call("read_file", JSONObject().put("path", "/a")) + "\n" + third,
          "user" to results("R1", "R2"),
        )
      )!!
    val m = CloudTools.openAiMessages(p, gemini = false)
    val a = m.getJSONObject(2)
    assertEquals(2, a.getJSONArray("tool_calls").length())
    assertEquals(third, a.getString("content"))
    assertEquals(listOf("tool", "tool"), (3 until m.length()).map { m.getJSONObject(it).getString("role") })
  }

  @Test
  fun openAi_moreResultsThanCalls_andNudge_becomeUserText() {
    val p =
      CloudTools.prepare(
        msgs(
          "system" to system,
          "user" to "a",
          "assistant" to call("webSearch", JSONObject().put("query", "x")),
          "user" to results("R1", "R2") + "\n\nRéponds maintenant.",
        )
      )!!
    val m = CloudTools.openAiMessages(p, gemini = false)
    assertEquals(listOf("system", "user", "assistant", "tool", "user"), (0 until m.length()).map { m.getJSONObject(it).getString("role") })
    val extra = m.getJSONObject(4).getString("content")
    assertTrue(extra.contains("R2"))
    assertTrue(extra.endsWith("Réponds maintenant."))
    assertFalse(extra.contains("R1"))
  }

  @Test
  fun openAi_malformedCall_staysTextWithItsResult() {
    val bad = ToolCalling.TOOL_CALL_OPEN + "\n{\"name\": \"webSearch\", \"arguments\": {\"query\": \n" + ToolCalling.TOOL_CALL_CLOSE
    val p =
      CloudTools.prepare(
        msgs(
          "system" to system,
          "user" to "a",
          "assistant" to bad + "\n" + call("read_file", JSONObject().put("path", "/a")),
          "user" to results("{\"error\":\"Invalid tool call\"}", "R2"),
        )
      )!!
    val m = CloudTools.openAiMessages(p, gemini = false)
    val a = m.getJSONObject(2)
    assertEquals(bad, a.getString("content"))
    val calls = a.getJSONArray("tool_calls")
    assertEquals(1, calls.length())
    assertEquals("read_file", calls.getJSONObject(0).getJSONObject("function").getString("name"))
    // The second block's id, paired with the second result.
    assertEquals("tc000x001", calls.getJSONObject(0).getString("id"))
    assertEquals("tool", m.getJSONObject(3).getString("role"))
    assertEquals("R2", m.getJSONObject(3).getString("content"))
    assertTrue(m.getJSONObject(4).getString("content").contains("Invalid tool call"))
  }

  @Test
  fun openAi_callWithoutResults_unchanged_bareCallAndShortenedResult() {
    val noResult = CloudTools.prepare(msgs("system" to system, "user" to "a", "assistant" to call("webSearch", JSONObject().put("query", "x"))))!!
    val m1 = CloudTools.openAiMessages(noResult, gemini = false)
    assertFalse(m1.getJSONObject(2).has("tool_calls"))

    // A bare JSON call (GPT-OSS style) and a result shortened by the context fitter (no closing tag).
    val shortened = results("x".repeat(700)).take(600) + "\n[… résultat raccourci …]"
    val p =
      CloudTools.prepare(
        msgs("system" to system, "user" to "a", "assistant" to "{\"name\": \"webSearch\", \"arguments\": {\"query\": \"q\"}}", "user" to shortened)
      )!!
    val m = CloudTools.openAiMessages(p, gemini = false)
    assertEquals("webSearch", m.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
    assertTrue(m.getJSONObject(3).getString("content").endsWith("[… résultat raccourci …]"))
    assertEquals(4, m.length())
  }

  // ---- History, Anthropic.

  @Test
  fun anthropic_callResultFinal_blocksAndAlternation() {
    val p =
      CloudTools.prepare(
        msgs(
          "system" to system,
          "assistant" to "Bonjour, je suis Altrex.",
          "user" to "Météo ?",
          "assistant" to "Je cherche.\n" + call("webSearch", JSONObject().put("query", "météo")),
          "user" to results("R1"),
          "user" to "Réponds maintenant.",
          "assistant" to "Il fait beau.",
        )
      )!!
    val (sys, turns) = CloudTools.anthropicMessages(p)
    assertEquals(instructions, sys)
    assertEquals(listOf("user", "assistant", "user", "assistant"), (0 until turns.length()).map { turns.getJSONObject(it).getString("role") })
    val a = turns.getJSONObject(1).getJSONArray("content")
    assertEquals("text", a.getJSONObject(0).getString("type"))
    val use = a.getJSONObject(1)
    assertEquals("tool_use", use.getString("type"))
    assertEquals("webSearch", use.getString("name"))
    assertEquals("météo", use.getJSONObject("input").getString("query"))
    val u = turns.getJSONObject(2).getJSONArray("content")
    assertEquals("tool_result", u.getJSONObject(0).getString("type"))
    assertEquals(use.getString("id"), u.getJSONObject(0).getString("tool_use_id"))
    assertEquals("R1", u.getJSONObject(0).getString("content"))
    // The separate nudge message is merged after the tool_result block.
    assertEquals("Réponds maintenant.", u.getJSONObject(1).getString("text"))
  }

  @Test
  fun anthropic_leadingAssistantCall_notPaired() {
    val p =
      CloudTools.prepare(
        msgs("system" to system, "assistant" to call("webSearch", JSONObject().put("query", "x")), "user" to results("R1"))
      )!!
    val (_, turns) = CloudTools.anthropicMessages(p)
    assertEquals(1, turns.length())
    val blocks = turns.getJSONObject(0).getJSONArray("content")
    assertEquals(1, blocks.length())
    assertEquals("text", blocks.getJSONObject(0).getString("type"))
  }

  @Test
  fun anthropic_parallelAndMismatch() {
    val p =
      CloudTools.prepare(
        msgs(
          "system" to system,
          "user" to "a",
          "assistant" to call("webSearch", JSONObject().put("query", "x")) + "\n" + call("read_file", JSONObject().put("path", "/a")),
          "user" to results("R1"),
        )
      )!!
    val (_, turns) = CloudTools.anthropicMessages(p)
    val a = turns.getJSONObject(1).getJSONArray("content")
    // One pair only: the unmatched second call stays text, before the tool_use block.
    assertEquals(listOf("text", "tool_use"), (0 until a.length()).map { a.getJSONObject(it).getString("type") })
    assertTrue(a.getJSONObject(0).getString("text").contains("read_file"))
    val u = turns.getJSONObject(2).getJSONArray("content")
    assertEquals(1, u.length())
    assertEquals("tool_result", u.getJSONObject(0).getString("type"))
  }

  // ---- Streams, OpenAI.

  @Test
  fun openAiStream_fragmentsSeveralCalls_reasoningAndText() {
    val texts = ArrayList<String>()
    val out =
      openAiStream(
        """
        data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning":"Il faut chercher."}}]}

        data: {"choices":[{"index":0,"delta":{"content":"Je regarde."}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"webSearch","arguments":""}}]}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"que"}}]}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ry\": \"météo Lyon\"}"}}]}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"read_file","arguments":"{\"path\":"}}]}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":" \"/sdcard/a.txt\"}"}}]}}]}

        data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

        data: [DONE]
        """,
        texts = texts,
      )
    val calls = loopCalls(out)
    assertEquals(listOf("webSearch", "read_file"), calls.map { it.name })
    assertEquals("météo Lyon", calls[0].arguments.getString("query"))
    assertEquals("/sdcard/a.txt", calls[1].arguments.getString("path"))
    assertTrue(out.contains(call("webSearch", JSONObject().put("query", "météo Lyon"))))
    assertTrue(out.startsWith("<think>\nIl faut chercher.\n</think>\n\nJe regarde."))
    assertEquals("Je regarde.", ToolCalling.displayText(out).trim())
    // What was streamed is a prefix of the final text and never shows a call.
    for (t in texts) {
      assertTrue(out.startsWith(t))
      assertFalse(t.contains(ToolCalling.TOOL_CALL_OPEN))
    }
  }

  @Test
  fun openAiStream_geminiStyle_emptyIdsWholeCalls() {
    val out =
      openAiStream(
        """
        data: {"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"id":"","type":"function","function":{"name":"webSearch","arguments":"{\"query\":\"a\"}"}}]}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"","type":"function","function":{"name":"load_skill","arguments":"{\"skillName\":\"meteo\"}"}}]}}]}

        data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
        """
      )
    val calls = loopCalls(out)
    assertEquals(listOf("webSearch", "load_skill"), calls.map { it.name })
    assertEquals("meteo", calls[1].arguments.getString("skillName"))
    assertTrue(out.startsWith(ToolCalling.TOOL_CALL_OPEN))
  }

  @Test
  fun openAiStream_badArguments_cleanErrorOrRepair() {
    val out =
      openAiStream(
        """
        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"write_file","arguments":"{\"path\": \"/a\", \"content\": \"abc"}}]}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"webSearch","arguments":"\"{\\\"query\\\":\\\"x\\\"}\""}}]}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":2,"id":"c3","function":{"name":"check_job"}}]}}]}

        data: {"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}
        """
      )
    val calls = loopCalls(out)
    assertEquals(3, calls.size)
    // Truncated arguments: the loop sees a malformed call (empty name) and answers its error.
    assertEquals("", calls[0].name)
    // Double-encoded arguments are unwrapped; no arguments at all is an empty object.
    assertEquals("x", calls[1].arguments.getString("query"))
    assertEquals("check_job", calls[2].name)
    assertEquals(0, calls[2].arguments.length())
    assertTrue(out.contains("Réponse coupée"))
  }

  @Test
  fun openAiStream_noCalls_unchanged() {
    val out =
      openAiStream(
        """
        data: {"choices":[{"index":0,"delta":{"content":"Bonjour"}}]}

        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
        """
      )
    assertEquals("Bonjour", out)
  }

  @Test
  fun openAiStream_toolErrorBeforeText_rejectsNative() {
    val fixture = """data: {"error":{"message":"No endpoints found that support tool use.","code":404}}"""
    try {
      openAiStream(fixture)
      fail()
    } catch (e: CloudTools.NativeToolsRejected) {
      assertTrue(e.toolRelated)
    }
    // Without native tools it is an ordinary error.
    try {
      openAiStream(fixture, native = false)
      fail()
    } catch (e: CloudApi.CloudException) {
      assertFalse(e is CloudTools.NativeToolsRejected)
    }
  }

  // ---- Streams, Anthropic.

  @Test
  fun anthropicStream_thinkingTextAndPartialJsonToolUse() {
    val texts = ArrayList<String>()
    val out =
      anthropicStream(
        """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[]}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Chercher la météo."}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Je vérifie."}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":1}

        event: content_block_start
        data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_01","name":"webSearch","input":{}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"query\": \"mét"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"éo Paris\"}"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":2}

        event: content_block_start
        data: {"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"toolu_02","name":"check_job","input":{}}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":3}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":40}}

        event: message_stop
        data: {"type":"message_stop"}
        """,
        texts,
      )
    val calls = loopCalls(out)
    assertEquals(listOf("webSearch", "check_job"), calls.map { it.name })
    assertEquals("météo Paris", calls[0].arguments.getString("query"))
    assertTrue(out.startsWith("<think>\nChercher la météo.\n</think>\n\nJe vérifie.\n\n" + ToolCalling.TOOL_CALL_OPEN))
    assertEquals("Je vérifie.", ToolCalling.displayText(out).trim())
    for (t in texts) assertTrue(out.startsWith(t))
  }

  @Test
  fun anthropicStream_refusalDropsCalls_fallbackBoundary_unfinishedBlock() {
    val refused =
      anthropicStream(
        """
        data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"run_command","input":{}}}

        data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"command\": \"ls"}}

        data: {"type":"message_delta","delta":{"stop_reason":"refusal"}}
        """
      )
    assertTrue(loopCalls(refused).isEmpty())
    assertTrue(refused.contains("a refusé"))

    val fallback =
      anthropicStream(
        """
        data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"run_command","input":{}}}

        data: {"type":"content_block_start","index":1,"content_block":{"type":"fallback"}}

        data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"t2","name":"webSearch","input":{}}}

        data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"a\"}"}}

        data: {"type":"content_block_stop","index":2}

        data: {"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"t3","name":"read_file","input":{}}}

        data: {"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"/a\"}"}}

        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}
        """
      )
    val calls = loopCalls(fallback)
    // The declined attempt's call is gone; the block that never ended is a malformed call.
    assertEquals(listOf("webSearch", ""), calls.map { it.name })
  }

  // ---- Fallback decision and memory.

  @Test
  fun fallbackDecision_errorText() {
    val openRouter = """{"error":{"message":"No endpoints found that support tool use. To learn more about provider routing, visit: https://openrouter.ai/docs/provider-routing","code":404}}"""
    assertTrue(CloudTools.shouldRetryWithText(404, openRouter))
    assertTrue(CloudTools.mentionsTools(openRouter))
    assertFalse(CloudTools.shouldRetryWithText(404, """{"error":{"message":"The model `x` does not exist"}}"""))
    assertTrue(CloudTools.shouldRetryWithText(400, """{"error":{"message":"tools is not supported for this model"}}"""))
    assertTrue(CloudTools.shouldRetryWithText(400, """{"error":{"message":"Bad request"}}"""))
    assertFalse(CloudTools.mentionsTools("""{"error":{"message":"Bad request"}}"""))
    assertFalse(CloudTools.shouldRetryWithText(429, "tool"))
    assertFalse(CloudTools.shouldRetryWithText(500, "tool"))
    assertFalse(CloudTools.shouldRetryWithText(401, "tool"))
  }

  @Test
  fun fallbackMemory_perProviderAndModel() {
    assertTrue(CloudTools.useNative(Provider.OPENROUTER, "x/free"))
    CloudTools.rememberTextOnly(Provider.OPENROUTER, "x/free")
    assertFalse(CloudTools.useNative(Provider.OPENROUTER, "x/free"))
    assertTrue(CloudTools.useNative(Provider.OPENROUTER, "y"))
    assertTrue(CloudTools.useNative(Provider.GROQ, "x/free"))
    CloudTools.forgetAll()
    assertTrue(CloudTools.useNative(Provider.OPENROUTER, "x/free"))
  }

  // ---- Round trip: text history -> native -> model answer -> text -> loop -> native again.

  @Test
  fun roundTrip_openAiAndAnthropic() {
    val history =
      arrayListOf(
        "system" to system,
        "user" to "Lis /a puis cherche.",
        "assistant" to call("read_file", JSONObject().put("path", "/a")),
        "user" to results("contenu"),
      )
    val p1 = CloudTools.prepare(msgs(*history.toTypedArray()))!!
    assertEquals("read_file", CloudTools.openAiMessages(p1, false).getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))

    // The model answers natively with a call carrying characters to escape.
    val answer =
      openAiStream(
        """
        data: {"choices":[{"index":0,"delta":{"reasoning":"Ensuite la recherche."}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_9","function":{"name":"webSearch","arguments":"{\"query\": \"a/b \\\"c\\\" é\"}"}}]}}]}

        data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
        """
      )
    val calls = loopCalls(answer)
    assertEquals(1, calls.size)
    assertEquals("a/b \"c\" é", calls[0].arguments.getString("query"))

    // What the loop appends, then the next request in both protocols.
    history.add("assistant" to ToolCalling.stripThinking(answer).trim())
    history.add("user" to results("trouvé"))
    val p2 = CloudTools.prepare(msgs(*history.toTypedArray()))!!
    val o = CloudTools.openAiMessages(p2, false)
    assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "tool"), (0 until o.length()).map { o.getJSONObject(it).getString("role") })
    val second = o.getJSONObject(4).getJSONArray("tool_calls").getJSONObject(0)
    assertEquals("tc001x000", second.getString("id"))
    assertEquals("a/b \"c\" é", JSONObject(second.getJSONObject("function").getString("arguments")).getString("query"))
    assertEquals("tc001x000", o.getJSONObject(5).getString("tool_call_id"))

    val (_, turns) = CloudTools.anthropicMessages(p2)
    assertEquals(listOf("user", "assistant", "user", "assistant", "user"), (0 until turns.length()).map { turns.getJSONObject(it).getString("role") })
    val use = turns.getJSONObject(3).getJSONArray("content").getJSONObject(0)
    assertEquals("tool_use", use.getString("type"))
    assertEquals("a/b \"c\" é", use.getJSONObject("input").getString("query"))
    assertEquals(use.getString("id"), turns.getJSONObject(4).getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
    assertNotNull(CloudTools.extractTools(system))
  }
}
