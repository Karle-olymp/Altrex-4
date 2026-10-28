package com.google.ai.edge.gallery.connectors

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.AddMcpServerFromUrlDialog
import com.google.ai.edge.gallery.customtasks.agentchat.McpManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.McpToolManagerBottomSheet
import com.google.ai.edge.gallery.mcp.McpServerState
import com.google.ai.edge.gallery.proto.McpAuth
import com.google.ai.edge.gallery.proto.McpPermissionPolicy
import kotlinx.coroutines.launch

const val CONNECTORS_ENTRY_ID = "altrex_connectors"

/**
 * [ALTREX] The Connectors screen: the connected services (state, tools, what needs approval) and a
 * catalog to add one in a tap (sign-in page, key, or nothing for public servers).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
  navigateUp: () -> Unit,
  viewModel: McpManagerViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val uiState by viewModel.uiState.collectAsState()

  LaunchedEffect(Unit) { viewModel.loadMcpServers() }

  // The connector being added, and an error to show under it.
  var busyId by remember { mutableStateOf<String?>(null) }
  var errorFor by remember { mutableStateOf<Pair<String, String>?>(null) }
  var keyFor by remember { mutableStateOf<ConnectorCatalog.Connector?>(null) }
  var showAddByUrl by remember { mutableStateOf(false) }
  var toolsOf by remember { mutableStateOf<String?>(null) }
  var removeUrl by remember { mutableStateOf<String?>(null) }

  // OAuth: discovery and registration, browser page, then the server is added with its tokens.
  var pending by remember { mutableStateOf<Pair<String, McpOAuth.Pending>?>(null) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val (id, p) = pending ?: return@rememberLauncherForActivityResult
      pending = null
      scope.launch {
        try {
          McpOAuth.complete(context, p, result.data)
          viewModel.addMcpServer(p.serverUrl, McpAuth.AuthMethodCase.OAUTH, "", "", connectorIdOf(id))
        } catch (e: Exception) {
          errorFor = id to (e.message ?: "Connexion impossible.")
          busyId = null
        }
      }
    }

  fun signIn(id: String, url: String) {
    busyId = id
    errorFor = null
    scope.launch {
      try {
        val p = McpOAuth.prepare(context, url)
        pending = id to p
        launcher.launch(McpOAuth.authorizationIntent(context, p))
      } catch (e: Exception) {
        errorFor = id to (e.message ?: "Connexion impossible.")
        busyId = null
      }
    }
  }

  fun connect(c: ConnectorCatalog.Connector) {
    when (c.auth) {
      ConnectorCatalog.Auth.NONE -> {
        busyId = c.id
        errorFor = null
        viewModel.addMcpServer(c.url, McpAuth.AuthMethodCase.NONE, "", "", c.id)
      }
      ConnectorCatalog.Auth.OAUTH -> signIn(c.id, c.url)
      ConnectorCatalog.Auth.KEY -> keyFor = c
    }
  }

  // The view model reports the end of an addition through its loading flag and error.
  LaunchedEffect(uiState.loadingMcpServer) {
    val id = busyId ?: return@LaunchedEffect
    if (!uiState.loadingMcpServer && pending == null) {
      uiState.error?.let { errorFor = id to it }
      viewModel.clearError()
      busyId = null
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Connecteurs") },
        navigationIcon = {
          IconButton(onClick = navigateUp) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour")
          }
        },
      )
    }
  ) { padding ->
    val connectedUrls = uiState.mcpServers.map { it.mcpServer.url.trimEnd('/') }.toSet()
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Text(
          "Relie Altrex à tes services. Le modèle s'en sert quand « Skills et outils » est activé dans " +
            "Discuter. Par défaut, les lectures passent librement et les actions te sont demandées.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (uiState.mcpServers.isNotEmpty()) {
        item { SectionTitle("Connectés") }
        items(uiState.mcpServers, key = { "c:" + it.mcpServer.url }) { state ->
          val catalog =
            ConnectorCatalog.byId(state.mcpServer.connectorId) ?: ConnectorCatalog.byUrl(state.mcpServer.url)
          ConnectedCard(
            state = state,
            catalog = catalog,
            busy = busyId == state.mcpServer.url,
            error = errorFor?.takeIf { it.first == state.mcpServer.url }?.second,
            onEnabled = { viewModel.setMcpServerEnabled(state.mcpServer.url, it) },
            onPolicy = { viewModel.setMcpServerPolicy(state.mcpServer.url, it) },
            onTools = { toolsOf = state.mcpServer.url },
            onReconnect = { signIn(state.mcpServer.url, state.mcpServer.url) },
            onRemove = { removeUrl = state.mcpServer.url },
          )
        }
      } else if (uiState.loadingMcpServer && busyId == null) {
        item {
          Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
          }
        }
      }

      for (category in ConnectorCatalog.CATEGORIES) {
        val entries = ConnectorCatalog.ALL.filter { it.category == category }
        item(key = "t:$category") { SectionTitle(category) }
        items(entries, key = { "k:" + it.id }) { c ->
          CatalogRow(
            connector = c,
            connected = c.url.trimEnd('/') in connectedUrls,
            busy = busyId == c.id,
            error = errorFor?.takeIf { it.first == c.id }?.second,
            onConnect = { connect(c) },
          )
        }
      }

      item {
        OutlinedButton(onClick = { showAddByUrl = true }, modifier = Modifier.fillMaxWidth()) {
          Icon(Icons.Outlined.Add, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Ajouter un connecteur par son adresse")
        }
      }
    }
  }

  keyFor?.let { c ->
    KeyDialog(
      connector = c,
      onDismiss = { keyFor = null },
      onConfirm = { key ->
        keyFor = null
        busyId = c.id
        errorFor = null
        viewModel.addMcpServer(
          c.url,
          McpAuth.AuthMethodCase.REQUEST_HEADER,
          "Authorization",
          ConnectorCatalog.bearer(key),
          c.id,
        )
      },
      onOpenKeyPage = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.keyPage))) }
      },
    )
  }

  if (showAddByUrl) {
    AddMcpServerFromUrlDialog(
      mcpManagerViewModel = viewModel,
      onDismissRequest = { showAddByUrl = false },
      onSuccess = {},
    )
  }

  toolsOf?.let { url ->
    McpToolManagerBottomSheet(mcpManagerViewModel = viewModel, serverUrl = url, onDismiss = { toolsOf = null })
  }

  removeUrl?.let { url ->
    AlertDialog(
      onDismissRequest = { removeUrl = null },
      title = { Text("Déconnecter ce connecteur ?") },
      text = { Text("Altrex oublie ce service et ses jetons. Tu pourras le reconnecter depuis le catalogue.") },
      confirmButton = {
        Button(
          onClick = {
            viewModel.removeMcpServer(url)
            removeUrl = null
          }
        ) {
          Text("Déconnecter")
        }
      },
      dismissButton = { TextButton(onClick = { removeUrl = null }) { Text("Annuler") } },
    )
  }
}

/** A reconnection uses the server URL as its id: keep the catalog id saved with the server. */
private fun connectorIdOf(id: String): String = if (id.startsWith("https://")) "" else id

@Composable
private fun SectionTitle(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 8.dp),
  )
}

@Composable
private fun Monogram(name: String, color: Long) {
  Box(
    modifier = Modifier.size(40.dp).background(Color(color), CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
      color = Color.White,
      fontWeight = FontWeight.Bold,
      style = MaterialTheme.typography.titleMedium,
    )
  }
}

@Composable
private fun CatalogRow(
  connector: ConnectorCatalog.Connector,
  connected: Boolean,
  busy: Boolean,
  error: String?,
  onConnect: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Monogram(connector.name, connector.color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(connector.name, style = MaterialTheme.typography.titleSmall)
          Text(
            connector.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            when (connector.auth) {
              ConnectorCatalog.Auth.OAUTH -> "Connexion avec ton compte"
              ConnectorCatalog.Auth.KEY -> "Avec une clé que tu crées sur le service"
              ConnectorCatalog.Auth.NONE -> "Public, sans compte"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.width(8.dp))
        when {
          busy -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
          connected -> Text("Connecté", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
          else -> FilledTonalButton(onClick = onConnect) { Text("Connecter") }
        }
      }
      error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
      }
    }
  }
}

@Composable
private fun ConnectedCard(
  state: McpServerState,
  catalog: ConnectorCatalog.Connector?,
  busy: Boolean,
  error: String?,
  onEnabled: (Boolean) -> Unit,
  onPolicy: (McpPermissionPolicy) -> Unit,
  onTools: () -> Unit,
  onReconnect: () -> Unit,
  onRemove: () -> Unit,
) {
  val server = state.mcpServer
  val host = runCatching { Uri.parse(server.url).host }.getOrNull().orEmpty()
  val name = catalog?.name ?: server.name.ifBlank { host }
  var expanded by remember { mutableStateOf(false) }
  val tools = server.toolsList
  val reads = tools.count { it.readOnly }
  val enabledTools = tools.count { it.enabled }

  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
  ) {
    Column(Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { expanded = !expanded }) {
        Monogram(name, catalog?.color ?: 0xFF607D8B)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(name, style = MaterialTheme.typography.titleSmall)
          Text(
            when {
              state.error != null -> state.error ?: ""
              else -> "$enabledTools outil(s) actif(s) sur ${tools.size} · $reads en lecture seule"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Switch(checked = server.enabled, enabled = state.error == null, onCheckedChange = onEnabled)
        Icon(
          if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = if (expanded) "Réduire" else "Détails",
          modifier = Modifier.padding(start = 4.dp),
        )
      }
      error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
      }
      if (expanded) {
        Text(
          host,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
        Text("Ce que le modèle peut faire sans te demander", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        PolicyOption(server.permissionPolicy, McpPermissionPolicy.MCP_PERMISSION_READS_FREE, "Lire librement, me demander pour les actions", onPolicy)
        PolicyOption(server.permissionPolicy, McpPermissionPolicy.MCP_PERMISSION_ASK_ALL, "Toujours me demander", onPolicy)
        PolicyOption(server.permissionPolicy, McpPermissionPolicy.MCP_PERMISSION_ALLOW_ALL, "Tout autoriser (sauf suppressions)", onPolicy)
        Text(
          "« Lecture » et « suppression » sont indiqués par le service lui-même pour chaque outil.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          if (state.error != null) TextButton(onClick = onReconnect, enabled = !busy) { Text("Reconnecter") }
          if (state.error == null && tools.isNotEmpty()) TextButton(onClick = onTools) { Text("Outils") }
          TextButton(onClick = onRemove) { Text("Déconnecter", color = MaterialTheme.colorScheme.error) }
        }
      }
    }
  }
}

@Composable
private fun PolicyOption(
  current: McpPermissionPolicy,
  value: McpPermissionPolicy,
  label: String,
  onPolicy: (McpPermissionPolicy) -> Unit,
) {
  val selected =
    current == value ||
      (value == McpPermissionPolicy.MCP_PERMISSION_READS_FREE && current == McpPermissionPolicy.UNRECOGNIZED)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().clickable { onPolicy(value) },
  ) {
    RadioButton(selected = selected, onClick = { onPolicy(value) })
    Text(label, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun KeyDialog(
  connector: ConnectorCatalog.Connector,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
  onOpenKeyPage: () -> Unit,
) {
  var key by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Connecter ${connector.name}") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "Crée une clé sur ${connector.name}, puis colle-la ici. Elle est chiffrée sur le téléphone et " +
            "n'est envoyée qu'à ${connector.name}.",
          style = MaterialTheme.typography.bodyMedium,
        )
        if (connector.keyPage.isNotEmpty()) {
          TextButton(onClick = onOpenKeyPage) { Text("Créer une clé sur ${connector.name}") }
        }
        OutlinedTextField(
          value = key,
          onValueChange = { key = it },
          label = { Text(connector.keyHint.ifEmpty { "Clé" }) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = { Button(onClick = { onConfirm(key) }, enabled = key.isNotBlank()) { Text("Connecter") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
  )
}
