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
import android.content.Context
import com.google.ai.edge.gallery.runtime.CloudApi
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
class RuntimeModelPort(private val model: Model, private val appContext: Context? = null) : ModelPort {
  /** The answer being written (completed by [stop]: Stop never waits for the runtime). */
  @Volatile private var current: CompletableDeferred<String>? = null
  @Volatile private var partial: StringBuilder? = null
  @Volatile private var abort: CloudApi.Abort? = null

  override val name: String
    get() = model.displayName.ifEmpty { model.name }

  override val local: Boolean
    get() = model.runtimeType != RuntimeType.REMOTE

  override val runtime: String
    get() =
      when {
        model.instance is CloudModelInstance -> "cloud"
        model.runtimeType == RuntimeType.LLAMA_CPP -> "llamacpp"
        model.runtimeType == RuntimeType.REMOTE -> "space"
        else -> "litert"
      }

  /** What the engine's conversation holds now (LiteRT-LM), for [ConversationReuse]. */
  @Volatile private var heldSystem: String? = null
  @Volatile private var held: List<AgentMsg>? = null
  @Volatile private var heldInstance: Any? = null

  /** Number of steps that reused the conversation instead of rebuilding it (status, tests). */
  @Volatile var reused = 0
    private set

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
    val cloud = model.instance as? CloudModelInstance
    if (cloud != null && appContext != null) return generateCloud(cloud, appContext, system, messages, onChunk)
    val helper = model.runtimeHelper
    val input: String
    if (model.runtimeType.usesTextProtocol) {
      input = LlamaHelper.chatRequest(listOf("system" to system) + messages.map { it.role to it.content })
    } else {
      if (heldInstance === model.instance && ConversationReuse.canContinue(heldSystem, held, system, messages)) {
        reused++
      } else {
        val history = messages.dropLast(1).map { if (it.role == "user") Message.user(it.content) else Message.model(it.content) }
        helper.resetConversation(model = model, systemInstruction = Contents.of(system), initialMessages = history)
        SharedModelSession.markConversationReplaced(model.name)
      }
      held = null
      input = messages.lastOrNull()?.content.orEmpty()
    }
    val done = CompletableDeferred<String>()
    val answer = StringBuilder()
    current = done
    partial = answer
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
      val text = done.await()
      if (!model.runtimeType.usesTextProtocol && !stoppedEarly) {
        heldSystem = system
        heldInstance = model.instance
        held = messages + AgentMsg("assistant", ConversationReuse.normalize(text))
      }
      text
    } catch (e: CancellationException) {
      helper.stopResponse(model)
      throw e
    } finally {
      current = null
      stoppedEarly = false
    }
  }

  /** Stop cut the answer: the conversation no longer matches the history, it is rebuilt next time. */
  @Volatile private var stoppedEarly = false

  /**
   * [ALTREX] Cloud models, called directly ([CloudApi.chat]) rather than through the chat helper:
   * Forge needs the error itself (a 429 becomes a [RateLimitedException], retried by the engine)
   * and a request it can abort (Stop or cancellation close the connection at once).
   */
  private suspend fun generateCloud(
    cloud: CloudModelInstance,
    context: Context,
    system: String,
    messages: List<AgentMsg>,
    onChunk: (String) -> Unit,
  ): String {
    val key = CloudApi.getKey(context, cloud.provider) ?: throw IllegalStateException("Aucune clé ${cloud.provider.label} : ajoute-la dans Réglages → Modèles cloud.")
    val json = LlamaHelper.chatRequest(listOf("system" to system) + messages.map { it.role to it.content }).removePrefix(LlamaHelper.CHAT_REQUEST_PREFIX)
    val maxTokens = model.llmMaxToken.takeIf { it > 0 }?.coerceIn(256, 64_000) ?: 8192
    val done = CompletableDeferred<String>()
    val answer = StringBuilder()
    val a = CloudApi.Abort()
    current = done
    partial = answer
    abort = a
    Thread {
        try {
          val text =
            CloudApi.chat(
              provider = cloud.provider,
              modelId = cloud.modelId,
              key = key,
              messagesJson = json,
              thinking = false,
              maxTokens = maxTokens,
              isCancelled = { a.aborted },
              onText = { full ->
                synchronized(answer) {
                  if (!a.aborted && full.startsWith(answer) && full.length > answer.length) {
                    val piece = full.substring(answer.length)
                    answer.append(piece)
                    onChunk(piece)
                  }
                }
              },
              abort = a,
            )
          done.complete(text)
        } catch (e: CloudApi.RateLimited) {
          done.completeExceptionally(RateLimitedException(e.message ?: "429", e.retryAfterMs, cloud.provider.label))
        } catch (e: Throwable) {
          done.completeExceptionally(
            if (e is CloudApi.CloudException) e
            else IllegalStateException("${cloud.provider.label} : réponse inattendue (${e.javaClass.simpleName}" + (e.message?.let { ": " + it.take(120) } ?: "") + ").")
          )
        }
      }
      .apply {
        isDaemon = true
        name = "forge-cloud"
      }
      .start()
    return try {
      done.await()
    } catch (e: CancellationException) {
      a.abort()
      throw e
    } finally {
      current = null
      abort = null
    }
  }

  override fun stop() {
    stoppedEarly = true
    held = null
    abort?.abort()
    // The engine goes on at once with what was written; the runtime stops in its own time.
    current?.let { d -> d.complete(partial?.let { synchronized(it) { it.toString() } }.orEmpty()) }
    runCatching { model.runtimeHelper.stopResponse(model) }
  }
}
