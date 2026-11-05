package com.google.ai.edge.gallery.forge.engine

import com.google.ai.edge.gallery.agent.AgentMsg

/**
 * [ALTREX] What the Forge engine needs from the outside world, as small interfaces: the real ones
 * talk to the loaded model, the Linux and the screen; the tests give fakes. Nothing here is
 * global, so several engines can run side by side (sub-agents, step 4 of docs/forge/FORGE.md).
 */

/** The model, whatever runs it (llama.cpp, LiteRT-LM, a Space, a cloud API). */
interface ModelPort {
  val name: String

  /** Characters of history that fit in the model's context, system prompt not included. */
  val historyBudgetChars: Int

  /** A model on the phone: smaller limits by default. */
  val local: Boolean

  /** One answer to [messages], streamed to [onChunk]; returns the whole raw text. */
  suspend fun generate(system: String, messages: List<AgentMsg>, onChunk: (String) -> Unit): String

  /** Stops the answer being written (the Stop button). */
  fun stop()
}

/** The model's shell for this project (see [com.google.ai.edge.gallery.tools.AgentShell]). */
interface ShellPort {
  data class Result(val exitCode: Int, val output: String, val cwd: String?, val timedOut: Boolean)

  /** Null when the Linux is ready (installed if needed), else why it is not. */
  suspend fun ensureReady(onProgress: (String) -> Unit): String?

  suspend fun run(command: String, timeoutMs: Long, onOutput: (String) -> Unit): Result

  /** A job id, or a message starting with "error:". */
  fun startJob(command: String): String

  fun checkJob(id: String, stop: Boolean, maxChars: Int): Map<String, String>
}

/** A question to the user about one action (see [PermissionPolicy]). */
data class ApprovalRequest(
  val callId: String,
  val tool: String,
  val title: String,
  /** The command, or the diff of the file change (lines of [com.google.ai.edge.gallery.forge.tools.DiffLines]). */
  val detail: String,
  val isDiff: Boolean = false,
  val risky: Boolean = false,
  val escalation: Boolean = false,
  val justification: String? = null,
  /** What « Toujours » covers ("npm", "les fichiers"), or null when it is not offered. */
  val grantLabel: String? = null,
)

data class ApprovalAnswer(val choice: Choice, val reason: String = "") {
  enum class Choice(val journal: String) {
    OUI("oui"),
    TOUJOURS("toujours"),
    NON("non"),
    /** Refuse and end the turn. */
    ARRETER("arreter"),
    /** Nobody answered in time, or the screen was gone: a refusal. */
    SANS_REPONSE("sans_reponse"),
  }
}

data class PlanReview(val outcome: Outcome, val feedback: String = "", val nextMode: ForgeMode? = null) {
  enum class Outcome { VALIDE, A_MODIFIER, REFUSE }
}

/** The screen, as the engine sees it. Every call waits for the user; no answer is a refusal. */
interface ForgeInteractions {
  suspend fun approve(request: ApprovalRequest): ApprovalAnswer

  /** The user's answer, or null when there was none. */
  suspend fun ask(callId: String, question: String, options: List<String>): String?

  suspend fun reviewPlan(callId: String, markdown: String): PlanReview
}

/** Project context given to the model: instructions, environment, memory (ALTREX.md). */
interface ForgeContext {
  /** The system prompt without the tools block (the engine adds it). Stable during a session. */
  fun systemPrompt(): String

  /** Text to give the model when it first works in [rel] (an ALTREX.md of a sub-folder), or null. */
  fun onPathTouched(rel: String): String? = null
}

/**
 * Limits of one turn, combinable (docs/forge/FORGE.md §4): steps, tokens (estimated at 3
 * characters per token), duration; plus the Stop button.
 */
data class ForgeLimits(
  val maxSteps: Int,
  val maxTokens: Int,
  val maxDurationMs: Long,
  /** Longest tool result given to the model; longer command outputs go to a file. */
  val maxResultChars: Int,
  /** Lines Read gives when the model does not say. */
  val readLines: Int,
) {
  companion object {
    fun forModel(model: ModelPort): ForgeLimits =
      if (model.local) {
        val small = model.historyBudgetChars < 16_000
        ForgeLimits(
          maxSteps = 20,
          maxTokens = 200_000,
          maxDurationMs = 45 * 60_000L,
          maxResultChars = if (small) 2_500 else 6_000,
          readLines = if (small) 120 else 250,
        )
      } else {
        ForgeLimits(maxSteps = 40, maxTokens = 1_500_000, maxDurationMs = 30 * 60_000L, maxResultChars = 12_000, readLines = 400)
      }
  }
}
