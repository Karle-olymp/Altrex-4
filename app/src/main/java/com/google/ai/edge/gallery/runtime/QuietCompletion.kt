package com.google.ai.edge.gallery.runtime

import com.google.ai.edge.gallery.data.ARGORA_MODE_KEY
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.usesTextProtocol
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nehuatl.llamacpp.LlamaHelper

/**
 * [ALTREX] State shared by everything that talks to a loaded model: the chat, and requests made
 * outside of it (notes on a long document, answers in the assistant panel).
 */
object SharedModelSession {
  private val _chatGenerating = MutableStateFlow(false)

  /** True while the chat is writing an answer. */
  val chatGenerating: StateFlow<Boolean> = _chatGenerating.asStateFlow()

  fun setChatGenerating(generating: Boolean) {
    _chatGenerating.value = generating
  }

  private val invalidated = Collections.synchronizedSet(mutableSetOf<String>())

  /**
   * A LiteRT-LM model keeps the chat history inside its conversation: a request made outside the
   * chat replaces that conversation. The chat then rebuilds it from its messages before the next one.
   */
  fun markConversationReplaced(modelName: String) {
    invalidated.add(modelName)
  }

  fun consumeConversationReplaced(modelName: String): Boolean = invalidated.remove(modelName)
}

/** One request to a loaded model, outside of the chat history. */
object QuietCompletion {
  /**
   * Sends [messages] (role "system", "user" or "assistant"; the last one is the user's) and returns
   * the whole answer. [onPartial] receives the answer so far while it is written.
   */
  suspend fun run(
    model: Model,
    messages: List<Pair<String, String>>,
    argoraMode: String? = null,
    onPartial: (String) -> Unit = {},
  ): Result<String> {
    if (model.instance == null) return Result.failure(IllegalStateException("Le modèle n'est pas chargé."))
    val helper = model.runtimeHelper
    val input: String
    if (model.runtimeType.usesTextProtocol) {
      // llama.cpp (and a remote Space) is stateless between requests: the whole exchange is sent each time.
      input = LlamaHelper.chatRequest(messages)
    } else {
      val system = messages.firstOrNull { it.first == "system" }?.second
      val history =
        messages.dropLast(1).filter { it.first != "system" }.map { (role, content) ->
          if (role == "user") Message.user(content) else Message.model(content)
        }
      helper.resetConversation(
        model = model,
        systemInstruction = system?.let { Contents.of(it) },
        initialMessages = history,
      )
      SharedModelSession.markConversationReplaced(model.name)
      input = messages.last().second
    }

    val result = CompletableDeferred<Result<String>>()
    val answer = StringBuilder()
    helper.runInference(
      model = model,
      input = input,
      resultListener = { partial, done, _ ->
        if (partial.isNotEmpty()) {
          answer.append(partial)
          onPartial(answer.toString())
        }
        if (done) result.complete(Result.success(answer.toString()))
      },
      cleanUpListener = { result.complete(Result.failure(IllegalStateException("Le modèle a été déchargé."))) },
      onError = { message -> result.complete(Result.failure(IllegalStateException(message))) },
      extraContext = argoraMode?.let { mapOf(ARGORA_MODE_KEY to it) },
    )
    return result.await()
  }

  fun stop(model: Model) {
    model.runtimeHelper.stopResponse(model)
  }
}
