package com.google.ai.edge.gallery.ui.home

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

/** Altrex side menu: spaces first, then tools, then models and settings. Rows slide in one by one. */
@Composable
fun AltrexDrawer(
  isOpen: Boolean,
  onOpenTask: (String) -> Unit,
  onOpenModels: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
    Column(
      modifier =
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
      StaggerIn(index = 0, trigger = isOpen) { DrawerHeader() }

      StaggerIn(index = 1, trigger = isOpen) { SectionLabel("ESPACES") }
      SPACES.forEachIndexed { i, entry ->
        StaggerIn(index = 2 + i, trigger = isOpen) {
          SpaceCard(entry = entry, onClick = { onOpenTask(entry.taskId) })
        }
        Spacer(modifier = Modifier.height(10.dp))
      }

      Spacer(modifier = Modifier.height(8.dp))
      StaggerIn(index = 5, trigger = isOpen) { SectionLabel("OUTILS") }
      TOOLS.forEachIndexed { i, entry ->
        StaggerIn(index = 6 + i, trigger = isOpen) {
          ToolRow(
            title = entry.title,
            subtitle = entry.subtitle,
            icon = entry.icon,
            onClick = { onOpenTask(entry.taskId) },
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
      StaggerIn(index = 9, trigger = isOpen) {
        Box(
          modifier =
            Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant)
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
      StaggerIn(index = 10, trigger = isOpen) {
        ToolRow(
          title = "Modèles",
          subtitle = "Importer, choisir et gérer les modèles",
          icon = Icons.AutoMirrored.Rounded.ListAlt,
          onClick = onOpenModels,
        )
      }
      StaggerIn(index = 11, trigger = isOpen) {
        ToolRow(
          title = "Réglages",
          subtitle = "Thème et préférences de l'application",
          icon = Icons.Rounded.Settings,
          onClick = onOpenSettings,
        )
      }
      Spacer(modifier = Modifier.height(24.dp))
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
      delay(40L + index * 50L)
      shown = true
    } else {
      shown = false
    }
  }
  val progress by
    animateFloatAsState(
      targetValue = if (shown) 1f else 0f,
      animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
      label = "drawerStagger",
    )
  Box(
    modifier =
      Modifier.graphicsLayer {
        alpha = progress
        translationX = (1f - progress) * -32.dp.toPx()
      }
  ) {
    content()
  }
}

@Composable
private fun DrawerHeader() {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp),
  ) {
    Box(
      modifier =
        Modifier.size(52.dp)
          .clip(RoundedCornerShape(16.dp))
          .background(
            Brush.linearGradient(
              listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
            )
          ),
      contentAlignment = Alignment.Center,
    ) {
      Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.size(76.dp),
      )
    }
    Column(modifier = Modifier.padding(start = 14.dp)) {
      Text(
        "Altrex",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
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
    text,
    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
  )
}

@Composable
private fun SpaceCard(entry: DrawerEntry, onClick: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by
    animateFloatAsState(
      targetValue = if (pressed) 0.97f else 1f,
      animationSpec = tween(durationMillis = 120),
      label = "cardPress",
    )
  val gradient = MaterialTheme.customColors.taskBgGradientColors[entry.gradientIndex]
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(
          width = 1.dp,
          color = MaterialTheme.colorScheme.outlineVariant,
          shape = RoundedCornerShape(20.dp),
        )
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .padding(14.dp),
  ) {
    Box(
      modifier =
        Modifier.size(46.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(Brush.linearGradient(listOf(gradient[0], gradient[1]))),
      contentAlignment = Alignment.Center,
    ) {
      Icon(entry.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
    }
    Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
      Text(
        entry.title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        entry.subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Icon(
      Icons.AutoMirrored.Rounded.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ToolRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by
    animateFloatAsState(
      targetValue = if (pressed) 0.97f else 1f,
      animationSpec = tween(durationMillis = 120),
      label = "rowPress",
    )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Start,
    modifier =
      Modifier.fillMaxWidth()
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .clip(RoundedCornerShape(14.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 10.dp),
  ) {
    Box(
      modifier =
        Modifier.size(38.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(modifier = Modifier.padding(start = 14.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
