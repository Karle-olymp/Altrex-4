package com.google.ai.edge.gallery.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.common.AltrexAvatar
import com.google.ai.edge.gallery.ui.common.AltrexLogoLoader

/**
 * [ALTREX] Shown when the chat has no model yet (first launch, offline first launch, every model
 * removed). Replaces Google's home screen: it always offers a way forward, never a blank screen.
 *
 * @param loading the model list is still being read; the buttons stay usable meanwhile.
 * @param catalogUnavailable the online model list could not be loaded (offline, server error).
 */
@Composable
fun WelcomeScreen(
  onOpenMenu: () -> Unit,
  onOpenModels: () -> Unit,
  onAddCloudModel: () -> Unit,
  onImportModel: () -> Unit,
  modifier: Modifier = Modifier,
  loading: Boolean = false,
  catalogUnavailable: Boolean = false,
  onRetry: () -> Unit = {},
) {
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing)
  ) {
    IconButton(onClick = onOpenMenu, modifier = Modifier.padding(4.dp)) {
      Icon(Icons.Rounded.Menu, contentDescription = "Ouvrir le menu")
    }
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier =
        Modifier.align(Alignment.Center)
          .widthIn(max = 440.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 28.dp, vertical = 56.dp),
    ) {
      AltrexAvatar(size = 72.dp)
      Spacer(Modifier.height(24.dp))
      Text(
        "Bienvenue dans Altrex",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(10.dp))
      Text(
        "Pour discuter, ajoute d'abord un modèle : télécharge-le sur le téléphone, " +
          "branche un modèle cloud avec ta clé, ou importe un fichier que tu as déjà.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(28.dp))

      WelcomeAction(
        label = "Choisir un modèle à télécharger",
        icon = Icons.AutoMirrored.Rounded.ListAlt,
        primary = true,
        onClick = onOpenModels,
      )
      Spacer(Modifier.height(10.dp))
      WelcomeAction(label = "Ajouter un modèle cloud", icon = Icons.Outlined.Cloud, onClick = onAddCloudModel)
      Spacer(Modifier.height(10.dp))
      WelcomeAction(label = "Importer un modèle", icon = Icons.Outlined.UploadFile, onClick = onImportModel)

      Spacer(Modifier.height(24.dp))
      when {
        loading ->
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AltrexLogoLoader(size = 26.dp)
            Spacer(Modifier.height(8.dp))
            Text(
              "Chargement de la liste des modèles…",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        catalogUnavailable ->
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
              Modifier.fillMaxWidth()
                .background(
                  MaterialTheme.colorScheme.surfaceContainerLow,
                  RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
          ) {
            Text(
              "La liste des modèles à télécharger n'a pas pu être chargée (pas de réseau ?). " +
                "Les modèles cloud et l'import restent possibles.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) { Text("Réessayer") }
          }
        else ->
          Text(
            "Les notes vocales et le terminal marchent déjà sans modèle : ouvre le menu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
      }
    }
  }
}

/** Neutral screen of the start route while the model list loads (a short moment, usually). */
@Composable
fun StartLoadingScreen(modifier: Modifier = Modifier) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    AltrexLogoLoader(size = 40.dp)
  }
}

@Composable
private fun WelcomeAction(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit,
  primary: Boolean = false,
) {
  val shape = RoundedCornerShape(16.dp)
  val content: @Composable () -> Unit = {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    Text(label, modifier = Modifier.padding(start = 10.dp))
  }
  val buttonModifier = Modifier.fillMaxWidth().height(52.dp)
  if (primary) {
    Button(onClick = onClick, shape = shape, modifier = buttonModifier) { content() }
  } else {
    FilledTonalButton(onClick = onClick, shape = shape, modifier = buttonModifier) { content() }
  }
}
