package com.google.ai.edge.gallery.forge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.forge.workspace.ForgeWorkspace
import com.google.ai.edge.gallery.ui.common.AltrexAvatar

/** [ALTREX] Forge's home (maquette B-technique/01-accueil): new project, projects, recent sessions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ForgeHome(
  projects: List<ForgeWorkspace.ProjectInfo>,
  sessions: List<ForgeWorkspace.SessionInfo>,
  oldProjectName: String?,
  busy: String?,
  now: Long,
  onMenu: (() -> Unit)?,
  onBuild: () -> Unit,
  onImport: () -> Unit,
  onClone: () -> Unit,
  onMigrate: () -> Unit,
  onDismissMigration: () -> Unit,
  onOpenProject: (String) -> Unit,
  onOpenSession: (ForgeWorkspace.SessionInfo) -> Unit,
  onDeleteProject: (String) -> Unit,
) {
  val p = LocalForgePalette.current
  var toDelete by remember { mutableStateOf<String?>(null) }
  Column(Modifier.fillMaxSize().background(p.bg)) {
    ForgeTopBar(title = "Forge", subtitle = "Altrex", onMenu = onMenu)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
      item {
        Text("Nouveau projet", style = ForgeType.title.copy(fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)), color = p.text, modifier = Modifier.padding(top = 12.dp))
        Text("Une idée, un dossier ou un dépôt : Forge s'occupe du reste.", style = ForgeType.body, color = p.muted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        Row(
          Modifier.fillMaxWidth().clip(shape()).background(p.accent).clickable(onClick = onBuild).padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(Modifier.size(44.dp).clip(shape()).border(1.dp, p.onAccent.copy(alpha = 0.3f), shape()), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = p.onAccent)
          }
          Spacer(Modifier.width(14.dp))
          Column(Modifier.weight(1f)) {
            Text("Build", style = ForgeType.title, color = p.onAccent)
            Text("Décris ton idée : Forge propose l'architecture, puis construit.", style = ForgeType.body, color = p.onAccent)
          }
          Icon(Icons.Outlined.ChevronRight, null, tint = p.onAccent)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          HomeTile("Importer un dossier", Icons.Outlined.Folder, Modifier.weight(1f), onImport)
          HomeTile("Cloner un dépôt", Icons.Outlined.Download, Modifier.weight(1f), onClone)
        }
        busy?.let {
          Spacer(Modifier.height(10.dp))
          Text("▸ $it", style = ForgeType.monoSmall, color = p.accent)
        }
        if (oldProjectName != null) {
          Spacer(Modifier.height(12.dp))
          Column(Modifier.fillMaxWidth().clip(shape()).border(1.dp, p.warn.copy(alpha = 0.6f), shape()).padding(14.dp)) {
            Text("Ton projet de l'ancien écran Code", style = ForgeType.bodyStrong, color = p.text)
            Text(
              "« $oldProjectName » est dans un dossier du téléphone. Forge travaille dans son propre dossier (/root/projets) : copie-le pour le reprendre ici. L'original n'est pas touché.",
              style = ForgeType.body,
              color = p.muted,
              modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              ForgeButton("Importer dans Forge", primary = true, modifier = Modifier.weight(1.4f), onClick = onMigrate)
              ForgeButton("Plus tard", modifier = Modifier.weight(1f), onClick = onDismissMigration)
            }
          }
        }
        SectionHeader("Projets")
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
      }
      if (projects.isEmpty()) {
        item { Text("Aucun projet pour l'instant.", style = ForgeType.body, color = p.faint, modifier = Modifier.padding(vertical = 14.dp)) }
      }
      items(projects, key = { "p-" + it.name }) { pr ->
        Row(
          Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenProject(pr.name) }, onLongClick = { toDelete = pr.name }).padding(vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          KindBadge(pr.kind, pr.name)
          Spacer(Modifier.width(14.dp))
          Column(Modifier.weight(1f)) {
            Text(pr.name, style = ForgeType.mono.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
              listOfNotNull(pr.kind.ifEmpty { null }, pr.branch?.let { "⑂ $it" }, if (!pr.trusted) "non approuvé" else null).joinToString("  ·  ").ifEmpty { "—" },
              style = ForgeType.monoSmall,
              color = if (!pr.trusted) p.warn else p.muted,
            )
          }
          Text(relativeTime(pr.updated, now), style = ForgeType.body, color = p.muted)
          Icon(Icons.Outlined.ChevronRight, null, tint = p.faint)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
      }
      if (sessions.isNotEmpty()) {
        item {
          SectionHeader("Sessions récentes")
          Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        }
        items(sessions.take(8), key = { "s-" + it.project + it.id }) { s ->
          Row(Modifier.fillMaxWidth().clickable { onOpenSession(s) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint, label) =
              when (s.lastEnd) {
                null -> Triple(Icons.AutoMirrored.Outlined.List, p.accent, "En cours" + if (s.todosTotal > 0) " · ${s.todosDone}/${s.todosTotal}" else "")
                "done" -> Triple(Icons.Outlined.Check, p.ok, "Terminée")
                "interrupted" -> Triple(Icons.Outlined.Schedule, p.warn, "Interrompue")
                "blocked", "error", "limit" -> Triple(Icons.Outlined.Warning, p.warn, if (s.lastEnd == "limit") "Limite" else if (s.lastEnd == "error") "Erreur" else "Bloquée")
                else -> Triple(Icons.Outlined.Check, p.muted, "Arrêtée")
              }
            Box(Modifier.size(44.dp).clip(shape()).border(1.dp, p.line2, shape()), contentAlignment = Alignment.Center) {
              Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
              Text(s.title, style = ForgeType.bodyStrong, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text("${s.project} · ${relativeTime(s.updated, now)}", style = ForgeType.body, color = p.muted, maxLines = 1)
            }
            Text(label, style = ForgeType.monoSmall, color = tint)
          }
          Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        }
      }
      item { Spacer(Modifier.height(32.dp)) }
    }
  }
  toDelete?.let { name ->
    AlertDialog(
      onDismissRequest = { toDelete = null },
      title = { Text("Supprimer « $name » ?") },
      text = { Text("Le dossier /root/projets/$name et ses sessions seront effacés du téléphone. C'est définitif.") },
      confirmButton = {
        TextButton(onClick = {
          onDeleteProject(name)
          toDelete = null
        }) { Text("Supprimer") }
      },
      dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
    )
  }
}

@Composable
private fun HomeTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
  val p = LocalForgePalette.current
  Column(modifier.clip(shape()).border(1.dp, p.line2, shape()).clickable(onClick = onClick).padding(16.dp)) {
    Icon(icon, null, tint = p.accent)
    Spacer(Modifier.height(14.dp))
    Text(label, style = ForgeType.bodyStrong, color = p.text)
  }
}

@Composable
private fun KindBadge(kind: String, name: String) {
  val p = LocalForgePalette.current
  val (letters, color) =
    when (kind) {
      "Python" -> "Py" to p.warn
      "Node", "React", "Vue", "Svelte" -> "JS" to p.ok
      "Next.js" -> "N" to p.text
      "Rust" -> "Rs" to p.del
      "Go" -> "Go" to p.accent
      "Kotlin" -> "Kt" to p.accent
      "Web" -> "W" to p.accent
      else -> name.take(1).uppercase() to p.muted
    }
  Box(Modifier.size(44.dp).clip(shape()).border(1.dp, p.line2, shape()), contentAlignment = Alignment.Center) {
    Text(letters, style = ForgeType.mono, color = color)
  }
}

@Composable
fun ForgeTopBar(
  title: String,
  subtitle: String,
  onMenu: (() -> Unit)?,
  navigationIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Menu,
  actions: @Composable () -> Unit = {},
) {
  val p = LocalForgePalette.current
  Column {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      if (onMenu != null) IconButton(onClick = onMenu) { Icon(navigationIcon, "Menu", tint = p.muted) } else Spacer(Modifier.width(12.dp))
      AltrexAvatar(size = 30.dp)
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(title, style = ForgeType.title, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = ForgeType.monoSmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      actions()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
  }
}

/** "à l'instant", "il y a 2 h", "hier", "lun.", "3 nov.". */
fun relativeTime(time: Long, now: Long): String {
  if (time <= 0) return ""
  val d = (now - time).coerceAtLeast(0) / 1000
  return when {
    d < 60 -> "à l'instant"
    d < 3_600 -> "il y a ${d / 60} min"
    d < 86_400 -> "il y a ${d / 3_600} h"
    d < 2 * 86_400 -> "hier"
    d < 7 * 86_400 -> java.text.SimpleDateFormat("EEE", java.util.Locale.FRANCE).format(java.util.Date(time))
    else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.FRANCE).format(java.util.Date(time))
  }
}
