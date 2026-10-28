/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.home

import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Psychology
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import com.google.ai.edge.gallery.search.Embedder
import com.google.ai.edge.gallery.ui.common.ApiKeyEvents
import com.google.ai.edge.gallery.search.LocalMemory
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.LaunchedEffect
import com.google.ai.edge.gallery.search.MemoryStatus
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

private val THEME_OPTIONS = listOf(Theme.THEME_AUTO, Theme.THEME_LIGHT, Theme.THEME_DARK)

/**
 * [ALTREX] Settings as a full-screen page: grouped sections (Appearance, Models, Privacy,
 * Application) made of rows with an icon, a title and a subtitle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
  curThemeOverride: Theme,
  curFirebaseAnalytics: Boolean,
  modelManagerViewModel: ModelManagerViewModel,
  onDismissed: () -> Unit,
) {
  var selectedTheme by remember { mutableStateOf(curThemeOverride) }
  var hfToken by remember { mutableStateOf(modelManagerViewModel.getTokenStatusAndData().data) }
  val dateFormatter = remember {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
      .withZone(ZoneId.systemDefault())
      .withLocale(Locale.getDefault())
  }
  var customHfToken by remember { mutableStateOf("") }
  var isFocused by remember { mutableStateOf(false) }
  var showTokenEditor by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val interactionSource = remember { MutableInteractionSource() }
  val context = LocalContext.current

  val applyTheme: (Theme) -> Unit = { theme ->
    selectedTheme = theme
    // Updates the app's theme and saves it.
    ThemeSettings.themeOverride.value = theme
    modelManagerViewModel.saveThemeOverride(theme)
    // Keeps other activities launched from MainActivity on the same theme.
    val uiModeManager =
      context.applicationContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    when (theme) {
      Theme.THEME_AUTO -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
      Theme.THEME_LIGHT -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
      else -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
    }
  }

  Dialog(
    onDismissRequest = onDismissed,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    val focusManager = LocalFocusManager.current
    Column(
      modifier =
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .statusBarsPadding()
          .navigationBarsPadding()
          .clickable(interactionSource = interactionSource, indication = null) {
            focusManager.clearFocus()
          }
    ) {
      // Top bar.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
      ) {
        IconButton(onClick = onDismissed) {
          Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.cd_navigate_back_icon),
          )
        }
        Text(
          stringResource(R.string.drawer_settings_label),
          style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
          modifier = Modifier.padding(start = 4.dp),
        )
      }

      Column(
        modifier =
          Modifier.weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // Appearance.
        SettingsSection("Apparence") {
          SettingsRow(
            icon = Icons.Outlined.Palette,
            title = stringResource(R.string.theme_title),
            subtitle = "Couleurs de l'application",
          )
          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            THEME_OPTIONS.forEach { theme ->
              val selected = theme == selectedTheme
              Box(
                contentAlignment = Alignment.Center,
                modifier =
                  Modifier.weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                      if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                      else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .border(
                      1.dp,
                      if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.surfaceContainerHigh,
                      RoundedCornerShape(12.dp),
                    )
                    .clickable { applyTheme(theme) }
                    .padding(vertical = 10.dp),
              ) {
                Text(
                  stringResource(themeLabelRes(theme)),
                  style = MaterialTheme.typography.labelLarge,
                  color =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }

        // Models.
        SettingsSection("Modèles") {
          val curHfToken = hfToken
          val hasToken = curHfToken != null && curHfToken.accessToken.isNotEmpty()
          SettingsRow(
            icon = Icons.Outlined.Key,
            title = "Jeton Hugging Face",
            subtitle =
              if (curHfToken != null && hasToken) {
                curHfToken.accessToken.take(10) +
                  "… · expire le " +
                  dateFormatter.format(Instant.ofEpochMilli(curHfToken.expiresAtMs))
              } else {
                "Aucun jeton · pour les modèles à accès restreint et les Spaces privés (modèles distants)"
              },
            trailing = if (showTokenEditor) "Fermer" else "Modifier",
            onClick = { showTokenEditor = !showTokenEditor },
          )
          if (showTokenEditor) {
            val handleSaveToken = {
              modelManagerViewModel.saveAccessToken(
                accessToken = customHfToken,
                refreshToken = "",
                expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10,
              )
              hfToken = modelManagerViewModel.getTokenStatusAndData().data
              customHfToken = ""
              focusManager.clearFocus()
            }
            Column(
              modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 14.dp, bottom = 14.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              BasicTextField(
                value = customHfToken,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { handleSaveToken() }),
                modifier =
                  Modifier.fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                onValueChange = { customHfToken = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
              ) { innerTextField ->
                Box(
                  modifier =
                    Modifier.border(
                        width = 1.dp,
                        color =
                          if (isFocused) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp),
                      )
                      .height(44.dp),
                  contentAlignment = Alignment.CenterStart,
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                      if (customHfToken.isEmpty()) {
                        Text(
                          "Coller un jeton hf_…",
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          style = MaterialTheme.typography.bodySmall,
                        )
                      }
                      innerTextField()
                    }
                    if (customHfToken.isNotEmpty()) {
                      IconButton(onClick = handleSaveToken) {
                        Icon(
                          Icons.Rounded.CheckCircle,
                          contentDescription = stringResource(R.string.cd_done_icon),
                          tint = MaterialTheme.colorScheme.primary,
                        )
                      }
                    }
                  }
                }
              }
              if (hasToken) {
                OutlinedButton(
                  onClick = {
                    modelManagerViewModel.clearAccessToken()
                    hfToken = null
                  }
                ) {
                  Text("Effacer le jeton")
                }
              }
            }
          }
        }

        // [ALTREX] API keys of the online tools, stored encrypted on the phone.
        SettingsSection("Clés d'API") { ApiKeysSettings() }

        // [ALTREX] Local memory: a folder the model draws on.
        SettingsSection("Mémoire locale") { MemorySettings() }

        // [ALTREX] Linux terminal, first step (test screen).
        var showLinux by remember { mutableStateOf(false) }
        SettingsSection("Linux") {
          SettingsRow(
            icon = Icons.Outlined.Terminal,
            title = "Linux (test)",
            subtitle = "Alpine Linux dans Altrex : installer, lancer une commande",
            showChevron = true,
            onClick = { showLinux = true },
          )
        }
        if (showLinux) {
          com.google.ai.edge.gallery.terminal.LinuxTestDialog(onDismiss = { showLinux = false })
        }
        // [ALTREX] The model's own Linux (run_command tool): approvals and reset.
        SettingsSection("Linux du modèle") { AgentLinuxSettings() }

        // Privacy.
        SettingsSection("Confidentialité") {
          SettingsRow(
            icon = Icons.Outlined.Lock,
            title = "Ce qui sort du téléphone",
            subtitle =
              "Les modèles et la mémoire locale restent sur ton téléphone. Le scan de l'espace Code " +
                "envoie du code à SecurCode après ton accord, projet par projet ; la recherche web " +
                "n'envoie que tes requêtes à Brave ; l'import GitHub ne fait que télécharger. Aucune " +
                "clé n'est jamais montrée au modèle.",
          )
        }

        // Application.
        SettingsSection("Application") {
          SettingsRow(
            icon = Icons.Outlined.Info,
            title = "Version",
            subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
          )
          SettingsRow(
            icon = Icons.Outlined.Description,
            title = stringResource(R.string.third_party_libraries),
            subtitle = stringResource(R.string.view_licenses),
            showChevron = true,
            onClick = { context.startActivity(Intent(context, OssLicensesMenuActivity::class.java)) },
          )
        }
      }
    }
  }
}

/** [ALTREX] An online service whose key is kept in the Settings. */
private class ApiKeySpec(
  val name: String,
  val purpose: String,
  val help: String,
  val read: (android.content.Context) -> String?,
  val save: (android.content.Context, String) -> Boolean,
)

private val API_KEYS =
  listOf(
    ApiKeySpec(
      name = "Brave Search",
      purpose = "Recherche web de l'espace Code",
      help =
        "Colle ta clé Brave Search (api-dashboard.search.brave.com → API keys). Seules tes requêtes " +
          "de recherche partent vers Brave, jamais ton code ni un secret.",
      read = { com.google.ai.edge.gallery.ui.llmsingleturn.WebSettings.getBraveKey(it) },
      save = { c, k -> com.google.ai.edge.gallery.ui.llmsingleturn.WebSettings.saveBraveKey(c, k) },
    ),
    ApiKeySpec(
      name = "SecurCode",
      purpose = "Scan de sécurité du code",
      help =
        "Colle ta clé SecurCode. Le code n'est envoyé au scan qu'après ton accord, projet par projet.",
      read = { com.google.ai.edge.gallery.ui.security.SecurCodeSettings.getApiKey(it) },
      save = { c, k -> com.google.ai.edge.gallery.ui.security.SecurCodeSettings.saveApiKey(c, k) },
    ),
    ApiKeySpec(
      name = "GitHub",
      purpose = "Import de tes dépôts privés",
      help =
        "Colle un jeton GitHub « fine-grained » (github.com → Settings → Developer settings → " +
          "Personal access tokens) limité à la lecture : Contents « Read-only ». Il n'est envoyé " +
          "qu'à api.github.com. Sans jeton, seuls les dépôts publics sont importables.",
      read = { com.google.ai.edge.gallery.ui.github.GitHubSettings.getToken(it) },
      save = { c, k -> com.google.ai.edge.gallery.ui.github.GitHubSettings.saveToken(c, k) },
    ),
  )

/**
 * [ALTREX] Every API key in one place: status (masked), add or change, delete. Keys are stored
 * encrypted on the phone and are never sent to the model.
 */
@Composable
private fun ApiKeysSettings() {
  val context = LocalContext.current
  var version by remember { mutableStateOf(0) }
  var editing by remember { mutableStateOf<ApiKeySpec?>(null) }
  var input by remember { mutableStateOf("") }

  for (spec in API_KEYS) {
    val key = remember(version) { spec.read(context) }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.fillMaxWidth()
          .clickable {
            input = ""
            editing = spec
          }
          .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      Icon(
        Icons.Outlined.Key,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Spacer(modifier = Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(spec.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
        Text(
          spec.purpose + " · " + (key?.let { "enregistrée (…${it.takeLast(4)})" } ?: "aucune clé"),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (key != null) {
        TextButton(
          onClick = {
            spec.save(context, "")
            ApiKeyEvents.changed()
            version++
          }
        ) {
          Text("Supprimer")
        }
      } else {
        Text("Ajouter", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
      }
    }
  }

  editing?.let { spec ->
    com.google.ai.edge.gallery.customtasks.agentchat.SecretEditorDialog(
      title = "Clé ${spec.name}",
      fieldLabel = spec.help + " Elle est stockée chiffrée sur ce téléphone et n'est jamais envoyée au modèle.",
      value = input,
      onValueChange = { input = it },
      onDone = {
        if (input.isNotBlank()) {
          spec.save(context, input.trim())
          ApiKeyEvents.changed()
          version++
        }
        editing = null
      },
      onDismiss = { editing = null },
    )
  }
}

/**
 * [ALTREX] Local memory: choose a folder (notes, docs, configs, code, PDF), see its indexing, turn
 * it on or off in the chat, index it again, or forget it.
 */
@Composable
private fun MemorySettings() {
  val context = LocalContext.current
  val status by LocalMemory.status.collectAsState()
  var enabled by remember { mutableStateOf(LocalMemory.isEnabled(context)) }
  val folder = remember(status) { LocalMemory.folderName(context) }
  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        LocalMemory.chooseFolder(context, uri)
        enabled = true
      }
    }

  SettingsRow(
    icon = Icons.Outlined.Folder,
    title = folder?.let { "Dossier : $it" } ?: "Choisir un dossier",
    subtitle =
      if (folder == null) {
        "Altrex pourra y piocher pour répondre : notes, documents, configs, code, PDF. " +
          "Rien ne quitte le téléphone."
      } else {
        "Touche pour choisir un autre dossier."
      },
    showChevron = true,
    onClick = { picker.launch(null) },
  )
  if (folder == null) return

  val statusText =
    when (val st = status) {
      is MemoryStatus.Indexing ->
        if (st.total == 0) st.current else "Indexation… ${st.done}/${st.total} · ${st.current.substringAfterLast('/')}"
      is MemoryStatus.Ready ->
        "${st.files} fichiers · ${st.passages} passages" +
          (if (st.truncated) " · limite atteinte, une partie du dossier n'est pas indexée" else "") +
          (if (st.indexedAt > 0) " · mis à jour " + android.text.format.DateUtils.getRelativeTimeSpanString(st.indexedAt) else "")
      is MemoryStatus.Embedding -> "Analyse du sens… ${st.done}/${st.total} passages (la recherche par mots-clés marche déjà)"
      is MemoryStatus.Failed -> "Erreur : ${st.message}"
      MemoryStatus.NoFolder -> "Pas encore indexé."
    }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    Icon(
      Icons.Outlined.Psychology,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(22.dp),
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text("Utiliser dans le chat", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
      Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(
      checked = enabled,
      onCheckedChange = {
        enabled = it
        LocalMemory.setEnabled(context, it)
      },
    )
  }
  SemanticSearchRow()
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
  ) {
    OutlinedButton(
      onClick = { LocalMemory.reindex(context) },
      enabled = status !is MemoryStatus.Indexing,
      modifier = Modifier.weight(1f),
    ) {
      Text("Réindexer")
    }
    OutlinedButton(onClick = { LocalMemory.forget(context) }, modifier = Modifier.weight(1f)) {
      Text("Oublier ce dossier")
    }
  }
}

/**
 * [ALTREX] Search by meaning: finds a note about "backup" for a question about "sauvegarde". Needs
 * a 278 MB embedding model, downloaded on request and checked before use.
 */
@Composable
private fun SemanticSearchRow() {
  val context = LocalContext.current
  val received by LocalMemory.download.collectAsState()
  var downloaded by remember { mutableStateOf(Embedder.isDownloaded(context)) }
  var semantic by remember { mutableStateOf(LocalMemory.isSemanticEnabled(context)) }
  var error by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(received) {
    if (received == null) {
      downloaded = Embedder.isDownloaded(context)
      semantic = LocalMemory.isSemanticEnabled(context)
    }
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    Icon(
      Icons.Outlined.AutoAwesome,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(22.dp),
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text("Recherche par le sens", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
      val sub =
        when {
          received != null ->
            "Téléchargement… ${received!! * 100 / Embedder.SIZE_BYTES} % de ${Embedder.SIZE_BYTES / 1_000_000} Mo"
          error != null -> "Échec : $error"
          downloaded -> "Comprend les synonymes et les reformulations (modèle EmbeddingGemma installé)."
          else ->
            "Comprend les synonymes et les reformulations (« backup » pour « sauvegarde »). " +
              "Télécharge un modèle de ${Embedder.SIZE_BYTES / 1_000_000} Mo, une seule fois ; tout reste sur le téléphone."
        }
      Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when {
      received != null -> {}
      downloaded ->
        Switch(
          checked = semantic,
          onCheckedChange = {
            semantic = it
            LocalMemory.setSemanticEnabled(context, it)
          },
        )
      else ->
        OutlinedButton(
          onClick = {
            error = null
            LocalMemory.downloadSemanticModel(context) { error = it }
          }
        ) {
          Text("Télécharger")
        }
    }
  }
}

/**
 * [ALTREX] The model's Linux environment (run_command): whether each command needs approval, and a
 * reset that deletes everything the model installed there.
 */
@Composable
private fun AgentLinuxSettings() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var always by remember {
    mutableStateOf(com.google.ai.edge.gallery.tools.RunCommandTool.isAlwaysAllowed(context))
  }
  val env = remember { com.google.ai.edge.gallery.terminal.LinuxEnv.of(context, com.google.ai.edge.gallery.terminal.LinuxEnv.AGENT) }
  var installed by remember { mutableStateOf(env.isInstalled) }
  var resetting by remember { mutableStateOf(false) }
  Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
    Text(
      "Avec « Skills et outils », le modèle peut lancer des commandes dans son propre Linux, séparé " +
        "de ton terminal. Il a accès à Internet comme l'appli.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Toujours autoriser ses commandes", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
        Text(
          if (always) "Les commandes s'exécutent sans te demander" else "Chaque commande t'est montrée avant de s'exécuter",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      androidx.compose.material3.Switch(
        checked = always,
        onCheckedChange = {
          always = it
          com.google.ai.edge.gallery.tools.RunCommandTool.setAlwaysAllowed(context, it)
        },
      )
    }
    if (installed) {
      androidx.compose.material3.TextButton(
        enabled = !resetting,
        onClick = {
          resetting = true
          scope.launch {
            env.uninstall()
            installed = env.isInstalled
            resetting = false
          }
        },
      ) {
        Text(if (resetting) "Réinitialisation…" else "Réinitialiser le Linux du modèle")
      }
    }
  }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
  Text(
    title.uppercase(),
    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 6.dp),
  )
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
  ) {
    content()
  }
}

@Composable
private fun SettingsRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  trailing: String? = null,
  showChevron: Boolean = false,
  onClick: (() -> Unit)? = null,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = 14.dp),
  ) {
    Icon(
      icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(22.dp),
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (trailing != null) {
      Text(
        trailing,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp),
      )
    }
    if (showChevron) {
      Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@StringRes
private fun themeLabelRes(theme: Theme): Int =
  when (theme) {
    Theme.THEME_AUTO -> R.string.theme_auto
    Theme.THEME_LIGHT -> R.string.theme_light
    Theme.THEME_DARK -> R.string.theme_dark
    else -> R.string.unknown
  }
