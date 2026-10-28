package com.google.ai.edge.gallery.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGLinuxEnv"

/**
 * [ALTREX] A Linux environment (Alpine) inside the app, without root.
 *
 * Programs run through PRoot (GPL-2.0, a separate program shipped in the APK as libproot.so, see
 * THIRD_PARTY_NOTICES): it gives each process the Alpine files as its root and translates the system
 * calls. Android (apps targeting API 29+) refuses to execute files the app wrote itself, so PRoot
 * and its loader live in the APK's native library folder (the only executable place), and the loader
 * maps each Alpine program into memory instead of executing it from the app's files.
 *
 * Two separate environments: [USER] (the user's terminal) and [AGENT] (the model's), so that what
 * the model installs or breaks never touches the user's.
 */
class LinuxEnv private constructor(private val context: Context, val name: String) {
  companion object {
    const val USER = "user"
    const val AGENT = "agent"

    // Alpine Linux minirootfs, pinned and checked (packages: MIT, GPL and others, see Alpine).
    const val ALPINE_VERSION = "3.24.3"
    private const val ALPINE_URL =
      "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.3-aarch64.tar.gz"
    private const val ALPINE_SHA256 = "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50"
    private const val ALPINE_MAX_BYTES = 32L * 1024 * 1024

    const val DEFAULT_TIMEOUT_MS = 120_000L
    /** Output kept from one command (the rest is cut). */
    const val MAX_OUTPUT_CHARS = 200_000

    fun of(context: Context, name: String): LinuxEnv = LinuxEnv(context.applicationContext, name)
  }

  private val baseDir = File(context.filesDir, "linux/$name")
  val rootfs = File(baseDir, "rootfs")
  private val marker = File(baseDir, ".installed-$ALPINE_VERSION")

  val isInstalled: Boolean
    get() = marker.exists() && File(rootfs, "bin/busybox").exists()

  private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
  private val proot = File(nativeDir, "libproot.so")
  private val loader = File(nativeDir, "libproot-loader.so")

  /** PRoot and its loader are in the APK for this device (arm64 only). */
  val isSupported: Boolean
    get() = proot.exists() && loader.exists()

  /** Downloads, checks and unpacks Alpine. [progress] gets a short French status. */
  suspend fun install(progress: (String) -> Unit): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        if (!isSupported) throw IOException("Linux n'est pas disponible sur cet appareil (arm64 requis).")
        progress("Téléchargement d'Alpine $ALPINE_VERSION…")
        val archive = File(context.cacheDir, "alpine-$ALPINE_VERSION.tar.gz")
        download(ALPINE_URL, archive)
        progress("Vérification…")
        val sha = sha256(archive)
        if (!sha.equals(ALPINE_SHA256, ignoreCase = true)) {
          archive.delete()
          throw IOException("Archive Alpine altérée (empreinte inattendue), installation annulée.")
        }
        progress("Installation…")
        val tmp = File(baseDir, "rootfs.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        GZIPInputStream(archive.inputStream().buffered()).use { Tar.extract(it, tmp) }
        archive.delete()
        // Android has no /etc/resolv.conf to bind: give Alpine public resolvers.
        File(tmp, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(tmp, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(tmp, "root").mkdirs()
        File(tmp, "tmp").mkdirs()
        rootfs.deleteRecursively()
        if (!tmp.renameTo(rootfs)) throw IOException("Installation impossible (renommage).")
        baseDir.listFiles { f -> f.name.startsWith(".installed-") }?.forEach { it.delete() }
        marker.createNewFile()
        progress("Prêt.")
      }
    }

  /** Removes the environment (everything installed in it). */
  suspend fun uninstall() = withContext(Dispatchers.IO) { baseDir.deleteRecursively() }

  /** Exit code and output (stdout and stderr together) of one command. */
  data class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean)

  /**
   * Runs [command] with /bin/sh -lc in the environment, from /root. [onOutput] receives the output
   * as it comes. Network access is the app's (the model's environment adds its own rules).
   */
  suspend fun run(
    command: String,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    onOutput: (String) -> Unit = {},
  ): CommandResult =
    withContext(Dispatchers.IO) {
      check(isInstalled) { "Environnement Linux non installé." }
      val process = start(listOf("/bin/sh", "-lc", command))
      val out = StringBuilder()
      val reader = Thread {
        try {
          process.inputStream.bufferedReader().use { r ->
            val buf = CharArray(4096)
            while (true) {
              val n = r.read(buf)
              if (n < 0) break
              val chunk = String(buf, 0, n)
              synchronized(out) { if (out.length < MAX_OUTPUT_CHARS) out.append(chunk) }
              onOutput(chunk)
            }
          }
        } catch (e: IOException) {
          // Process killed.
        }
      }
      reader.start()
      val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
      if (!finished) process.destroyForcibly()
      reader.join(2_000)
      val text = synchronized(out) { out.toString() }
      CommandResult(
        exitCode = if (finished) process.exitValue() else -1,
        output = if (text.length >= MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) + "\n[… sortie coupée …]" else text,
        timedOut = !finished,
      )
    }

  /** How to launch a program inside the environment: PRoot, its arguments, the environment. */
  data class Launch(val executable: String, val args: List<String>, val env: Map<String, String>)

  /** [argv] run by PRoot with the Alpine files as its root, from /root. */
  fun launch(argv: List<String>): Launch {
    val tmp = File(context.cacheDir, "proot-tmp-$name").apply { mkdirs() }
    val args =
      listOf(
        "--kill-on-exit",
        "--link2symlink",
        "-0",
        "-r", rootfs.absolutePath,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-w", "/root",
      ) + argv
    val env =
      linkedMapOf(
        "PROOT_LOADER" to loader.absolutePath,
        "PROOT_TMP_DIR" to tmp.absolutePath,
        "HOME" to "/root",
        "USER" to "root",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
      )
    return Launch(proot.absolutePath, args, env)
  }

  /** Starts [argv] inside the environment (stdin/stdout/stderr pipes, stderr merged). */
  fun start(argv: List<String>): Process {
    val l = launch(argv)
    val pb = ProcessBuilder(listOf(l.executable) + l.args).redirectErrorStream(true).directory(rootfs)
    pb.environment().apply {
      clear()
      putAll(l.env)
    }
    Log.d(TAG, "[$name] start ${argv.firstOrNull()}")
    return pb.start()
  }

  private fun download(url: String, target: File) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 20_000
    conn.readTimeout = 60_000
    try {
      if (conn.responseCode != 200) throw IOException("Téléchargement d'Alpine impossible (${conn.responseCode}).")
      var total = 0L
      conn.inputStream.use { input ->
        target.outputStream().use { output ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > ALPINE_MAX_BYTES) throw IOException("Archive Alpine trop grosse.")
            output.write(buf, 0, n)
          }
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}

/**
 * [ALTREX] Minimal tar reader for the Alpine archive: files, folders, symbolic and hard links, with
 * the ustar prefix, PAX and GNU long names. Every path is checked to stay inside the target folder.
 */
internal object Tar {
  private const val BLOCK = 512

  fun extract(input: InputStream, target: File) {
    val root = target.canonicalFile
    val header = ByteArray(BLOCK)
    var paxPath: String? = null
    var paxLink: String? = null
    var longName: String? = null
    var longLink: String? = null
    while (true) {
      if (!readFully(input, header)) break
      if (header.all { it == 0.toByte() }) break
      val size = parseOctal(header, 124, 12)
      val type = header[156].toInt().toChar()
      var name = str(header, 0, 100)
      val prefix = if (str(header, 257, 6).startsWith("ustar")) str(header, 345, 155) else ""
      if (prefix.isNotEmpty()) name = "$prefix/$name"
      var link = str(header, 157, 100)
      val mode = parseOctal(header, 100, 8).toInt()
      when (type) {
        'x' -> {
          val pax = parsePax(String(readBytes(input, size), Charsets.UTF_8))
          paxPath = pax["path"]
          paxLink = pax["linkpath"]
          continue
        }
        'g' -> {
          skip(input, padded(size))
          continue
        }
        'L' -> {
          longName = String(readBytes(input, size), Charsets.UTF_8).trimEnd('\u0000')
          continue
        }
        'K' -> {
          longLink = String(readBytes(input, size), Charsets.UTF_8).trimEnd('\u0000')
          continue
        }
      }
      name = paxPath ?: longName ?: name
      link = paxLink ?: longLink ?: link
      paxPath = null
      paxLink = null
      longName = null
      longLink = null
      val out = safeFile(root, name)
      when (type) {
        '5' -> {
          out.mkdirs()
          chmod(out, mode)
        }
        '2' -> {
          out.parentFile?.mkdirs()
          out.delete()
          Os.symlink(link, out.path)
        }
        '1' -> {
          out.parentFile?.mkdirs()
          safeFile(root, link).copyTo(out, overwrite = true)
          chmod(out, mode)
        }
        '0', '\u0000', '7' -> {
          out.parentFile?.mkdirs()
          out.outputStream().use { o -> copy(input, o, size) }
          skip(input, padded(size) - size)
          chmod(out, mode)
          continue
        }
        else -> Unit
      }
      skip(input, padded(size))
    }
  }

  private fun safeFile(root: File, name: String): File {
    val clean = name.removePrefix("./").trimEnd('/')
    val f = File(root, clean)
    val parent = (f.parentFile ?: root).canonicalFile
    if (!parent.path.startsWith(root.path)) throw IOException("Chemin hors de l'archive : $name")
    return File(parent, f.name)
  }

  private fun chmod(f: File, mode: Int) {
    runCatching { Os.chmod(f.path, mode and 0x1ff) }
  }

  internal fun parsePax(text: String): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    val bytes = text.toByteArray(Charsets.UTF_8)
    while (i < bytes.size) {
      val sp = (i until bytes.size).firstOrNull { bytes[it] == ' '.code.toByte() } ?: break
      val len = String(bytes, i, sp - i).toIntOrNull() ?: break
      val record = String(bytes, sp + 1, len - (sp - i) - 2, Charsets.UTF_8)
      val eq = record.indexOf('=')
      if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
      i += len
    }
    return out
  }

  internal fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
    var v = 0L
    for (i in off until off + len) {
      val c = b[i].toInt()
      if (c == 0 || c == ' '.code) {
        if (v != 0L) break else continue
      }
      v = v * 8 + (c - '0'.code)
    }
    return v
  }

  private fun str(b: ByteArray, off: Int, len: Int): String {
    var end = off
    while (end < off + len && b[end] != 0.toByte()) end++
    return String(b, off, end - off, Charsets.UTF_8)
  }

  private fun padded(size: Long) = (size + BLOCK - 1) / BLOCK * BLOCK

  private fun readFully(input: InputStream, buf: ByteArray): Boolean {
    var read = 0
    while (read < buf.size) {
      val n = input.read(buf, read, buf.size - read)
      if (n < 0) {
        if (read == 0) return false
        throw IOException("Archive tronquée.")
      }
      read += n
    }
    return true
  }

  private fun readBytes(input: InputStream, size: Long): ByteArray {
    if (size > 1_000_000) throw IOException("En-tête d'archive trop grand.")
    val b = ByteArray(size.toInt())
    readFully(input, b)
    skip(input, padded(size) - size)
    return b
  }

  private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
    var left = size
    val buf = ByteArray(64 * 1024)
    while (left > 0) {
      val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
      if (n < 0) throw IOException("Archive tronquée.")
      out.write(buf, 0, n)
      left -= n
    }
  }

  private fun skip(input: InputStream, n: Long) {
    var left = n
    val buf = ByteArray(BLOCK)
    while (left > 0) {
      val r = input.read(buf, 0, minOf(BLOCK.toLong(), left).toInt())
      if (r < 0) return
      left -= r
    }
  }
}
