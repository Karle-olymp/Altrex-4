package com.google.ai.edge.gallery.forge.engine

import com.google.ai.edge.gallery.agent.AgentMsg
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.usesTextProtocol
import com.google.ai.edge.gallery.runtime.CloudModelInstance
import com.google.ai.edge.gallery.runtime.LlamaCppModelInstance
import com.google.ai.edge.gallery.runtime.SharedModelSession
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.nehuatl.llamacpp.LlamaHelper

/**
 * [ALTREX] [ModelPort] over Altrex's loaded models, with the same requests as Discuter's agent
 * loop:
 * - llama.cpp, Hugging Face Spaces and cloud APIs are stateless: the whole conversation goes as a
 *   chat request each step (the model's own chat template; cloud models get native tool calls
 *   through CloudTools and answer with the same `<tool_call>` text);
 * - LiteRT-LM (and AICore) keep a conversation inside the engine: it is rebuilt from Forge's
 *   history (system prompt with the tools block, earlier messages), then the last message is sent.
 *   Their native automatic tool calling is not used, so the tool format is the same everywhere.
 */
class RuntimeModelPort(private val model: Model) : ModelPort {
  override val name: String
    get() = model.displayName.ifEmpty { model.name }

  override val local: Boolean
    get() = model.runtimeType != RuntimeType.REMOTE

  override val historyBudgetChars: Int
    get() {
      if (model.instance is CloudModelInstance) return 120_000
      val tokens =
        (model.instance as? LlamaCppModelInstance)?.contextLength
          ?: model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
      // About 3 characters per token; a third of the context stays free for the answer.
      return tokens * 2
    }

  override suspend fun generate(system: String, messages: List<AgentMsg>, onChunk: (String) -> Unit): String {
    if (model.instance == null) throw IllegalStateException("Le modèle n'est pas chargé. Recharge-le depuis le sélecteur de modèle.")
    val helper = model.runtimeHelper
    val input: String
    if (model.runtimeType.usesTextProtocol) {
      input = LlamaHelper.chatRequest(listOf("system" to system) + messages.map { it.role to it.content })
    } else {
      val history = messages.dropLast(1).map { if (it.role == "user") Message.user(it.content) else Message.model(it.content) }
      helper.resetConversation(model = model, systemInstruction = Contents.of(system), initialMessages = history)
      SharedModelSession.markConversationReplaced(model.name)
      input = messages.lastOrNull()?.content.orEmpty()
    }
    val done = CompletableDeferred<String>()
    val answer = StringBuilder()
    helper.runInference(
      model = model,
      input = input,
      resultListener = { partial, finished, _ ->
        if (partial.isNotEmpty() && !done.isCompleted) {
          answer.append(partial)
          onChunk(partial)
        }
        if (finished && !done.isCompleted) done.complete(answer.toString())
      },
      cleanUpListener = { if (!done.isCompleted) done.complete(answer.toString()) },
      onError = { message -> if (!done.isCompleted) done.completeExceptionally(IllegalStateException(message)) },
    )
    return try {
      done.await()
    } catch (e: CancellationException) {
      helper.stopResponse(model)
      throw e
    }
  }

  override fun stop() {
    model.runtimeHelper.stopResponse(model)
  }
}
