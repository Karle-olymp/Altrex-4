package com.google.ai.edge.gallery.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.common.AltrexAvatar
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mms
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.delay

private data class DrawerEntry(
  val taskId: String,
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val gradientIndex: Int,
)

private val SPACES =
  listOf(
    DrawerEntry(BuiltInTaskId.LLM_CHAT, "Discuter", "Chat avec un modèle local", Icons.Outlined.Forum, 2),
    DrawerEntry(
      BuiltInTaskId.LLM_PROMPT_LAB,
      "Code",
      "Écrire, expliquer et corriger du code",
      Icons.Outlined.Code,
      3,
    ),
    DrawerEntry(
      BuiltInTaskId.LLM_SECURITY,
      "Sécurité",
      "Analyse de code avec SecurCode",
      Icons.Outlined.Shield,
      0,
    ),
  )

private val TOOLS =
  listOf(
    DrawerEntry(
      BuiltInTaskId.LLM_AGENT_CHAT,
      "Agent Skills",
      "Tâches agentiques par chat",
      Icons.Rounded.Flag,
      1,
    ),
    DrawerEntry(
      BuiltInTaskId.LLM_ASK_IMAGE,
      "Ask Image",
      "Poser des questions sur des images",
      Icons.Outlined.Mms,
      2,
    ),
    DrawerEntry(
      BuiltInTaskId.LLM_ASK_AUDIO,
      "Audio Scribe",
      "Transcrire et traduire de l'audio",
      Icons.Outlined.Mic,
      0,
    ),
  )

/**
 * Altrex side menu: logo, loaded model, then a single list of spaces and tools. The active entry
 * sits on an animated pill; rows slide in one after the other when the menu opens.
 */
@Composable
fun AltrexDrawer(
  isOpen: Boolean,
  currentTaskId: String?,
  loadedModelName: String?,
  onOpenTask: (String) -> Unit,
  onOpenModels: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  ModalDrawerSheet(
    drawerContainerColor = MaterialTheme.colorScheme.surface,
    drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
  ) {
    Column(modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp)) {
      Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        StaggerIn(index = 0, trigger = isOpen) { DrawerHeader() }
        StaggerIn(index = 1, trigger = isOpen) {
          LoadedModelCard(modelName = loadedModelName, onClick = onOpenModels)
        }

        StaggerIn(index = 2, trigger = isOpen) { SectionLabel("Espaces") }
        SPACES.forEachIndexed { i, entry ->
          StaggerIn(index = 3 + i, trigger = isOpen) {
            NavRow(
              title = entry.title,
              subtitle = entry.subtitle,
              icon = entry.icon,
              active = entry.taskId == currentTaskId,
              onClick = { onOpenTask(entry.taskId) },
            )
          }
        }

        StaggerIn(index = 6, trigger = isOpen) { SectionLabel("Outils") }
        TOOLS.forEachIndexed { i, entry ->
          StaggerIn(index = 7 + i, trigger = isOpen) {
            NavRow(
              title = entry.title,
              subtitle = entry.subtitle,
              icon = entry.icon,
              active = entry.taskId == currentTaskId,
              onClick = { onOpenTask(entry.taskId) },
            )
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
      }

      // Models and settings stay at the bottom, always visible.
      StaggerIn(index = 10, trigger = isOpen) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .padding(bottom = 10.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
          )
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BottomButton(
              label = "Modèles",
              icon = Icons.AutoMirrored.Rounded.ListAlt,
              onClick = onOpenModels,
              modifier = Modifier.weight(1f),
            )
            BottomButton(
              label = "Réglages",
              icon = Icons.Rounded.Settings,
              onClick = onOpenSettings,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}

/** Fades and slides its content in, with a delay that grows with [index], each time [trigger] turns on. */
@Composable
private fun StaggerIn(index: Int, trigger: Boolean, content: @Composable () -> Unit) {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(trigger) {
    if (trigger) {
      shown = false
      delay(30L + index * 35L)
      shown = true
    } else {
      shown = false
    }
  }
  val progress by
    animateFloatAsState(
      targetValue = if (shown) 1f else 0f,
      animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
      label = "drawerStagger",
    )
  Box(
    modifier =
      Modifier.graphicsLayer {
        alpha = progress.coerceIn(0f, 1f)
        translationX = (1f - progress) * -24.dp.toPx()
      }
  ) {
    content()
  }
}

@Composable
private fun DrawerHeader() {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 24.dp, bottom = 20.dp),
  ) {
    AltrexAvatar(size = 44.dp)
    Column(modifier = Modifier.padding(start = 14.dp)) {
      Text(
        "Altrex",
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        "IA locale · hors ligne",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text.uppercase(),
    style =
      MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp),
  )
}

/** Card with the loaded model and a dot that pulses while a model is loaded. Opens the models. */
@Composable
private fun LoadedModelCard(modelName: String?, onClick: () -> Unit) {
  val pulse by
    rememberInfiniteTransition(label = "modelPulse")
      .animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), repeatMode = RepeatMode.Reverse),
        label = "pulse",
      )
  val loaded = modelName != null
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(
          1.dp,
          MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
          RoundedCornerShape(18.dp),
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(14.dp)) {
      if (loaded) {
        Box(
          modifier =
            Modifier.size(14.dp)
              .graphicsLayer {
                scaleX = 0.6f + 0.4f * pulse
                scaleY = 0.6f + 0.4f * pulse
                alpha = 1f - pulse * 0.7f
              }
              .clip(CircleShape)
              .background(LoadedGreen)
        )
      }
      Box(
        modifier =
          Modifier.size(8.dp)
            .clip(CircleShape)
            .background(if (loaded) LoadedGreen else MaterialTheme.colorScheme.outline)
      )
    }
    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
      Text(
        if (loaded) "Modèle chargé" else "Aucun modèle chargé",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (modelName != null) {
        Text(
          modelName,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
        )
      }
    }
    Icon(
      Icons.AutoMirrored.Rounded.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
  }
}

private val LoadedGreen = Color(0xFF5FD4A0)

/** One menu entry. The active one sits on a tinted pill, its icon and title in the accent color. */
@Composable
private fun NavRow(
  title: String,
  subtitle: String,
  icon: ImageVector,
  active: Boolean,
  onClick: () -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by
    animateFloatAsState(
      targetValue = if (pressed) 0.97f else 1f,
      animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
      label = "rowPress",
    )
  val pill by
    animateColorAsState(
      targetValue =
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
      animationSpec = tween(250),
      label = "rowPill",
    )
  val accent by
    animateColorAsState(
      targetValue =
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      animationSpec = tween(250),
      label = "rowAccent",
    )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .padding(vertical = 2.dp)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .clip(RoundedCornerShape(16.dp))
        .background(pill)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 11.dp),
  ) {
    Icon(
      icon,
      contentDescription = null,
      tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(22.dp),
    )
    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = accent,
      )
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (active) {
      Box(
        modifier =
          Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
      )
    }
  }
}

@Composable
private fun BottomButton(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
    modifier =
      modifier
        .clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .clickable(onClick = onClick)
        .padding(vertical = 12.dp),
  ) {
    Icon(
      icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(18.dp),
    )
    Text(
      label,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}
