package com.google.ai.edge.gallery.agent

import com.google.ai.edge.gallery.ui.llmsingleturn.AgentMsg
import com.google.ai.edge.gallery.ui.llmsingleturn.AgentToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ALTREX] Guards of the local agent loop, made for small models (2B to 4B, 4096-token context):
 * they tend to call the same tool again and again, and one big tool result can fill the whole
 * context.
 */

/** Counts identical tool calls in a row and says when to warn, refuse or stop. */
class RepeatCallGuard {
  enum class Verdict {
    RUN,
    /** Run it, but remind the model that it already has this result. */
    RUN_WITH_WARNING,
    /** Do not run it again: the result would be the same. */
    REFUSE,
    /** The model is stuck: end the loop. */
    STOP,
  }

  private var lastKey: String? = null
  private var count = 0

  fun check(call: AgentToolCall): Verdict {
    val key = key(call)
    count = if (key == lastKey) count + 1 else 1
    lastKey = key
    return when {
      count >= STOP_AT -> Verdict.STOP
      count >= REFUSE_AT -> Verdict.REFUSE
      count >= WARN_AT -> Verdict.RUN_WITH_WARNING
      else -> Verdict.RUN
    }
  }

  companion object {
    const val WARN_AT = 3
    const val REFUSE_AT = 5
    const val STOP_AT = 8

    const val WARNING =
      "Tu as déjà fait exactement cet appel plusieurs fois et le résultat ne change pas. " +
        "Utilise ce que tu as, ou réponds à l'utilisateur."
    const val REFUSAL =
      "Appel refusé : identique aux précédents, il donnerait le même résultat. " +
        "N'appelle plus cet outil avec ces paramètres ; réponds avec ce que tu sais."

    /** Tool name plus arguments with sorted keys, so {"a":1,"b":2} and {"b":2,"a":1} match. */
    fun key(call: AgentToolCall): String =
      call.name.lowercase().replace("_", "") + ":" + canonical(call.arguments)

    private fun canonical(value: Any?): String =
      when (value) {
        is JSONObject ->
          value.keys().asSequence().sorted().joinToString(",", "{", "}") { k ->
            JSONObject.quote(k) + ":" + canonical(value.opt(k))
          }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value.trim())
        null, JSONObject.NULL -> "null"
        else -> value.toString()
      }
  }
}

/**
 * Keeps tool results small: a long one is cut to its beginning and end, and the whole text is kept
 * aside so that the model can read the rest with the hidden `read_tool_output` tool.
 */
class ToolResultBudget(private val maxChars: Int) {
  private val stored = LinkedHashMap<String, String>()

  fun fit(result: String): String {
    if (result.length <= maxChars) return result
    val id = "r${stored.size + 1}"
    stored[id] = result
    while (stored.size > MAX_STORED) stored.remove(stored.keys.first())
    val head = result.take(maxChars * 2 / 3)
    val tail = result.takeLast(maxChars / 4)
    val omitted = result.length - head.length - tail.length
    return head +
      "\n[… $omitted caractères omis. Pour lire la suite : read_tool_output avec " +
      "{\"id\": \"$id\", \"offset\": ${head.length}} …]\n" +
      tail
  }

  /** A page of a stored result, for the `read_tool_output` call. */
  fun read(id: String, offset: Int): String {
    val full = stored[id] ?: return "Aucun résultat « $id » (il a pu être effacé)."
    if (offset >= full.length) return "Fin du résultat « $id »."
    val start = offset.coerceAtLeast(0)
    val end = minOf(full.length, start + maxChars)
    val more = if (end < full.length) "\n[Suite : read_tool_output avec {\"id\": \"$id\", \"offset\": $end}]" else ""
    return full.substring(start, end) + more
  }

  companion object {
    const val READ_TOOL = "read_tool_output"
    private const val MAX_STORED = 8

    /** About a quarter of the context, at 3 characters per token. */
    fun forContext(contextTokens: Int) = ToolResultBudget((contextTokens * 3 / 4).coerceIn(1_500, 12_000))
  }
}

/**
 * Fits the messages of one agent turn (the user's request, then the model's tool calls and their
 * results) in [budgetChars]: the request and the latest steps stay; older steps are dropped as
 * whole call/result pairs, and older results are shortened first.
 */
object AgentContextFitter {
  private const val OLD_RESULT_CHARS = 600

  /** [requestIndex]: position of the user's request; the tool steps come after it. */
  fun fit(messages: List<AgentMsg>, requestIndex: Int, budgetChars: Int): List<AgentMsg> {
    fun size(list: List<AgentMsg>) = list.sumOf { it.content.length + 16 }
    if (size(messages) <= budgetChars) return messages
    // Earlier conversation and the request stay as they are.
    val firstStep = (requestIndex + 1).coerceAtMost(messages.size)
    val head = messages.subList(0, firstStep)
    val steps = messages.subList(firstStep, messages.size).chunked(2)

    // 1. Shorten the results of every step but the last one.
    val shortened =
      steps.mapIndexed { i, step ->
        if (i == steps.lastIndex) step
        else step.map { m ->
          if (m.role != "assistant" && m.content.length > OLD_RESULT_CHARS) {
            m.copy(content = m.content.take(OLD_RESULT_CHARS) + "\n[… résultat raccourci …]")
          } else {
            m
          }
        }
      }
    var kept = shortened
    // 2. Drop the oldest steps, keeping at least the last one.
    while (kept.size > 1 && size(head) + size(kept.flatten()) > budgetChars) kept = kept.drop(1)
    val dropped = steps.size - kept.size
    // The note goes with the request, so that user and assistant turns keep alternating.
    val newHead =
      if (dropped > 0 && head.isNotEmpty()) {
        head.dropLast(1) +
          head.last().copy(
            content = head.last().content + "\n\n[$dropped étape(s) d'outils plus anciennes omises pour tenir dans la mémoire.]"
          )
      } else {
        head
      }
    return newHead + kept.flatten()
  }
}
