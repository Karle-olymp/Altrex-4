package com.google.ai.edge.gallery.notes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.common.BrandCyan
import com.google.ai.edge.gallery.ui.common.BrandGreen
import com.google.ai.edge.gallery.ui.theme.codeFontFamily

/**
 * [ALTREX] The recording screen, stateless: what is heard appears live, the big button pauses or
 * resumes, "Terminer" saves. When on-device recognition is missing, it says so and offers the
 * explicit choices (download the language, or use the system service knowingly).
 */
@Composable
fun RecordingContent(
  state: RecordingState,
  availability: RecognizerAvailability,
  reducedMotion: Boolean,
  onToggle: () -> Unit,
  onFinish: () -> Unit,
  onDiscard: () -> Unit,
  onClose: () -> Unit,
  onDownloadLanguage: () -> Unit,
  onUseSystemService: () -> Unit,
  onRetryCheck: () -> Unit,
) {
  val ready = state.recording || availability is RecognizerAvailability.OnDevice || state.usingSystemService
  Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
  Column(Modifier.fillMaxSize().navigationBarsPadding()) {
    // Header: close, title, where the recognition runs.
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Fermer") }
      Text("Nouvelle note", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      if (ready) PrivacyBadge(systemService = state.usingSystemService)
    }

    // Transcript, live.
    val scroll = rememberScrollState()
    LaunchedEffect(state.fullText.length) { scroll.animateScrollTo(scroll.maxValue) }
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
      if (!ready) {
        AvailabilityCard(availability, onDownloadLanguage, onUseSystemService, onRetryCheck, Modifier.align(Alignment.Center))
      } else if (state.fullText.isBlank()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            if (state.paused) "En pause" else "Parle librement…",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
          Spacer(Modifier.height(10.dp))
          Text(
            "Les pauses ne coupent pas l'écoute. Seul le texte est gardé, jamais l'audio.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      } else {
        val partialColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        Text(
          buildAnnotatedString {
            append(state.committed)
            if (state.partial.isNotBlank()) {
              if (state.committed.isNotBlank()) append(" ")
              withStyle(SpanStyle(color = partialColor)) { append(state.partial) }
            }
          },
          style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
          modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = 16.dp),
        )
      }
    }

    state.message?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
      )
    }

    // Timer and status.
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        formatElapsed(state.elapsedMs),
        fontFamily = codeFontFamily,
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (state.recording) 0.9f else 0.4f),
      )
      Text(
        when {
          !state.recording -> " "
          state.paused -> "En pause"
          else -> "J'écoute"
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Controls: discard, record/pause, finish.
    Row(
      Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SideButton(Icons.Outlined.DeleteOutline, "Abandonner", enabled = state.recording, onClick = onDiscard)
      RecordButton(
        listening = state.recording && !state.paused,
        level = state.level,
        enabled = ready,
        reducedMotion = reducedMotion,
        onClick = onToggle,
      )
      SideButton(Icons.Outlined.Check, "Terminer", enabled = state.recording && state.fullText.isNotBlank(), onClick = onFinish, prominent = true)
    }
  }
  }
}

@Composable
private fun PrivacyBadge(systemService: Boolean) {
  val (icon, label) =
    if (systemService) Icons.Outlined.CloudQueue to "Service système" else Icons.Outlined.Lock to "Sur l'appareil"
  Row(
    Modifier.clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
      .padding(horizontal = 10.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun AvailabilityCard(
  availability: RecognizerAvailability,
  onDownloadLanguage: () -> Unit,
  onUseSystemService: () -> Unit,
  onRetryCheck: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      when (availability) {
        RecognizerAvailability.Checking,
        RecognizerAvailability.OnDevice -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Préparation de la reconnaissance vocale…", style = MaterialTheme.typography.bodyMedium)
          }
        }
        is RecognizerAvailability.LanguageDownloadable -> {
          Text("Pack de langue à installer", style = MaterialTheme.typography.titleMedium)
          Text(
            "La reconnaissance sur l'appareil existe pour « ${availability.language} » mais n'est pas encore installée. " +
              "Le téléchargement est fait par Android, une seule fois.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FilledTonalButton(onClick = onDownloadLanguage) { Text("Télécharger le pack de langue") }
          SystemServiceChoice(onUseSystemService)
        }
        is RecognizerAvailability.LanguageDownloading -> {
          Text("Téléchargement du pack « ${availability.language} »", style = MaterialTheme.typography.titleMedium)
          Text(
            "Android installe la reconnaissance hors ligne. Réessaie dans un moment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedButton(onClick = onRetryCheck) { Text("Réessayer") }
        }
        is RecognizerAvailability.OnDeviceUnavailable -> {
          Text("Reconnaissance hors ligne indisponible", style = MaterialTheme.typography.titleMedium)
          Text(availability.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (availability.systemAvailable) SystemServiceChoice(onUseSystemService)
          else Text("Aucun service de reconnaissance vocale n'est installé.", style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

@Composable
private fun SystemServiceChoice(onUseSystemService: () -> Unit) {
  Text(
    "Tu peux utiliser le service de reconnaissance du système à la place : selon le téléphone, il peut envoyer l'audio à son fournisseur (Google, Samsung…).",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  TextButton(onClick = onUseSystemService) { Text("Utiliser le service du système") }
}

/** The big button: brand gradient disc, rings that breathe with the voice. */
@Composable
private fun RecordButton(listening: Boolean, level: Float, enabled: Boolean, reducedMotion: Boolean, onClick: () -> Unit) {
  val smoothLevel by animateFloatAsState(if (listening) level else 0f, spring(stiffness = 300f), label = "level")
  val pulse =
    if (listening && !reducedMotion) {
      val t = rememberInfiniteTransition(label = "pulse")
      t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "pulse").value
    } else {
      0f
    }
  val press by animateFloatAsState(if (listening) 1f else 0.94f, tween(220), label = "press")
  Box(
    Modifier.size(132.dp)
      .clickable(
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onClickLabel = if (listening) "Mettre en pause" else "Écouter",
        onClick = onClick,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val c = Offset(size.width / 2f, size.height / 2f)
      val base = 36.dp.toPx() * press
      if (listening) {
        // A ring that follows the voice, and a slow wave going out.
        drawCircle(BrandCyan, radius = base + 4.dp.toPx() + 22.dp.toPx() * smoothLevel, center = c, alpha = 0.10f + 0.08f * smoothLevel)
        if (!reducedMotion) {
          drawCircle(
            BrandGreen,
            radius = base + 30.dp.toPx() * pulse,
            center = c,
            alpha = 0.35f * (1f - pulse),
            style = Stroke(1.5.dp.toPx()),
          )
        }
      }
      drawCircle(
        Brush.linearGradient(listOf(BrandCyan, BrandGreen), start = Offset(c.x - base, c.y - base), end = Offset(c.x + base, c.y + base)),
        radius = base,
        center = c,
        alpha = if (enabled) 1f else 0.35f,
      )
    }
    Icon(
      if (listening) Icons.Outlined.Pause else Icons.Outlined.Mic,
      contentDescription = if (listening) "Pause" else "Écouter",
      tint = Color(0xFF0C151B),
      modifier = Modifier.size(32.dp),
    )
  }
}

@Composable
private fun SideButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, prominent: Boolean = false) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier.size(56.dp)
        .clip(CircleShape)
        .background(
          if (prominent && enabled) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        icon,
        contentDescription = label,
        tint =
          when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            prominent -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.onSurface
          },
      )
    }
    Spacer(Modifier.height(6.dp))
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.8f else 0.35f),
    )
  }
}

fun formatElapsed(ms: Long): String {
  val s = ms / 1000
  return "%02d:%02d".format(s / 60, s % 60)
}
