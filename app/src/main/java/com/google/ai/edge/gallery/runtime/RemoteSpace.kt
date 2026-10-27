package com.google.ai.edge.gallery.runtime

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.security.SecretCipher
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.nehuatl.llamacpp.LlamaHelper

private const val TAG = "AGRemoteSpace"

/**
 * [ALTREX] A model that runs in a Hugging Face Space instead of on the phone (for example Argora 4B
 * served by llama-server behind Gradio). Its name in the model list is [PREFIX] + the Space id.
 *
 * The Space must expose a Gradio endpoint [API_NAME] taking (messages as a JSON string, thinking on
 * or off, max tokens) and returning the answer text, reasoning between think tags. Altrex sends the
 * whole conversation each turn (system prompt with the tools, history, tool results), exactly as it
 * does for a local GGUF model, so chat, Argora modes and tools work the same way.
 */
object RemoteSpace {
  const val PREFIX = "space:"
  const val API_NAME = "generate"

  /** owner/name, as on huggingface.co/spaces/owner/name. */
  private val SPACE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}/[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$")

  fun isRemote(modelName: String): Boolean = modelName.startsWith(PREFIX)

  /**
   * Model name of a Space: [PREFIX] + owner:name. Never a "/": the model name is a segment of the
   * chat's navigation route (route_model/{taskId}/{modelName}), and a "/" in it matched no screen,
   * which crashed the app, also at every launch since the last chat is reopened.
   */
  fun modelName(spaceId: String): String = PREFIX + spaceId.replace('/', ':')

  /** Space id (owner/name) of a remote model name, old "space:owner/name" form included. */
  fun spaceId(modelName: String): String = modelName.removePrefix(PREFIX).replaceFirst(':', '/')

  /**
   * Normalizes what the user typed: "owner/name", or a huggingface.co/spaces/... or *.hf.space
   * link. Returns null when it is not a Space id.
   */
  fun parseSpaceId(input: String): String? {
    var id = input.trim().removeSuffix("/")
    id = id.substringAfter("huggingface.co/spaces/", id)
    id = id.substringBefore("?").substringBefore("#")
    if (id.contains(".hf.space")) return null // the subdomain loses the owner/name split
    id = id.split("/").take(2).joinToString("/")
    return id.takeIf { SPACE_ID.matches(it) }
  }

  /** https://owner-name.hf.space (Hugging Face's rule: lower case, "/", "_" and "." become "-"). */
  fun baseUrl(spaceId: String): String =
    "https://" + spaceId.lowercase().replace('/', '-').replace('_', '-').replace('.', '-') + ".hf.space"

  // ---- Token (needed for a private Space), encrypted with the Keystore like the other secrets.

  private const val PREFS = "altrex_remote_space"
  private const val KEY_TOKEN = "hf_token"

  fun getToken(context: Context): String? =
    try {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)
        ?.let { SecretCipher.decrypt(it) }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
      null
    }

  fun saveToken(context: Context, token: String): Boolean =
    try {
      val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      if (token.isBlank()) edit.remove(KEY_TOKEN) else edit.putString(KEY_TOKEN, SecretCipher.encrypt(token.trim()))
      edit.commit()
    } catch (e: Exception) {
      false
    }

  // ---- Gradio API (POST /gradio_api/call/<api>, then the result as server-sent events).

  class SpaceException(message: String) : IOException(message)

  /** Calls [API_NAME] and returns the answer. Blocking: call it on a background thread. */
  fun generate(
    spaceId: String,
    token: String?,
    messagesJson: String,
    thinking: Boolean,
    maxTokens: Int,
    isCancelled: () -> Boolean,
  ): String {
    val base = baseUrl(spaceId)
    val body = JSONObject().put("data", JSONArray().put(messagesJson).put(thinking).put(maxTokens))
    val eventId = postWithRetry("$base/gradio_api/call/$API_NAME", token, body.toString(), isCancelled)
    return readResult("$base/gradio_api/call/$API_NAME/$eventId", token, isCancelled)
  }

  /** A sleeping Space answers 503 while it starts: retry for up to about 3 minutes. */
  private fun postWithRetry(url: String, token: String?, body: String, isCancelled: () -> Boolean): String {
    var waitMs = 2_000L
    val deadline = System.currentTimeMillis() + 180_000L
    while (true) {
      if (isCancelled()) throw SpaceException("Annulé.")
      val conn = open(url, token, readTimeoutMs = 60_000)
      try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        when {
          code == 200 -> {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text).optString("event_id").takeIf { it.isNotEmpty() }
              ?: throw SpaceException("Réponse inattendue du Space (pas d'event_id).")
          }
          code == 401 || code == 403 ->
            throw SpaceException(
              "Accès refusé au Space ($code). S'il est privé, ajoute ton jeton Hugging Face dans " +
                "Réglages → Clés d'API."
            )
          // A private Space answers 404 to every request without a valid token: it hides itself.
          code == 404 && token.isNullOrBlank() ->
            throw SpaceException(
              "Space introuvable. S'il est privé, Hugging Face le cache sans jeton : ajoute un jeton " +
                "« Read » dans Réglages → Clés d'API → Hugging Face."
            )
          code == 404 ->
            throw SpaceException(
              "Space ou API « $API_NAME » introuvable. Vérifie que ton jeton Hugging Face donne accès " +
                "à ce Space (jeton « Read » du même compte) et que le Space expose l'API $API_NAME."
            )
          (code == 503 || code == 502 || code == 504) && System.currentTimeMillis() < deadline -> {
            Log.d(TAG, "Space starting ($code), retrying in $waitMs ms")
          }
          else -> throw SpaceException("Le Space a répondu $code.")
        }
      } finally {
        conn.disconnect()
      }
      Thread.sleep(waitMs)
      waitMs = (waitMs * 2).coerceAtMost(15_000L)
    }
  }

  private fun readResult(url: String, token: String?, isCancelled: () -> Boolean): String {
    val conn = open(url, token, readTimeoutMs = 330_000)
    try {
      val code = conn.responseCode
      if (code != 200) throw SpaceException("Le Space a répondu $code au résultat.")
      conn.inputStream.bufferedReader().use { reader ->
        var event = ""
        while (true) {
          if (isCancelled()) throw SpaceException("Annulé.")
          val line = reader.readLine() ?: break
          when {
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
              val data = line.removePrefix("data:").trim()
              when (event) {
                "complete" -> return parseOutput(data)
                "error" ->
                  throw SpaceException(
                    "Erreur du Space" + (data.takeIf { it.isNotEmpty() && it != "null" }?.let { " : $it" } ?: ".")
                  )
              }
            }
          }
        }
      }
      throw SpaceException("Le Space a fermé la connexion sans réponse.")
    } finally {
      conn.disconnect()
    }
  }

  /** The output of a one-output Gradio function: ["text"]. */
  internal fun parseOutput(data: String): String {
    val arr = JSONArray(data)
    if (arr.length() == 0 || arr.isNull(0)) throw SpaceException("Le Space n'a renvoyé aucun texte.")
    return arr.getString(0)
  }

  private fun open(url: String, token: String?, readTimeoutMs: Int): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 20_000
    conn.readTimeout = readTimeoutMs
    conn.instanceFollowRedirects = false
    // The token only ever goes to the Space's own *.hf.space host, built from its id.
    if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
    return conn
  }
}

/** [ALTREX] The loaded state of a remote model: nothing in RAM, only where to send requests. */
data class RemoteModelInstance(val spaceId: String, val token: String?)

/**
 * [ALTREX] [LlmModelHelper] for [RemoteSpace] models. Stateless like llama.cpp: the agent loop sends
 * the full conversation (a [LlamaHelper.chatRequest]) on every turn.
 */
object RemoteModelHelper : LlmModelHelper {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Bumped by Stop: a request compares it with the value it started with. */
  private val stopGen = AtomicInteger(0)

  private lateinit var appContext: Context

  override fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    appContext = context.applicationContext
    val spaceId = RemoteSpace.spaceId(model.name)
    if (RemoteSpace.parseSpaceId(spaceId) == null) {
      onDone("Identifiant de Space invalide : $spaceId")
      return
    }
    model.instance = RemoteModelInstance(spaceId = spaceId, token = RemoteSpace.getToken(appContext))
    onDone("")
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    // Nothing kept between requests.
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    stopGen.incrementAndGet()
    model.instance = null
    onDone()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? RemoteModelInstance
    if (instance == null) {
      onError("Le modèle distant n'est pas initialisé.")
      return
    }
    if (images.isNotEmpty() || audioClips.isNotEmpty()) {
      onError("Ce modèle distant ne lit ni les images ni l'audio.")
      return
    }
    val messagesJson =
      if (input.startsWith(LlamaHelper.CHAT_REQUEST_PREFIX)) {
        input.removePrefix(LlamaHelper.CHAT_REQUEST_PREFIX)
      } else {
        JSONArray()
          .put(JSONObject().put("role", "system").put("content", LlamaHelper.getDefaultSystemPrompt()))
          .put(JSONObject().put("role", "user").put("content", input))
          .toString()
      }
    val thinking = extraContext?.get("enable_thinking") == "true"
    val maxTokens = model.llmMaxToken.takeIf { it > 0 }?.coerceAtMost(2048) ?: 1200
    // The token may have been added after the model was loaded.
    val token = RemoteSpace.getToken(appContext) ?: instance.token
    val myGen = stopGen.get()
    scope.launch {
      try {
        val answer =
          RemoteSpace.generate(
            spaceId = instance.spaceId,
            token = token,
            messagesJson = messagesJson,
            thinking = thinking,
            maxTokens = maxTokens,
            isCancelled = { stopGen.get() != myGen },
          )
        if (stopGen.get() != myGen) {
          cleanUpListener()
          return@launch
        }
        resultListener(answer, false, null)
        resultListener("", true, null)
      } catch (e: Exception) {
        if (stopGen.get() != myGen) {
          cleanUpListener()
        } else {
          Log.w(TAG, "Remote request failed", e)
          onError(
            (e as? RemoteSpace.SpaceException)?.message
              ?: "Space injoignable (${e.javaClass.simpleName}). Vérifie ta connexion."
          )
        }
      }
    }
  }

  override fun stopResponse(model: Model) {
    stopGen.incrementAndGet()
  }
}
