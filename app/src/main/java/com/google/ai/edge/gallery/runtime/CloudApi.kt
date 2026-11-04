package com.google.ai.edge.gallery.runtime

import android.content.Context
import com.google.ai.edge.gallery.security.SecretCipher
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ALTREX] Cloud models through their providers' APIs, with the user's own key: Claude (Anthropic's
 * native Messages API) and the providers that speak the OpenAI chat format (OpenAI, Gemini, Grok,
 * Mistral, DeepSeek, OpenRouter, Groq).
 *
 * A cloud model is a remote model (RuntimeType.REMOTE) named [PREFIX] + provider + ":" + model id.
 * Like the Hugging Face Spaces, it gets the whole conversation each turn as a chat request (system
 * prompt with the tools, history, tool results) and streams its answer back, so chat, Argora modes
 * and tools work the same way. The tools are passed natively (function calling, [CloudTools]); the
 * agent loop still sees the text protocol. The key only ever goes to its provider's fixed host.
 */
object CloudApi {
  const val PREFIX = "cloud:"

  enum class Protocol { ANTHROPIC, OPENAI }

  enum class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val protocol: Protocol,
    /** Where the user creates a key. */
    val keyPage: String,
    /** A few model ids to start with (the full list is fetched with the key). */
    val suggestions: List<String>,
  ) {
    ANTHROPIC(
      "anthropic", "Claude (Anthropic)", "https://api.anthropic.com/v1", Protocol.ANTHROPIC,
      "https://platform.claude.com/settings/keys",
      listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
    ),
    OPENAI(
      "openai", "GPT (OpenAI)", "https://api.openai.com/v1", Protocol.OPENAI,
      "https://platform.openai.com/api-keys", emptyList(),
    ),
    GEMINI(
      "gemini", "Gemini (Google)", "https://generativelanguage.googleapis.com/v1beta/openai", Protocol.OPENAI,
      "https://aistudio.google.com/apikey", emptyList(),
    ),
    XAI("xai", "Grok (xAI)", "https://api.x.ai/v1", Protocol.OPENAI, "https://console.x.ai", emptyList()),
    MISTRAL(
      "mistral", "Mistral", "https://api.mistral.ai/v1", Protocol.OPENAI,
      "https://console.mistral.ai/api-keys", emptyList(),
    ),
    DEEPSEEK(
      "deepseek", "DeepSeek", "https://api.deepseek.com", Protocol.OPENAI,
      "https://platform.deepseek.com/api_keys", emptyList(),
    ),
    OPENROUTER(
      "openrouter", "OpenRouter (des centaines de modèles)", "https://openrouter.ai/api/v1", Protocol.OPENAI,
      "https://openrouter.ai/settings/keys", emptyList(),
    ),
    GROQ(
      "groq", "Groq", "https://api.groq.com/openai/v1", Protocol.OPENAI,
      "https://console.groq.com/keys", emptyList(),
    );

    companion object {
      fun byId(id: String): Provider? = entries.find { it.id == id }
    }
  }

  // ---- Names. A "/" in a model id (OpenRouter: "vendor/model") would break the chat's navigation
  // route, so it is stored as "~".

  fun isCloud(modelName: String): Boolean = modelName.startsWith(PREFIX)

  fun modelName(provider: Provider, modelId: String): String =
    PREFIX + provider.id + ":" + modelId.trim().replace('/', '~')

  /** Provider and model id of a cloud model name, or null. */
  fun parse(modelName: String): Pair<Provider, String>? {
    if (!isCloud(modelName)) return null
    val rest = modelName.removePrefix(PREFIX)
    val provider = Provider.byId(rest.substringBefore(':')) ?: return null
    val id = rest.substringAfter(':', "").replace('~', '/')
    return if (id.isBlank()) null else provider to id
  }

  // ---- Keys, encrypted on the phone (Keystore).

  private const val PREFS = "altrex_cloud_keys"

  fun getKey(context: Context, provider: Provider): String? =
    try {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(provider.id, null)
        ?.let { SecretCipher.decrypt(it) }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
      null
    }

  /** Saves the key (empty: deletes it). */
  fun saveKey(context: Context, provider: Provider, key: String): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
    if (key.isBlank()) prefs.remove(provider.id) else prefs.putString(provider.id, SecretCipher.encrypt(key.trim()))
    return prefs.commit()
  }

  open class CloudException(message: String) : IOException(message)

  // ---- Model list.

  /** Model ids the key can use. Network: call off the main thread. */
  fun listModels(provider: Provider, key: String): List<String> {
    val conn = open("${provider.baseUrl}/models" + if (provider.protocol == Protocol.ANTHROPIC) "?limit=100" else "", provider, key, 30_000)
    try {
      val code = conn.responseCode
      val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw CloudException(httpError(provider, code, text))
      val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
      return (0 until data.length())
        .mapNotNull { data.optJSONObject(it)?.optString("id")?.removePrefix("models/")?.takeIf { id -> id.isNotBlank() } }
        .distinct()
        .sorted()
    } finally {
      conn.disconnect()
    }
  }

  // ---- Chat.

  /**
   * Sends the conversation ([messagesJson]: [{"role", "content"}], system first) and streams the
   * answer: [onText] gets the whole text so far, reasoning between think tags. Returns the final
   * text. Blocking: call it on a background thread.
   *
   * When the system prompt carries the agent's tools (text protocol, ToolCalling), they go to the
   * provider as native tools and the native calls come back as `<tool_call>` text, appended at the
   * end ([CloudTools]). A provider or model that refuses native tools gets the original text
   * request once, and keeps it for the rest of the session.
   */
  fun chat(
    provider: Provider,
    modelId: String,
    key: String,
    messagesJson: String,
    thinking: Boolean,
    maxTokens: Int,
    isCancelled: () -> Boolean,
    onText: (String) -> Unit,
  ): String {
    val messages = JSONArray(messagesJson)
    val native = CloudTools.prepare(messages)?.takeIf { CloudTools.useNative(provider, modelId) }
    if (native != null) {
      try {
        return send(provider, modelId, key, messages, native, thinking, maxTokens, isCancelled, onText)
      } catch (e: CloudTools.NativeToolsRejected) {
        if (e.toolRelated) CloudTools.rememberTextOnly(provider, modelId)
      }
      val answer = send(provider, modelId, key, messages, null, thinking, maxTokens, isCancelled, onText)
      // The text request worked where the native one did not: no native retry every turn.
      CloudTools.rememberTextOnly(provider, modelId)
      return answer
    }
    return send(provider, modelId, key, messages, null, thinking, maxTokens, isCancelled, onText)
  }

  private fun send(
    provider: Provider,
    modelId: String,
    key: String,
    messages: JSONArray,
    native: CloudTools.Prepared?,
    thinking: Boolean,
    maxTokens: Int,
    isCancelled: () -> Boolean,
    onText: (String) -> Unit,
  ): String =
    when (provider.protocol) {
      Protocol.ANTHROPIC ->
        anthropicChat(provider, modelId, key, messages, native, thinking, maxTokens, isCancelled, onText, fallbacks = true)
      Protocol.OPENAI -> openAiChat(provider, modelId, key, messages, native, maxTokens, isCancelled, onText)
    }

  /** Builds the text shown in the chat from reasoning and answer, reasoning between think tags. */
  private class TextBuilder {
    private val sb = StringBuilder()
    private var inThink = false

    fun think(t: String) {
      if (t.isEmpty()) return
      if (!inThink) {
        sb.append("<think>\n")
        inThink = true
      }
      sb.append(t)
    }

    fun answer(t: String) {
      if (t.isEmpty()) return
      closeThink()
      sb.append(t)
    }

    fun closeThink() {
      if (inThink) {
        sb.append("\n</think>\n\n")
        inThink = false
      }
    }

    override fun toString(): String = sb.toString()
  }

  /** Claude models that take the server-side refusal fallback (Claude API). */
  private val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

  /**
   * Anthropic Messages API: system apart, alternating user/assistant turns starting with user.
   * [native]: the tools go in `tools` and the history's tool steps become tool_use / tool_result
   * blocks ([CloudTools.anthropicMessages]); no `tool_choice` (auto is the default, forced choices
   * are a 400 on current models) and no sampling parameters (a 400 on API version 2026-10-21).
   *
   * Thinking: the loop never keeps thinking blocks (it strips the reasoning), so a replayed history
   * has text and tool_use blocks only. That is the documented recovery shape for preserved thinking
   * ("strip every thinking block from the history, each turn's text and tool_use blocks stay"):
   * the binding checks apply to replayed thinking blocks, and with none there is nothing to fail.
   * So the `thinking` parameter is kept as asked, with or without tool history (on Opus 5.5 /
   * Sonnet 5.5 thinking is always on anyway and cannot be disabled). If a provider still rejects
   * it, the native request falls back to the text one ([chat]).
   */
  internal fun anthropicBody(
    modelId: String,
    messages: JSONArray,
    thinking: Boolean,
    maxTokens: Int,
    fallbacks: Boolean,
    native: CloudTools.Prepared? = null,
  ): JSONObject {
    val system: String
    val turns: JSONArray
    if (native != null) {
      val (s, t) = CloudTools.anthropicMessages(native)
      system = s
      turns = t
    } else {
      val sys = StringBuilder()
      turns = JSONArray()
      var lastRole = ""
      for (i in 0 until messages.length()) {
        val m = messages.optJSONObject(i) ?: continue
        val role = m.optString("role")
        val content = m.optString("content")
        if (role == "system") {
          if (sys.isNotEmpty()) sys.append("\n\n")
          sys.append(content)
          continue
        }
        val r = if (role == "assistant") "assistant" else "user"
        if (content.isBlank()) continue
        if (turns.length() == 0 && r == "assistant") continue
        if (r == lastRole) {
          // Two turns in a row from the same side (tool results after a user message): merged.
          val prev = turns.getJSONObject(turns.length() - 1)
          prev.put("content", prev.getString("content") + "\n\n" + content)
        } else {
          turns.put(JSONObject().put("role", r).put("content", content))
          lastRole = r
        }
      }
      system = sys.toString()
    }
    val body =
      JSONObject()
        .put("model", modelId)
        .put("max_tokens", maxTokens)
        .put("stream", true)
        .put("messages", turns)
    if (system.isNotEmpty()) body.put("system", system)
    if (native != null) body.put("tools", CloudTools.anthropicTools(native.tools))
    // Reasoning shown only when asked; otherwise the model's default (adaptive on current models).
    if (thinking) body.put("thinking", JSONObject().put("type", "adaptive").put("display", "summarized"))
    if (fallbacks && modelId in FALLBACK_MODELS) body.put("fallbacks", "default")
    return body
  }

  private fun anthropicChat(
    provider: Provider,
    modelId: String,
    key: String,
    messages: JSONArray,
    native: CloudTools.Prepared?,
    thinking: Boolean,
    maxTokens: Int,
    isCancelled: () -> Boolean,
    onText: (String) -> Unit,
    fallbacks: Boolean,
  ): String {
    val body = anthropicBody(modelId, messages, thinking, maxTokens, fallbacks, native)
    val conn = open("${provider.baseUrl}/messages", provider, key, 600_000)
    if (body.has("fallbacks")) conn.setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
    try {
      val code = post(conn, body)
      if (code !in 200..299) {
        val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        // An account without the fallback feature: same request without it.
        if (code == 400 && body.has("fallbacks") && text.contains("fallback", ignoreCase = true)) {
          conn.disconnect()
          return anthropicChat(provider, modelId, key, messages, native, thinking, maxTokens, isCancelled, onText, fallbacks = false)
        }
        if (native != null && CloudTools.shouldRetryWithText(code, text)) {
          throw CloudTools.NativeToolsRejected(httpError(provider, code, text), CloudTools.mentionsTools(text))
        }
        throw CloudException(httpError(provider, code, text))
      }
      val stream = AnthropicStream(provider, native != null, onText)
      readSse(conn, isCancelled, stream::onEvent)
      return stream.finish()
    } finally {
      conn.disconnect()
    }
  }

  /** Reads an Anthropic stream: text, reasoning and tool_use blocks. */
  internal class AnthropicStream(
    private val provider: Provider,
    private val nativeTools: Boolean,
    private val onText: (String) -> Unit,
  ) {
    private val out = TextBuilder()
    private val calls = CloudTools.AnthropicCalls()
    private var stop = ""
    private var shown = 0

    fun onEvent(event: String, data: String) {
      val o = runCatching { JSONObject(data) }.getOrNull() ?: return
      when (o.optString("type").ifEmpty { event }) {
        "content_block_start" -> {
          val b = o.optJSONObject("content_block") ?: return
          when (b.optString("type")) {
            "tool_use" -> calls.start(o.optInt("index"), b)
            "fallback" -> calls.fallbackBoundary()
          }
        }
        "content_block_delta" -> {
          val d = o.optJSONObject("delta") ?: return
          when (d.optString("type")) {
            "text_delta" -> out.answer(d.optString("text"))
            "thinking_delta" -> out.think(d.optString("thinking"))
            "input_json_delta" -> calls.delta(o.optInt("index"), d.optString("partial_json"))
          }
          emit()
        }
        "content_block_stop" -> calls.stop(o.optInt("index"))
        "message_delta" -> o.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let { stop = it }
        "error" -> {
          val message = (o.optJSONObject("error")?.optString("message") ?: data).take(300)
          if (nativeTools && shown == 0 && CloudTools.mentionsTools(message)) {
            throw CloudTools.NativeToolsRejected("Erreur de ${provider.label} : $message", true)
          }
          throw CloudException("Erreur de ${provider.label} : $message")
        }
      }
    }

    private fun emit() {
      val t = out.toString()
      if (t.length > shown) {
        shown = t.length
        onText(t)
      }
    }

    /** The final text; the tool calls (stop_reason "tool_use") come last, in the loop's format. */
    fun finish(): String {
      out.closeThink()
      if (stop == "refusal") out.answer("\n\n(${provider.label} a refusé de répondre à cette demande.)")
      if (stop == "max_tokens") out.answer("\n\n(Réponse coupée : limite de longueur atteinte.)")
      // A refused turn's tool calls must not run (a refusal can cut one off mid-input).
      return CloudTools.withCalls(out.toString(), if (stop == "refusal") emptyList() else calls.calls())
    }
  }

  /**
   * OpenAI chat format. [native]: the tools go in `tools` (`tool_choice` left out: "auto" is the
   * default) and the history's tool steps become `tool_calls` / `tool` messages.
   */
  internal fun openAiBody(
    provider: Provider,
    modelId: String,
    messages: JSONArray,
    maxTokens: Int,
    native: CloudTools.Prepared? = null,
  ): JSONObject {
    val clean =
      if (native != null) {
        CloudTools.openAiMessages(native, gemini = provider == Provider.GEMINI)
      } else {
        JSONArray().apply {
          for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role").takeIf { it == "system" || it == "assistant" } ?: "user"
            put(JSONObject().put("role", role).put("content", m.optString("content")))
          }
        }
      }
    val body =
      JSONObject()
        .put("model", modelId)
        .put("messages", clean)
        .put("stream", true)
        // OpenAI's newer models only take max_completion_tokens; the others still use max_tokens.
        .put(if (provider == Provider.OPENAI) "max_completion_tokens" else "max_tokens", maxTokens)
    if (native != null) body.put("tools", CloudTools.openAiTools(native.tools))
    return body
  }

  private fun openAiChat(
    provider: Provider,
    modelId: String,
    key: String,
    messages: JSONArray,
    native: CloudTools.Prepared?,
    maxTokens: Int,
    isCancelled: () -> Boolean,
    onText: (String) -> Unit,
  ): String {
    val conn = open("${provider.baseUrl}/chat/completions", provider, key, 600_000)
    try {
      val code = post(conn, openAiBody(provider, modelId, messages, maxTokens, native))
      if (code !in 200..299) {
        val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (native != null && CloudTools.shouldRetryWithText(code, text)) {
          throw CloudTools.NativeToolsRejected(httpError(provider, code, text), CloudTools.mentionsTools(text))
        }
        throw CloudException(httpError(provider, code, text))
      }
      val stream = OpenAiStream(provider, native != null, onText)
      readSse(conn, isCancelled) { _, data -> stream.onData(data) }
      return stream.finish()
    } finally {
      conn.disconnect()
    }
  }

  /** Reads an OpenAI-style stream: answer, reasoning and `tool_calls` fragments. */
  internal class OpenAiStream(
    private val provider: Provider,
    private val nativeTools: Boolean,
    private val onText: (String) -> Unit,
  ) {
    private val out = TextBuilder()
    private val calls = CloudTools.OpenAiCalls()
    private var finish = ""
    private var shown = 0

    fun onData(data: String) {
      if (data == "[DONE]") return
      val o = runCatching { JSONObject(data) }.getOrNull() ?: return
      o.optJSONObject("error")?.let {
        val message = it.optString("message").take(300)
        // OpenRouter can refuse tools inside a 200 stream, before any text.
        if (nativeTools && shown == 0 && CloudTools.mentionsTools(message)) {
          throw CloudTools.NativeToolsRejected("Erreur de ${provider.label} : $message", true)
        }
        throw CloudException("Erreur de ${provider.label} : $message")
      }
      val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return
      choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
      val d = choice.optJSONObject("delta") ?: return
      // Reasoning: DeepSeek says reasoning_content, OpenRouter and Groq say reasoning.
      out.think(d.optString("reasoning_content").takeIf { it != "null" }.orEmpty())
      out.think(d.optString("reasoning").takeIf { it != "null" }.orEmpty())
      out.answer(d.optString("content").takeIf { it != "null" }.orEmpty())
      d.optJSONArray("tool_calls")?.let { calls.add(it) }
      val t = out.toString()
      if (t.length > shown) {
        shown = t.length
        onText(t)
      }
    }

    /** The final text; the tool calls (finish_reason "tool_calls") come last, in the loop's format. */
    fun finish(): String {
      out.closeThink()
      if (finish == "length") out.answer("\n\n(Réponse coupée : limite de longueur atteinte.)")
      if (finish == "content_filter") out.answer("\n\n(${provider.label} a filtré cette réponse.)")
      return CloudTools.withCalls(out.toString(), if (finish == "content_filter") emptyList() else calls.calls())
    }
  }

  // ---- HTTP.

  private fun open(url: String, provider: Provider, key: String, readTimeoutMs: Int): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 20_000
    conn.readTimeout = readTimeoutMs
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("Accept", "application/json, text/event-stream")
    when (provider.protocol) {
      Protocol.ANTHROPIC -> {
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2026-10-21")
      }
      Protocol.OPENAI -> conn.setRequestProperty("Authorization", "Bearer $key")
    }
    if (provider == Provider.OPENROUTER) conn.setRequestProperty("X-Title", "Altrex")
    return conn
  }

  private fun post(conn: HttpURLConnection, body: JSONObject): Int {
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    conn.outputStream.use { it.write(body.toString().toByteArray()) }
    return conn.responseCode
  }

  /** Server-sent events: calls [onEvent] with (event name, data) for each data line. */
  private fun readSse(conn: HttpURLConnection, isCancelled: () -> Boolean, onEvent: (String, String) -> Unit) {
    conn.inputStream.bufferedReader().use { readSseLines(it, isCancelled, onEvent) }
  }

  internal fun readSseLines(reader: BufferedReader, isCancelled: () -> Boolean, onEvent: (String, String) -> Unit) {
    var event = ""
    while (true) {
      if (isCancelled()) throw CloudException("Annulé.")
      val line = reader.readLine() ?: break
      when {
        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
        line.startsWith("data:") -> onEvent(event, line.removePrefix("data:").trim())
        line.isEmpty() -> event = ""
      }
    }
  }

  /** A readable error: the provider's own message, with a hint for the usual cases. */
  internal fun httpError(provider: Provider, code: Int, body: String): String {
    val o = runCatching { JSONObject(body) }.getOrNull()
    val message =
      (o?.optJSONObject("error")?.optString("message") ?: o?.optString("message") ?: o?.optString("error"))
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?.replace(Regex("\\s+"), " ")
        ?.take(300)
    val hint =
      when (code) {
        401, 403 -> "Clé refusée : vérifie-la dans Réglages → Modèles cloud."
        404 -> "Modèle introuvable chez ${provider.label} : vérifie son identifiant."
        429 -> "Limite atteinte (quota ou crédit épuisé) : réessaie plus tard ou vérifie ton compte."
        in 500..599 -> "${provider.label} est indisponible pour le moment."
        else -> null
      }
    return listOfNotNull("${provider.label} a répondu $code.", hint, message).joinToString(" ")
  }
}
