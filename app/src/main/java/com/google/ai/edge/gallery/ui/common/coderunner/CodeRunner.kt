/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common.coderunner

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [ALTREX] Languages the phone can run itself. */
enum class RunLanguage(val label: String) {
  JAVASCRIPT("JavaScript"),
  HTML("HTML"),
  PYTHON("Python"),
}

fun runnableLanguage(language: String): RunLanguage? =
  when (language.trim().lowercase()) {
    "js", "javascript", "node", "nodejs", "mjs" -> RunLanguage.JAVASCRIPT
    "html", "htm" -> RunLanguage.HTML
    "py", "python", "python3" -> RunLanguage.PYTHON
    else -> null
  }

/**
 * [ALTREX] Pyodide (CPython compiled to WebAssembly), downloaded once into the app's files and then
 * loaded from there, so Python runs offline.
 */
object PyodideStore {
  const val VERSION = "314.0.7"
  private const val CDN = "https://cdn.jsdelivr.net/pyodide/v$VERSION/full/"
  val FILES =
    listOf("pyodide.js", "pyodide.asm.mjs", "pyodide.asm.wasm", "python_stdlib.zip", "pyodide-lock.json")

  /** Approximate download size, shown before asking. */
  const val APPROX_SIZE_MB = 13

  fun dir(context: Context): File = File(context.filesDir, "pyodide/$VERSION")

  fun isInstalled(context: Context): Boolean {
    val d = dir(context)
    return FILES.all { File(d, it).let { f -> f.isFile && f.length() > 0 } }
  }

  /** Downloads the missing files; [onProgress] gets 0..1. Throws on failure. */
  suspend fun install(context: Context, onProgress: (Float) -> Unit) =
    withContext(Dispatchers.IO) {
      val d = dir(context)
      d.mkdirs()
      FILES.forEachIndexed { index, name ->
        val target = File(d, name)
        if (target.isFile && target.length() > 0) return@forEachIndexed
        val tmp = File(d, "$name.part")
        val conn = URL(CDN + name).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
          if (conn.responseCode != 200) error("HTTP ${conn.responseCode} pour $name")
          val total = conn.contentLengthLong
          conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
              val buf = ByteArray(64 * 1024)
              var done = 0L
              while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                val part = if (total > 0) done.toFloat() / total else 0.5f
                onProgress((index + part.coerceIn(0f, 1f)) / FILES.size)
              }
            }
          }
          if (!tmp.renameTo(target)) error("Impossible d'enregistrer $name")
        } finally {
          conn.disconnect()
          tmp.delete()
        }
      }
      onProgress(1f)
    }
}

/** Receives what the running code prints. Calls arrive on the main thread. */
interface RunnerListener {
  fun onOutput(text: String, isError: Boolean)

  fun onFinished(ok: Boolean)

  /** User code asked for input (Python input(), JS prompt()): answer with [answer] or null. */
  fun onInputRequest(message: String, answer: (String?) -> Unit)
}

/**
 * [ALTREX] Runs code in a locked-down WebView: no file or content access, no network (every request
 * is answered locally; only the Pyodide files of the app are served), a CSP that forbids outgoing
 * connections, and a single bridge object that can only report output.
 */
@SuppressLint("SetJavaScriptEnabled")
class CodeRunner(private val context: Context, private val listener: RunnerListener) {
  val webView: WebView = WebView(context)
  private val main = Handler(Looper.getMainLooper())
  @Volatile private var finished = false

  private inner class Bridge {
    @JavascriptInterface
    fun out(kind: String, text: String) {
      main.post { listener.onOutput(text, kind == "err") }
    }

    @JavascriptInterface
    fun done(ok: Boolean) {
      main.post {
        if (!finished) {
          finished = true
          listener.onFinished(ok)
        }
      }
    }
  }

  init {
    with(webView.settings) {
      javaScriptEnabled = true
      allowFileAccess = false
      allowContentAccess = false
      domStorageEnabled = false
      databaseEnabled = false
      cacheMode = WebSettings.LOAD_NO_CACHE
      javaScriptCanOpenWindowsAutomatically = false
      setSupportMultipleWindows(false)
      @Suppress("DEPRECATION")
      allowFileAccessFromFileURLs = false
      @Suppress("DEPRECATION")
      allowUniversalAccessFromFileURLs = false
    }
    webView.addJavascriptInterface(Bridge(), "AltrexOut")
    webView.webViewClient =
      object : WebViewClient() {
        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest,
        ): WebResourceResponse = serveLocal(request.url.toString())

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
          true
      }
    webView.webChromeClient =
      object : WebChromeClient() {
        override fun onJsPrompt(
          view: WebView,
          url: String?,
          message: String?,
          defaultValue: String?,
          result: JsPromptResult,
        ): Boolean {
          listener.onInputRequest(message.orEmpty()) { answer ->
            if (answer == null) result.cancel() else result.confirm(answer)
          }
          return true
        }

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
          Log.d(TAG, "console: ${message.message()}")
          return true
        }
      }
  }

  /** Only the app's Pyodide files are served; anything else gets an empty 403. */
  private fun serveLocal(url: String): WebResourceResponse {
    val prefix = "$ORIGIN/pyodide/"
    if (url.startsWith(prefix)) {
      val name = url.removePrefix(prefix).substringBefore('?')
      if (name in PyodideStore.FILES) {
        val file = File(PyodideStore.dir(context), name)
        if (file.isFile) {
          val mime =
            when {
              name.endsWith(".wasm") -> "application/wasm"
              name.endsWith(".js") || name.endsWith(".mjs") -> "text/javascript"
              name.endsWith(".json") -> "application/json"
              else -> "application/octet-stream"
            }
          return WebResourceResponse(mime, null, FileInputStream(file))
        }
      }
    }
    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
  }

  fun run(language: RunLanguage, code: String) {
    finished = false
    val page =
      when (language) {
        RunLanguage.JAVASCRIPT -> jsPage(code)
        RunLanguage.PYTHON -> pythonPage(code)
        RunLanguage.HTML -> htmlPage(code)
      }
    webView.loadDataWithBaseURL("$ORIGIN/runner/", page, "text/html", "utf-8", null)
  }

  fun stop() {
    webView.stopLoading()
    webView.loadUrl("about:blank")
    if (!finished) {
      finished = true
      listener.onFinished(false)
    }
  }

  fun destroy() {
    finished = true
    webView.stopLoading()
    webView.removeJavascriptInterface("AltrexOut")
    webView.destroy()
  }

  companion object {
    private const val TAG = "CodeRunner"
    private const val ORIGIN = "https://altrex.runner.local"

    // No outgoing connections at all; WebAssembly allowed for Pyodide.
    private const val CSP =
      "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'self' 'unsafe-inline' " +
        "'unsafe-eval' 'wasm-unsafe-eval' data: blob:; connect-src 'self'; img-src 'self' data: blob:\">"

    private val CONSOLE_HOOK =
      """
      <script>
      (function(){
        function fmt(v){
          if (typeof v === 'string') return v;
          try { return JSON.stringify(v, null, 2) ?? String(v); } catch(e) { return String(v); }
        }
        function send(kind, args){ AltrexOut.out(kind, Array.prototype.map.call(args, fmt).join(' ') + '\n'); }
        console.log = function(){ send('out', arguments); };
        console.info = console.log; console.debug = console.log;
        console.warn = function(){ send('err', arguments); };
        console.error = function(){ send('err', arguments); };
        window.onerror = function(msg, src, line, col, err){ send('err', [err && err.stack ? err.stack : msg]); };
        window.onunhandledrejection = function(e){ send('err', [e.reason && e.reason.stack ? e.reason.stack : String(e.reason)]); };
      })();
      </script>
      """.trimIndent()

    /** Embeds user code as a JS string literal (never spliced into HTML or JS syntax directly). */
    private fun jsString(code: String): String =
      buildString {
        append('"')
        for (c in code) {
          when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            ' ' -> append("\\u2028")
            ' ' -> append("\\u2029")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
          }
        }
        append('"')
      }

    private fun jsPage(code: String) =
      """
      <!doctype html><html><head><meta charset="utf-8">$CSP$CONSOLE_HOOK</head><body><script>
      (async function(){
        try {
          let result = (0, eval)(${jsString(code)});
          if (result && typeof result.then === 'function') result = await result;
          if (result !== undefined) console.log(result);
          AltrexOut.done(true);
        } catch (e) {
          console.error(e && e.stack ? e.stack : String(e));
          AltrexOut.done(false);
        }
      })();
      </script></body></html>
      """.trimIndent()

    private fun pythonPage(code: String) =
      """
      <!doctype html><html><head><meta charset="utf-8">$CSP$CONSOLE_HOOK
      <script src="$ORIGIN/pyodide/pyodide.js"></script></head><body><script>
      (async function(){
        try {
          const py = await loadPyodide({
            indexURL: "$ORIGIN/pyodide/",
            stdout: (s) => AltrexOut.out('out', s + '\n'),
            stderr: (s) => AltrexOut.out('err', s + '\n'),
            stdin: () => { const v = window.prompt(''); return v === null ? null : v; },
          });
          await py.runPythonAsync(${jsString(code)});
          AltrexOut.done(true);
        } catch (e) {
          AltrexOut.out('err', String(e && e.message ? e.message : e) + '\n');
          AltrexOut.done(false);
        }
      })();
      </script></body></html>
      """.trimIndent()

    // HTML is shown as a page: the console hook and the CSP go first, the user's document after.
    private fun htmlPage(code: String): String {
      val head = "<meta charset=\"utf-8\">$CSP$CONSOLE_HOOK"
      val done = "<script>window.addEventListener('load', function(){ AltrexOut.done(true); });</script>"
      val lower = code.lowercase()
      val idx = lower.indexOf("<head>")
      return if (idx >= 0) {
        code.substring(0, idx + 6) + head + done + code.substring(idx + 6)
      } else {
        "<!doctype html><html><head>$head$done</head><body>$code</body></html>"
      }
    }
  }
}
