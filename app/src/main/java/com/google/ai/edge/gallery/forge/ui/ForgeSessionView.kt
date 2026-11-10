package com.google.ai.edge.gallery.forge.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.forge.engine.ApprovalAnswer
import com.google.ai.edge.gallery.forge.engine.ApprovalRequest
import com.google.ai.edge.gallery.forge.engine.ForgeEngine
import com.google.ai.edge.gallery.forge.engine.ForgeMode
import com.google.ai.edge.gallery.forge.engine.Preset
import com.google.ai.edge.gallery.forge.journal.FeedItem
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.journal.SessionState
import com.google.ai.edge.gallery.forge.workspace.ForgeCommands

/** Everything the session view shows (one immutable value: easy to draw in tests). */
data class SessionUi(
  val project: String,
  val branch: String?,
  val model: String,
  val local: Boolean,
  val state: SessionState,
  val live: ForgeEngine.Live,
  val running: Boolean,
  val contextRatio: Float,
  val trusted: Boolean,
  val approval: ApprovalRequest?,
  val questionId: String?,
  val planId: String?,
  /** The project folder on the phone (produced files: share, copy). */
  val projectRoot: java.io.File = java.io.File("."),
)

/** Callbacks of the session view. */
data class SessionActions(
  val onBack: () -> Unit = {},
  val onSend: (String) -> Unit = {},
  val onStop: () -> Unit = {},
  val onMode: (ForgeMode) -> Unit = {},
  val onApprove: (ApprovalAnswer) -> Unit = {},
  val onAnswer: (String) -> Unit = {},
  val onPlanValidate: () -> Unit = {},
  val onPlanChanges: (String) -> Unit = {},
  val onPlanRefuse: () -> Unit = {},
  val onTrust: () -> Unit = {},
  val onPickModel: () -> Unit = {},
  val onPreview: (String?) -> Unit = {},
  val onCommand: (String) -> Unit = {},
  /** 📎: picks a file, copies it into the project, then gives its `@` mention to insert. */
  val onAttach: ((String) -> Unit) -> Unit = {},
  /** The project's files, for the `@` picker. */
  val listFiles: () -> List<String> = { emptyList() },
)

/** [ALTREX] A Forge session (maquette B-technique/02-session; C when the Plan mode is on). */
@Composable
fun ForgeSessionView(ui: SessionUi, actions: SessionActions) {
  val plan = ui.state.mode.isPlan
  val p = if (plan) ForgePalette.C else ForgePalette.B
  androidx.compose.runtime.CompositionLocalProvider(LocalForgePalette provides p) {
    var showModes by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(p.bg)) {
      if (p.grid) BlueprintGrid()
      Column(Modifier.fillMaxSize()) {
        ForgeTopBar(
          title = ui.project,
          subtitle = ui.state.title.ifEmpty { "Nouvelle session" },
          onMenu = actions.onBack,
          navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
        ) {
          IconButton(onClick = { actions.onPreview(null) }) { Icon(Icons.Outlined.Visibility, "Aperçu", tint = p.muted) }
          Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Plus", tint = p.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              for ((label, cmd) in listOf("Nouvelle session" to "/nouvelle", "Modifications (/diff)" to "/diff", "Annuler le dernier tour (/undo)" to "/undo", "Instantanés (/checkpoints)" to "/checkpoints", "Vérifier les outils (/doctor)" to "/doctor", "Mémoire du projet (/init)" to "/init", "Scanner avec SecurCode" to "/scan", "Commandes (/aide)" to "/aide")) {
                DropdownMenuItem(text = { Text(label) }, onClick = {
                  menu = false
                  actions.onCommand(cmd)
                })
              }
            }
          }
        }
        StatusLine(ui, actions.onPickModel)
        if (!ui.trusted) TrustBanner(actions.onTrust)
        TasksPanel(ui.state.todos, animate = ui.running)
        Feed(ui, actions, Modifier.weight(1f))
        InputBar(ui, actions, onModes = { showModes = true })
      }
    }
    if (showModes) {
      ModeSheet(current = ui.state.mode, onPick = {
        showModes = false
        actions.onMode(it)
      }, onDismiss = { showModes = false })
    }
  }
}

/** The model's name for the status line: without its provider path (groq/openai/gpt-oss-120b → gpt-oss-120b). */
internal fun shortModelName(name: String, max: Int = 22): String {
  val n = name.substringAfterLast('/').substringAfterLast(':').ifEmpty { name }
  return if (n.length > max) n.take(max - 1) + "…" else n
}

@Composable
private fun StatusLine(ui: SessionUi, onPickModel: () -> Unit) {
  val p = LocalForgePalette.current
  Column {
    // Narrow screens: shorter labels; the line also scrolls sideways, nothing is cut.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
      val narrow = maxWidth < 420.dp
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ui.branch?.let {
          Text("⑂ ${if (narrow && it.length > 14) it.take(13) + "…" else it}", style = ForgeType.monoSmall, color = p.text, maxLines = 1)
          Text(if (narrow) " · " else "  ·  ", style = ForgeType.monoSmall, color = p.faint)
        }
        Row(Modifier.clickable(onClick = onPickModel), verticalAlignment = Alignment.CenterVertically) {
          Dot(if (ui.running) p.accent else p.ok)
          Spacer(Modifier.width(6.dp))
          Text(if (narrow) shortModelName(ui.model, 18) else shortModelName(ui.model, 32), style = ForgeType.monoSmall, color = p.text, maxLines = 1)
        }
        Text(if (narrow) " · ctx " else "  ·  contexte ", style = ForgeType.monoSmall, color = p.muted, maxLines = 1)
        Box(Modifier.width(if (narrow) 40.dp else 70.dp).height(4.dp).clip(smallShape()).background(p.line2)) {
          Box(Modifier.fillMaxWidth(ui.contextRatio.coerceIn(0f, 1f)).height(4.dp).background(if (ui.contextRatio > 0.85f) p.warn else p.accent))
        }
        Text(" ${(ui.contextRatio * 100).toInt().coerceIn(0, 100)} %", style = ForgeType.monoSmall, color = p.muted, maxLines = 1)
        if (ui.local && ui.live.tokensPerSecond > 0f) {
          Text("  ·  ${"%.1f".format(ui.live.tokensPerSecond)} t/s", style = ForgeType.monoSmall, color = p.muted, maxLines = 1)
        }
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
  }
}

@Composable
private fun TrustBanner(onTrust: () -> Unit) {
  val p = LocalForgePalette.current
  Row(Modifier.fillMaxWidth().background(p.warn.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
      "Projet venu de l'extérieur : son ALTREX.md n'est pas donné à l'agent.",
      style = ForgeType.body,
      color = p.warn,
      modifier = Modifier.weight(1f),
    )
    Text("Faire confiance", style = ForgeType.bodyStrong, color = p.accent, modifier = Modifier.clickable(onClick = onTrust).padding(8.dp))
  }
}

/** The pinned task list: « Tâches 2/5 », one segment per task, folded on a tap. */
@Composable
private fun TasksPanel(todos: List<ForgeEvent.Todo>, animate: Boolean) {
  if (todos.isEmpty()) return
  val p = LocalForgePalette.current
  var open by rememberSaveable { mutableStateOf(true) }
  val done = todos.count { it.status == "completed" }
  // The square of the task in progress blinks gently while the agent works (1.6 s cycle).
  val pulse =
    if (animate) rememberInfiniteTransition(label = "tache").animateFloat(1f, 0.5f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse").value
    else 1f
  Column(Modifier.fillMaxWidth().background(p.surface).animateContentSize(tween(160))) {
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.AutoMirrored.Outlined.List, null, tint = p.muted, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(10.dp))
      Text("Tâches", style = ForgeType.bodyStrong, color = p.text)
      Spacer(Modifier.width(8.dp))
      Text("$done/${todos.size}", style = ForgeType.mono, color = p.accent)
      Spacer(Modifier.width(12.dp))
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in todos) {
          Box(
            Modifier.weight(1f).height(4.dp).clip(smallShape()).background(
              when (t.status) {
                "completed" -> p.accent
                "in_progress" -> p.accent.copy(alpha = 0.55f * pulse)
                else -> p.line2
              }
            )
          )
        }
      }
      Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = p.muted)
    }
    if (open) {
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
        for (t in todos) {
          Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
              Modifier.size(20.dp).clip(smallShape()).border(1.dp, if (t.status == "pending") p.line2 else p.accent, smallShape()),
              contentAlignment = Alignment.Center,
            ) {
              when (t.status) {
                "completed" -> Text("✓", style = ForgeType.monoSmall, color = p.accent)
                "in_progress" -> Box(Modifier.size(10.dp).alpha(pulse).background(p.accent))
              }
            }
            Spacer(Modifier.width(12.dp))
            Text(
              t.content,
              style = ForgeType.body.copy(textDecoration = if (t.status == "completed") androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
              color = if (t.status == "completed") p.faint else p.text,
              modifier = Modifier.weight(1f),
            )
            if (t.status == "in_progress") Text("en cours", style = ForgeType.monoSmall, color = p.accent)
          }
        }
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
  }
}

@Composable
private fun Feed(ui: SessionUi, a: SessionActions, modifier: Modifier) {
  val p = LocalForgePalette.current
  val listState = rememberLazyListState()
  val items = ui.state.feed
  val liveText = ui.live.text
  val showDeliverables = !ui.running && ui.state.lastTurnFiles.isNotEmpty()
  LaunchedEffect(items.size, liveText.length / 80, ui.approval?.callId, ui.running) {
    val count = listState.layoutInfo.totalItemsCount
    if (count > 0) listState.animateScrollToItem(count - 1)
  }
  LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (items.isEmpty() && !ui.running) {
      item {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
          Text("Que construit-on ?", style = ForgeType.title, color = p.text)
          Text(
            "Décris ce que tu veux : Forge lit le projet, propose, modifie et vérifie. Tape / pour les commandes (/init écrit la mémoire du projet).",
            style = ForgeType.body,
            color = p.muted,
            modifier = Modifier.padding(top = 6.dp),
          )
        }
      }
    }
    items(items, key = { feedKey(it) }) { it ->
      when (it) {
        is FeedItem.UserText -> UserBubble(it.text)
        is FeedItem.AssistantText -> AssistantBlock(it.text, it.thinking)
        is FeedItem.ToolCard -> ToolCard(it, if (it.result == null) ui.live.toolProgress else null, ui.running)
        is FeedItem.Note -> NoteBlock(it.kind, it.text)
        is FeedItem.TurnEnd -> TurnEndBlock(it.reason, it.detail)
        is FeedItem.Plan -> PlanCard(it, pending = ui.planId == it.id, onValidate = a.onPlanValidate, onChanges = a.onPlanChanges, onRefuse = a.onPlanRefuse)
        is FeedItem.Question -> QuestionCard(it, pending = ui.questionId == it.id, onAnswer = a.onAnswer)
      }
    }
    if (ui.running && (liveText.isNotEmpty() || ui.live.thinking.isNotEmpty())) {
      item(key = "live") { AssistantBlock(liveText, ui.live.thinking) }
    }
    ui.approval?.let { req -> item(key = "approval-" + req.callId) { ApprovalCard(req, a.onApprove) } }
    if (ui.running && ui.approval == null && liveText.isEmpty() && ui.live.tool == null) {
      item(key = "thinking") { Text("▸ Forge réfléchit… (étape ${ui.live.step})", style = ForgeType.monoSmall, color = p.faint) }
    }
    if (showDeliverables) {
      item(key = "livrables") {
        Column {
          Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
          Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("FICHIERS PRODUITS", style = ForgeType.section, color = p.muted)
            for (f in ui.state.lastTurnFiles.take(8)) {
              Spacer(Modifier.width(10.dp))
              DeliverableChip(f, ui.projectRoot, a.onPreview)
            }
          }
        }
      }
    }
  }
}

private fun feedKey(i: FeedItem): String =
  when (i) {
    is FeedItem.ToolCard -> "t-" + i.id
    is FeedItem.Plan -> "p-" + i.id
    is FeedItem.Question -> "q-" + i.id
    else -> i.javaClass.simpleName + "-" + i.turn + "-" + i.hashCode()
  }

@Composable
private fun InputBar(ui: SessionUi, a: SessionActions, onModes: () -> Unit) {
  val p = LocalForgePalette.current
  var text by rememberSaveable { mutableStateOf("") }
  val suggestions = ForgeCommands.suggestions(text)
  // `@` picker: the token being typed after an @, matched against the project's files.
  val mentionQuery = Regex("(?:^|\\s)@([\\w.\\-/]*)$").find(text)?.groupValues?.get(1)
  val allFiles = remember(mentionQuery != null) { if (mentionQuery != null) a.listFiles() else emptyList() }
  val mentionHits = if (mentionQuery != null) com.google.ai.edge.gallery.forge.tools.Mentions.fuzzy(mentionQuery, allFiles, 6) else emptyList()
  Column(Modifier.fillMaxWidth().background(p.surface)) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    if (suggestions.isNotEmpty()) {
      Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
        for (c in suggestions.take(6)) {
          Row(Modifier.fillMaxWidth().clickable { text = "/" + c.name + " " }.padding(vertical = 6.dp)) {
            Text(c.usage, style = ForgeType.mono, color = p.accent, modifier = Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.help, style = ForgeType.body, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
    if (mentionHits.isNotEmpty()) {
      Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
        for (f in mentionHits) {
          Row(Modifier.fillMaxWidth().clickable {
            text = text.dropLast(mentionQuery!!.length) + f + " "
          }.padding(vertical = 6.dp)) {
            Text("@" + f.substringAfterLast('/'), style = ForgeType.mono, color = p.accent, modifier = Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f, style = ForgeType.monoSmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
    Column(Modifier.padding(12.dp)) {
      ForgeTextField(text, { text = it }, if (ui.state.mode.isPlan) "Décris ce qu'il faut planifier…" else "Demande une modification…", maxLines = 6)
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        val mode = ui.state.mode
        Row(
          Modifier.clip(shape()).border(1.dp, if (mode.preset == Preset.TOUT_AUTORISER) p.warn else p.line2, shape()).clickable(onClick = onModes).padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(modeIcon(mode), null, tint = if (mode.preset == Preset.TOUT_AUTORISER) p.warn else p.accent, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(mode.label, style = ForgeType.bodyStrong, color = p.text)
          Icon(Icons.Outlined.ExpandMore, null, tint = p.muted, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { if (!text.startsWith("/")) text = "/" + text }) { Text("/", style = ForgeType.title, color = p.muted) }
        IconButton(onClick = { text = if (text.isEmpty() || text.endsWith(" ")) "$text@" else "$text @" }) { Text("@", style = ForgeType.title, color = p.muted) }
        IconButton(onClick = { a.onAttach { m -> text = (text.trimEnd() + " " + m + " ").trimStart() } }) { Icon(Icons.Outlined.AttachFile, "Joindre un fichier", tint = p.muted) }
        Spacer(Modifier.weight(1f))
        if (ui.running) {
          Box(Modifier.size(52.dp).clip(shape()).border(1.dp, p.del, shape()).clickable(onClick = a.onStop), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Stop, "Arrêter", tint = p.del)
          }
        } else {
          val enabled = text.isNotBlank()
          Box(
            Modifier.size(52.dp).clip(shape()).background(if (enabled) p.accent else p.line2).clickable(enabled = enabled) {
              val t = text.trim()
              text = ""
              if (ForgeCommands.parse(t) != null) a.onCommand(t) else a.onSend(t)
            },
            contentAlignment = Alignment.Center,
          ) {
            Icon(Icons.Outlined.ArrowUpward, "Envoyer", tint = if (enabled) p.onAccent else p.faint)
          }
        }
      }
    }
  }
}

internal fun modeIcon(mode: ForgeMode) =
  when (mode.preset) {
    Preset.DEMANDER -> Icons.AutoMirrored.Outlined.HelpOutline
    Preset.EDITIONS_AUTO -> Icons.Outlined.Edit
    Preset.PLAN -> Icons.Outlined.Description
    Preset.TOUT_AUTORISER -> Icons.Outlined.Bolt
    null -> Icons.Outlined.Tune
  }
