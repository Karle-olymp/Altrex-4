package com.google.ai.edge.gallery.forge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.forge.engine.ForgeLimits
import com.google.ai.edge.gallery.forge.workspace.ForgeSettings

/**
 * [ALTREX] « Réglages de Forge »: the light mode, the limits of a turn (empty = default) and the
 * user's global ALTREX.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgeSettingsSheet(
  settings: ForgeSettings,
  current: ForgeLimits,
  light: Boolean,
  local: Boolean,
  onSave: (ForgeSettings) -> Unit,
  onEditMemory: () -> Unit,
  onDismiss: () -> Unit,
) {
  val p = LocalForgePalette.current
  var mode by remember { mutableStateOf(settings.light) }
  var cloud by remember { mutableStateOf(settings.maxStepsCloud?.toString().orEmpty()) }
  var localSteps by remember { mutableStateOf(settings.maxStepsLocal?.toString().orEmpty()) }
  var minutes by remember { mutableStateOf(settings.maxMinutes?.toString().orEmpty()) }
  var buildTurns by remember { mutableStateOf(settings.buildMaxTurns?.toString().orEmpty()) }
  var buildTries by remember { mutableStateOf(settings.buildMaxTries?.toString().orEmpty()) }
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
      Text("Réglages de Forge", style = ForgeType.title, color = p.text)
      Text(
        "Ce modèle : ${current.maxSteps} étapes, ${current.maxDurationMs / 60_000} min par tour, résultats d'outils de ${current.maxResultChars} caractères" +
          (if (light) " · mode léger actif" else "") + ".",
        style = ForgeType.monoSmall,
        color = p.muted,
        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
      )
      SectionHeader("MODE LÉGER")
      Text(
        "Pour les modèles locaux lents : 7 outils, consignes courtes (moins de 600 jetons), résultats plus courts. Jamais pour un modèle cloud.",
        style = ForgeType.body,
        color = p.muted,
      )
      for (l in ForgeSettings.Light.entries) {
        Row(Modifier.fillMaxWidth().clickable { mode = l }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
          RadioButton(selected = mode == l, onClick = { mode = l }, colors = RadioButtonDefaults.colors(selectedColor = p.accent))
          Text(l.label, style = ForgeType.body, color = p.text)
        }
      }
      Spacer(Modifier.height(10.dp))
      SectionHeader("LIMITES D'UN TOUR (VIDE = PAR DÉFAUT)")
      LimitField("Étapes, modèle cloud (40)", cloud) { cloud = it }
      LimitField("Étapes, modèle local (20 ; 15 en mode léger)", localSteps) { localSteps = it }
      LimitField("Durée en minutes (30 cloud, 45 local)", minutes) { minutes = it }
      Text(
        "Bornes : ${ForgeSettings.MIN_STEPS} à ${ForgeSettings.MAX_STEPS} étapes, ${ForgeSettings.MIN_MINUTES} à ${ForgeSettings.MAX_MINUTES} min. S'appliquent au prochain tour.",
        style = ForgeType.monoSmall,
        color = p.faint,
      )
      Spacer(Modifier.height(10.dp))
      SectionHeader("MODE BUILD (VIDE = PAR DÉFAUT)")
      LimitField("Tours au plus (8)", buildTurns) { buildTurns = it }
      LimitField("Essais de correction d'une même vérification (3)", buildTries) { buildTries = it }
      Spacer(Modifier.height(10.dp))
      SectionHeader("MÉMOIRE")
      ForgeButton("Modifier l'ALTREX.md global", onClick = onEditMemory)
      Text("Lu dans tous tes projets, avant leur propre ALTREX.md.", style = ForgeType.monoSmall, color = p.faint, modifier = Modifier.padding(top = 4.dp))
      Spacer(Modifier.height(16.dp))
      Row {
        ForgeButton("Enregistrer", primary = true, onClick = {
          fun num(t: String, lo: Int, hi: Int) = t.trim().toIntOrNull()?.coerceIn(lo, hi)
          onSave(
            ForgeSettings(
              light = mode,
              maxStepsCloud = num(cloud, ForgeSettings.MIN_STEPS, ForgeSettings.MAX_STEPS),
              maxStepsLocal = num(localSteps, ForgeSettings.MIN_STEPS, ForgeSettings.MAX_STEPS),
              maxMinutes = num(minutes, ForgeSettings.MIN_MINUTES, ForgeSettings.MAX_MINUTES),
              buildMaxTurns = num(buildTurns, 1, ForgeSettings.MAX_BUILD_TURNS),
              buildMaxTries = num(buildTries, 1, ForgeSettings.MAX_BUILD_TRIES),
            )
          )
        })
        Spacer(Modifier.width(8.dp))
        ForgeButton("Fermer", onClick = onDismiss)
      }
      if (!local) Text("Modèle actuel : cloud (le mode léger ne s'applique pas).", style = ForgeType.monoSmall, color = p.faint, modifier = Modifier.padding(top = 8.dp))
    }
  }
}

@Composable
private fun LimitField(label: String, value: String, onChange: (String) -> Unit) {
  val p = LocalForgePalette.current
  Column(Modifier.padding(vertical = 4.dp)) {
    Text(label, style = ForgeType.body, color = p.text)
    ForgeTextField(value, { v -> onChange(v.filter { it.isDigit() }.take(3)) }, "par défaut")
  }
}

/** [ALTREX] The user's global ALTREX.md, edited in the app (12 000 characters are read at most). */
@Composable
fun GlobalMemoryDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
  val p = LocalForgePalette.current
  var text by remember { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = p.surface,
    title = { Text("ALTREX.md global", color = p.text) },
    text = {
      Column {
        Text("Tes consignes pour tous les projets (langue, style, outils préférés…). 6 000 caractères sont lus au plus.", style = ForgeType.body, color = p.muted)
        Spacer(Modifier.height(8.dp))
        ForgeTextField(text, { text = it }, "# Mes consignes\n- Réponds en français…", minLines = 8, maxLines = 16)
        Text("${text.length} caractères", style = ForgeType.monoSmall, color = if (text.length > 6_000) p.warn else p.faint)
      }
    },
    confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Enregistrer", color = p.accent) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = p.muted) } },
  )
}
