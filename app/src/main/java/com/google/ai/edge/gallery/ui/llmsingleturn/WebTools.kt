package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Web tools of the Code space: web_search (Brave Search API) and web_fetch (page text).
 *
 * Safety rules enforced by code, never by the model:
 * - the API key is stored encrypted and never shown to the model;
 * - web content is untrusted: once it has been read, write_file / replace_lines / delete_file are
 *   blocked for the rest of the request (the user must send a new message to authorize writing);
 * - once web content has been read AND a project file has been read in the same request, further
 *   web calls are blocked (prevents a booby-trapped page from sending file contents out in a URL);
 * - web_fetch refuses local / private addresses, checks every redirect, and caps size and time;
 * - a file of a repository imported from GitHub counts as untrusted content too: after reading
 *   one, web calls and scans are blocked for the rest of the request, and writes are allowed only
 *   inside the imported folders (fixing the repository still works, spreading out of it does not).
 */
object WebTools {
  private const val TAG = "WebTools"
  private const val BRAVE_URL = "https://api.search.brave.com/res/v1/web/search"
  private const val MAX_RESULTS = 5
  private const val SNIPPET_CHARS = 220
  private const val MAX_PAGE_CHARS = 1500
  private const val MAX_SEARCH_BYTES = 300_000
  private const val MAX_DOWNLOAD_BYTES = 400_000
  private const val MAX_REDIRECTS = 3
  private const val CONNECT_TIMEOUT_MS = 10_000
  private const val READ_TIMEOUT_MS = 15_000
  private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/120.0 Mobile Safari/537.36"

  private val WRITE_TOOLS = setOf("write_file", "replace_lines", "delete_file")
  private val BRAVE_LANGS = setOf("fr", "en", "es", "de", "it", "pt", "nl", "ja", "ko", "ru")

  @Volatile private var webContentSeen = false
  @Volatile private var projectFileRead = false
  @Volatile private var untrustedFileRead = false

  private val lock = Any()
  private var lastPageUrl: String = ""
  private var lastPageText: String = ""

  val specs: List<AgentToolSpec> =
    listOf(
      AgentToolSpec(
        name = "web_search",
        description =
          "Cherche sur le web (Brave Search) et renvoie jusqu'à 5 résultats : titre, adresse, " +
            "extrait. À utiliser pour une information récente ou une documentation que tu ne connais pas.",
        params = listOf(AgentToolParam("query", "string", "Mots-clés de recherche, courts (2 à 8 mots)")),
      ),
      AgentToolSpec(
        name = "web_fetch",
        description =
          "Télécharge une page web et renvoie son texte, environ 1500 caractères à la fois. " +
            "Utilise offset pour lire la suite de la même page.",
        params =
          listOf(
            AgentToolParam("url", "string", "Adresse complète de la page (https)"),
            AgentToolParam(
              "offset",
              "integer",
              "Position de départ en caractères pour lire la suite (0 par défaut)",
              required = false,
            ),
          ),
      ),
    )

  /** Called once per user request: forgets what the previous request read. */
  fun beginRun() {
    webContentSeen = false
    projectFileRead = false
    untrustedFileRead = false
  }

  /** The request starts with someone else's code in the prompt (opened file of an imported repo). */
  fun markUntrustedRead() {
    untrustedFileRead = true
  }

  /** Executor of the Code space: web tools, plus the file tools behind the web-content guard. */
  fun executeInCode(context: Context, call: AgentToolCall, readCap: Int): ToolOutcome {
    return when {
      call.name == "web_search" || call.name == "web_fetch" -> executeWeb(context, call)
      call.name in WRITE_TOOLS && untrustedFileRead && !insideImported(context, call) ->
        ToolOutcome(
          "Erreur : écriture bloquée par sécurité. Un fichier d'un dépôt importé depuis GitHub a " +
            "été lu pendant cette demande (il peut contenir des instructions piégées) : tu ne peux " +
            "modifier que les fichiers de ce dépôt. Explique à l'utilisateur ce que tu proposerais.",
          "⛔ ${call.name} bloqué hors du dépôt importé : un fichier de ce dépôt a été lu dans la même demande.",
          endTurn = true,
        )
      call.name in WRITE_TOOLS && webContentSeen ->
        ToolOutcome(
          "Erreur : écriture bloquée par sécurité, car du contenu web a été lu pendant cette " +
            "demande (il peut contenir des instructions piégées). N'écris rien : explique à " +
            "l'utilisateur ce que tu proposerais, et dis-lui de te donner l'ordre d'écrire dans un " +
            "nouveau message.",
          "⛔ ${call.name} bloqué : du contenu web a été lu. Redonne l'ordre d'écrire dans un nouveau message.",
          endTurn = true,
        )
      // [ALTREX] The scan sends project code out (to SecurCode only, with consent): not after
      // web content was read, which may carry injected instructions.
      // Next pages of the last report come from memory: nothing is sent, they stay allowed.
      call.name == "securcode_scan" && (webContentSeen || untrustedFileRead) &&
        call.arguments.optInt("page", 1) <= 1 ->
        failEnd(
          "scan bloqué par sécurité, car du contenu non fiable a été lu pendant cette demande. Dis à " +
            "l'utilisateur de relancer le scan dans un nouveau message.",
          "⛔ securcode_scan bloqué : du contenu non fiable a été lu. Relance le scan dans un nouveau message.",
        )
      call.name == "securcode_scan" -> {
        val out = com.google.ai.edge.gallery.ui.security.SecurityTools.execute(context, call, readCap)
        // The report of an imported repository can quote its code: untrusted as well.
        val target = FileActions.normalizeDir(call.arguments.optString("path", "."))
        val imported = com.google.ai.edge.gallery.ui.github.GitHubSettings.importedFolders(context)
        val coversImported =
          target != null && imported.isNotEmpty() &&
            (target.isEmpty() || com.google.ai.edge.gallery.ui.github.GitHubPaths.isInside(target, imported) ||
              imported.any { it.startsWith("$target/") })
        if (coversImported && !out.endTurn) {
          untrustedFileRead = true
          out.copy(
            result =
              com.google.ai.edge.gallery.security.UntrustedContent.frame(
                "rapport SecurCode d'un dépôt importé depuis GitHub",
                out.result,
              ),
            untrusted = true,
          )
        } else {
          out
        }
      }
      call.name == "read_file" -> {
        projectFileRead = true
        CodeTools.execute(context, call, readCap).also { if (it.untrusted) untrustedFileRead = true }
      }
      else -> CodeTools.execute(context, call, readCap)
    }
  }

  private fun insideImported(context: Context, call: AgentToolCall): Boolean {
    val path = FileActions.normalize(CodeTools.cleanPath(call.arguments.optString("path", ""))) ?: return false
    return com.google.ai.edge.gallery.ui.github.GitHubSettings.isImported(context, path)
  }

  private fun fail(message: String, log: String) = ToolOutcome("Erreur : $message", "✗ $log")

  private fun failEnd(message: String, userLog: String) =
    ToolOutcome("Erreur : $message", userLog, endTurn = true)

  private fun untrusted(body: String): String =
    com.google.ai.edge.gallery.security.UntrustedContent.frame("page web", body)

  private fun executeWeb(context: Context, call: AgentToolCall): ToolOutcome {
    if (untrustedFileRead) {
      return failEnd(
        "appel web bloqué par sécurité : un fichier d'un dépôt importé depuis GitHub a été lu dans " +
          "cette demande.",
        "⛔ Appel web bloqué : un fichier d'un dépôt importé a été lu dans la même demande. " +
          "Redonne ta demande dans un nouveau message.",
      )
    }
    if (webContentSeen && projectFileRead) {
      return failEnd(
        "appel web bloqué par sécurité : du contenu web et un fichier du projet ont été lus dans " +
          "cette demande.",
        "⛔ Appel web bloqué : du contenu web et un fichier du projet ont été lus dans la même demande. " +
          "Redonne ta demande dans un nouveau message.",
      )
    }
    return try {
      if (call.name == "web_search") {
        search(context, call.arguments.optString("query", ""))
      } else {
        fetch(call.arguments.optString("url", ""), call.arguments.optInt("offset", 0))
      }
    } catch (e: Exception) {
      Log.e(TAG, "${call.name} failed", e)
      val msg = e.message ?: "erreur réseau"
      fail(msg, "${call.name} : $msg")
    }
  }

  // ---------------------------------------------------------------- web_search

  private fun search(context: Context, rawQuery: String): ToolOutcome {
    val query = rawQuery.trim().take(200)
    if (query.isEmpty()) return fail("la requête est vide.", "web_search : requête vide")
    // [ALTREX] A secret (key, token, password) must never leave the phone in a search query.
    if (com.google.ai.edge.gallery.security.SecretRedactor.containsSecret(query)) {
      return fail(
        "la requête contenait un secret (clé, token ou mot de passe) : elle n'a pas été envoyée. " +
          "Reformule-la sans le secret.",
        "⛔ Recherche bloquée : la requête contenait un secret.",
      )
    }

    val key = WebSettings.getBraveKey(context)
    if (key == null) {
      WebSettings.keyDialogRequest.value = true
      return failEnd(
        "aucune clé API Brave n'est enregistrée.",
        "✗ Recherche web impossible : clé Brave absente. Saisis-la via la barre 🌐 en haut de l'écran, puis relance ta demande.",
      )
    }
    if (!WebSettings.takeSearchQuota(context)) {
      return failEnd(
        "limite quotidienne de recherches atteinte.",
        "✗ Limite de ${WebSettings.DAILY_LIMIT} recherches par jour atteinte (elle protège ton crédit Brave). Réessaie demain.",
      )
    }

    val lang = Locale.getDefault().language
    val langParam = if (lang in BRAVE_LANGS) "&search_lang=$lang" else ""
    val url =
      BRAVE_URL +
        "?q=" + URLEncoder.encode(query, "UTF-8") +
        "&count=" + MAX_RESULTS +
        "&result_filter=web" +
        langParam

    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "GET"
      conn.connectTimeout = CONNECT_TIMEOUT_MS
      conn.readTimeout = READ_TIMEOUT_MS
      conn.setRequestProperty("Accept", "application/json")
      conn.setRequestProperty("X-Subscription-Token", key)
      conn.setRequestProperty("User-Agent", USER_AGENT)
      val code = conn.responseCode
      if (code != 200) {
        val msg =
          when (code) {
            401, 403 -> "la clé Brave est refusée (vérifie-la dans la barre 🌐)."
            429 -> "trop de requêtes, ou crédit Brave épuisé."
            else -> "Brave a répondu avec le code $code."
          }
        return fail(msg, "web_search : $msg")
      }
      val body = readLimited(conn.inputStream, MAX_SEARCH_BYTES)
      val results = org.json.JSONObject(body).optJSONObject("web")?.optJSONArray("results")
      val sb = StringBuilder()
      var n = 0
      if (results != null) {
        for (i in 0 until results.length()) {
          if (n >= MAX_RESULTS) break
          val r = results.optJSONObject(i) ?: continue
          val link = r.optString("url", "")
          if (link.isEmpty()) continue
          n++
          sb.append(n).append(". ").append(cleanSnippet(r.optString("title", ""))).append("\n")
          sb.append(link).append("\n")
          sb.append(cleanSnippet(r.optString("description", "")).take(SNIPPET_CHARS)).append("\n\n")
        }
      }
      webContentSeen = true
      return if (n == 0) {
        ToolOutcome("Aucun résultat pour « $query ».", "🌐 Recherche : « $query » (aucun résultat)")
      } else {
        ToolOutcome(untrusted(sb.toString().trimEnd()), "🌐 Recherche : « $query » ($n résultats)")
      }
    } finally {
      conn.disconnect()
    }
  }

  // ---------------------------------------------------------------- web_fetch

  private fun fetch(rawUrl: String, rawOffset: Int): ToolOutcome {
    val url = rawUrl.trim()
    if (url.isEmpty()) return fail("l'adresse est vide.", "web_fetch : adresse vide")
    val start = rawOffset.coerceAtLeast(0)

    val cached = synchronized(lock) { if (url == lastPageUrl) lastPageText else null }
    val text: String =
      cached
        ?: download(url).also {
          synchronized(lock) {
            lastPageUrl = url
            lastPageText = it
          }
        }

    webContentSeen = true
    if (text.isBlank()) {
      return fail(
        "la page ne contient pas de texte lisible (elle est peut-être générée par JavaScript).",
        "web_fetch : page sans texte lisible",
      )
    }
    if (start >= text.length) {
      return ToolOutcome("Fin de la page : il n'y a plus de texte après la position $start.", "🌐 Fin de la page : $url")
    }
    val end = minOf(text.length, start + MAX_PAGE_CHARS)
    val chunk = text.substring(start, end)
    val tail =
      if (end < text.length) {
        "\n[Suite disponible : rappelle web_fetch avec offset=$end. Reste ${text.length - end} caractères.]"
      } else {
        "\n[Fin de la page.]"
      }
    return ToolOutcome(
      untrusted(chunk + tail),
      "🌐 Lu : $url (caractères $start à $end sur ${text.length})",
    )
  }

  /** Downloads [startUrl] (following at most [MAX_REDIRECTS] redirects, each one re-checked). */
  private fun download(startUrl: String): String {
    var current = startUrl
    for (hop in 0..MAX_REDIRECTS) {
      // [ALTREX] Each address, redirects included: public web sites only (UrlGuard).
      com.google.ai.edge.gallery.security.UrlGuard.checkUrl(current)

      val conn = URL(current).openConnection() as HttpURLConnection
      try {
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.1")
        val code = conn.responseCode
        if (code in 300..399) {
          val location =
            conn.getHeaderField("Location")
              ?: throw IllegalArgumentException("redirection sans destination.")
          current = URI(current).resolve(location).toString()
          continue
        }
        if (code != 200) throw IllegalArgumentException("la page a répondu avec le code $code.")
        val type = (conn.contentType ?: "").lowercase()
        if (!(type.startsWith("text/") || type.contains("json") || type.contains("xml"))) {
          throw IllegalArgumentException("type de contenu non pris en charge ($type).")
        }
        val raw = readLimited(conn.inputStream, MAX_DOWNLOAD_BYTES)
        return if (type.contains("html")) htmlToText(raw) else normalize(raw)
      } finally {
        conn.disconnect()
      }
    }
    throw IllegalArgumentException("trop de redirections.")
  }

  // ---------------------------------------------------------------- text helpers

  private fun readLimited(input: InputStream, max: Int): String {
    return input.use { s ->
      val out = ByteArrayOutputStream()
      val buf = ByteArray(8192)
      while (out.size() < max) {
        val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
      }
      out.toString("UTF-8")
    }
  }

  private val BLOCKS_TO_DROP =
    Regex("(?is)<(script|style|noscript|svg|nav|footer|aside|form|iframe)[^>]*>.*?</\\1>")
  private val COMMENTS = Regex("(?s)<!--.*?-->")
  private val LINE_BREAKS = Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/pre)[^>]*>")
  private val ANY_TAG = Regex("(?s)<[^>]+>")
  private val NUMERIC_ENTITY = Regex("&#(\\d+);")
  private val SPACES = Regex("[ \\t\\u00A0]+")

  private fun htmlToText(html: String): String {
    var s = BLOCKS_TO_DROP.replace(html, " ")
    s = COMMENTS.replace(s, " ")
    s = LINE_BREAKS.replace(s, "\n")
    s = ANY_TAG.replace(s, " ")
    return normalize(decodeEntities(s))
  }

  private fun decodeEntities(text: String): String {
    val named =
      text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
    return NUMERIC_ENTITY.replace(named) { m ->
      val cp = m.groupValues[1].toIntOrNull()
      if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else ""
    }
  }

  private fun normalize(text: String): String =
    SPACES.replace(text, " ")
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .joinToString("\n")

  private fun cleanSnippet(text: String): String =
    normalize(decodeEntities(ANY_TAG.replace(text, " "))).replace('\n', ' ')
}
