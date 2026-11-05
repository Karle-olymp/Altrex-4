package com.google.ai.edge.gallery.runtime

import com.google.ai.edge.gallery.agent.ToolCalling
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * [ALTREX] Native tool calling for cloud models ([CloudApi]).
 *
 * The agent loop speaks a TEXT tool protocol (ToolCalling: a "# Tools" section with the definitions
 * in the system prompt, `<tool_call>` blocks in the assistant turns, `<tool_response>` blocks in a
 * user turn). Local and Argora models are trained on it; cloud models are trained on their API's
 * own function calling and handle the text protocol badly. This adapter translates at the API edge
 * only, so the loop and the text format stay exactly as they are:
 *
 * 1. the tool definitions are read back from the system prompt and sent as the API's `tools`, and
 *    the "# Tools" boilerplate is removed from the system text sent with them;
 * 2. the history's `<tool_call>` / `<tool_response>` pairs become native tool calls and results
 *    (deterministic ids); anything that does not pair up cleanly stays as plain text;
 * 3. tool calls streamed back natively are turned into the `<tool_call>` text the loop parses,
 *    appended after the visible answer.
 *
 * Nothing here logs: message contents and tool arguments never leave this file except in the
 * request itself.
 */
internal object CloudTools {
  // Same tags as ToolCalling, built the same way.
  private val TOOLS_OPEN = "<" + "tools>"
  private val TOOLS_CLOSE = "<" + "/tools>"
  private val RESP_OPEN = "<" + "tool_response>"
  private val RESP_CLOSE = "<" + "/tool_response>"
  private val CALL_OPEN: String get() = ToolCalling.TOOL_CALL_OPEN
  private val CALL_CLOSE: String get() = ToolCalling.TOOL_CALL_CLOSE

  /** Start of the section ToolCalling.systemPrompt appends after the instructions. */
  private const val TOOLS_HEADER = "# Tools\n\nYou may call one or more functions"
  /** Last line of that section, just before the closing call tag. */
  private const val FORMAT_LINE = "{\"name\": <function-name>, \"arguments\": <args-json-object>}"
  /** Names every provider accepts (OpenAI, Anthropic, Gemini, Mistral...). */
  private val NAME_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

  data class ToolDef(val name: String, val description: String, val parameters: JSONObject)

  data class Msg(val role: String, val content: String)

  /** A request that carries tools: the definitions, and the messages with the cleaned system text. */
  data class Prepared(val tools: List<ToolDef>, val messages: List<Msg>)

  data class Call(val id: String, val name: String, val arguments: JSONObject)

  data class Result(val id: String, val content: String)

  /**
   * One turn in a protocol-neutral form. Assistant: [text] then [calls]. User: [results] then
   * [text]. System: [text].
   */
  data class Turn(
    val role: String,
    val text: String,
    val calls: List<Call> = emptyList(),
    val results: List<Result> = emptyList(),
  )

  // ---- 1. Tool definitions.

  /**
   * The tool definitions of a system prompt made by ToolCalling.systemPrompt, and the system text
   * without the "# Tools" section. Null when there is no such section or anything in it looks off
   * (the request is then sent unchanged, in the text protocol).
   */
  fun extractTools(system: String): Pair<List<ToolDef>, String>? {
    val start = system.lastIndexOf(TOOLS_HEADER)
    if (start < 0) return null
    val open = system.indexOf(TOOLS_OPEN + "\n", start)
    if (open < 0) return null
    val close = system.indexOf(TOOLS_CLOSE, open)
    if (close < 0) return null
    val defs = ArrayList<ToolDef>()
    for (line in system.substring(open + TOOLS_OPEN.length, close).split('\n')) {
      if (line.isBlank()) continue
      val o = runCatching { JSONObject(line.trim()) }.getOrNull() ?: return null
      if (o.optString("type") != "function") return null
      val f = o.optJSONObject("function") ?: return null
      val name = f.optString("name")
      if (!NAME_RE.matches(name)) return null
      val params =
        f.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())
      defs.add(ToolDef(name, f.optString("description"), params))
    }
    if (defs.isEmpty() || defs.map { it.name }.toSet().size != defs.size) return null
    val format = system.indexOf(FORMAT_LINE, close)
    if (format < 0) return null
    val end = system.indexOf(CALL_CLOSE, format)
    if (end < 0) return null
    val before = system.substring(0, start).trimEnd()
    val after = system.substring(end + CALL_CLOSE.length).trim()
    return defs to listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n\n")
  }

  /** The request in native form, or null when it carries no tools (then nothing changes). */
  fun prepare(messages: JSONArray): Prepared? {
    val list = ArrayList<Msg>()
    for (i in 0 until messages.length()) {
      val m = messages.optJSONObject(i) ?: continue
      list.add(Msg(m.optString("role"), m.optString("content")))
    }
    val at = list.indexOfFirst { it.role == "system" && it.content.contains(TOOLS_HEADER) }
    if (at < 0) return null
    val (defs, cleaned) = extractTools(list[at].content) ?: return null
    list[at] = Msg("system", cleaned)
    return Prepared(defs, list)
  }

  // ---- 2. History.

  private sealed class Seg {
    data class Text(val text: String) : Seg()

    /** A call block: [raw] is its exact text, [name] is empty when the loop could not parse it. */
    data class CallBlock(val raw: String, val name: String, val arguments: JSONObject) : Seg()

    data class Response(val raw: String, val body: String) : Seg()
  }

  /** An assistant message cut into text and call blocks, read with the loop's own parser. */
  private fun assistantSegments(content: String): List<Seg> {
    if (!content.contains(CALL_OPEN)) {
      val bare = ToolCalling.bareCall(content) ?: return listOf(Seg.Text(content))
      return listOf(Seg.CallBlock(content, bare.name, bare.arguments))
    }
    val segs = ArrayList<Seg>()
    var idx = 0
    while (true) {
      val s = content.indexOf(CALL_OPEN, idx)
      if (s < 0) {
        segs.add(Seg.Text(content.substring(idx)))
        break
      }
      segs.add(Seg.Text(content.substring(idx, s)))
      val e = content.indexOf(CALL_CLOSE, s + CALL_OPEN.length)
      val end = if (e < 0) content.length else e + CALL_CLOSE.length
      val raw = content.substring(s, end)
      val parsed = ToolCalling.parseToolCalls(raw).firstOrNull()
      segs.add(Seg.CallBlock(raw, parsed?.name.orEmpty(), parsed?.arguments ?: JSONObject()))
      idx = end
    }
    return segs
  }

  /**
   * A user message cut into text and response blocks. A block without its closing tag (a result
   * shortened by the context fitter) runs to the next block or the end.
   */
  private fun userSegments(content: String): List<Seg> {
    val segs = ArrayList<Seg>()
    var idx = 0
    while (true) {
      val s = content.indexOf(RESP_OPEN, idx)
      if (s < 0) {
        segs.add(Seg.Text(content.substring(idx)))
        break
      }
      segs.add(Seg.Text(content.substring(idx, s)))
      val bodyStart = s + RESP_OPEN.length
      val close = content.indexOf(RESP_CLOSE, bodyStart)
      val next = content.indexOf(RESP_OPEN, bodyStart)
      val (bodyEnd, end) =
        when {
          close >= 0 && (next < 0 || close < next) -> close to close + RESP_CLOSE.length
          next >= 0 -> next to next
          else -> content.length to content.length
        }
      segs.add(Seg.Response(content.substring(s, end), content.substring(bodyStart, bodyEnd).trim()))
      idx = end
    }
    return segs
  }

  /** A short alphanumeric id, the same for the same position (Mistral wants exactly 9 characters). */
  internal fun callId(turn: Int, call: Int): String =
    "tc" + turn.toString(36).padStart(3, '0') + "x" + call.toString(36).padStart(3, '0')

  /**
   * The history in native form. An assistant message with call blocks, followed by a user message
   * with response blocks, becomes native calls and results, paired in order (the loop answers its
   * calls in order). A call that cannot be paired (malformed JSON, no response left) and a response
   * without its call stay in their message as plain text, so the request is always a valid
   * sequence. [anthropic]: blank messages and leading assistant turns are skipped, as the Messages
   * API wants (their calls then are not paired either).
   */
  fun convert(messages: List<Msg>, anthropic: Boolean): List<Turn> {
    val out = ArrayList<Turn>()
    var turnNo = 0
    var started = false
    var i = 0
    while (i < messages.size) {
      val m = messages[i]
      val role =
        when (m.role) {
          "system" -> "system"
          "assistant" -> "assistant"
          else -> "user"
        }
      if (role == "system") {
        out.add(Turn("system", m.content))
        i++
        continue
      }
      if (anthropic && (m.content.isBlank() || (!started && role == "assistant"))) {
        i++
        continue
      }
      started = true
      if (role == "user") {
        out.add(Turn("user", m.content))
        i++
        continue
      }
      val segs = assistantSegments(m.content)
      val next = messages.getOrNull(i + 1)?.takeIf { it.role != "system" && it.role != "assistant" }
      val respSegs =
        if (next != null && segs.any { it is Seg.CallBlock }) userSegments(next.content) else emptyList()
      val responses = respSegs.filterIsInstance<Seg.Response>()
      val text = StringBuilder()
      val calls = ArrayList<Call>()
      val paired = HashMap<Int, String>()
      var k = 0
      for (s in segs) {
        when (s) {
          is Seg.Text -> text.append(s.text)
          is Seg.CallBlock -> {
            if (s.name.isNotEmpty() && NAME_RE.matches(s.name) && k < responses.size) {
              val id = callId(turnNo, k)
              calls.add(Call(id, s.name, s.arguments))
              paired[k] = id
            } else {
              text.append(s.raw)
            }
            k++
          }
          is Seg.Response -> text.append(s.raw)
        }
      }
      if (calls.isEmpty()) {
        out.add(Turn("assistant", m.content))
        i++
        continue
      }
      turnNo++
      out.add(Turn("assistant", text.toString().trim(), calls = calls))
      val userText = StringBuilder()
      val results = ArrayList<Result>()
      var r = 0
      for (s in respSegs) {
        when (s) {
          is Seg.Response -> {
            val id = paired[r]
            if (id != null) results.add(Result(id, s.body)) else userText.append(s.raw)
            r++
          }
          is Seg.Text -> userText.append(s.text)
          is Seg.CallBlock -> userText.append(s.raw)
        }
      }
      out.add(Turn("user", userText.toString().trim(), results = results))
      i += 2
    }
    return out
  }

  // ---- Request parts.

  fun openAiTools(defs: List<ToolDef>): JSONArray =
    JSONArray().apply {
      for (d in defs) {
        put(
          JSONObject()
            .put("type", "function")
            .put(
              "function",
              JSONObject().put("name", d.name).put("description", d.description).put("parameters", d.parameters),
            )
        )
      }
    }

  fun anthropicTools(defs: List<ToolDef>): JSONArray =
    JSONArray().apply {
      for (d in defs) {
        put(JSONObject().put("name", d.name).put("description", d.description).put("input_schema", d.parameters))
      }
    }

  /**
   * OpenAI chat messages. [gemini]: Gemini 3 checks a "thought signature" on each replayed call;
   * the loop does not keep them, so the documented placeholder that skips the check is sent.
   */
  fun openAiMessages(p: Prepared, gemini: Boolean): JSONArray {
    val arr = JSONArray()
    for (t in convert(p.messages, anthropic = false)) {
      when (t.role) {
        "system" -> arr.put(JSONObject().put("role", "system").put("content", t.text))
        "assistant" -> {
          val o = JSONObject().put("role", "assistant")
          o.put("content", if (t.text.isEmpty() && t.calls.isNotEmpty()) JSONObject.NULL else t.text)
          if (t.calls.isNotEmpty()) {
            val calls = JSONArray()
            for (c in t.calls) {
              val call =
                JSONObject()
                  .put("id", c.id)
                  .put("type", "function")
                  .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments.toString()))
              if (gemini) {
                call.put(
                  "extra_content",
                  JSONObject().put("google", JSONObject().put("thought_signature", "skip_thought_signature_validator")),
                )
              }
              calls.put(call)
            }
            o.put("tool_calls", calls)
          }
          arr.put(o)
        }
        else -> {
          for (r in t.results) {
            arr.put(JSONObject().put("role", "tool").put("tool_call_id", r.id).put("content", r.content))
          }
          if (t.results.isEmpty() || t.text.isNotBlank()) {
            arr.put(JSONObject().put("role", "user").put("content", t.text))
          }
        }
      }
    }
    return arr
  }

  /**
   * Anthropic system text and turns: alternating roles starting with user, tool_result blocks
   * first in their user turn, consecutive turns of the same side merged.
   */
  fun anthropicMessages(p: Prepared): Pair<String, JSONArray> {
    val turns = convert(p.messages, anthropic = true)
    val system = turns.filter { it.role == "system" && it.text.isNotEmpty() }.joinToString("\n\n") { it.text }
    val arr = JSONArray()
    var lastRole = ""
    for (t in turns) {
      if (t.role == "system") continue
      val blocks = JSONArray()
      if (t.role == "assistant") {
        if (t.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", t.text))
        for (c in t.calls) {
          blocks.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", c.arguments))
        }
      } else {
        for (r in t.results) {
          blocks.put(
            JSONObject().put("type", "tool_result").put("tool_use_id", r.id).put("content", r.content.ifEmpty { "(vide)" })
          )
        }
        if (t.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", t.text))
      }
      if (blocks.length() == 0) continue
      if (arr.length() == 0 && t.role == "assistant") continue
      if (t.role == lastRole) {
        val prev = arr.getJSONObject(arr.length() - 1).getJSONArray("content")
        for (b in 0 until blocks.length()) prev.put(blocks.get(b))
      } else {
        arr.put(JSONObject().put("role", t.role).put("content", blocks))
        lastRole = t.role
      }
    }
    return system to arr
  }

  // ---- 3. Streamed calls back to text.

  /** A call being streamed: [complete] is false when its block never ended. */
  class StreamedCall(var id: String, var name: String) {
    val args = StringBuilder()
    var complete = true
  }

  /**
   * OpenAI `delta.tool_calls[]` fragments. Normally keyed by `index`, the first fragment carrying
   * the id and name. Some endpoints (Gemini's compat one) send whole calls with an empty id, no
   * index or the same index: a fragment that brings a name to a call that already has one, or
   * another id, starts a new call.
   */
  class OpenAiCalls {
    private val calls = ArrayList<StreamedCall>()
    private val byIndex = HashMap<Int, StreamedCall>()

    fun add(deltas: JSONArray) {
      for (j in 0 until deltas.length()) {
        val d = deltas.optJSONObject(j) ?: continue
        val f = d.optJSONObject("function")
        val id = d.optString("id").takeIf { it != "null" }.orEmpty()
        val name = f?.optString("name")?.takeIf { it != "null" }.orEmpty()
        val fragment =
          when (val a = f?.opt("arguments")) {
            null, JSONObject.NULL -> ""
            is String -> a
            else -> a.toString()
          }
        val index = if (d.has("index") && !d.isNull("index")) d.optInt("index", -1) else -1
        var cur = if (index >= 0) byIndex[index] else calls.lastOrNull()
        val startsNew =
          cur == null ||
            (name.isNotEmpty() && cur.name.isNotEmpty()) ||
            (id.isNotEmpty() && cur.id.isNotEmpty() && id != cur.id)
        if (startsNew || cur == null) {
          cur = StreamedCall(id, name)
          calls.add(cur)
          if (index >= 0) byIndex[index] = cur
        } else {
          if (cur.id.isEmpty()) cur.id = id
          if (cur.name.isEmpty()) cur.name = name
        }
        cur.args.append(fragment)
      }
    }

    fun calls(): List<StreamedCall> = calls.toList()
  }

  /** Anthropic tool_use blocks: content_block_start, input_json_delta partials, content_block_stop. */
  class AnthropicCalls {
    private val blocks = sortedMapOf<Int, StreamedCall>()
    private val initial = HashMap<Int, String>()
    private val open = HashSet<Int>()

    fun start(index: Int, block: JSONObject) {
      val c = StreamedCall(block.optString("id"), block.optString("name"))
      blocks[index] = c
      open.add(index)
      block.optJSONObject("input")?.takeIf { it.length() > 0 }?.let { initial[index] = it.toString() }
    }

    fun delta(index: Int, partial: String) {
      blocks[index]?.args?.append(partial)
    }

    fun stop(index: Int) {
      open.remove(index)
    }

    /**
     * A server-side refusal fallback took over mid-answer: tool_use blocks before the boundary
     * belong to the declined attempt and must not run (Claude API, refusal fallbacks).
     */
    fun fallbackBoundary() {
      blocks.clear()
      initial.clear()
      open.clear()
    }

    fun calls(): List<StreamedCall> =
      blocks.map { (index, c) ->
        if (c.args.isEmpty()) initial[index]?.let { c.args.append(it) }
        c.complete = index !in open
        c
      }
  }

  /** The arguments as a JSON object: empty for nothing, unwrapped when double-encoded, else null. */
  internal fun parseArguments(raw: String): JSONObject? {
    val t = raw.trim()
    if (t.isEmpty() || t == "null") return JSONObject()
    runCatching { JSONObject(t) }.getOrNull()?.let { return it }
    val v = runCatching { JSONTokener(t).nextValue() }.getOrNull()
    if (v is String) runCatching { JSONObject(v) }.getOrNull()?.let { return it }
    return null
  }

  /**
   * The exact `<tool_call>` text the loop parses. A call whose name or arguments are unusable is
   * written as a block the loop's parser rejects, so the loop answers its "malformed call" error
   * and the model can retry, instead of running a tool with truncated arguments.
   */
  fun callText(c: StreamedCall): String {
    val args = if (c.complete) parseArguments(c.args.toString()) else null
    if (args != null && NAME_RE.matches(c.name)) return ToolCalling.callMessage(c.name, args).content
    val raw = c.args.toString().replace(CALL_OPEN, "").replace(CALL_CLOSE, "")
    var body = "{\"name\": " + JSONObject.quote(c.name) + ", \"arguments\": " + raw
    if (runCatching { JSONObject(body) }.isSuccess) body = "{\"name\": " + JSONObject.quote(c.name) + ", \"arguments\": "
    return CALL_OPEN + "\n" + body + "\n" + CALL_CLOSE
  }

  /** [text] (the visible answer, reasoning included) followed by the calls, if any. */
  fun withCalls(text: String, calls: List<StreamedCall>): String {
    if (calls.isEmpty()) return text
    val sep = if (text.isEmpty() || text.endsWith("\n\n")) "" else "\n\n"
    return text + sep + calls.joinToString("\n") { callText(it) }
  }

  // ---- 4. Fallback to the text protocol.

  /** The provider refused the native request: retry in the text protocol. */
  class NativeToolsRejected(message: String, val toolRelated: Boolean) : CloudApi.CloudException(message)

  private val textOnly = ConcurrentHashMap.newKeySet<String>()

  private fun key(provider: CloudApi.Provider, modelId: String) = provider.id + ":" + modelId

  fun useNative(provider: CloudApi.Provider, modelId: String): Boolean = key(provider, modelId) !in textOnly

  /** For this session, this model gets the text protocol (no retry every turn). */
  fun rememberTextOnly(provider: CloudApi.Provider, modelId: String) {
    textOnly.add(key(provider, modelId))
  }

  internal fun forgetAll() = textOnly.clear()

  /** An error that names tools ("No endpoints found that support tool use", "tools is not supported"...). */
  fun mentionsTools(body: String): Boolean {
    val b = body.lowercase()
    return listOf("tool", "function", "thought_signature", "reasoning_content").any { it in b }
  }

  /**
   * Whether a failed native request is retried in the text protocol: any 400/422 (a request the
   * provider could not take, the text one is what worked before), a 404/405/501 only when it is
   * about tools (otherwise it is the model id or the endpoint, the same for both).
   */
  fun shouldRetryWithText(code: Int, body: String): Boolean =
    when (code) {
      400, 422 -> true
      404, 405, 501 -> mentionsTools(body)
      else -> false
    }
}
