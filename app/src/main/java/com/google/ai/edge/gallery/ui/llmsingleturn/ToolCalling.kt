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
}
