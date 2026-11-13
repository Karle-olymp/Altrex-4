package com.google.ai.edge.gallery.forge.engine

import com.google.ai.edge.gallery.agent.AgentMsg
import com.google.ai.edge.gallery.agent.ToolCalling

/**
 * [ALTREX] LiteRT-LM keeps a conversation inside the engine (with its cache). Rebuilding it at
 * every step of a turn made Gemma very slow on the phone (≈ 2 000 tokens of prompt re-read each
 * time). When the new request only adds the model's last answer and one new message to what the
 * conversation already holds, the conversation is kept and only the new message is sent.
 */
object ConversationReuse {
  /** The answer as the engine keeps it in its history (reasoning removed, trimmed). */
  fun normalize(answer: String): String = ToolCalling.stripThinking(answer).trim().ifBlank { "…" }

  /**
   * True when [messages] = what the conversation holds ([held], its last answer included) + one
   * new user message, with the same [system] prompt.
   */
  fun canContinue(heldSystem: String?, held: List<AgentMsg>?, system: String, messages: List<AgentMsg>): Boolean {
    if (heldSystem == null || held == null || heldSystem != system) return false
    if (messages.size != held.size + 1 || messages.lastOrNull()?.role != "user") return false
    for (i in held.indices) if (held[i].role != messages[i].role || held[i].content != messages[i].content) return false
    return true
  }
}
