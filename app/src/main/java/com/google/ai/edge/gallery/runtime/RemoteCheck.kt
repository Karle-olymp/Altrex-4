package com.google.ai.edge.gallery.runtime

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [ALTREX] Where a cloud model or a Space stands, as the model bar names it. */
enum class RemoteState(val label: String) {
  UNKNOWN("Non vérifié"),
  CHECKING("Connexion…"),
  WAKING("Réveil en cours…"),
  READY("Prêt"),
  OFFLINE("Hors ligne"),
  NO_KEY("Clé manquante"),
  KEY_REFUSED("Clé refusée"),
  ERROR("Indisponible"),
}

data class RemoteStatus(val state: RemoteState, val detail: String = "", val checkedAtMs: Long = 0) {
  val busy: Boolean
    get() = state == RemoteState.CHECKING || state == RemoteState.WAKING
}

/**
 * [ALTREX] « Vérifier la connexion » for models that are not on the phone (cloud:…, space:…).
 * They have nothing to load, so instead of Charger / Décharger their bar says whether a message
 * would go through, without sending one:
 * - cloud: one GET of the provider's model list with the key (no tokens used): the key is valid
 *   and the model exists ;
 * - Space: one GET of its API description; a sleeping Space answers 503 while it starts, so the
 *   check waits (« Space en veille, réveil en cours ») until it is up.
 */
object RemoteCheck {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val jobs = ConcurrentHashMap<String, Job>()

  private val _status = MutableStateFlow<Map<String, RemoteStatus>>(emptyMap())
  val status: StateFlow<Map<String, RemoteStatus>> = _status.asStateFlow()

  fun isRemoteModel(modelName: String): Boolean = CloudApi.isCloud(modelName) || RemoteSpace.isRemote(modelName)

  /** What is known without any request: a missing key, else the last check. */
  fun current(context: Context, modelName: String): RemoteStatus {
    val known = _status.value[modelName]
    if (known != null && (known.busy || known.state == RemoteState.READY)) return known
    val cloud = CloudApi.parse(modelName)
    if (cloud != null && CloudApi.getKey(context, cloud.first) == null) {
      return RemoteStatus(RemoteState.NO_KEY, "Ajoute la clé ${cloud.first.label} dans Réglages → Modèles cloud.")
    }
    if (known != null && known.state != RemoteState.NO_KEY) return known
    return RemoteStatus(RemoteState.UNKNOWN, if (cloud != null) "Vérifie la connexion, ou envoie un message." else "Un Space en veille met 1 à 3 min à démarrer : vérifie la connexion pour le réveiller.")
  }

  private fun set(modelName: String, s: RemoteStatus) {
    _status.update { it + (modelName to s.copy(checkedAtMs = System.currentTimeMillis())) }
  }

  /** A real request went through or failed: the bar follows. */
  fun report(modelName: String, ok: Boolean, detail: String = "") {
    if (jobs[modelName]?.isActive == true) return
    if (ok) set(modelName, RemoteStatus(RemoteState.READY, "Dernière réponse reçue.")) else if (detail.isNotBlank()) set(modelName, RemoteStatus(RemoteState.ERROR, detail.take(240)))
  }

  /** Starts the check of [modelName] (ignored while one runs). */
  fun check(context: Context, modelName: String) {
    if (jobs[modelName]?.isActive == true) return
    val app = context.applicationContext
    set(modelName, RemoteStatus(RemoteState.CHECKING, "Connexion…"))
    jobs[modelName] =
      scope.launch {
        val result =
          try {
            val cloud = CloudApi.parse(modelName)
            when {
              cloud != null -> checkCloud(app, cloud.first, cloud.second)
              RemoteSpace.isRemote(modelName) -> {
                val id = RemoteSpace.spaceId(modelName)
                if (RemoteSpace.parseSpaceId(id) == null) RemoteStatus(RemoteState.ERROR, "Identifiant de Space invalide : $id")
                else {
                  val token = RemoteSpace.getToken(app)
                  val url = RemoteSpace.baseUrl(id) + "/gradio_api/info"
                  spaceLoop(hasToken = !token.isNullOrBlank(), fetch = { httpGet(url, token) }, onState = { set(modelName, it) })
                }
              }
              else -> RemoteStatus(RemoteState.ERROR, "Modèle distant invalide.")
            }
          } catch (e: Exception) {
            RemoteStatus(RemoteState.OFFLINE, offlineDetail(e))
          }
        set(modelName, result)
      }
  }

  // ---- Cloud ------------------------------------------------------------------------------------

  private fun checkCloud(context: Context, provider: CloudApi.Provider, modelId: String): RemoteStatus {
    val key = CloudApi.getKey(context, provider) ?: return RemoteStatus(RemoteState.NO_KEY, "Ajoute la clé ${provider.label} dans Réglages → Modèles cloud.")
    return try {
      cloudVerdict(provider, modelId, CloudApi.listModels(provider, key))
    } catch (e: CloudApi.CloudException) {
      cloudError(provider, e.code, e.message.orEmpty())
    }
  }

  /** The key answered with the model list: is [modelId] in it? */
  internal fun cloudVerdict(provider: CloudApi.Provider, modelId: String, models: List<String>): RemoteStatus =
    when {
      // OpenRouter lists its models without a key: the list proves the network, not the key.
      provider == CloudApi.Provider.OPENROUTER && modelId in models -> RemoteStatus(RemoteState.READY, "${provider.label} répond et le modèle existe (la clé sera vérifiée au premier message).")
      models.isEmpty() || modelId in models -> RemoteStatus(RemoteState.READY, "Clé acceptée par ${provider.label}, modèle disponible.")
      else -> RemoteStatus(RemoteState.ERROR, "Clé acceptée, mais « $modelId » n'est pas dans la liste des modèles de ${provider.label}.")
    }

  internal fun cloudError(provider: CloudApi.Provider, code: Int, message: String): RemoteStatus =
    when (code) {
      401, 403 -> RemoteStatus(RemoteState.KEY_REFUSED, "${provider.label} refuse la clé : vérifie-la dans Réglages → Modèles cloud.")
      429 -> RemoteStatus(RemoteState.ERROR, "Clé valide, mais limite ou quota atteint chez ${provider.label}.")
      in 500..599 -> RemoteStatus(RemoteState.ERROR, "${provider.label} est indisponible pour le moment ($code).")
      else -> RemoteStatus(RemoteState.ERROR, message.ifBlank { "${provider.label} a répondu $code." }.take(240))
    }

  // ---- Space ------------------------------------------------------------------------------------

  /** One answer of the Space: what to show, and whether to ask again (it is waking up). */
  internal fun spaceVerdict(code: Int, body: String, hasToken: Boolean): Pair<RemoteStatus, Boolean> =
    when {
      code == 200 && body.contains("\"/${RemoteSpace.API_NAME}\"") -> RemoteStatus(RemoteState.READY, "Le Space est démarré et expose l'API ${RemoteSpace.API_NAME}.") to false
      code == 200 && body.trimStart().startsWith("{") -> RemoteStatus(RemoteState.ERROR, "Le Space répond, mais n'expose pas l'API « ${RemoteSpace.API_NAME} ».") to false
      // A page instead of the API description: the Space is still starting.
      code == 200 || code == 502 || code == 503 || code == 504 -> RemoteStatus(RemoteState.WAKING, "Space en veille, réveil en cours…") to true
      code == 401 || code == 403 -> RemoteStatus(RemoteState.KEY_REFUSED, "Accès refusé au Space ($code) : vérifie ton jeton Hugging Face (Réglages → Modèles).") to false
      code == 404 && !hasToken -> RemoteStatus(RemoteState.NO_KEY, "Space introuvable. S'il est privé, ajoute un jeton Hugging Face « Read » dans Réglages → Modèles.") to false
      code == 404 -> RemoteStatus(RemoteState.ERROR, "Space introuvable, ou ton jeton n'y donne pas accès.") to false
      else -> RemoteStatus(RemoteState.ERROR, "Le Space a répondu $code.") to false
    }

  /** Asks the Space until it is up, for [maxWaitMs] at most (a sleeping Space starts in 1 to 3 min). */
  internal fun spaceLoop(
    hasToken: Boolean,
    fetch: () -> Pair<Int, String>,
    onState: (RemoteStatus) -> Unit,
    maxWaitMs: Long = 240_000L,
    now: () -> Long = { System.currentTimeMillis() },
    sleep: (Long) -> Unit = { Thread.sleep(it) },
  ): RemoteStatus {
    val deadline = now() + maxWaitMs
    var wait = 3_000L
    var woke = false
    while (true) {
      val (code, body) = fetch()
      val (status, again) = spaceVerdict(code, body, hasToken)
      if (!again) return if (woke && status.state == RemoteState.READY) status.copy(detail = "Space réveillé : il est prêt.") else status
      if (now() >= deadline) return RemoteStatus(RemoteState.ERROR, "Le Space ne démarre pas (en pause, en erreur ou en construction ?). Regarde sa page sur Hugging Face.")
      woke = true
      onState(status)
      sleep(wait)
      wait = (wait * 3 / 2).coerceAtMost(15_000L)
    }
  }

  private fun httpGet(url: String, token: String?): Pair<Int, String> {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
      conn.connectTimeout = 15_000
      conn.readTimeout = 30_000
      conn.instanceFollowRedirects = false
      // The token only ever goes to the Space's own *.hf.space host.
      if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
      val code = conn.responseCode
      val text = runCatching { (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText().take(400_000) } }.getOrNull().orEmpty()
      code to text
    } finally {
      conn.disconnect()
    }
  }

  internal fun offlineDetail(e: Exception): String =
    when (e) {
      is java.net.UnknownHostException -> "Pas de connexion Internet (ou nom introuvable)."
      is java.net.SocketTimeoutException -> "Pas de réponse : réseau trop lent ou service injoignable."
      is IOException -> "Connexion impossible : ${e.message ?: e.javaClass.simpleName}".take(200)
      else -> (e.message ?: e.javaClass.simpleName).take(200)
    }
}
