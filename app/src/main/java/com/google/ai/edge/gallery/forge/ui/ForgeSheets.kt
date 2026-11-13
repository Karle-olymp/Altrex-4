package com.google.ai.edge.gallery.forge.ui

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.ai.edge.gallery.forge.preview.PreviewServer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ai.edge.gallery.forge.engine.ForgeMode
import com.google.ai.edge.gallery.forge.engine.Preset
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.common.coderunner.CodeRunnerContent
import com.google.ai.edge.gallery.ui.common.coderunner.runnableLanguage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** [ALTREX] « Mode de travail » (maquette 03-plan-modes): the four chips, honest about PRoot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSheet(
  current: ForgeMode,
  onPick: (ForgeMode) -> Unit,
  onDismiss: () -> Unit,
  learning: Boolean = false,
  /** Label of the running Build (« Build · tour 2 »), null when the Build mode is off. */
  build: String? = null,
  canSwitch: Boolean = true,
  onLearning: (Boolean) -> Unit = {},
  onBuild: (Boolean) -> Unit = {},
) {
  val p = LocalForgePalette.current
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
      Text("Mode de travail", style = ForgeType.title, color = p.text)
      Text("Ce que Forge peut faire sans te demander", style = ForgeType.body, color = p.muted, modifier = Modifier.padding(bottom = 14.dp))
      if (current.preset == null) {
        Text(
          "Actuel : Personnalisé (bac à sable : ${current.sandbox.label}, approbation : ${current.approval.label})",
          style = ForgeType.monoSmall,
          color = p.warn,
          modifier = Modifier.padding(bottom = 10.dp),
        )
      }
      Column(Modifier.clip(shape()).border(1.dp, p.line, shape())) {
        for (preset in Preset.entries) {
          val selected = current == preset.mode
          Row(
            Modifier.fillMaxWidth().background(if (selected) p.accent.copy(alpha = 0.07f) else p.surface).clickable { onPick(preset.mode) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(Modifier.size(44.dp).clip(shape()).border(1.dp, if (selected) p.accent else p.line2, shape()), contentAlignment = Alignment.Center) {
              Icon(modeIcon(preset.mode), null, tint = if (preset == Preset.TOUT_AUTORISER) p.warn else if (selected) p.accent else p.muted)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
              Text(preset.label, style = ForgeType.bodyStrong, color = p.text)
              Text(preset.description, style = ForgeType.body, color = p.muted)
            }
            Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) p.accent else p.line2, CircleShape), contentAlignment = Alignment.Center) {
              if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(p.accent))
            }
          }
          Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        }
      }
      Spacer(Modifier.height(12.dp))
      Row(Modifier.fillMaxWidth().clip(shape()).border(1.dp, if (build != null) p.accent else p.line, shape()).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Construction, null, tint = if (build != null) p.accent else p.muted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
          Text(build ?: "Mode Build", style = ForgeType.bodyStrong, color = p.text)
          Text("Une idée → un plan à valider → Forge construit seul, vérifie (fichiers, tests, aperçu), puis propose des améliorations une fois.", style = ForgeType.body, color = p.muted)
        }
        Switch(checked = build != null, onCheckedChange = { onBuild(it) }, enabled = canSwitch)
      }
      Spacer(Modifier.height(8.dp))
      Row(Modifier.fillMaxWidth().clip(shape()).border(1.dp, if (learning) p.accent else p.line, shape()).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = if (learning) p.accent else p.muted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
          Text("Mode Apprentissage", style = ForgeType.bodyStrong, color = p.text)
          Text("Forge explique ce qu'il fait et pourquoi, propose de petits exercices, vérifie que tu as compris, et te laisse faire.", style = ForgeType.body, color = p.muted)
        }
        Switch(checked = learning, onCheckedChange = { onLearning(it) })
      }
      Spacer(Modifier.height(12.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Shield, null, tint = p.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          "Isolation partielle : le Linux (PRoot) ne cloisonne pas vraiment. Forge applique lui-même les droits du mode, avant chaque action.",
          style = ForgeType.body,
          color = p.muted,
        )
      }
    }
  }
}

/** Files the Aperçu can draw or run. */
private val PREVIEW_EXT = setOf("html", "htm", "svg", "css", "md", "markdown", "js", "mjs", "py")

/** Files the Aperçu opens through its own web server ([PreviewServer]). */
private val SERVED_EXT = setOf("html", "htm", "svg", "css", "js", "mjs")

fun previewableFiles(root: File, max: Int = 100): List<String> {
  val out = ArrayList<String>()
  fun walk(d: File, rel: String, depth: Int) {
    if (depth > 6 || out.size >= max) return
    for (f in d.listFiles()?.sortedBy { it.name }.orEmpty()) {
      if (out.size >= max) return
      if (f.name.startsWith(".") || f.name == "node_modules") continue
      val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
      if (f.isDirectory) walk(f, r, depth + 1) else if (f.extension.lowercase() in PREVIEW_EXT) out.add(r)
    }
  }
  walk(root, "", 0)
  return out
}

/** Only pages served inside the phone (the Linux, or the Aperçu's server): nothing on the internet from here. */
fun isLocalUrl(url: String): Boolean = Regex("^https?://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?([/?#].*)?$").matches(url.trim())

/** How the Aperçu shows [file]: through its server (web files), drawn (Markdown), or run (Python). */
internal fun previewKind(file: String): String =
  when (file.substringAfterLast('.', "").lowercase()) {
    in SERVED_EXT -> "web"
    "md", "markdown" -> "markdown"
    "py" -> "python"
    else -> "none"
  }

/**
 * [ALTREX] Aperçu: the project's files through the app's own web server ([PreviewServer], started
 * here and stopped when the sheet closes: relative links, styles and scripts work as on a site),
 * Markdown drawn, Python run on the phone; and, in « Serveur local », the page of a server the
 * model started in the Linux (http://localhost:PORT). « Recharger », and a reload by itself when
 * the agent changes a file of the project ([reloadSignal]) while the sheet is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(root: File, initialFile: String?, reloadSignal: Int = 0, onDismiss: () -> Unit) {
  val p = LocalForgePalette.current
  var file by remember { mutableStateOf(initialFile) }
  var tab by remember { mutableStateOf(0) }
  var url by remember { mutableStateOf("http://localhost:3000") }
  var shownUrl by remember { mutableStateOf<String?>(null) }
  var files by remember { mutableStateOf(emptyList<String>()) }
  var text by remember { mutableStateOf<String?>(null) }
  var runJs by remember { mutableStateOf(false) }
  var reloadTick by remember { mutableIntStateOf(0) }
  var server by remember { mutableStateOf<PreviewServer?>(null) }
  var serverError by remember { mutableStateOf<String?>(null) }

  // The server lives as long as the sheet.
  LaunchedEffect(root) {
    val s = withContext(Dispatchers.IO) { runCatching { PreviewServer(root).start() } }
    s.onSuccess { server = it }.onFailure { serverError = it.message ?: it.javaClass.simpleName }
  }
  DisposableEffect(root) {
    onDispose {
      val s = server
      server = null
      if (s != null) Thread { s.stop() }.apply { isDaemon = true }.start()
    }
  }
  // The agent changed a file: reload (once the writes of that step are done).
  var firstSignal by remember { mutableStateOf(true) }
  LaunchedEffect(reloadSignal) {
    if (firstSignal) {
      firstSignal = false
    } else {
      delay(400)
      reloadTick++
    }
  }
  // [ALTREX] Changed outside Forge (the Terminal, another app): the open file is reloaded too.
  LaunchedEffect(file) {
    val f = file?.takeIf { !isLocalUrl(it) }?.let { File(root, it) } ?: return@LaunchedEffect
    var seen = withContext(Dispatchers.IO) { f.lastModified() to f.length() }
    while (true) {
      delay(1_500)
      val now = withContext(Dispatchers.IO) { f.lastModified() to f.length() }
      if (now != seen) {
        seen = now
        reloadTick++
      }
    }
  }
  LaunchedEffect(root, reloadTick) { files = withContext(Dispatchers.IO) { previewableFiles(root) } }
  LaunchedEffect(file, reloadTick) {
    runJs = runJs && file != null
    text =
      file?.takeIf { previewKind(it) != "web" || runJs }?.let { f ->
        withContext(Dispatchers.IO) { File(root, f).takeIf { it.isFile && it.length() < 2_000_000 }?.readText() }
      }
  }
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Aperçu", style = ForgeType.title, color = p.text, modifier = Modifier.weight(1f))
        val showing = file != null || shownUrl != null
        if (showing) {
          IconButton(onClick = { reloadTick++ }) { Icon(Icons.Outlined.Refresh, "Recharger", tint = p.accent) }
        }
      }
      Spacer(Modifier.height(4.dp))
      val f = file
      val u = shownUrl
      when {
        u != null -> {
          Text("← $u", style = ForgeType.monoSmall, color = p.accent, maxLines = 1, modifier = Modifier.clickable { shownUrl = null }.padding(vertical = 6.dp))
          PreviewWeb(u, reloadTick, Modifier.fillMaxWidth().weight(1f))
        }
        f != null -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("← $f", style = ForgeType.mono, color = p.accent, maxLines = 1, modifier = Modifier.weight(1f).clickable { file = null; runJs = false }.padding(vertical = 6.dp))
            val ext = f.substringAfterLast('.', "").lowercase()
            if (ext == "js" || ext == "mjs") {
              Text(if (runJs) "Voir le fichier" else "Exécuter", style = ForgeType.bodyStrong, color = p.accent, modifier = Modifier.clickable { runJs = !runJs; reloadTick++ }.padding(8.dp))
            }
          }
          Box(Modifier.fillMaxWidth().weight(1f)) {
            val kind = previewKind(f)
            val t = text
            when {
              kind == "web" && !runJs -> {
                val srv = server
                when {
                  srv != null -> PreviewWeb(srv.urlFor(f), reloadTick, Modifier.fillMaxSize())
                  serverError != null -> Text("Le serveur de l'aperçu n'a pas pu démarrer : $serverError", style = ForgeType.body, color = p.warn)
                  else -> Text("Démarrage de l'aperçu…", style = ForgeType.body, color = p.muted)
                }
              }
              t == null -> Text("Fichier illisible ou trop gros.", style = ForgeType.body, color = p.muted)
              kind == "markdown" ->
                Column(Modifier.verticalScroll(rememberScrollState())) { MarkdownText(text = t, textColor = p.text, linkColor = p.accent) }
              else -> {
                val lang = runnableLanguage(f.substringAfterLast('.', "").lowercase())
                if (lang != null) androidx.compose.runtime.key(f, t, reloadTick) { CodeRunnerContent(language = lang, code = t, fill = true, title = f.substringAfterLast('/')) }
                else Text("Pas d'aperçu pour ce type de fichier.", style = ForgeType.body, color = p.muted)
              }
            }
          }
        }
        else -> {
          Row(Modifier.fillMaxWidth().clip(shape()).border(1.dp, p.line, shape())) {
            for ((i, label) in listOf("Fichiers du projet", "Serveur local").withIndex()) {
              Box(
                Modifier.weight(1f).background(if (tab == i) p.accent.copy(alpha = 0.10f) else p.surface).clickable { tab = i }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
              ) {
                Text(label, style = ForgeType.bodyStrong, color = if (tab == i) p.accent else p.muted)
              }
            }
          }
          Spacer(Modifier.height(10.dp))
          if (tab == 0) {
            Text(
              "Les pages s'ouvrent par le serveur de l'aperçu (dans l'appli, 127.0.0.1 seulement) : liens, styles et scripts relatifs fonctionnent.",
              style = ForgeType.body,
              color = p.faint,
            )
            if (File(root, "index.html").isFile || File(root, "index.htm").isFile) {
              Spacer(Modifier.height(8.dp))
              ForgeButton("Ouvrir le site (index.html)", primary = true, enabled = server != null) { file = if (File(root, "index.html").isFile) "index.html" else "index.htm" }
            }
            if (files.isEmpty()) Text("Aucun fichier HTML, SVG, CSS, Markdown, JavaScript ou Python.", style = ForgeType.body, color = p.faint, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
              items(files) { name ->
                Text(name, style = ForgeType.mono, color = p.text, modifier = Modifier.fillMaxWidth().clickable { file = name }.padding(vertical = 10.dp))
              }
            }
          } else {
            Text(
              "Pour un serveur lancé par l'agent dans le Linux (node, python…) : lance-le d'abord (Bash en arrière-plan), puis ouvre son adresse.",
              style = ForgeType.body,
              color = p.faint,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              ForgeTextField(url, { url = it }, "http://localhost:3000", modifier = Modifier.weight(1f))
              Spacer(Modifier.width(8.dp))
              ForgeButton("Ouvrir", primary = true, enabled = isLocalUrl(url)) { shownUrl = url.trim() }
            }
          }
        }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}

/**
 * The Aperçu's WebView: the page as its author wrote it (no forced or algorithmic darkening, white
 * background by default), JavaScript and local storage on, links kept on the phone (localhost and
 * 127.0.0.1; anything else is not opened). Reloaded when [reloadTick] changes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWeb(url: String, reloadTick: Int, modifier: Modifier) {
  var view by remember { mutableStateOf<WebView?>(null) }
  var loaded by remember { mutableStateOf<String?>(null) }
  var first by remember { mutableStateOf(true) }
  LaunchedEffect(reloadTick) {
    if (first) first = false else view?.reload()
  }
  AndroidView(
    factory = { ctx ->
      WebView(ctx).apply {
        configurePreview(this)
        view = this
      }
    },
    update = { w ->
      if (loaded != url) {
        loaded = url
        w.loadUrl(url)
      }
    },
    onRelease = { w ->
      w.stopLoading()
      w.destroy()
      if (view === w) view = null
    },
    modifier = modifier.fillMaxSize(),
  )
}

/** Settings of the Aperçu's WebView (see [PreviewWeb]). */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal fun configurePreview(w: WebView) {
  w.setBackgroundColor(android.graphics.Color.WHITE)
  w.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    // Our pages are http://127.0.0.1 or http://localhost: no https page loads them, so mixed
    // content never applies; the default (refused) stays.
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
  }
  // The app's dark theme must not darken the page.
  if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
    WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, false)
  }
  if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
    WebSettingsCompat.setForceDark(w.settings, WebSettingsCompat.FORCE_DARK_OFF)
  }
  w.webViewClient =
    object : WebViewClient() {
      // Links stay on the phone (the Aperçu's server, or the model's server); nothing else opens here.
      override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
        !isLocalUrl(request?.url?.toString().orEmpty())
    }
}
