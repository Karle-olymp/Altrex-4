package com.google.ai.edge.gallery.ui.security

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client of the SecurCode API v1 (POST /api-scan). Reads a folder, a .zip or a single file of a
 * Forge project, sends the text files, and condenses the report. [ALTREX] Forge's `/scan`: the
 * app shows the report itself (no model in between), after the user's consent for the project.
 */
object SecurCodeClient {
  private const val API_URL = "https://hpyojztsldlyeqjrudbs.supabase.co/functions/v1/api-scan"
  private const val MAX_FILES = 400
  private const val MAX_FILE_BYTES = 300_000
  private const val MAX_TOTAL_BYTES = 4_000_000
  private const val MAX_DEPTH = 10
  private const val PAGE_SIZE = 10

  /** What the screen shows: the report, or why there is none. */
  data class ScanOutcome(val text: String, val ok: Boolean)

  private val SKIP_PARTS = listOf("node_modules/", ".git/", "dist/")
  private val BINARY_EXT =
    listOf(
      ".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".zip", ".woff", ".woff2", ".ttf", ".map",
      ".lock",
    )

  private class ScanInput {
    val files = ArrayList<Pair<String, String>>()
    var skipped = 0
    var truncated = false
    var totalBytes = 0
  }

  private class Cached(val key: String, val vulns: List<JSONObject>, val header: String)

  @Volatile private var cache: Cached? = null

  private fun fail(message: String) = ScanOutcome("✗ $message", ok = false)

  private fun addFile(input: ScanInput, path: String, bytes: ByteArray) {
    if (input.files.size >= MAX_FILES || input.totalBytes + bytes.size > MAX_TOTAL_BYTES) {
      input.truncated = true
      return
    }
    val text = decodeStrict(bytes)
    if (text == null || text.isBlank() || text.indexOf('\u0000') >= 0) {
      input.skipped++
      return
    }
    input.files.add(path to text)
    input.totalBytes += bytes.size
  }

  private fun keep(name: String): Boolean {
    val lower = name.lowercase()
    return SKIP_PARTS.none { lower.contains(it) } && BINARY_EXT.none { lower.endsWith(it) }
  }

  private fun decodeStrict(bytes: ByteArray): String? =
    try {
      Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    } catch (e: Exception) {
      null
    }

  /** Reads at most [cap] bytes; returns null if the stream is longer. */
  private fun readCapped(input: InputStream, cap: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      total += n
      if (total > cap) return null
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  private fun collectDir(dir: File, prefix: String, depth: Int, input: ScanInput) {
    if (depth > MAX_DEPTH) {
      input.truncated = true
      return
    }
    for (f in dir.listFiles()?.sortedBy { it.name }.orEmpty()) {
      if (input.files.size >= MAX_FILES || input.totalBytes >= MAX_TOTAL_BYTES) {
        input.truncated = true
        return
      }
      // A link is not followed: it could lead out of the project.
      if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
      val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
      if (f.isDirectory) {
        if (keep("$rel/") && f.name != ".altrex") collectDir(f, rel, depth + 1, input)
      } else if (f.isFile && keep(rel)) {
        val bytes = runCatching { f.inputStream().use { readCapped(it, MAX_FILE_BYTES) } }.getOrNull()
        if (bytes == null) input.skipped++ else addFile(input, rel, bytes)
      }
    }
  }

  private fun severityRank(s: String): Int =
    when (s.uppercase()) {
      "CRITICAL" -> 0
      "HIGH" -> 1
      "MEDIUM" -> 2
      else -> 3
    }

  /** Page [page] of the cached report, as the user reads it. */
  private fun pageOutcome(c: Cached, page: Int): ScanOutcome {
    val from = (page - 1) * PAGE_SIZE
    if (c.vulns.isEmpty()) return ScanOutcome("🛡 " + c.header + "\n\nAucune faille détectée.", ok = true)
    if (from >= c.vulns.size) return ScanOutcome("Il n'y a plus de failles à afficher (total : ${c.vulns.size}).", ok = true)
    val items = c.vulns.subList(from, minOf(from + PAGE_SIZE, c.vulns.size))
    val body = StringBuilder()
    items.forEachIndexed { i, v ->
      val category = v.optString("category", "")
      val verify = if (category == "XSS" || category == "AI_DEEP") ", à vérifier" else ""
      val line = v.optInt("lineNumber", 0)
      val location = v.optString("filePath", "?") + if (line > 0) ":$line" else ""
      body.append("${from + i + 1}. [${v.optString("severity", "?")}] ")
      body.append(v.optString("title", "(sans titre)")).append(" — ").append(location)
      body.append(" ($category$verify)")
      val masked = v.optString("matchedTextMasked", "")
      if (masked.isNotEmpty()) body.append(" valeur masquée : ").append(masked)
      val rec = v.optString("recommendation", "")
      if (rec.isNotEmpty()) body.append("\n   Conseil : ").append(rec.take(180))
      body.append("\n")
    }
    val remaining = c.vulns.size - (from + items.size)
    val out = StringBuilder()
    out.append("🛡 ").append(c.header).append("\n\n")
    out.append("Failles ${from + 1} à ${from + items.size} sur ${c.vulns.size} :\n").append(body)
    if (remaining > 0) out.append("\nIl reste $remaining failles : tape « /scan ${page + 1} » pour la suite.")
    out.append("\nLes failles XSS et AI_DEEP sont des pistes à vérifier, pas des certitudes.")
    return ScanOutcome(out.toString().trimEnd(), ok = true)
  }

  /**
   * Scans the Forge project in [root] ([projectId] keys the user's consent, [label] names it).
   * Page 2 and after come from the last report, without a new request.
   */
  fun scanProject(context: Context, root: File, projectId: String, label: String, page: Int = 1): ScanOutcome {
    val cacheKey = projectId
    if (page > 1) {
      val c = cache
      return if (c != null && c.key == cacheKey) pageOutcome(c, page) else fail("aucun scan récent de ce projet : lance d'abord /scan.")
    }
    val key = SecurCodeSettings.getApiKey(context)
    if (key == null) {
      SecurCodeSettings.keyDialogRequest.value = true
      return fail("Clé API SecurCode requise : saisis-la dans la fenêtre, puis relance /scan.")
    }
    if (!SecurCodeSettings.isConsented(context, projectId)) {
      SecurCodeSettings.pendingConsent.value = ConsentRequest(projectId, label)
      return fail("Autorisation d'envoi requise : réponds dans la fenêtre, puis relance /scan.")
    }
    if (!root.isDirectory) return fail("dossier du projet introuvable.")
    val input = ScanInput()
    try {
      collectDir(root, "", 0, input)
    } catch (e: Exception) {
      return fail("lecture impossible : ${e.message}")
    }
    if (input.files.isEmpty()) return fail("aucun fichier texte exploitable trouvé.")
    return send(key, input, cacheKey, label)
  }

  private fun send(key: String, input: ScanInput, cacheKey: String, raw: String): ScanOutcome {
    val filesJson = JSONArray()
    for ((p, c) in input.files) {
      filesJson.put(JSONObject().put("path", p).put("content", c))
    }
    val body = JSONObject().put("files", filesJson).toString()

    val conn = URL(API_URL).openConnection() as HttpURLConnection
    var code = -1
    var text = ""
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 20_000
      conn.readTimeout = 180_000
      conn.doOutput = true
      conn.setRequestProperty("Authorization", "Bearer $key")
      conn.setRequestProperty("Content-Type", "application/json")
      conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      text = stream?.use { it.bufferedReader().readText() } ?: ""
    } catch (e: IOException) {
      return fail("SecurCode injoignable (réseau ou délai dépassé) : vérifie ta connexion et réessaie.")
    } finally {
      conn.disconnect()
    }

    if (code != 200) {
      val apiMessage =
        try {
          JSONObject(text).optString("error", "")
        } catch (e: Exception) {
          ""
        }
      return when (code) {
        401 -> {
          SecurCodeSettings.keyDialogRequest.value = true
          fail("Clé SecurCode invalide ou révoquée : remplace-la dans la fenêtre, puis relance /scan.")
        }
        429 -> fail("Quota quotidien SecurCode atteint (20 analyses par jour) : réessaie demain.")
        400 -> fail("requête refusée par SecurCode : $apiMessage")
        else -> fail("erreur SecurCode ($code) $apiMessage".trim())
      }
    }

    val report =
      try {
        JSONObject(text)
      } catch (e: Exception) {
        return fail("réponse SecurCode illisible.")
      }
    val summary = report.optJSONObject("summary") ?: JSONObject()
    val vulnArray = report.optJSONArray("vulnerabilities") ?: JSONArray()
    val vulns = ArrayList<JSONObject>()
    for (i in 0 until vulnArray.length()) {
      vulnArray.optJSONObject(i)?.let { vulns.add(it) }
    }
    val sorted = vulns.sortedBy { severityRank(it.optString("severity", "")) }

    val score = report.optInt("score", -1)
    val quota = report.optJSONObject("quota")
    val quotaText =
      if (quota != null) "${quota.optInt("remaining", 0)}/${quota.optInt("max", 0)} analyses restantes aujourd'hui"
      else ""
    val counts =
      "${summary.optInt("criticalCount", 0)} critique(s), ${summary.optInt("highCount", 0)} élevée(s), " +
        "${summary.optInt("mediumCount", 0)} moyenne(s), ${summary.optInt("lowCount", 0)} faible(s)"
    val notes = StringBuilder()
    if (input.skipped > 0) notes.append(" ${input.skipped} fichier(s) ignoré(s) (binaire ou trop gros).")
    if (input.truncated) notes.append(" Projet tronqué (limites d'envoi) : seule une partie a été analysée.")
    val header =
      "Rapport SecurCode pour « ${raw.ifEmpty { "." }} » : score ${if (score >= 0) score else "?"}/100 " +
        "(100 = aucune faille), $counts, ${report.optInt("totalFilesScanned", input.files.size)} fichier(s) " +
        "analysé(s). $quotaText.$notes"
    val c = Cached(cacheKey, sorted, header)
    cache = c

    return pageOutcome(c, 1)
  }
}
