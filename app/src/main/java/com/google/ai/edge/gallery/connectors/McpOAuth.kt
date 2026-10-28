package com.google.ai.edge.gallery.connectors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.security.SecretCipher
import com.google.ai.edge.gallery.security.UrlGuard
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AGMcpOAuth"

/**
 * [ALTREX] Sign-in to a remote MCP server (a "connector"), following the MCP authorization spec:
 * 1. the server's protected resource metadata (RFC 9728) names its authorization server;
 * 2. the authorization server's metadata (RFC 8414, or OpenID discovery) gives its endpoints;
 * 3. Altrex registers itself as a public client (dynamic registration, RFC 7591), once per server;
 * 4. authorization code with PKCE S256 in a browser tab (AppAuth), with the `resource` parameter
 *    (RFC 8707) so the token is only valid for this MCP server;
 * 5. tokens stored encrypted (Keystore), refreshed when they expire.
 *
 * Every URL fetched here goes through [UrlGuard] and must be https: a server cannot point Altrex at
 * the phone's network or at a plain-http endpoint.
 */
object McpOAuth {
  /** Where the browser sends the user back (AppAuth's RedirectUriReceiverActivity). */
  val redirectUri: Uri by lazy { Uri.parse("${BuildConfig.APPLICATION_ID}:/oauth2redirect") }

  /** What is needed to start the browser step, kept until it comes back. */
  data class Pending(
    val serverUrl: String,
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String?,
    val scope: String?,
  )

  class OAuthException(message: String) : IOException(message)

  // ---------------------------------------------------------------- discovery and registration

  /** Steps 1 to 3. Network; call off the main thread. */
  suspend fun prepare(context: Context, serverUrl: String): Pending =
    withContext(Dispatchers.IO) {
      val server = httpsUrl(serverUrl)
      val (resourceMeta, scopeHint) = protectedResourceMetadata(server)
      val asIssuer =
        resourceMeta?.optJSONArray("authorization_servers")?.optString(0)?.takeIf { it.isNotBlank() }
          ?: origin(server) // older servers: the MCP server is its own authorization server
      val asMeta = authorizationServerMetadata(httpsUrl(asIssuer))
      val authEndpoint = asMeta.optString("authorization_endpoint").ifBlank { null }
        ?: throw OAuthException("Le serveur d'autorisation ne donne pas d'adresse de connexion.")
      val tokenEndpoint = asMeta.optString("token_endpoint").ifBlank { null }
        ?: throw OAuthException("Le serveur d'autorisation ne donne pas d'adresse de jeton.")
      httpsUrl(authEndpoint)
      httpsUrl(tokenEndpoint)
      val methods = asMeta.optJSONArray("code_challenge_methods_supported")
      if (methods != null && (0 until methods.length()).none { methods.optString(it) == "S256" }) {
        throw OAuthException("Ce service ne prend pas en charge PKCE (S256) : connexion refusée par sécurité.")
      }
      val (clientId, clientSecret) = clientFor(context, asIssuer, asMeta)
      val scope =
        scopeHint
          ?: resourceMeta?.optJSONArray("scopes_supported")?.let { joinStrings(it) }
      Pending(server, asIssuer, authEndpoint, tokenEndpoint, clientId, clientSecret, scope)
    }

  /** RFC 9728: from the 401 challenge, else the well-known paths. Null when the server has none. */
  private fun protectedResourceMetadata(server: String): Pair<JSONObject?, String?> {
    var scopeHint: String? = null
    val candidates = mutableListOf<String>()
    // An unauthenticated request: a protected server answers 401 with resource_metadata.
    runCatching {
      val conn = open(server)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Accept", "application/json, text/event-stream")
      conn.outputStream.use {
        it.write(
          """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"Altrex","version":"1"}}}"""
            .toByteArray()
        )
      }
      if (conn.responseCode == 401) {
        val challenge = conn.getHeaderField("WWW-Authenticate").orEmpty()
        challengeParam(challenge, "resource_metadata")?.let { candidates.add(it) }
        scopeHint = challengeParam(challenge, "scope")
      }
      conn.disconnect()
    }
    val u = URL(server)
    val path = u.path.trimEnd('/')
    if (path.isNotEmpty()) candidates.add("${origin(server)}/.well-known/oauth-protected-resource$path")
    candidates.add("${origin(server)}/.well-known/oauth-protected-resource")
    for (c in candidates) {
      val json = runCatching { getJson(httpsUrl(c)) }.getOrNull()
      if (json != null) return json to scopeHint
    }
    return null to scopeHint
  }

  /** RFC 8414 / OpenID discovery, with the path-insertion variants of the MCP spec. */
  private fun authorizationServerMetadata(issuer: String): JSONObject {
    val u = URL(issuer)
    val path = u.path.trimEnd('/')
    val o = origin(issuer)
    val candidates =
      if (path.isEmpty()) {
        listOf("$o/.well-known/oauth-authorization-server", "$o/.well-known/openid-configuration")
      } else {
        listOf(
          "$o/.well-known/oauth-authorization-server$path",
          "$o/.well-known/openid-configuration$path",
          "$o$path/.well-known/openid-configuration",
        )
      }
    for (c in candidates) {
      val json = runCatching { getJson(httpsUrl(c)) }.getOrNull()
      if (json != null && json.has("authorization_endpoint")) return json
    }
    throw OAuthException("Ce serveur ne publie pas de configuration OAuth : utilise une clé d'API à la place.")
  }

  /** RFC 7591 dynamic registration, once per authorization server (kept). */
  private fun clientFor(context: Context, issuer: String, asMeta: JSONObject): Pair<String, String?> {
    store(context).getString("client|$issuer", null)?.let { saved ->
      val o = JSONObject(SecretCipher.decrypt(saved))
      return o.getString("client_id") to o.optString("client_secret").ifBlank { null }
    }
    val endpoint = asMeta.optString("registration_endpoint").ifBlank { null }
      ?: throw OAuthException(
        "Ce service n'accepte pas l'enregistrement automatique des applis : il faut un identifiant " +
          "client déclaré chez lui (pas encore pris en charge)."
      )
    val body =
      JSONObject()
        .put("client_name", "Altrex")
        .put("redirect_uris", JSONArray().put(redirectUri.toString()))
        .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
        .put("response_types", JSONArray().put("code"))
        .put("token_endpoint_auth_method", "none")
    val conn = open(httpsUrl(endpoint))
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    conn.outputStream.use { it.write(body.toString().toByteArray()) }
    val code = conn.responseCode
    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    conn.disconnect()
    if (code !in 200..299) throw OAuthException("Enregistrement d'Altrex refusé par le service ($code).")
    val o = JSONObject(text)
    val clientId = o.optString("client_id").ifBlank { null } ?: throw OAuthException("Réponse d'enregistrement invalide.")
    store(context).edit().putString("client|$issuer", SecretCipher.encrypt(o.toString())).apply()
    return clientId to o.optString("client_secret").ifBlank { null }
  }

  // ---------------------------------------------------------------- browser step and tokens

  /** Step 4: the browser intent (PKCE S256 is generated by AppAuth). */
  fun authorizationIntent(context: Context, pending: Pending): Intent {
    val config =
      AuthorizationServiceConfiguration(Uri.parse(pending.authorizationEndpoint), Uri.parse(pending.tokenEndpoint))
    val request =
      AuthorizationRequest.Builder(config, pending.clientId, ResponseTypeValues.CODE, redirectUri)
        .apply { pending.scope?.takeIf { it.isNotBlank() }?.let { setScope(it) } }
        .setAdditionalParameters(mapOf("resource" to pending.serverUrl))
        .build()
    return AuthorizationService(context).getAuthorizationRequestIntent(request)
  }

  /** Step 5: code → tokens, stored for [Pending.serverUrl]. */
  suspend fun complete(context: Context, pending: Pending, data: Intent?) {
    val response = data?.let { AuthorizationResponse.fromIntent(it) }
    val error = data?.let { AuthorizationException.fromIntent(it) }
    if (response == null) {
      throw OAuthException(
        error?.errorDescription ?: error?.error ?: "Connexion annulée."
      )
    }
    val service = AuthorizationService(context)
    try {
      val tokenRequest = response.createTokenExchangeRequest(mapOf("resource" to pending.serverUrl))
      val tokens =
        suspendCancellableCoroutine { cont ->
          val callback =
            AuthorizationService.TokenResponseCallback { tr, ex ->
              cont.resume(tr to ex)
            }
          if (pending.clientSecret != null) {
            service.performTokenRequest(
              tokenRequest,
              net.openid.appauth.ClientSecretBasic(pending.clientSecret),
              callback,
            )
          } else {
            service.performTokenRequest(tokenRequest, callback)
          }
        }
      val tr = tokens.first ?: throw OAuthException(tokens.second?.errorDescription ?: "Échange du code refusé.")
      val access = tr.accessToken ?: throw OAuthException("Le service n'a pas donné de jeton.")
      saveTokens(
        context,
        pending,
        access,
        tr.refreshToken,
        tr.accessTokenExpirationTime ?: (System.currentTimeMillis() + 3_600_000L),
      )
    } finally {
      service.dispose()
    }
  }

  /**
   * A valid access token for [serverUrl], refreshed if it expires within a minute. Null when the
   * user is not signed in (or the refresh was refused: sign in again).
   */
  suspend fun accessToken(context: Context, serverUrl: String): String? =
    withContext(Dispatchers.IO) {
      val saved = store(context).getString("token|$serverUrl", null) ?: return@withContext null
      val o = runCatching { JSONObject(SecretCipher.decrypt(saved)) }.getOrNull() ?: return@withContext null
      val expiresAt = o.optLong("expires_at")
      if (expiresAt - 60_000 > System.currentTimeMillis()) return@withContext o.getString("access_token")
      val refresh = o.optString("refresh_token").ifBlank { null } ?: return@withContext null
      runCatching { refresh(context, serverUrl, o, refresh) }
        .onFailure { Log.w(TAG, "Token refresh failed for $serverUrl", it) }
        .getOrNull()
    }

  fun isSignedIn(context: Context, serverUrl: String): Boolean =
    store(context).contains("token|$serverUrl")

  /** Forgets the tokens of [serverUrl] (sign-out, server removed). */
  fun signOut(context: Context, serverUrl: String) {
    store(context).edit().remove("token|$serverUrl").apply()
  }

  private fun refresh(context: Context, serverUrl: String, saved: JSONObject, refreshToken: String): String {
    val form =
      buildString {
        append("grant_type=refresh_token")
        append("&refresh_token=").append(enc(refreshToken))
        append("&client_id=").append(enc(saved.getString("client_id")))
        append("&resource=").append(enc(serverUrl))
      }
    val conn = open(httpsUrl(saved.getString("token_endpoint")))
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    conn.setRequestProperty("Accept", "application/json")
    saved.optString("client_secret").ifBlank { null }?.let { secret ->
      val basic = android.util.Base64.encodeToString(
        "${enc(saved.getString("client_id"))}:${enc(secret)}".toByteArray(),
        android.util.Base64.NO_WRAP,
      )
      conn.setRequestProperty("Authorization", "Basic $basic")
    }
    conn.outputStream.use { it.write(form.toByteArray()) }
    val code = conn.responseCode
    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    conn.disconnect()
    if (code !in 200..299) {
      if (code == 400 || code == 401) signOut(context, serverUrl)
      throw OAuthException("Renouvellement du jeton refusé ($code).")
    }
    val o = JSONObject(text)
    val access = o.getString("access_token")
    val expiresIn = o.optLong("expires_in", 3600)
    saved.put("access_token", access)
    saved.put("expires_at", System.currentTimeMillis() + expiresIn * 1000)
    o.optString("refresh_token").ifBlank { null }?.let { saved.put("refresh_token", it) }
    store(context).edit().putString("token|$serverUrl", SecretCipher.encrypt(saved.toString())).apply()
    return access
  }

  private fun saveTokens(context: Context, p: Pending, access: String, refresh: String?, expiresAt: Long) {
    val o =
      JSONObject()
        .put("access_token", access)
        .put("expires_at", expiresAt)
        .put("token_endpoint", p.tokenEndpoint)
        .put("client_id", p.clientId)
        .put("issuer", p.issuer)
    refresh?.let { o.put("refresh_token", it) }
    p.clientSecret?.let { o.put("client_secret", it) }
    store(context).edit().putString("token|${p.serverUrl}", SecretCipher.encrypt(o.toString())).apply()
  }

  // ---------------------------------------------------------------- helpers

  private fun store(context: Context) =
    context.applicationContext.getSharedPreferences("altrex_mcp_oauth", Context.MODE_PRIVATE)

  /** https only, public host only (UrlGuard). */
  internal fun httpsUrl(url: String): String {
    val u = URL(url.trim())
    if (u.protocol != "https") throw OAuthException("Adresse refusée (https obligatoire) : $url")
    UrlGuard.checkUrl(u.toString())
    return u.toString()
  }

  internal fun origin(url: String): String {
    val u = URL(url)
    return "${u.protocol}://${u.host}${if (u.port != -1) ":${u.port}" else ""}"
  }

  /** A parameter of a WWW-Authenticate challenge (quoted or not). */
  internal fun challengeParam(challenge: String, name: String): String? =
    Regex("""(?i)\b$name\s*=\s*(?:"([^"]*)"|([^\s,]+))""").find(challenge)?.let {
      it.groupValues[1].ifEmpty { it.groupValues[2] }
    }

  private fun joinStrings(arr: JSONArray): String? =
    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  private fun open(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 15_000
      readTimeout = 20_000
      instanceFollowRedirects = false
      setRequestProperty("User-Agent", "Altrex")
    }

  private fun getJson(url: String): JSONObject {
    val conn = open(url)
    conn.setRequestProperty("Accept", "application/json")
    try {
      if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
      val text = conn.inputStream.bufferedReader().use { it.readText() }
      if (text.length > 200_000) throw IOException("Réponse trop grande")
      return JSONObject(text)
    } finally {
      conn.disconnect()
    }
  }
}
