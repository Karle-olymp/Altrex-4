package com.google.ai.edge.gallery.forge.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.google.ai.edge.gallery.ui.common.coderunner.RunLanguage
import com.google.ai.edge.gallery.ui.common.coderunner.runnableLanguage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [ALTREX] « Mode de travail » (maquette 03-plan-modes): the four chips, honest about PRoot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSheet(current: ForgeMode, onPick: (ForgeMode) -> Unit, onDismiss: () -> Unit) {
  val p = LocalForgePalette.current
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
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
      Row(Modifier.fillMaxWidth().clip(shape()).border(1.dp, p.line, shape()).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = p.faint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
          Text("Mode Apprentissage", style = ForgeType.bodyStrong, color = p.muted)
          Text("Explique chaque outil et le code écrit. Arrive à l'étape 2.", style = ForgeType.body, color = p.faint)
        }
        Switch(checked = false, onCheckedChange = null, enabled = false)
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
private val PREVIEW_EXT = setOf("html", "htm", "svg", "md", "markdown", "js", "mjs", "py")

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

/** Only pages served inside the phone (the Linux): nothing on the internet from here. */
fun isLocalUrl(url: String): Boolean = Regex("^https?://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?(/.*)?$").matches(url.trim())

/**
 * [ALTREX] Aperçu, kept from the former Code screen: a file of the project (HTML, SVG and Markdown
 * drawn; JavaScript and Python run on the phone, without network), or a page served by a server
 * started in the Linux (http://localhost:PORT, NOT verified on a phone yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(root: File, initialFile: String?, onDismiss: () -> Unit) {
  val p = LocalForgePalette.current
  var file by remember { mutableStateOf(initialFile) }
  var url by remember { mutableStateOf("http://localhost:3000") }
  var shownUrl by remember { mutableStateOf<String?>(null) }
  var files by remember { mutableStateOf(emptyList<String>()) }
  var text by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(root) { files = withContext(Dispatchers.IO) { previewableFiles(root) } }
  LaunchedEffect(file) {
    text = file?.let { f -> withContext(Dispatchers.IO) { File(root, f).takeIf { it.isFile && it.length() < 2_000_000 }?.readText() } }
  }
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp)) {
      Text("Aperçu", style = ForgeType.title, color = p.text)
      Spacer(Modifier.height(8.dp))
      val f = file
      val u = shownUrl
      when {
        u != null -> {
          Text(u, style = ForgeType.monoSmall, color = p.muted, modifier = Modifier.clickable { shownUrl = null }.padding(vertical = 6.dp))
          LocalPage(u, Modifier.fillMaxWidth().weight(1f))
        }
        f != null -> {
          Text("← $f", style = ForgeType.mono, color = p.accent, modifier = Modifier.clickable { file = null }.padding(vertical = 6.dp))
          val t = text
          val ext = f.substringAfterLast('.', "").lowercase()
          Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
              t == null -> Text("Fichier illisible ou trop gros.", style = ForgeType.body, color = p.muted)
              ext == "md" || ext == "markdown" ->
                Column(Modifier.verticalScroll(rememberScrollState())) { MarkdownText(text = t, textColor = p.text, linkColor = p.accent) }
              else -> {
                val lang = if (ext == "svg" || ext == "htm") RunLanguage.HTML else runnableLanguage(ext)
                if (lang != null) androidx.compose.runtime.key(f, t) { CodeRunnerContent(language = lang, code = t, fill = true, title = f.substringAfterLast('/')) }
                else Text("Pas d'aperçu pour ce type de fichier.", style = ForgeType.body, color = p.muted)
              }
            }
          }
        }
        else -> {
          Text("SERVEUR LOCAL", style = ForgeType.section, color = p.muted)
          Text("Lance d'abord le serveur dans le Linux (Bash en arrière-plan), puis ouvre son adresse.", style = ForgeType.body, color = p.faint)
          Spacer(Modifier.height(6.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            ForgeTextField(url, { url = it }, "http://localhost:3000", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            ForgeButton("Ouvrir", primary = true, enabled = isLocalUrl(url)) { shownUrl = url.trim() }
          }
          SectionHeader("Fichiers du projet")
          if (files.isEmpty()) Text("Aucun fichier HTML, SVG, Markdown, JavaScript ou Python.", style = ForgeType.body, color = p.faint)
          LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
            items(files) { name ->
              Text(name, style = ForgeType.mono, color = p.text, modifier = Modifier.fillMaxWidth().clickable { file = name }.padding(vertical = 10.dp))
            }
          }
        }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LocalPage(url: String, modifier: Modifier) {
  AndroidView(
    factory = { ctx ->
      WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient =
          object : WebViewClient() {
            // Links stay on the local server; nothing else is opened here.
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
              !isLocalUrl(request?.url?.toString().orEmpty())
          }
        loadUrl(url)
      }
    },
    modifier = modifier.fillMaxSize(),
  )
}
