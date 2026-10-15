package com.google.ai.edge.gallery.ui.llmsingleturn

import org.json.JSONArray
import org.json.JSONObject

/** One parameter of a tool. */
data class AgentToolParam(
  val name: String,
  val type: String,
  val description: String,
  val required: Boolean = true,
)

/** Description of a tool, rendered as JSON in the system prompt (Qwen / Hermes format). */
data class AgentToolSpec(
  val name: String,
  val description: String,
  val params: List<AgentToolParam>,
)

/** A tool call found in the model output. An empty [name] means the JSON was invalid. */
data class AgentToolCall(val name: String, val arguments: JSONObject, val raw: String)

/** A message of the conversation. [role] is "user" or "assistant". */
data class AgentMsg(val role: String, val content: String)

/**
 * Native tool calling for ChatML models trained on the Hermes format (Qwen2.5, Qwen3...): tools are
 * described in the system prompt, the model answers with <tool_call>{json}</tool_call> and the
 * results are sent back inside <tool_response> blocks.
 */
object ToolCalling {
  // Tags are built with concatenation so that nothing can mistake them for markup.
  private val TOOLS_OPEN = "<" + "tools>"
  private val TOOLS_CLOSE = "<" + "/tools>"
  private val CALL_OPEN = "<" + "tool_call>"
  private val CALL_CLOSE = "<" + "/tool_call>"
  private val RESP_OPEN = "<" + "tool_response>"
  private val RESP_CLOSE = "<" + "/tool_response>"
  private val THINK_OFF = "<" + "think>\n\n<" + "/think>\n\n"
  private const val IM_START = "<|im_start|>"
  private const val IM_END = "<|im_end|>"

  private fun specJson(spec: AgentToolSpec): String {
    val props = JSONObject()
    val required = JSONArray()
    for (p in spec.params) {
      props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
      if (p.required) required.put(p.name)
    }
    val parameters =
      JSONObject().put("type", "object").put("properties", props).put("required", required)
    val function =
      JSONObject()
        .put("name", spec.name)
        .put("description", spec.description)
        .put("parameters", parameters)
    return JSONObject().put("type", "function").put("function", function).toString().replace("\\/", "/")
  }

  /** System prompt: [instructions] followed by the tool definitions. */
  fun systemPrompt(specs: List<AgentToolSpec>, instructions: String): String {
    val sb = StringBuilder()
    sb.append(instructions.trim())
    sb.append("\n\n# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
    sb.append("You are provided with function signatures within ")
    sb.append(TOOLS_OPEN).append(TOOLS_CLOSE).append(" XML tags:\n")
    sb.append(TOOLS_OPEN).append("\n")
    for (s in specs) sb.append(specJson(s)).append("\n")
    sb.append(TOOLS_CLOSE).append("\n\n")
    sb.append("For each function call, return a json object with function name and arguments within ")
    sb.append(CALL_OPEN).append(CALL_CLOSE).append(" XML tags:\n")
    sb.append(CALL_OPEN).append("\n")
    sb.append("{\"name\": <function-name>, \"arguments\": <args-json-object>}\n")
    sb.append(CALL_CLOSE)
    return sb.toString()
  }

  /** Full ChatML prompt, ending with the assistant tag (thinking disabled). */
  fun buildPrompt(system: String, messages: List<AgentMsg>): String {
    val sb = StringBuilder()
    sb.append(IM_START).append("system\n").append(system).append(IM_END).append("\n")
    for (m in messages) {
      sb.append(IM_START).append(m.role).append("\n").append(m.content).append(IM_END).append("\n")
    }
    sb.append(IM_START).append("assistant\n").append(THINK_OFF)
    return sb.toString()
  }

  /**
   * [ALTREX] The same conversation as a chat request: the engine formats it with the model's own
   * chat template (Gemma, Llama, Mistral, Qwen...) instead of always using ChatML.
   */
  fun buildChatRequest(system: String, messages: List<AgentMsg>): String {
    val list = ArrayList<Pair<String, String>>()
    if (system.isNotBlank()) list.add("system" to system)
    for (m in messages) list.add(m.role to m.content)
    return org.nehuatl.llamacpp.LlamaHelper.chatRequest(list)
  }

  /** Tool results, sent back as a user message. */
  fun toolResponseMessage(results: List<String>): AgentMsg {
    val sb = StringBuilder()
    for (r in results) {
      sb.append(RESP_OPEN).append("\n").append(r).append("\n").append(RESP_CLOSE).append("\n")
    }
    return AgentMsg("user", sb.toString().trimEnd())
  }

  fun parseToolCalls(text: String): List<AgentToolCall> {
    val calls = ArrayList<AgentToolCall>()
    var idx = 0
    while (true) {
      val start = text.indexOf(CALL_OPEN, idx)
      if (start < 0) break
      val bodyStart = start + CALL_OPEN.length
      val end = text.indexOf(CALL_CLOSE, bodyStart)
      val body = (if (end < 0) text.substring(bodyStart) else text.substring(bodyStart, end)).trim()
      idx = if (end < 0) text.length else end + CALL_CLOSE.length
      calls.add(parseOne(body) ?: AgentToolCall("", JSONObject(), body))
    }
    return calls
  }

  private fun parseOne(body: String): AgentToolCall? {
    return try {
      val obj = JSONObject(body)
      val name = obj.optString("name", "")
      if (name.isEmpty()) return null
      val rawArgs = obj.opt("arguments") ?: obj.opt("parameters")
      val args =
        when (rawArgs) {
          is JSONObject -> rawArgs
          is String ->
            try {
              JSONObject(rawArgs)
            } catch (e: Exception) {
              JSONObject()
            }
          else -> JSONObject()
        }
      AgentToolCall(name, args, body)
    } catch (e: Exception) {
      null
    }
  }

  /** The model's text without its tool_call blocks (also removes an unterminated block). */
  fun stripToolCalls(text: String): String {
    val re =
      Regex(
        Regex.escape(CALL_OPEN) + ".*?(?:" + Regex.escape(CALL_CLOSE) + "|\\z)",
        RegexOption.DOT_MATCHES_ALL,
      )
    return text.replace(re, "").trim()
  }

  internal val THINK_OPEN = "<" + "think>"
  internal val THINK_CLOSE = "<" + "/think>"
  internal val TOOL_CALL_OPEN get() = CALL_OPEN
  internal val TOOL_CALL_CLOSE get() = CALL_CLOSE
}

/**
 * [ALTREX] Splits a streamed answer as it arrives: visible text is shown right away, reasoning
 * between think tags goes to the "thinking" area, and tool_call blocks stay hidden. Tags may be cut
 * across chunks, so a tail that could be the start of a tag is held back until the next chunk.
 */
class StreamSplitter {
  private enum class Mode { TEXT, THINK, TOOL }

  private var mode = Mode.TEXT
  private val pending = StringBuilder()
  private var startedText = false

  data class Out(val text: String, val thinking: String)

  fun feed(chunk: String): Out {
    pending.append(chunk)
    return drain(final = false)
  }

  fun finish(): Out = drain(final = true)

  private fun drain(final: Boolean): Out {
    val text = StringBuilder()
    val thinking = StringBuilder()
    while (pending.isNotEmpty()) {
      val buf = pending.toString()
      when (mode) {
        Mode.TEXT -> {
          val iThink = buf.indexOf(ToolCalling.THINK_OPEN)
          val iTool = buf.indexOf(ToolCalling.TOOL_CALL_OPEN)
          val iStray = buf.indexOf(ToolCalling.THINK_CLOSE)
          val next = listOf(iThink, iTool, iStray).filter { it >= 0 }.minOrNull()
          if (next != null) {
            text.append(buf, 0, next)
            if (next == iStray) {
              // A closing tag without its opening one (the template opened it): drop the tag.
              pending.delete(0, next + ToolCalling.THINK_CLOSE.length)
            } else if (next == iThink) {
              mode = Mode.THINK
              pending.delete(0, next + ToolCalling.THINK_OPEN.length)
            } else {
              mode = Mode.TOOL
              pending.delete(0, next + ToolCalling.TOOL_CALL_OPEN.length)
            }
            continue
          }
          val keep =
            if (final) 0
            else partialTagTail(buf, listOf(ToolCalling.THINK_OPEN, ToolCalling.TOOL_CALL_OPEN, ToolCalling.THINK_CLOSE))
          text.append(buf, 0, buf.length - keep)
          pending.delete(0, buf.length - keep)
          break
        }
        Mode.THINK -> {
          val end = buf.indexOf(ToolCalling.THINK_CLOSE)
          if (end >= 0) {
            thinking.append(buf, 0, end)
            pending.delete(0, end + ToolCalling.THINK_CLOSE.length)
            mode = Mode.TEXT
            continue
          }
          val keep = if (final) 0 else partialTagTail(buf, listOf(ToolCalling.THINK_CLOSE))
          thinking.append(buf, 0, buf.length - keep)
          pending.delete(0, buf.length - keep)
          break
        }
        Mode.TOOL -> {
          val end = buf.indexOf(ToolCalling.TOOL_CALL_CLOSE)
          if (end >= 0) {
            pending.delete(0, end + ToolCalling.TOOL_CALL_CLOSE.length)
            mode = Mode.TEXT
            continue
          }
          val keep = if (final) 0 else partialTagTail(buf, listOf(ToolCalling.TOOL_CALL_CLOSE))
          pending.delete(0, buf.length - keep)
          break
        }
      }
    }
    // No leading blank lines before the first visible word.
    var visible = text.toString()
    if (!startedText) {
      visible = visible.trimStart()
      if (visible.isNotEmpty()) startedText = true
    }
    return Out(visible, thinking.toString())
  }

  /** Length of the longest end of [buf] that is the beginning of one of [tags]. */
  private fun partialTagTail(buf: String, tags: List<String>): Int {
    var best = 0
    for (tag in tags) {
      for (len in minOf(tag.length - 1, buf.length) downTo 1) {
        if (buf.regionMatches(buf.length - len, tag, 0, len)) {
          if (len > best) best = len
          break
        }
      }
    }
    return best
  }
}
