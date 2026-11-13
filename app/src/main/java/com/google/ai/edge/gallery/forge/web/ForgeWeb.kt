package com.google.ai.edge.gallery.forge.web

import com.google.ai.edge.gallery.forge.tools.ToolOutcome
import com.google.ai.edge.gallery.security.SecretRedactor
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** [ALTREX] WebFetch and WebSearch of Forge, behind an interface (tests give a fake). */
interface WebPort {
  suspend fun fetch(url: String, prompt: String?, maxChars: Int): ToolOutcome

  suspend fun search(query: String, maxChars: Int): ToolOutcome
}

/**
 * [ALTREX] Which addresses WebFetch may reach: public HTTPS only. Refused: other schemes, a user
 * name in the address, localhost and `.local`/`.internal` names, and any name that resolves to a
 * private, loopback, link-local, CGNAT, multicast or unspecified address (IPv4 and IPv6). Checked
 * again at every redirection.
 */
object UrlGuard {
  /** Null when [url] may be fetched, else the reason (in French, for the model). */
  fun check(url: String, resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): String? {
    val u = runCatching { URI(url.trim()) }.getOrNull() ?: return "Adresse illisible : $url"
    if (u.scheme?.lowercase() != "https") return "WebFetch n'accepte que les adresses https:// (pas ${u.scheme ?: "sans schéma"})."
    if (u.rawUserInfo != null) return "Adresse refusée : pas d'identifiant dans l'adresse."
    val host = u.host?.lowercase()?.trimEnd('.') ?: return "Adresse sans nom d'hôte."
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home.arpa")) {
      return "Adresse refusée : $host est une adresse locale. WebFetch ne lit que des pages publiques."
    }
    if (u.port != -1 && u.port != 443) return "Adresse refusée : seul le port 443 (https) est permis."
    val addrs = runCatching { resolve(host.removePrefix("[").removeSuffix("]")) }.getOrElse { return "Nom introuvable : $host." }
    if (addrs.isEmpty()) return "Nom introuvable : $host."
    addrs.firstOrNull { isPrivate(it) }?.let { return "Adresse refusée : $host mène à une adresse privée ou locale (${it.hostAddress}). WebFetch ne lit que des pages publiques." }
    return null
  }

  fun isPrivate(a: InetAddress): Boolean {
    if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
    val b = a.address
    if (a is Inet4Address) {
      val x = b[0].toInt() and 0xff
      val y = b[1].toInt() and 0xff
      return x == 0 || x == 10 || x == 127 || (x == 100 && y in 64..127) || (x == 169 && y == 254) || (x == 172 && y in 16..31) ||
        (x == 192 && y == 168) || (x == 192 && y == 0 && (b[2].toInt() and 0xff) == 0) || (x == 198 && y in 18..19) || x >= 224
    }
    if (a is Inet6Address) {
      val x = b[0].toInt() and 0xff
      if ((x and 0xfe) == 0xfc) return true // fc00::/7, unique local
      // IPv4-mapped (::ffff:a.b.c.d) and NAT64 (64:ff9b::/96): judge the embedded IPv4 address.
      val mapped = (0 until 10).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
      val nat64 = x == 0 && (b[1].toInt() and 0xff) == 0x64 && (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b
      if (mapped || nat64) return isPrivate(InetAddress.getByAddress(b.copyOfRange(12, 16)))
    }
    return false
  }
}

/** [ALTREX] Text of an HTML page: no scripts, styles or tags; entities decoded; blank lines folded. */
object HtmlText {
  data class Page(val title: String, val text: String)

  fun extract(html: String): Page {
    val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { decode(it).trim() }.orEmpty()
    var s = html
    s = s.replace(Regex("(?is)<(script|style|noscript|svg|template|iframe|head)\\b[^>]*>.*?</\\1\\s*>"), " ")
    s = s.replace(Regex("(?s)<!--.*?-->"), " ")
    s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
    s = s.replace(Regex("(?i)<li\\b[^>]*>"), "\n- ")
    s = s.replace(Regex("(?i)</?(p|div|section|article|header|footer|li|ul|ol|tr|table|h[1-6]|pre|blockquote|main|nav)\\b[^>]*>"), "\n")
    s = s.replace(Regex("(?s)<[^>]+>"), " ")
    s = decode(s)
    val lines = s.lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
    val out = StringBuilder()
    var blank = 0
    for (l in lines) {
      if (l.isEmpty()) {
        if (++blank == 1 && out.isNotEmpty()) out.append('\n')
      } else {
        blank = 0
        out.append(l).append('\n')
      }
    }
    return Page(title, out.toString().trim())
  }

  private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "ecirc" to "ê", "laquo" to "«", "raquo" to "»", "rsquo" to "’", "hellip" to "…", "mdash" to "—", "ndash" to "–", "copy" to "©")

  fun decode(s: String): String =
    Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
      val e = m.groupValues[1]
      when {
        e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { cp -> String(Character.toChars(cp.coerceIn(0, 0x10FFFF))) } ?: m.value
        e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { cp -> String(Character.toChars(cp.coerceIn(0, 0x10FFFF))) } ?: m.value
        else -> NAMED[e.lowercase()] ?: m.value
      }
    }

  /**
   * At most [max] characters of [text]; with a [prompt], the paragraphs that share the most words
   * with it come first (a cheap stand-in for reading the page with a second model).
   */
  fun excerpt(text: String, prompt: String?, max: Int): Pair<String, Boolean> {
    if (text.length <= max) return text to false
    val words = prompt.orEmpty().lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.toSet()
    if (words.isEmpty()) return text.take(max) to true
    val paras = text.split(Regex("\n{2,}|\n(?=- )")).filter { it.isNotBlank() }
    val scored = paras.withIndex().map { (i, p) -> Triple(i, p, words.count { w -> p.lowercase().contains(w) }) }
    val chosen = scored.filter { it.third > 0 }.sortedByDescending { it.third }
    val out = StringBuilder()
    val keep = ArrayList<Int>()
    for (c in chosen) {
      if (out.length + c.second.length > max) continue
      keep.add(c.first)
      out.append(c.second).append("\n\n")
    }
    if (keep.isEmpty()) return text.take(max) to true
    // Back in page order, so the excerpt reads naturally.
    val ordered = keep.sorted().joinToString("\n\n[…]\n\n") { paras[it] }
    return ordered.take(max) to true
  }
}

/**
 * [ALTREX] The real web of Forge. WebFetch: public HTTPS only ([UrlGuard], checked at every
 * redirection, 5 at most), 2 MB at most, 10 s to connect, 20 s in all; text, HTML (turned into
 * text), JSON and XML only; secrets in the address refused. WebSearch: Brave Search with the key of
 * the Settings (never shown to the model) and the daily limit of Discuter.
 */
class HttpWeb(
  private val braveKey: () -> String?,
  private val takeQuota: () -> Boolean,
  private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : WebPort {
  override suspend fun fetch(url: String, prompt: String?, maxChars: Int): ToolOutcome =
    withContext(Dispatchers.IO) {
      if (SecretRedactor.containsSecret(url)) return@withContext ToolOutcome.error("L'adresse contient un secret (clé, jeton, mot de passe) : elle n'a pas été ouverte.")
      var current = url.trim()
      val deadline = System.currentTimeMillis() + TOTAL_MS
      repeat(MAX_REDIRECTS + 1) {
        UrlGuard.check(current, resolve)?.let { return@withContext ToolOutcome.refused(it) }
        val c = (URL(current).openConnection() as HttpsURLConnection).apply {
          instanceFollowRedirects = false
          connectTimeout = CONNECT_MS
          readTimeout = READ_MS
          setRequestProperty("User-Agent", "AltrexForge/1.0 (+lecture de page pour un agent de code)")
          setRequestProperty("Accept", "text/html,text/plain,application/json,application/xml;q=0.9,*/*;q=0.1")
        }
        try {
          val code = c.responseCode
          if (code in 300..399) {
            val loc = c.getHeaderField("Location") ?: return@withContext ToolOutcome.error("Redirection sans adresse (HTTP $code).")
            current = URL(URL(current), loc).toString()
            return@repeat
          }
          if (code !in 200..299) return@withContext ToolOutcome.error("La page a répondu HTTP $code.", summary = "HTTP $code")
          val type = c.contentType.orEmpty().lowercase()
          if (type.isNotEmpty() && !(type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("javascript"))) {
            return@withContext ToolOutcome.error("Contenu non textuel ($type) : WebFetch ne lit que du texte, du HTML ou du JSON.")
          }
          val bytes = readBounded(c, deadline)
          val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1)?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
          val raw = String(bytes.first, charset)
          val page = if (type.contains("html") || (type.isEmpty() && raw.trimStart().startsWith("<"))) HtmlText.extract(raw) else HtmlText.Page("", raw.trim())
          val (content, cut) = HtmlText.excerpt(SecretRedactor.redact(page.text), prompt, maxChars.coerceAtLeast(500))
          return@withContext ToolOutcome(
            ToolOutcome.Status.OK,
            ToolOutcome.json(
              "url" to url,
              "final_url" to (if (current != url) current else null),
              "title" to page.title.ifEmpty { null },
              "content" to content.ifEmpty { "(page sans texte)" },
              "truncated" to (if (cut || bytes.second) true else null),
              "note" to (if (cut || bytes.second) "Page coupée. Précise « prompt » pour recevoir les passages utiles en premier." else null),
              "status" to "ok",
            ),
            summary = page.title.ifEmpty { URL(current).host }.take(60),
            detail = content.take(4_000),
          )
        } finally {
          c.disconnect()
        }
      }
      ToolOutcome.error("Trop de redirections (plus de $MAX_REDIRECTS).")
    }

  private fun readBounded(c: HttpURLConnection, deadline: Long): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream()
    var cut = false
    c.inputStream.use { input ->
      val buf = ByteArray(16 * 1024)
      while (true) {
        if (System.currentTimeMillis() > deadline) {
          cut = true
          break
        }
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() >= MAX_BYTES) {
          cut = true
          break
        }
      }
    }
    return out.toByteArray() to cut
  }

  override suspend fun search(query: String, maxChars: Int): ToolOutcome =
    withContext(Dispatchers.IO) {
      val q = query.trim()
      if (q.isEmpty()) return@withContext ToolOutcome.error("WebSearch demande une « query ».")
      if (SecretRedactor.containsSecret(q)) return@withContext ToolOutcome.error("La recherche contient un secret (clé, jeton, mot de passe) : elle n'a pas été envoyée.")
      val key = braveKey() ?: return@withContext ToolOutcome.error(NO_KEY, summary = "pas de clé")
      if (!takeQuota()) return@withContext ToolOutcome.error("Limite quotidienne de recherches atteinte (réglée pour protéger le crédit Brave). Réessaie demain, ou lis une page connue avec WebFetch.")
      val c = (URL("https://api.search.brave.com/res/v1/web/search?count=8&q=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpsURLConnection).apply {
        connectTimeout = CONNECT_MS
        readTimeout = READ_MS
        setRequestProperty("Accept", "application/json")
        setRequestProperty("X-Subscription-Token", key)
      }
      try {
        val code = c.responseCode
        if (code == 401 || code == 403) return@withContext ToolOutcome.error("Brave Search refuse la clé (HTTP $code) : vérifie-la dans Réglages.")
        if (code == 429) return@withContext ToolOutcome.error("Brave Search : limite atteinte (HTTP 429). Réessaie plus tard.")
        if (code !in 200..299) return@withContext ToolOutcome.error("Brave Search a répondu HTTP $code.")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val results = parseBrave(body)
        val arr = org.json.JSONArray()
        var used = 0
        for (r in results) {
          val o = JSONObject().put("title", r.first).put("url", r.second).put("snippet", r.third)
          used += o.toString().length
          if (used > maxChars) break
          arr.put(o)
        }
        ToolOutcome(
          ToolOutcome.Status.OK,
          "{\"query\":" + JSONObject.quote(q) + ",\"results\":" + arr.toString() + (if (arr.length() == 0) ",\"note\":\"Aucun résultat.\"" else "") + ",\"status\":\"ok\"}",
          summary = "${arr.length()} résultat(s)",
          detail = results.take(arr.length()).joinToString("\n") { "${it.first}\n${it.second}" }.take(4_000),
        )
      } finally {
        c.disconnect()
      }
    }

  companion object {
    const val NO_KEY =
      "WebSearch demande une clé Brave Search : l'utilisateur peut l'ajouter dans Réglages → Recherche web (Brave). En attendant, lis une page connue avec WebFetch, ou dis-lui ce qu'il faudrait chercher."
    private const val MAX_REDIRECTS = 5
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val CONNECT_MS = 10_000
    private const val READ_MS = 15_000
    private const val TOTAL_MS = 20_000L

    /** (title, url, snippet) of a Brave answer; HTML of the snippets removed. */
    fun parseBrave(body: String): List<Triple<String, String, String>> {
      val web = runCatching { JSONObject(body).optJSONObject("web")?.optJSONArray("results") }.getOrNull() ?: return emptyList()
      return (0 until web.length()).mapNotNull { i ->
        val o = web.optJSONObject(i) ?: return@mapNotNull null
        val url = o.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Triple(HtmlText.decode(o.optString("title").replace(Regex("<[^>]+>"), "")), url, HtmlText.decode(o.optString("description").replace(Regex("<[^>]+>"), "")).take(300))
      }
    }
  }
}
