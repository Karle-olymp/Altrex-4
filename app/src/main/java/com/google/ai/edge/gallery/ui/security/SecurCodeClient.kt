package com.google.ai.edge.gallery.ui.security

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.ai.edge.gallery.ui.llmsingleturn.FileActions
import com.google.ai.edge.gallery.ui.llmsingleturn.ProjectContextBuilder
import com.google.ai.edge.gallery.ui.llmsingleturn.ProjectFolderManager
import com.google.ai.edge.gallery.ui.llmsingleturn.ToolOutcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client of the SecurCode API v1 (POST /api-scan). Reads a folder, a .zip or a single file from the
 * project folder, sends the text files, and condenses the report so that a small model can use it.
 */
object SecurCodeClient {
  private const val API_URL = "https://hpyojztsldlyeqjrudbs.supabase.co/functions/v1/api-scan"
  private const val MAX_FILES = 400
  private const val MAX_FILE_BYTES = 300_000
  private const val MAX_TOTAL_BYTES = 4_000_000
  private const val MAX_ENTRIES = 5_000
  private const val MAX_DEPTH = 10
  private const val PAGE_SIZE = 10

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

  private fun fail(message: String, log: String) = ToolOutcome("Erreur : $message", "✗ $log")

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

  private fun resolve(root: DocumentFile, path: String): DocumentFile? {
    var cur: DocumentFile = root
    if (path.isEmpty()) return cur
    for (seg in path.split('/')) {
      cur = cur.findFile(seg) ?: return null
    }
    return cur
  }

  private fun collectDir(
    context: Context,
    dir: DocumentFile,
    prefix: String,
    depth: Int,
    input: ScanInput,
  ) {
    if (depth > MAX_DEPTH) {
      input.truncated = true
      return
    }
    for (f in dir.listFiles()) {
      if (input.files.size >= MAX_FILES || input.totalBytes >= MAX_TOTAL_BYTES) {
        input.truncated = true
        return
      }
      val name = f.name ?: continue
      val rel = if (prefix.isEmpty()) name else "$prefix/$name"
      if (f.isDirectory) {
        if (keep("$rel/")) collectDir(context, f, rel, depth + 1, input)
      } else if (f.isFile && keep(rel)) {
        val bytes =
          try {
            context.contentResolver.openInputStream(f.uri)?.use { readCapped(it, MAX_FILE_BYTES) }
          } catch (e: Exception) {
            null
          }
        if (bytes == null) input.skipped++ else addFile(input, rel, bytes)
      }
    }
  }

  // The zip is read in memory only: nothing is extracted to disk.
  private fun collectZip(context: Context, uri: Uri, input: ScanInput) {
    context.contentResolver.openInputStream(uri)?.use { raw ->
      ZipInputStream(raw.buffered()).use { zin ->
        var count = 0
        var entry = zin.nextEntry
        while (entry != null) {
          count++
          if (count > MAX_ENTRIES || input.files.size >= MAX_FILES) {
            input.truncated = true
            break
          }
          val name = entry.name.replace('\\', '/')
          if (!entry.isDirectory) {
            val safe = !name.startsWith("/") && name.split('/').none { it == ".." }
            if (!safe) {
              input.skipped++
            } else if (keep(name)) {
              val bytes = readCapped(zin, MAX_FILE_BYTES)
              if (bytes == null) input.skipped++ else addFile(input, name, bytes)
            }
          }
          entry = zin.nextEntry
        }
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

  private fun pageOutcome(c: Cached, page: Int): ToolOutcome {
    val from = (page - 1) * PAGE_SIZE
    if (from >= c.vulns.size) {
      return ToolOutcome(
        "Il n'y a plus de failles à afficher (total : ${c.vulns.size}).",
        "🛡 SecurCode : fin de la liste",
      )
    }
    val items = c.vulns.subList(from, minOf(from + PAGE_SIZE, c.vulns.size))
    val sb = StringBuilder()
    sb.append(c.header).append("\n\n")
    sb.append("Failles ${from + 1} à ${from + items.size} sur ${c.vulns.size} :\n")
    items.forEachIndexed { i, v ->
      val category = v.optString("category", "")
      val verify = if (category == "XSS" || category == "AI_DEEP") ", à vérifier" else ""
      val line = v.optInt("lineNumber", 0)
      val location = v.optString("filePath", "?") + if (line > 0) ":$line" else ""
      sb.append("${from + i + 1}. [${v.optString("severity", "?")}] ")
      sb.append(v.optString("title", "(sans titre)")).append(" — ").append(location)
      sb.append(" ($category$verify)")
      val masked = v.optString("matchedTextMasked", "")
      if (masked.isNotEmpty()) sb.append(" valeur masquée : ").append(masked)
      val rec = v.optString("recommendation", "")
      if (rec.isNotEmpty()) sb.append("\n   Conseil : ").append(rec.take(180))
      sb.append("\n")
    }
    val remaining = c.vulns.size - (from + items.size)
    if (remaining > 0) {
      sb.append("\nIl reste $remaining failles : rappelle securcode_scan avec la même cible et page=")
      sb.append(page + 1).append(" (sans refaire de scan).")
    }
    sb.append("\nLes failles XSS et AI_DEEP sont des pistes à vérifier, pas des certitudes.")
    val log = "🛡 SecurCode : failles ${from + 1}–${from + items.size} / ${c.vulns.size}"
    return ToolOutcome(sb.toString(), log)
  }

  fun scan(context: Context, args: JSONObject): ToolOutcome {
    val raw = args.optString("path", ".")
    val page = args.optInt("page", 1).coerceAtLeast(1)
    val rel =
      FileActions.normalizeDir(raw) ?: return fail("chemin invalide : $raw", "securcode_scan : chemin invalide")
    val root =
      ProjectFolderManager.getSavedProjectFolder(context)
        ?: return fail(
          "aucun dossier de projet n'est choisi. L'utilisateur doit en choisir un.",
          "securcode_scan : aucun dossier de projet",
        )
    val projectId = root.uri.toString()
    val cacheKey = "$projectId|$rel"

    // Next pages of the last scan: served from memory, no new API call.
    if (page > 1) {
      val c = cache
      return if (c != null && c.key == cacheKey) {
        pageOutcome(c, page)
      } else {
        fail(
          "aucun scan récent pour cette cible : appelle d'abord securcode_scan sans page.",
          "securcode_scan : aucun scan en mémoire",
        )
      }
    }

    val key = SecurCodeSettings.getApiKey(context)
    if (key == null) {
      SecurCodeSettings.keyDialogRequest.value = true
      return fail(
        "aucune clé API SecurCode n'est enregistrée. Une fenêtre s'ouvre pour que l'utilisateur la saisisse ; dis-lui de relancer sa demande ensuite.",
        "🔑 Clé API SecurCode requise",
      )
    }
    if (!SecurCodeSettings.isConsented(context, projectId)) {
      SecurCodeSettings.pendingConsent.value = ConsentRequest(projectId, root.name ?: "projet")
      return fail(
        "l'envoi de ce projet à SecurCode n'est pas autorisé. Une fenêtre de confirmation s'affiche ; dis à l'utilisateur de répondre puis de relancer sa demande. Ne prétends pas avoir analysé quoi que ce soit.",
        "🔒 Autorisation d'envoi requise",
      )
    }

    val target =
      resolve(root, rel)
        ?: ProjectContextBuilder.findByName(context, raw.substringAfterLast('/'))?.let {
          resolve(root, it)
        }
        ?: return fail("introuvable : $raw", "securcode_scan : introuvable : $raw")

    val input = ScanInput()
    try {
      val targetName = target.name ?: ""
      if (target.isDirectory) {
        collectDir(context, target, "", 0, input)
      } else if (target.isFile && targetName.endsWith(".zip", ignoreCase = true)) {
        collectZip(context, target.uri, input)
      } else if (target.isFile) {
        val bytes =
          context.contentResolver.openInputStream(target.uri)?.use { readCapped(it, MAX_FILE_BYTES) }
        if (bytes == null) input.skipped++ else addFile(input, if (rel.isEmpty()) targetName else rel, bytes)
      } else {
        return fail("« $raw » n'est ni un dossier, ni un zip, ni un fichier.", "securcode_scan : cible invalide")
      }
    } catch (e: Exception) {
      return fail("lecture impossible : ${e.message}", "securcode_scan : lecture impossible")
    }
    if (input.files.isEmpty()) {
      return fail("aucun fichier texte exploitable trouvé.", "securcode_scan : aucun fichier texte")
    }

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
      return fail(
        "réseau indisponible ou délai dépassé (${e.message}).",
        "✗ SecurCode : réseau indisponible",
      )
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
          fail(
            "clé API SecurCode invalide ou révoquée. Une fenêtre s'ouvre pour la remplacer.",
            "✗ SecurCode : clé invalide ou révoquée",
          )
        }
        429 ->
          fail(
            "quota quotidien SecurCode atteint (réinitialisé à minuit UTC). Ne relance pas de scan aujourd'hui.",
            "✗ SecurCode : quota quotidien atteint",
          )
        400 -> fail("requête refusée par SecurCode : $apiMessage", "✗ SecurCode : requête refusée")
        else -> fail("erreur SecurCode ($code) $apiMessage".trim(), "✗ SecurCode : erreur $code")
      }
    }

    val report =
      try {
        JSONObject(text)
      } catch (e: Exception) {
        return fail("réponse SecurCode illisible.", "✗ SecurCode : réponse illisible")
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

    val outcome = pageOutcome(c, 1)
    val log =
      "🛡 SecurCode : score ${if (score >= 0) score else "?"}/100 — $counts" +
        (if (quotaText.isNotEmpty()) " ($quotaText)" else "") +
        (if (input.truncated) " ⚠ projet tronqué" else "")
    return ToolOutcome(outcome.result, log)
  }
}
