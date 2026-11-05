package com.google.ai.edge.gallery.forge

import com.google.ai.edge.gallery.forge.preview.PreviewServer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Aperçu's web server: MIME types, index, 404, path traversal, links out of the project, token. */
class PreviewServerTest {
  private val base = Files.createTempDirectory("apercu").toFile()
  private val root = File(base, "projet").apply { mkdirs() }
  private val secret = File(base, "secret.txt").apply { writeText("clé d'API") }
  private val server = PreviewServer(root, token = "t0k3n")

  init {
    File(root, "index.html").writeText("<link rel=stylesheet href=public/style.css><h1>Accueil</h1>")
    File(root, "public").mkdirs()
    File(root, "public/style.css").writeText("body{color:#123}")
    File(root, "public/app.js").writeText("console.log(1)")
    File(root, "logo.svg").writeText("<svg/>")
    File(root, "img").mkdirs()
    File(root, "img/a b.png").writeBytes(byteArrayOf(1, 2, 3))
    File(root, "docs").mkdirs()
    File(root, "docs/notes.txt").writeText("n")
    File(root, ".env").writeText("KEY=secret")
    File(root, ".git").mkdirs()
    File(root, ".git/config").writeText("[core]")
  }

  private fun get(target: String, cookie: Boolean = true, method: String = "GET"): PreviewServer.Response =
    server.respond(PreviewServer.Request(method, target, if (cookie) mapOf("cookie" to "x=1; altrex_apercu=t0k3n") else emptyMap()))

  private fun body(r: PreviewServer.Response): String = r.file?.readText() ?: String(r.bytes ?: ByteArray(0))

  @Test
  fun mimeTypes_andIndex() {
    assertEquals("text/html; charset=utf-8", get("/").headers["Content-Type"])
    assertTrue(body(get("/")).contains("Accueil"))
    assertEquals("text/css; charset=utf-8", get("/public/style.css").headers["Content-Type"])
    assertEquals("text/javascript; charset=utf-8", get("/public/app.js").headers["Content-Type"])
    assertEquals("image/svg+xml", get("/logo.svg").headers["Content-Type"])
    assertEquals("image/png", get("/img/a%20b.png").headers["Content-Type"])
    assertEquals(3L, get("/img/a%20b.png").length)
    assertEquals("application/octet-stream", PreviewServer.mimeType("x.bin"))
    assertEquals("no-store", get("/").headers["Cache-Control"])
    // A folder without index: a listing; without its final « / »: redirected (relative links).
    val dir = get("/docs/")
    assertEquals(200, dir.status)
    assertTrue(body(dir).contains("notes.txt"))
    val redirect = get("/docs?altrex_apercu=t0k3n")
    assertEquals(301, redirect.status)
    assertEquals("/docs/?altrex_apercu=t0k3n", redirect.headers["Location"])
    assertEquals(405, get("/", method = "POST").status)
    assertEquals(200, get("/", method = "HEAD").status)
  }

  @Test
  fun notFound_andHiddenFiles() {
    assertEquals(404, get("/absent.html").status)
    assertEquals(404, get("/.env").status)
    assertEquals(404, get("/.git/config").status)
    assertFalse(body(get("/")).contains(".env"))
  }

  @Test
  fun traversal_isRefused() {
    for (t in listOf("/../secret.txt", "/public/../../secret.txt", "/%2e%2e/secret.txt", "/%2E%2E%2Fsecret.txt", "/..%5csecret.txt", "/a%00.html", "secret.txt")) {
      val r = get(t)
      assertTrue("$t → ${r.status}", r.status == 404 || r.status == 400)
      assertFalse(t, body(r).contains("clé"))
    }
    assertNull(server.resolve("/../secret.txt"))
  }

  @Test
  fun symlinkOutOfTheProject_isRefused_insideIsServed() {
    val out = File(root, "fuite.txt").toPath()
    val inside = File(root, "lien.css").toPath()
    try {
      Files.createSymbolicLink(out, secret.toPath())
      Files.createSymbolicLink(inside, File(root, "public/style.css").toPath())
      Files.createSymbolicLink(File(root, "dehors").toPath(), base.toPath())
    } catch (e: Exception) {
      return // No symbolic links on this file system.
    }
    assertEquals(404, get("/fuite.txt").status)
    assertEquals(404, get("/dehors/secret.txt").status)
    assertEquals(200, get("/lien.css").status)
    assertFalse(body(get("/")).contains("fuite"))
  }

  @Test
  fun token_isRequired_andBecomesACookie() {
    assertEquals(403, get("/", cookie = false).status)
    val first = get("/?altrex_apercu=t0k3n", cookie = false)
    assertEquals(200, first.status)
    assertTrue(first.headers["Set-Cookie"]!!.startsWith("altrex_apercu=t0k3n;"))
    assertTrue(first.headers["Set-Cookie"]!!.contains("HttpOnly"))
    assertEquals(403, get("/?altrex_apercu=faux", cookie = false).status)
    assertTrue(server.urlFor("img/a b.png").endsWith("/img/a%20b.png?altrex_apercu=t0k3n"))
  }

  @Test
  fun realSocket_onLoopbackOnly() {
    server.start()
    try {
      assertTrue(server.port > 0)
      val conn = URL(server.urlFor("public/style.css")).openConnection() as HttpURLConnection
      assertEquals(200, conn.responseCode)
      assertEquals("text/css; charset=utf-8", conn.getHeaderField("Content-Type"))
      assertEquals("body{color:#123}", conn.inputStream.bufferedReader().readText())
      val no = URL("http://127.0.0.1:${server.port}/index.html").openConnection() as HttpURLConnection
      assertEquals(403, no.responseCode)
      assertTrue(server.owns("http://127.0.0.1:${server.port}/x"))
    } finally {
      server.stop()
    }
    assertFalse(server.isRunning)
  }
}
