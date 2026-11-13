package com.google.ai.edge.gallery.forge.preview

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * [ALTREX] The Aperçu's own web server: serves one project folder to the WebView, from inside the
 * app (no Python, no PRoot), so that relative links (public/style.css, img/logo.png, fetch('data.json'))
 * work as on a real site.
 *
 * - Bound to 127.0.0.1 only, on a port chosen by the system; started when the Aperçu opens and
 *   stopped when it closes.
 * - Only [root]: a path that leaves it (`..`, an encoded `%2e%2e`, a symbolic link pointing outside)
 *   is refused; hidden files and folders (.git, .env, .altrex) are not served.
 * - Other apps of the phone can reach 127.0.0.1 too: every request must carry [token], given in
 *   the first page's address (`?altrex_apercu=…`) and then kept by the WebView as a cookie
 *   (HttpOnly, SameSite=Strict). Without it: 403.
 * - GET and HEAD, the usual MIME types, index.html for a folder, a plain listing otherwise,
 *   `Cache-Control: no-store` so that Recharger always shows the file as it is now.
 */
class PreviewServer(root: File, val token: String = newToken()) {
  val root: File = root.canonicalFile

  private var socket: ServerSocket? = null
  private val pool =
    Executors.newFixedThreadPool(
      MAX_CLIENTS,
      ThreadFactory { r -> Thread(r, "altrex-apercu").apply { isDaemon = true } },
    )

  /** The port, once [start]ed (0 before). */
  @Volatile var port: Int = 0
    private set

  val isRunning: Boolean
    get() = socket?.isClosed == false

  /** Opens the socket and starts answering. Call off the main thread. */
  @Synchronized
  fun start(): PreviewServer {
    if (isRunning) return this
    val s = ServerSocket(0, 32, InetAddress.getByName(HOST))
    socket = s
    port = s.localPort
    Thread({ acceptLoop(s) }, "altrex-apercu-accept").apply { isDaemon = true }.start()
    return this
  }

  @Synchronized
  fun stop() {
    runCatching { socket?.close() }
    socket = null
    pool.shutdownNow()
  }

  /** Address of [rel] (a path inside the project, "" for its root), with the token for the first load. */
  fun urlFor(rel: String): String {
    val path = rel.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
    return "http://$HOST:$port/$path?$TOKEN_PARAM=$token"
  }

  /** Whether [url] is a page of this server (links stay in the Aperçu). */
  fun owns(url: String): Boolean = url.startsWith("http://$HOST:$port/") || url == "http://$HOST:$port"

  private fun acceptLoop(s: ServerSocket) {
    while (!s.isClosed) {
      val client =
        try {
          s.accept()
        } catch (e: IOException) {
          break
        }
      try {
        pool.execute { serve(client) }
      } catch (e: Exception) {
        runCatching { client.close() }
      }
    }
  }

  private fun serve(client: Socket) {
    client.use { c ->
      try {
        c.soTimeout = 10_000
        val input = BufferedInputStream(c.getInputStream())
        val request = readHead(input) ?: return
        val response = respond(request)
        write(c.getOutputStream(), response, request.method == "HEAD")
      } catch (e: SocketException) {
        // The WebView went away.
      } catch (e: IOException) {
        // Idem.
      }
    }
  }

  // ------------------------------------------------------------------------------- HTTP, testable

  data class Request(val method: String, val target: String, val headers: Map<String, String>)

  class Response(
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    /** The body: bytes, or a file streamed as it is. */
    val bytes: ByteArray? = null,
    val file: File? = null,
  ) {
    val length: Long
      get() = file?.length() ?: bytes?.size?.toLong() ?: 0L
  }

  /** The answer to [r] (no network: what the unit tests call). */
  fun respond(r: Request): Response {
    if (r.method != "GET" && r.method != "HEAD") return text(405, "Method Not Allowed", "Méthode non permise.", mapOf("Allow" to "GET, HEAD"))
    val rawPath = r.target.substringBefore('#').substringBefore('?')
    val query = r.target.substringAfter('?', "")
    val queryToken = query.split('&').firstNotNullOfOrNull { kv -> kv.split('=', limit = 2).takeIf { it[0] == TOKEN_PARAM }?.getOrNull(1) }
    val cookieToken =
      r.headers["cookie"]?.split(';')?.firstNotNullOfOrNull { kv -> kv.trim().split('=', limit = 2).takeIf { it[0] == COOKIE }?.getOrNull(1) }
    val tokenOk = constantTimeEquals(queryToken, token) || constantTimeEquals(cookieToken, token)
    if (!tokenOk) return text(403, "Forbidden", "Accès refusé : cette page n'est servie qu'à l'Aperçu d'Altrex.")
    val extra = if (queryToken != null && !constantTimeEquals(cookieToken, token)) {
      mapOf("Set-Cookie" to "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
    } else {
      emptyMap()
    }
    val decoded = decodePath(rawPath) ?: return text(400, "Bad Request", "Adresse invalide.", extra)
    val target = resolve(decoded) ?: return text(404, "Not Found", "Introuvable : $decoded", extra)
    if (target.isDirectory) {
      if (!rawPath.endsWith("/")) {
        val q = if (query.isNotEmpty()) "?$query" else ""
        return Response(301, "Moved Permanently", mapOf("Location" to "$rawPath/$q") + extra + NO_STORE, bytes = ByteArray(0))
      }
      for (name in INDEX_FILES) {
        val index = File(target, name)
        if (index.isFile && inside(index)) return fileResponse(index, extra)
      }
      return Response(200, "OK", mapOf("Content-Type" to "text/html; charset=utf-8") + extra + NO_STORE, bytes = listing(decoded, target).toByteArray())
    }
    return fileResponse(target, extra)
  }

  private fun fileResponse(f: File, extra: Map<String, String>): Response =
    Response(200, "OK", mapOf("Content-Type" to mimeType(f.name)) + extra + NO_STORE, file = f)

  /**
   * The file [path] (decoded, starting with "/") stands for, or null when it does not exist, is
   * hidden, or is outside [root] (`..`, or a link that leads outside: the real path is checked).
   */
  internal fun resolve(path: String): File? {
    if (path.contains('\u0000') || path.contains('\\')) return null
    val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.any { it == ".." || (it.startsWith(".") && it != ".well-known") }) return null
    val f = parts.fold(root) { acc, p -> File(acc, p) }
    if (!f.exists()) return null
    return f.takeIf { inside(it) }
  }

  private fun inside(f: File): Boolean {
    val real = runCatching { f.canonicalFile }.getOrNull() ?: return false
    return real == root || real.path.startsWith(root.path + File.separator)
  }

  private fun listing(path: String, dir: File): String {
    val items =
      dir.listFiles().orEmpty().filter { !it.name.startsWith(".") && inside(it) }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
    return buildString {
      append("<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
      append("<title>").append(escape(path)).append("</title><body style=\"font-family:sans-serif\"><h3>").append(escape(path)).append("</h3><ul>")
      for (f in items) {
        val name = f.name + if (f.isDirectory) "/" else ""
        append("<li><a href=\"").append(escape(encodeSegment(f.name) + if (f.isDirectory) "/" else "")).append("\">").append(escape(name)).append("</a></li>")
      }
      append("</ul></body>")
    }
  }

  private fun text(status: Int, reason: String, message: String, extra: Map<String, String> = emptyMap()) =
    Response(status, reason, mapOf("Content-Type" to "text/plain; charset=utf-8") + extra + NO_STORE, bytes = message.toByteArray())

  private fun write(out: OutputStream, r: Response, headOnly: Boolean) {
    val head = StringBuilder("HTTP/1.1 ${r.status} ${r.reason}\r\n")
    val headers = LinkedHashMap(r.headers)
    headers["Content-Length"] = r.length.toString()
    headers["X-Content-Type-Options"] = "nosniff"
    headers["Connection"] = "close"
    for ((k, v) in headers) head.append(k).append(": ").append(v).append("\r\n")
    head.append("\r\n")
    out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
    if (!headOnly) {
      when {
        r.file != null -> r.file.inputStream().use { it.copyTo(out, 64 * 1024) }
        r.bytes != null -> out.write(r.bytes)
      }
    }
    out.flush()
  }

  companion object {
    const val HOST = "127.0.0.1"
    const val TOKEN_PARAM = "altrex_apercu"
    private const val COOKIE = "altrex_apercu"
    private const val MAX_CLIENTS = 6
    private const val MAX_HEAD = 16 * 1024
    private val INDEX_FILES = listOf("index.html", "index.htm")
    private val NO_STORE = mapOf("Cache-Control" to "no-store")

    fun newToken(): String {
      val b = ByteArray(18)
      SecureRandom().nextBytes(b)
      return b.joinToString("") { "%02x".format(it) }
    }

    private val MIME =
      mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "map" to "application/json; charset=utf-8",
        "webmanifest" to "application/manifest+json; charset=utf-8",
        "svg" to "image/svg+xml",
        "xml" to "application/xml; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "md" to "text/plain; charset=utf-8",
        "csv" to "text/csv; charset=utf-8",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "ico" to "image/x-icon",
        "bmp" to "image/bmp",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "wasm" to "application/wasm",
        "pdf" to "application/pdf",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
      )

    fun mimeType(name: String): String = MIME[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"

    /** Percent-decoding of a path (UTF-8; "+" stays "+"); null when malformed. */
    internal fun decodePath(raw: String): String? {
      if (!raw.startsWith("/")) return null
      val out = java.io.ByteArrayOutputStream()
      var i = 0
      while (i < raw.length) {
        val c = raw[i]
        if (c == '%') {
          if (i + 2 >= raw.length) return null
          val v = raw.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
          out.write(v)
          i += 3
        } else {
          // The head is read byte per char (ISO-8859-1): those chars are the bytes themselves.
          if (c.code < 256) out.write(c.code) else out.write(c.toString().toByteArray(Charsets.UTF_8))
          i++
        }
      }
      return String(out.toByteArray(), Charsets.UTF_8)
    }

    internal fun encodeSegment(s: String): String =
      buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
          val c = b.toInt() and 0xff
          if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-._~!$&'()*+,;=:@") append(c.toChar()) else append("%%%02X".format(c))
        }
      }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun constantTimeEquals(a: String?, b: String): Boolean {
      if (a == null || a.length != b.length) return false
      var d = 0
      for (i in a.indices) d = d or (a[i].code xor b[i].code)
      return d == 0
    }

    /** Request line and headers (names lower-cased); null when the client sent nothing usable. */
    internal fun readHead(input: InputStream): Request? {
      val lines = ArrayList<String>()
      val line = StringBuilder()
      var total = 0
      while (true) {
        val b = input.read()
        if (b < 0) return null
        total++
        if (total > MAX_HEAD) return null
        if (b == '\n'.code) {
          val l = line.toString().trimEnd('\r')
          line.setLength(0)
          if (l.isEmpty()) break
          lines.add(l)
        } else {
          line.append(b.toChar())
        }
      }
      val first = lines.firstOrNull()?.split(' ') ?: return null
      if (first.size < 2) return null
      val headers = HashMap<String, String>()
      for (h in lines.drop(1)) {
        val i = h.indexOf(':')
        if (i > 0) headers[h.substring(0, i).trim().lowercase(Locale.ROOT)] = h.substring(i + 1).trim()
      }
      return Request(first[0].uppercase(Locale.ROOT), first[1], headers)
    }
  }
}
