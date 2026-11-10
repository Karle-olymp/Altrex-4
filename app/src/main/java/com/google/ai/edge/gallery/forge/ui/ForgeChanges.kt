package com.google.ai.edge.gallery.forge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.ai.edge.gallery.forge.checkpoints.Checkpoints
import com.google.ai.edge.gallery.forge.checkpoints.FileChange
import com.google.ai.edge.gallery.forge.journal.SessionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [ALTREX] One line of a change list: kind, path, +N −M. */
@Composable
private fun ChangeRow(c: FileChange, open: Boolean, onClick: () -> Unit) {
  val p = LocalForgePalette.current
  Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    val (label, color) =
      when (c.kind) {
        "A" -> "créé" to p.ok
        "D" -> "supprimé" to p.del
        else -> "modifié" to p.accent
      }
    Text(label, style = ForgeType.monoSmall, color = color, modifier = Modifier.width(64.dp))
    Text(c.path, style = ForgeType.mono, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    if (c.binary) {
      Text("binaire", style = ForgeType.monoSmall, color = p.faint)
    } else {
      Text("+${c.added}", style = ForgeType.monoSmall, color = p.ok)
      Spacer(Modifier.width(6.dp))
      Text("−${c.removed}", style = ForgeType.monoSmall, color = p.del)
    }
    Text(if (open) "  ▴" else "  ▾", style = ForgeType.monoSmall, color = p.faint)
  }
}

/** [ALTREX] /diff: one card per file (+N −M), opened on a tap to show its numbered diff. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffSheet(title: String, entries: List<Pair<FileChange, String>>, onDismiss: () -> Unit) {
  val p = LocalForgePalette.current
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
      Text(title, style = ForgeType.title, color = p.text)
      val added = entries.sumOf { it.first.added }
      val removed = entries.sumOf { it.first.removed }
      Text("${entries.size} fichier(s) · +$added −$removed", style = ForgeType.monoSmall, color = p.muted, modifier = Modifier.padding(bottom = 12.dp))
      if (entries.isEmpty()) Text("Aucune différence.", style = ForgeType.body, color = p.muted)
      LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        items(entries, key = { it.first.path }) { (c, patch) ->
          var open by remember(c.path) { mutableStateOf(entries.size == 1) }
          Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, p.line, shape())) {
            ChangeRow(c, open) { open = !open }
            if (open && patch.isNotEmpty()) {
              Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
              DetailLines(patch, isDiff = true, maxFolded = 40)
            }
          }
        }
      }
    }
  }
}

/** [ALTREX] /undo: what will be put back, what is left alone; nothing happens without « Annuler ». */
@Composable
fun UndoDialog(plan: Checkpoints.UndoPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val p = LocalForgePalette.current
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = p.surface,
    title = { Text("Annuler le tour ${plan.turn} ?", color = p.text) },
    text = {
      Column {
        if (plan.restore.isEmpty()) {
          Text("Aucun fichier à remettre comme avant.", style = ForgeType.body, color = p.muted)
        } else {
          Text("Ces fichiers reviennent à leur état d'avant le tour :", style = ForgeType.body, color = p.muted)
          for (c in plan.restore.take(12)) {
            Text(
              (if (c.kind == "A") "supprimé  " else "remis     ") + c.path,
              style = ForgeType.monoSmall,
              color = p.text,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (plan.restore.size > 12) Text("… et ${plan.restore.size - 12} autre(s)", style = ForgeType.monoSmall, color = p.faint)
        }
        if (plan.conflicts.isNotEmpty()) {
          Spacer(Modifier.height(8.dp))
          Text("Laissés tels quels (tu les as modifiés depuis) :", style = ForgeType.body, color = p.warn)
          for (c in plan.conflicts.take(8)) Text(c, style = ForgeType.monoSmall, color = p.warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text("Un instantané de l'état actuel est gardé. Ta branche Git n'est pas touchée.", style = ForgeType.monoSmall, color = p.faint)
      }
    },
    confirmButton = { TextButton(enabled = plan.restore.isNotEmpty(), onClick = onConfirm) { Text("Annuler le tour", color = p.del) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Garder", color = p.muted) } },
  )
}

/** [ALTREX] /checkpoints: the snapshots of the session, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckpointsSheet(state: SessionState, storeKind: String, onUndo: () -> Unit, onDiff: () -> Unit, onDismiss: () -> Unit) {
  val p = LocalForgePalette.current
  val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.FRANCE) }
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
      Text("Instantanés", style = ForgeType.title, color = p.text)
      Text(
        when (storeKind) {
          "git" -> "Dépôt Git fantôme, à part de ton dépôt : ta branche n'est jamais touchée."
          "copie" -> "Copies faites par l'appli (git absent du Linux) : fichiers de plus de 2 Mo non couverts."
          else -> "Pris avant la première modification de chaque tour, et à sa fin."
        },
        style = ForgeType.body,
        color = p.muted,
        modifier = Modifier.padding(bottom = 12.dp),
      )
      val list = state.checkpoints.reversed()
      if (list.isEmpty()) Text("Aucun pour l'instant : l'agent n'a encore rien modifié dans cette session.", style = ForgeType.body, color = p.muted)
      for (c in list.take(30)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Tour ${c.turn}", style = ForgeType.bodyStrong, color = if (c.turn in state.undone) p.faint else p.text, modifier = Modifier.width(72.dp))
          Text(
            fmt.format(Date(c.ts)) + " · " + (if (c.post == null) "sans fin" else "${c.files.size} fichier(s)") + (if (c.turn in state.undone) " · annulé" else ""),
            style = ForgeType.monoSmall,
            color = p.muted,
            modifier = Modifier.weight(1f),
          )
          Text(c.pre.take(8), style = ForgeType.monoSmall, color = p.faint)
        }
      }
      Spacer(Modifier.height(12.dp))
      Row {
        state.undoable?.let { u -> ForgeButton("Annuler le tour ${u.turn}", onClick = onUndo) }
        Spacer(Modifier.width(8.dp))
        if (list.isNotEmpty()) ForgeButton("Voir le diff", onClick = onDiff)
      }
    }
  }
}

/** [ALTREX] A produced file: open in the preview, share, copy its content or its path. */
@Composable
fun DeliverableChip(file: String, root: File, onPreview: (String) -> Unit) {
  val p = LocalForgePalette.current
  val context = LocalContext.current
  var menu by remember { mutableStateOf(false) }
  Box {
    Row(
      Modifier.clip(shape()).border(1.dp, p.line2, shape()).clickable { menu = true }.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = p.muted, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(6.dp))
      Text(file.substringAfterLast('/'), style = ForgeType.mono, color = p.text)
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      DropdownMenuItem(text = { Text("Ouvrir dans l'aperçu") }, onClick = { menu = false; onPreview(file) })
      DropdownMenuItem(text = { Text("Partager") }, onClick = { menu = false; shareFile(context, File(root, file)) })
      DropdownMenuItem(text = { Text("Copier le contenu") }, onClick = { menu = false; copyContent(context, File(root, file)) })
      DropdownMenuItem(text = { Text("Copier le chemin") }, onClick = { menu = false; copyText(context, file, "Chemin copié") })
    }
  }
}

private fun copyText(context: Context, text: String, toast: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  cm.setPrimaryClip(ClipData.newPlainText("Forge", text))
  Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

private fun copyContent(context: Context, f: File) {
  val bytes = runCatching { f.readBytes() }.getOrNull()
  when {
    bytes == null -> Toast.makeText(context, "Fichier introuvable.", Toast.LENGTH_SHORT).show()
    bytes.size > 200 * 1024 -> Toast.makeText(context, "Trop gros pour le presse-papiers : partage-le.", Toast.LENGTH_SHORT).show()
    com.google.ai.edge.gallery.forge.tools.FileTools.looksBinary(bytes) -> Toast.makeText(context, "Fichier binaire : partage-le.", Toast.LENGTH_SHORT).show()
    else -> copyText(context, bytes.toString(Charsets.UTF_8), "Contenu copié")
  }
}

private fun shareFile(context: Context, f: File) {
  runCatching {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", f)
    val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "text/plain"
    val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Partager ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }.onFailure { Toast.makeText(context, "Partage impossible : ${it.message}", Toast.LENGTH_LONG).show() }
}
