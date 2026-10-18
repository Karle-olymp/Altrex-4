package com.google.ai.edge.gallery.ui.github

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/** A repository as listed by GitHub. */
data class RepoInfo(
  val fullName: String,
  val isPrivate: Boolean,
  val defaultBranch: String,
  val description: String,
  val sizeKb: Long,
)

/** Result of an import into the project folder. */
data class ImportResult(val folder: String, val files: Int, val skipped: Int, val truncated: Boolean)

/** An error to show to the user as is (French). */
class GitHubException(message: String) : IOException(message)

/**
 * [ALTREX] GitHub connector: lists the user's repositories and downloads one as an archive into
 * the project folder. Read-only: nothing is ever sent to GitHub except the requests themselves.
 *
 * - The token goes only to api.github.com. The archive link GitHub redirects to (codeload) is
 *   followed by hand, without the token.
 * - The archive is capped in size, files and per-file size, and every path is checked before
 *   anything is written (no "..", no absolute path, nothing outside the new folder).
 */
object GitHubClient {
  private const val TAG = "AGGitHub"
  private const val API = "https://api.github.com"
  private val DOWNLOAD_HOSTS = setOf("codeload.github.com")
  private const val MAX_ZIP_BYTES = 150L * 1024 * 1024
  private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
  private const val MAX_FILE_BYTES = 10L * 1024 * 1024
  private const val MAX_FILES = 4_000
  private const val MAX_JSON_BYTES = 2 * 1024 * 1024

  private fun open(url: String, token: String?): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    conn.setRequestProperty("User-Agent", "Altrex")
    // Only API requests carry the token (the URL always starts with the API's address).
    if (token != null && url.startsWith("$API/")) conn.setRequestProperty("Authorization", "Bearer $token")
    return conn
  }

  private fun errorFor(conn: HttpURLConnection, what: String, withToken: Boolean): GitHubException {
    val code = conn.responseCode
    val message =
      when {
        code == 401 -> "GitHub a refusé le jeton (expiré ou révoqué). Change-le dans Réglages → Clés d'API."
        code == 403 && conn.getHeaderField("X-RateLimit-Remaining") == "0" ->
          if (withToken) "limite de requêtes GitHub atteinte, réessaie dans une heure."
          else "limite de requêtes GitHub atteinte (60 par heure sans jeton). Ajoute un jeton dans Réglages → Clés d'API."
        code == 403 -> "accès refusé par GitHub à $what (le jeton n'a pas le droit de lire ce dépôt ?)."
        code == 404 ->
          if (withToken) "$what introuvable (nom, branche, ou dépôt non autorisé pour ce jeton)."
          else "$what introuvable. S'il est privé, ajoute un jeton GitHub dans Réglages → Clés d'API."
        else -> "GitHub a répondu $code pour $what."
      }
    return GitHubException(message)
  }

  private fun readCapped(input: InputStream, cap: Int): String {
    val buf = ByteArray(64 * 1024)
    val out = java.io.ByteArrayOutputStream()
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      out.write(buf, 0, n)
      if (out.size() > cap) throw GitHubException("réponse de GitHub trop grosse.")
    }
    return out.toString("UTF-8")
  }

  private fun getJson(path: String, token: String?, what: String): String {
    val conn = open("$API$path", token)
    try {
      if (conn.responseCode != 200) throw errorFor(conn, what, token != null)
      return conn.inputStream.use { readCapped(it, MAX_JSON_BYTES) }
    } finally {
      conn.disconnect()
    }
  }

  private fun repoInfo(o: JSONObject) =
    RepoInfo(
      fullName = o.optString("full_name"),
      isPrivate = o.optBoolean("private"),
      defaultBranch = o.optString("default_branch", "main"),
      description = o.optString("description").takeIf { it != "null" }.orEmpty(),
      sizeKb = o.optLong("size"),
    )

  /** The repositories of the token's owner (and those shared with them), most recent first. */
  fun listRepos(token: String): List<RepoInfo> {
    val body = getJson("/user/repos?per_page=100&sort=pushed", token, "la liste de tes dépôts")
    val arr = JSONArray(body)
    return (0 until arr.length()).map { repoInfo(arr.getJSONObject(it)) }.filter { it.fullName.isNotEmpty() }
  }

  /** Details of one repository (public, or private and allowed for the token). */
  fun getRepo(repo: RepoRef, token: String?): RepoInfo =
    repoInfo(JSONObject(getJson("/repos/${repo.owner}/${repo.name}", token, "le dépôt ${repo.fullName}")))

  /** Downloads the archive of [repo] at [ref] into [dest]. [onProgress] gets the bytes received. */
  fun download(repo: RepoRef, ref: String, token: String?, dest: File, onProgress: (Long) -> Unit) {
    if (!GitHubPaths.isValidRef(ref)) throw GitHubException("nom de branche invalide : $ref")
    val first = open("$API/repos/${repo.owner}/${repo.name}/zipball/$ref", token)
    val location: String
    try {
      when (first.responseCode) {
        301, 302, 303, 307, 308 -> location = first.getHeaderField("Location") ?: throw GitHubException("lien d'archive manquant.")
        else -> throw errorFor(first, "la branche « $ref » de ${repo.fullName}", token != null)
      }
    } finally {
      first.disconnect()
    }
    val url = URL(location)
    if (url.protocol != "https" || url.host !in DOWNLOAD_HOSTS) {
      throw GitHubException("lien d'archive inattendu (${url.host}), téléchargement refusé.")
    }
    // The archive link is temporary and signed: no token here.
    val conn = open(location, null)
    try {
      if (conn.responseCode != 200) throw GitHubException("le téléchargement a échoué (${conn.responseCode}).")
      var received = 0L
      conn.inputStream.use { input ->
        dest.outputStream().use { out ->
          val buf = ByteArray(256 * 1024)
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            received += n
            if (received > MAX_ZIP_BYTES) throw GitHubException("dépôt trop gros (plus de 150 Mo).")
            out.write(buf, 0, n)
            onProgress(received)
          }
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  /**
   * Writes the archive [zip] into a new folder of [parent] named after the repository.
   * [onFolderCreated] gets the folder's name as soon as it exists, [onProgress] the number of
   * files written so far.
   */
  fun extract(
    context: Context,
    zip: File,
    parent: DocumentFile,
    repo: RepoRef,
    onFolderCreated: (String) -> Unit,
    onProgress: (Int) -> Unit,
  ): ImportResult {
    val taken = parent.listFiles().mapNotNull { it.name }.toSet()
    val folderName = GitHubPaths.folderName(repo, taken)
    val target = parent.createDirectory(folderName) ?: throw GitHubException("impossible de créer le dossier « $folderName ».")
    // Marked before the first file is written, so that a cancelled copy is marked too.
    onFolderCreated(folderName)
    val dirs = HashMap<String, DocumentFile>()
    dirs[""] = target
    fun dir(path: String): DocumentFile {
      dirs[path]?.let { return it }
      val up = dir(path.substringBeforeLast('/', ""))
      val created = up.createDirectory(path.substringAfterLast('/')) ?: throw GitHubException("impossible de créer « $path ».")
      dirs[path] = created
      return created
    }

    var files = 0
    var skipped = 0
    var total = 0L
    var truncated = false
    ZipFile(zip).use { archive ->
      for (entry in archive.entries()) {
        val path = GitHubPaths.entryPath(entry.name) ?: continue
        if (entry.isDirectory) {
          dir(path)
          continue
        }
        if (files >= MAX_FILES || total >= MAX_TOTAL_BYTES) {
          truncated = true
          break
        }
        if (entry.size > MAX_FILE_BYTES) {
          skipped++
          continue
        }
        val folder = dir(path.substringBeforeLast('/', ""))
        val doc = folder.createFile("application/octet-stream", path.substringAfterLast('/'))
        if (doc == null) {
          skipped++
          continue
        }
        val written =
          try {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
              archive.getInputStream(entry).use { copyCapped(it, out, MAX_FILE_BYTES) }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Cannot write $path", e)
            null
          }
        if (written == null) {
          // Too big once decompressed (the size in the archive can lie) or not writable.
          doc.delete()
          skipped++
          continue
        }
        total += written
        files++
        if (files % 20 == 0) onProgress(files)
      }
    }
    onProgress(files)
    return ImportResult(folderName, files, skipped, truncated)
  }

  /** Copies at most [cap] bytes; returns the size, or null when the input is bigger. */
  private fun copyCapped(input: InputStream, out: OutputStream, cap: Long): Long? {
    val buf = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
      val n = input.read(buf)
      if (n < 0) return size
      size += n
      if (size > cap) return null
      out.write(buf, 0, n)
    }
  }
}
