package com.google.ai.edge.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.common.AltrexAvatar
import com.google.ai.edge.gallery.ui.common.AltrexLogoLoader
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** [ALTREX] Quick actions on a text selected in another app. */
internal enum class AskAction(val label: String, val icon: ImageVector, val instruction: String) {
  EXPLAIN("Expliquer", Icons.Rounded.Lightbulb, "Explique-moi ce texte simplement."),
  SUMMARIZE("Résumer", Icons.AutoMirrored.Rounded.ShortText, "Résume ce texte en quelques phrases."),
  KEY_POINTS("Points clés", Icons.AutoMirrored.Rounded.FormatListBulleted, "Donne-moi les points clés de ce texte."),
  SECURITY(
    "Sécurité",
    Icons.Rounded.Shield,
    "Analyse ce contenu du point de vue de la sécurité : données sensibles, liens ou commandes " +
      "dangereuses, tentative d'arnaque, failles. Dis-moi ce que je dois faire.",
  ),
  FIX(
    "Corriger",
    Icons.Rounded.Spellcheck,
    "Corrige l'orthographe et la grammaire de ce texte. Renvoie seulement le texte corrigé.",
  ),
  TRANSLATE(
    "Traduire",
    Icons.Rounded.Translate,
    "Traduis ce texte en français, ou en anglais s'il est déjà en français.",
  ),
}

/** Quick actions on the text shown on screen (assistant). */
private val SCREEN_ACTIONS =
  listOf(
    Triple("Résumer", Icons.AutoMirrored.Rounded.ShortText, "Résume ce qui est affiché."),
    Triple("Expliquer", Icons.Rounded.Lightbulb, "Explique-moi ce qui est affiché, simplement."),
    Triple(
      "Sécurité",
      Icons.Rounded.Shield,
      "Ce contenu est-il sûr ? Repère les arnaques, liens ou demandes suspectes et dis-moi quoi faire.",
    ),
    Triple("Points clés", Icons.AutoMirrored.Rounded.FormatListBulleted, "Donne-moi les points clés de ce qui est affiché."),
  )

/** Sheet shown after "Demander à Altrex" on a text selection. */
@Composable
internal fun AskSheet(
  excerpt: String,
  onAction: (AskAction) -> Unit,
  onQuestion: (String) -> Unit,
  onOpenInChat: () -> Unit,
  onDismiss: () -> Unit,
) {
  var question by rememberSaveable { mutableStateOf("") }
  AltrexSheet(onDismiss = onDismiss) { close ->
    SheetHeader(onClose = { close(onDismiss) })
    ChipRow {
      AskAction.entries.forEachIndexed { index, action ->
        ActionChip(index, action.label, action.icon) { close { onAction(action) } }
      }
    }
    QuoteCard(excerpt)
    AskField(
      value = question,
      onValueChange = { question = it },
      placeholder = "Demande n'importe quoi sur ce texte…",
      onSend = { close { onQuestion(question.trim()) } },
      onOpenInChat = { close(onOpenInChat) },
    )
  }
}

/** Sheet opened by the phone's assistant gesture (side key, corner swipe…). */
@Composable
internal fun AssistantSheet(
  screenText: String,
  onSend: (question: String, useScreen: Boolean) -> Unit,
  onOpenApp: () -> Unit,
  onDismiss: () -> Unit,
) {
  var question by rememberSaveable { mutableStateOf("") }
  var useScreen by rememberSaveable { mutableStateOf(screenText.isNotEmpty()) }
  AltrexSheet(onDismiss = onDismiss) { close ->
    SheetHeader(onClose = { close(onDismiss) }, onOpenApp = { close(onOpenApp) })
    if (screenText.isNotEmpty()) {
      ScreenToggle(checked = useScreen, onToggle = { useScreen = !useScreen })
      AnimatedVisibility(
        visible = useScreen,
        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
      ) {
        ChipRow {
          SCREEN_ACTIONS.forEachIndexed { index, (label, icon, prompt) ->
            ActionChip(index, label, icon) { close { onSend(prompt, true) } }
          }
        }
      }
    }
    AskField(
      value = question,
      onValueChange = { question = it },
      placeholder = if (useScreen && screenText.isNotEmpty()) "Demande à propos de cet écran…" else "Demande à Altrex…",
      onSend = { close { onSend(question.trim(), useScreen) } },
      onOpenInChat = null,
      autoFocus = screenText.isEmpty(),
    )
  }
}

/** Small card while a shared file is being read. */
@Composable
internal fun SharePreparing() {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { shown = true }
  Box(
    modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp),
    contentAlignment = Alignment.BottomCenter,
  ) {
    AnimatedVisibility(
      visible = shown,
      enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
      ) {
        Row(
          modifier = Modifier.padding(start = 10.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          AltrexLogoLoader(size = 32.dp)
          Text("Préparation pour Altrex…", style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

/**
 * Bottom sheet over the current app: a light scrim, a sheet that springs up from the bottom, and a
 * matching exit before any action runs. It closes on back, on a tap outside, or when dragged down.
 * [content] receives `close(then)`, which plays the exit animation and then runs `then`.
 */
@Composable
private fun AltrexSheet(
  onDismiss: () -> Unit,
  content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit,
) {
  val visible = remember { MutableTransitionState(false).apply { targetState = true } }
  var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
  val close: (() -> Unit) -> Unit = { then ->
    if (afterClose == null) {
      afterClose = then
      visible.targetState = false
    }
  }
  LaunchedEffect(visible.isIdle, visible.currentState) {
    if (visible.isIdle && !visible.currentState) afterClose?.invoke()
  }
  BackHandler { close(onDismiss) }

  val scope = rememberCoroutineScope()
  val dragOffset = remember { Animatable(0f) }
  val dismissDistance = with(LocalDensity.current) { 110.dp.toPx() }

  Box(modifier = Modifier.fillMaxSize()) {
    AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
      Box(
        modifier =
          Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = null, indication = null) { close(onDismiss) }
      )
    }
    AnimatedVisibility(
      visibleState = visible,
      modifier = Modifier.align(Alignment.BottomCenter),
      enter =
        slideInVertically(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) { it } +
          fadeIn(tween(160)),
      exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
    ) {
      Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 12.dp,
        modifier =
          Modifier.fillMaxWidth()
            .offset { IntOffset(0, dragOffset.value.roundToInt()) }
            .draggable(
              orientation = Orientation.Vertical,
              state =
                rememberDraggableState { delta ->
                  scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
                },
              onDragStopped = {
                if (dragOffset.value > dismissDistance) {
                  close(onDismiss)
                } else {
                  dragOffset.animateTo(0f, spring(dampingRatio = 0.7f))
                }
              },
            ),
      ) {
        Column(
          modifier =
            Modifier.navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(
              modifier =
                Modifier.size(width = 36.dp, height = 4.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            )
          }
          content(close)
        }
      }
    }
  }
}

@Composable
private fun SheetHeader(onClose: () -> Unit, onOpenApp: (() -> Unit)? = null) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    AltrexAvatar(size = 28.dp)
    Spacer(Modifier.width(10.dp))
    Text(
      "Altrex",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.weight(1f),
    )
    if (onOpenApp != null) {
      IconButton(onClick = onOpenApp) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Ouvrir Altrex")
      }
    }
    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Fermer") }
  }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
  ) {
    content()
  }
}

/** A pill action that slides in after the sheet, slightly after the previous one. */
@Composable
private fun ActionChip(index: Int, label: String, icon: ImageVector, onClick: () -> Unit) {
  val haptic = LocalHapticFeedback.current
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "chipPress")
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    delay(90L + index * 45L)
    shown = true
  }
  val appear by
    animateFloatAsState(
      if (shown) 1f else 0f,
      spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
      label = "chipIn",
    )
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    modifier =
      Modifier.graphicsLayer {
          alpha = appear
          translationX = (1f - appear) * 24.dp.toPx()
          scaleX = pressScale
          scaleY = pressScale
        }
        .clip(CircleShape)
        .clickable(interactionSource = interaction, indication = null) {
          haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
          onClick()
        },
  ) {
    Row(
      modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
      Text(label, style = MaterialTheme.typography.labelLarge)
    }
  }
}

/** The selected text, three lines by default; a tap shows more. Code keeps a monospace font. */
@Composable
private fun QuoteCard(text: String) {
  var expanded by remember { mutableStateOf(false) }
  val looksLikeCode = remember(text) { text.count { it == ';' || it == '{' || it == '}' } >= 3 }
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { expanded = !expanded },
  ) {
    Row(modifier = Modifier.padding(14.dp).animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
      Icon(
        Icons.Rounded.FormatQuote,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(10.dp))
      Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = if (looksLikeCode) FontFamily.Monospace else null,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) 12 else 3,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ScreenToggle(checked: Boolean, onToggle: () -> Unit) {
  val container by
    animateColorAsState(
      if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
      label = "screenToggle",
    )
  val onContainer by
    animateColorAsState(
      if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
      label = "screenToggleText",
    )
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = container,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onToggle),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(Icons.AutoMirrored.Rounded.ScreenShare, contentDescription = null, tint = onContainer)
      Column(modifier = Modifier.weight(1f)) {
        Text(
          if (checked) "Altrex voit le texte de l'écran" else "Écran non partagé",
          style = MaterialTheme.typography.labelLarge,
          color = onContainer,
        )
        Text(
          if (checked) "Touche pour ne pas l'utiliser" else "Touche pour poser une question sur l'écran",
          style = MaterialTheme.typography.bodySmall,
          color = onContainer.copy(alpha = 0.75f),
        )
      }
    }
  }
}

/**
 * The question field, as a pill. Its round button sends the question; while the field is empty it
 * opens the chat instead (when [onOpenInChat] is given), with the text ready to be completed.
 */
@Composable
private fun AskField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  onSend: () -> Unit,
  onOpenInChat: (() -> Unit)?,
  autoFocus: Boolean = false,
) {
  val canSend = value.isNotBlank()
  val focus = remember { FocusRequester() }
  if (autoFocus) {
    LaunchedEffect(Unit) {
      delay(250)
      runCatching { focus.requestFocus() }
    }
  }
  Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
      TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
        colors =
          TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
          ),
        modifier = Modifier.weight(1f).focusRequester(focus),
      )
      val showButton = canSend || onOpenInChat != null
      AnimatedVisibility(visible = showButton, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        val background by
          animateColorAsState(
            if (canSend) MaterialTheme.colorScheme.primary else Color.Transparent,
            label = "sendBackground",
          )
        Box(
          modifier =
            Modifier.size(44.dp)
              .clip(CircleShape)
              .background(background)
              .clickable { if (canSend) onSend() else onOpenInChat?.invoke() },
          contentAlignment = Alignment.Center,
        ) {
          AnimatedContent(
            targetState = canSend,
            transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
            label = "sendIcon",
          ) { send ->
            if (send) {
              Icon(Icons.Rounded.ArrowUpward, "Envoyer", tint = MaterialTheme.colorScheme.onPrimary)
            } else {
              Icon(
                Icons.AutoMirrored.Rounded.OpenInNew,
                "Ouvrir dans le chat",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}
