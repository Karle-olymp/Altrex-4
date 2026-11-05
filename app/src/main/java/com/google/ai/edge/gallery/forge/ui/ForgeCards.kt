package com.google.ai.edge.gallery.forge.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.forge.engine.ApprovalAnswer
import com.google.ai.edge.gallery.forge.engine.ApprovalRequest
import com.google.ai.edge.gallery.forge.journal.FeedItem
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.ui.common.MarkdownText
import org.json.JSONObject

/** [ALTREX] Forge's feed items, drawn from the journal (look B, or C in Plan mode). */

@Composable
internal fun shape() = RoundedCornerShape(LocalForgePalette.current.radius.dp)

@Composable
internal fun smallShape() = RoundedCornerShape((LocalForgePalette.current.radius - 2).coerceAtLeast(0).dp)

@Composable
fun UserBubble(text: String) {
  val p = LocalForgePalette.current
  Row(
    Modifier.fillMaxWidth().clip(shape()).background(p.surface2).drawBehind {
      drawRect(p.accent, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
    }
      .padding(horizontal = 14.dp, vertical = 12.dp)
  ) {
    Text(text, style = ForgeType.body, color = p.text)
  }
}

@Composable
fun AssistantBlock(text: String, thinking: String) {
  val p = LocalForgePalette.current
  Column(Modifier.fillMaxWidth()) {
    if (thinking.isNotBlank()) {
      var open by remember { mutableStateOf(false) }
      Row(Modifier.clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("RÉFLEXION", style = ForgeType.section, color = p.faint)
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = p.faint, modifier = Modifier.size(16.dp))
      }
      if (open) Text(thinking.trim(), style = ForgeType.monoSmall, color = p.muted, modifier = Modifier.padding(bottom = 6.dp))
    }
    if (text.isNotBlank()) MarkdownText(text = text, textColor = p.text, linkColor = p.accent)
  }
}

private data class ToolLook(val icon: ImageVector, val verb: String)

private fun look(name: String, args: JSONObject, result: ForgeEvent.ToolResult?): ToolLook =
  when (name) {
    "Read" -> ToolLook(Icons.Outlined.Description, "Lu")
    "Write" -> ToolLook(Icons.AutoMirrored.Outlined.InsertDriveFile, if (result != null && runCatching { JSONObject(result.modelText).optBoolean("created") }.getOrDefault(false)) "Créé" else "Écrit")
    "Edit" -> ToolLook(Icons.Outlined.Edit, "Modifié")
    "Glob" -> ToolLook(Icons.Outlined.Search, "Fichiers")
    "Grep" -> ToolLook(Icons.Outlined.Search, "Recherche")
    "Bash" -> ToolLook(Icons.Outlined.Terminal, if (args.optBoolean("run_in_background")) "Tâche de fond" else "Commande")
    "BashOutput" -> ToolLook(Icons.Outlined.Terminal, "Sortie")
    "KillShell" -> ToolLook(Icons.Outlined.StopCircle, "Arrêt")
    "TodoWrite" -> ToolLook(Icons.AutoMirrored.Outlined.List, "Tâches")
    "ExitPlanMode" -> ToolLook(Icons.Outlined.Description, "Plan")
    "AskUserQuestion" -> ToolLook(Icons.AutoMirrored.Outlined.HelpOutline, "Question")
    else -> ToolLook(Icons.Outlined.Warning, name.ifEmpty { "Appel illisible" })
  }

private fun target(name: String, args: JSONObject): String =
  when (name) {
    "Read", "Write", "Edit" -> args.optString("file_path").ifEmpty { args.optString("path") }
    "Glob" -> args.optString("pattern")
    "Grep" -> args.optString("pattern").let { p -> args.optString("path").takeIf { it.isNotEmpty() }?.let { "$p  $it" } ?: p }
    "Bash" -> args.optString("command")
    "BashOutput", "KillShell" -> args.optString("job_id")
    "AskUserQuestion" -> args.optString("question")
    else -> ""
  }.lineSequence().firstOrNull().orEmpty()

/**
 * A tool call: one line (icon, verb, target, result), opened to show the diff or the output.
 * More than 5 lines stay folded behind « N lignes de plus ».
 */
@Composable
fun ToolCard(item: FeedItem.ToolCard, progress: String?, running: Boolean = true) {
  val p = LocalForgePalette.current
  val args = remember(item.args) { runCatching { JSONObject(item.args) }.getOrDefault(JSONObject()) }
  val r = item.result
  val l = look(item.name, args, r)
  val detail = r?.detail.orEmpty()
  var open by rememberSaveable(item.id) { mutableStateOf(item.name == "Edit" || item.name == "Write") }
  Column(
    Modifier.fillMaxWidth().clip(shape()).border(1.dp, p.line, shape()).background(p.surface.copy(alpha = if (p.grid) 0.92f else 1f))
      .animateContentSize(tween(160))
  ) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = detail.isNotEmpty()) { open = !open }.padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(l.icon, null, tint = p.muted, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(10.dp))
      Text(l.verb, style = ForgeType.bodyStrong, color = p.text)
      Spacer(Modifier.width(8.dp))
      Text(target(item.name, args), style = ForgeType.mono, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Spacer(Modifier.width(8.dp))
      when {
        r == null && running -> CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 2.dp)
        r == null -> Text("…", style = ForgeType.monoSmall, color = p.faint)
        r.status == "refused" -> Text("refusé", style = ForgeType.monoSmall, color = p.warn)
        r.status == "error" -> Text("✗ " + r.summary.ifEmpty { "erreur" }, style = ForgeType.monoSmall, color = p.del, maxLines = 1)
        r.added + r.removed > 0 -> {
          Text("+${r.added}", style = ForgeType.monoSmall, color = p.ok)
          Spacer(Modifier.width(6.dp))
          Text("−${r.removed}", style = ForgeType.monoSmall, color = p.del)
        }
        item.name == "Bash" && r.summary.startsWith("code 0") -> Text("✓ " + r.summary, style = ForgeType.monoSmall, color = p.ok)
        else -> Text(r.summary, style = ForgeType.monoSmall, color = p.faint, maxLines = 1)
      }
      if (detail.isNotEmpty()) {
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = p.faint, modifier = Modifier.padding(start = 4.dp).size(18.dp))
      }
    }
    if (r == null && !progress.isNullOrBlank()) {
      // The last lines of a running command (4 at most), live.
      Text(
        progress.lines().joinToString("\n") { "▸ $it" },
        style = ForgeType.monoSmall,
        color = p.faint,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 42.dp, end = 14.dp, bottom = 10.dp),
      )
    }
    if (open && detail.isNotEmpty()) {
      Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
      DetailLines(detail, isDiff = item.name == "Edit" || item.name == "Write")
    }
  }
}

/** Diff lines ("n\t+\ttext") or plain output, folded after 5 lines. */
@Composable
fun DetailLines(detail: String, isDiff: Boolean, maxFolded: Int = 5) {
  val p = LocalForgePalette.current
  val lines = remember(detail) { detail.split('\n') }
  var all by remember(detail) { mutableStateOf(false) }
  val shown = if (all) lines else lines.take(maxFolded)
  // One column for the lines and the « voir tout » link: in a Box (the approval card) two
  // siblings were drawn on top of each other, the link over the first line of the diff.
  Column(Modifier.fillMaxWidth()) {
  Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
    for (line in shown) {
      val parts = line.split('\t', limit = 3)
      if (isDiff && parts.size == 3) {
        val sign = parts[1]
        val bg = when (sign) { "+" -> p.ok.copy(alpha = 0.09f); "-" -> p.del.copy(alpha = 0.09f); else -> Color.Transparent }
        Row(Modifier.background(bg).padding(horizontal = 12.dp, vertical = 1.dp).widthIn(min = 320.dp)) {
          Text(parts[0].padStart(4), style = ForgeType.monoSmall, color = p.faint)
          Text(" ${if (sign == "-") "−" else sign} ", style = ForgeType.monoSmall, color = if (sign == "+") p.ok else if (sign == "-") p.del else p.faint)
          Text(parts[2], style = ForgeType.monoSmall, color = p.text, softWrap = false)
        }
      } else {
        Text(line, style = ForgeType.monoSmall, color = p.muted, softWrap = false, modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp))
      }
    }
  }
  if (lines.size > maxFolded) {
    Text(
      if (all) "replier" else "… ${lines.size - maxFolded} lignes de plus · voir tout",
      style = ForgeType.monoSmall,
      color = p.accent,
      modifier = Modifier.fillMaxWidth().clickable { all = !all }.padding(horizontal = 12.dp, vertical = 8.dp),
    )
  }
  }
}

@Composable
fun NoteBlock(kind: String, text: String) {
  val p = LocalForgePalette.current
  val color = if (kind == "erreur" || kind == "blocage") p.warn else p.muted
  Row(Modifier.fillMaxWidth().clip(shape()).border(1.dp, color.copy(alpha = 0.4f), shape()).padding(12.dp)) {
    Icon(if (kind == "erreur" || kind == "blocage") Icons.Outlined.Warning else Icons.Outlined.Description, null, tint = color, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(8.dp))
    if (kind == "scan" || kind == "doctor") MarkdownText(text = text, textColor = p.text, linkColor = p.accent) else Text(text, style = ForgeType.body, color = p.text)
  }
}

@Composable
fun TurnEndBlock(reason: String, detail: String) {
  val p = LocalForgePalette.current
  val (label, color) =
    when (reason) {
      "interrupted" -> "Interrompu" to p.warn
      "stopped" -> "Arrêté" to p.muted
      "limit" -> "Limite atteinte" to p.warn
      "blocked" -> "Bloqué" to p.warn
      "error" -> "Erreur" to p.del
      else -> reason to p.muted
    }
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label.uppercase(), style = ForgeType.section, color = color)
    if (detail.isNotEmpty()) {
      Spacer(Modifier.width(8.dp))
      Text(detail, style = ForgeType.monoSmall, color = p.muted)
    }
  }
}

/** A plan given by ExitPlanMode, in the C look (frame with corner marks, « lecture seule »). */
@Composable
fun PlanCard(item: FeedItem.Plan, pending: Boolean, onValidate: () -> Unit, onChanges: (String) -> Unit, onRefuse: () -> Unit) {
  val c = ForgePalette.C
  var editing by remember { mutableStateOf(false) }
  var feedback by remember { mutableStateOf("") }
  Column(
    Modifier.fillMaxWidth().background(c.bg).drawBehind {
      val m = 5.dp.toPx()
      val len = 14.dp.toPx()
      val w = 1.5.dp.toPx()
      drawLine(c.accent, Offset(-m, -m), Offset(-m + len, -m), w)
      drawLine(c.accent, Offset(-m, -m), Offset(-m, -m + len), w)
      drawLine(c.accent, Offset(size.width + m, size.height + m), Offset(size.width + m - len, size.height + m), w)
      drawLine(c.accent, Offset(size.width + m, size.height + m), Offset(size.width + m, size.height + m - len), w)
      drawRect(c.line2, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
      .padding(16.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Outlined.Description, null, tint = c.accent, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text("Plan proposé", style = ForgeType.title.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
      Box(
        Modifier.drawBehind {
          drawRect(c.pencilOrWarn(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
        }
          .padding(horizontal = 8.dp, vertical = 4.dp)
      ) {
        Text("LECTURE SEULE", style = ForgeType.section, color = c.warn)
      }
    }
    Spacer(Modifier.height(10.dp))
    MarkdownText(text = item.markdown, textColor = c.text, linkColor = c.accent)
    val d = item.decision
    when {
      d != null -> {
        Spacer(Modifier.height(10.dp))
        Text(
          if (d.approved) "✓ Validé" + (d.mode?.let { m -> com.google.ai.edge.gallery.forge.engine.ForgeMode.parse(m)?.let { " · mode ${it.label}" } } ?: "")
          else if (d.feedback == "refusé") "Refusé" else "À modifier : ${d.feedback}",
          style = ForgeType.monoSmall,
          color = if (d.approved) c.ok else c.warn,
        )
      }
      pending -> {
        Spacer(Modifier.height(4.dp))
        Text("rien ne change sans ton accord", style = ForgeType.monoSmall, color = c.faint)
        Spacer(Modifier.height(12.dp))
        if (editing) {
          ForgeTextField(feedback, { feedback = it }, "Ce qu'il faut changer…", minLines = 2)
          Spacer(Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeButton("Envoyer", primary = true, modifier = Modifier.weight(1f)) { onChanges(feedback) }
            ForgeButton("Annuler", modifier = Modifier.weight(1f)) { editing = false }
          }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeButton("Valider le plan", primary = true, icon = Icons.Outlined.Check, modifier = Modifier.weight(1.4f)) { onValidate() }
            ForgeButton("Modifier", modifier = Modifier.weight(1f)) { editing = true }
            ForgeButton("Refuser", dashed = true, modifier = Modifier.weight(1f)) { onRefuse() }
          }
        }
      }
    }
  }
}

private fun ForgePalette.pencilOrWarn(): Color = warn

@Composable
fun QuestionCard(item: FeedItem.Question, pending: Boolean, onAnswer: (String) -> Unit) {
  val p = LocalForgePalette.current
  var free by remember { mutableStateOf("") }
  Column(Modifier.fillMaxWidth().clip(shape()).border(1.dp, p.accent.copy(alpha = 0.5f), shape()).background(p.surface).padding(14.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = p.accent, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(item.question, style = ForgeType.bodyStrong, color = p.text)
    }
    if (item.answer != null) {
      Spacer(Modifier.height(8.dp))
      Text(if (item.answer.isEmpty()) "Sans réponse" else "→ ${item.answer}", style = ForgeType.mono, color = if (item.answer.isEmpty()) p.warn else p.accent)
    } else if (pending) {
      Spacer(Modifier.height(10.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in item.options) ForgeButton(o) { onAnswer(o) }
      }
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        ForgeTextField(free, { free = it }, "Ou réponds librement…", modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        ForgeButton("Envoyer", primary = true, enabled = free.isNotBlank()) { onAnswer(free) }
      }
    }
  }
}

/**
 * The approval card: Oui / Toujours pour… / Non, plutôt… (with a reason for the model) / Arrêter.
 * No answer within 5 minutes counts as « non ».
 */
@Composable
fun ApprovalCard(request: ApprovalRequest, onAnswer: (ApprovalAnswer) -> Unit) {
  val p = LocalForgePalette.current
  var refusing by remember(request.callId) { mutableStateOf(false) }
  var reason by remember(request.callId) { mutableStateOf("") }
  val edge = if (request.risky) p.warn else p.accent
  Column(Modifier.fillMaxWidth().clip(shape()).border(1.dp, edge, shape()).background(p.surface).padding(14.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(if (request.risky) Icons.Outlined.Warning else Icons.AutoMirrored.Outlined.HelpOutline, null, tint = edge, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(request.title, style = ForgeType.bodyStrong, color = p.text, modifier = Modifier.weight(1f))
    }
    if (request.escalation) {
      Spacer(Modifier.height(6.dp))
      Text("Déjà refusée une fois. Raison donnée : ${request.justification.orEmpty()}", style = ForgeType.monoSmall, color = p.warn)
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().clip(smallShape()).background(p.bg).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
      if (request.isDiff) DetailLines(request.detail, isDiff = true, maxFolded = 12)
      else Text(request.detail, style = ForgeType.mono, color = p.text, modifier = Modifier.padding(10.dp))
    }
    Spacer(Modifier.height(12.dp))
    if (refusing) {
      ForgeTextField(reason, { reason = it }, "Non, plutôt… (dit au modèle)", minLines = 1)
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ForgeButton("Refuser", primary = true, modifier = Modifier.weight(1f)) { onAnswer(ApprovalAnswer(ApprovalAnswer.Choice.NON, reason)) }
        ForgeButton("Retour", modifier = Modifier.weight(1f)) { refusing = false }
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ForgeButton("Oui", primary = true, icon = Icons.Outlined.Check, modifier = Modifier.weight(1f)) { onAnswer(ApprovalAnswer(ApprovalAnswer.Choice.OUI)) }
        ForgeButton("Non, plutôt…", modifier = Modifier.weight(1f)) { refusing = true }
      }
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        request.grantLabel?.let { g ->
          ForgeButton("Toujours pour $g", modifier = Modifier.weight(1.4f)) { onAnswer(ApprovalAnswer(ApprovalAnswer.Choice.TOUJOURS)) }
        }
        ForgeButton("Arrêter", dashed = true, icon = Icons.Outlined.Close, modifier = Modifier.weight(1f)) { onAnswer(ApprovalAnswer(ApprovalAnswer.Choice.ARRETER)) }
      }
      Spacer(Modifier.height(6.dp))
      Text("Sans réponse dans 5 min : refusé.", style = ForgeType.monoSmall, color = p.faint)
    }
  }
}

// ------------------------------------------------------------------------------------- basics

@Composable
fun ForgeButton(
  label: String,
  modifier: Modifier = Modifier,
  primary: Boolean = false,
  dashed: Boolean = false,
  enabled: Boolean = true,
  icon: ImageVector? = null,
  onClick: () -> Unit,
) {
  val p = LocalForgePalette.current
  val s = shape()
  val base = modifier.heightIn(min = 44.dp).clip(s)
  val styled =
    when {
      primary -> base.background(if (enabled) p.accent else p.line2)
      dashed ->
        base.drawBehind {
          drawRoundRect(
            p.line2,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(p.radius.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
          )
        }
      else -> base.border(BorderStroke(1.dp, p.line2), s)
    }
  Row(
    styled.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    val color = if (primary) p.onAccent else if (dashed) p.muted else p.text
    icon?.let {
      Icon(it, null, tint = color, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
    }
    Text(label, style = ForgeType.bodyStrong, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
fun ForgeTextField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, minLines: Int = 1, maxLines: Int = 6) {
  val p = LocalForgePalette.current
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    placeholder = { Text(placeholder, style = ForgeType.body, color = p.faint) },
    textStyle = ForgeType.body.copy(color = p.text),
    minLines = minLines,
    maxLines = maxLines,
    shape = shape(),
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.accent,
        unfocusedBorderColor = p.line2,
        cursorColor = p.accent,
        focusedContainerColor = p.surface,
        unfocusedContainerColor = p.surface,
      ),
    modifier = modifier.fillMaxWidth(),
  )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
  val p = LocalForgePalette.current
  Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(text.uppercase(), style = ForgeType.section, color = p.muted, modifier = Modifier.weight(1f))
    trailing?.invoke()
  }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
  Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}
