/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.os.Bundle
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.mcp.McpServerState
import com.google.ai.edge.gallery.mcp.McpServersProvider
import com.google.ai.edge.gallery.proto.McpAuth
import com.google.ai.edge.gallery.proto.McpServer
import com.google.ai.edge.gallery.proto.McpServers
import com.google.ai.edge.gallery.proto.McpTool
import com.google.ai.edge.gallery.proto.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.google.ai.edge.gallery.security.UntrustedContent
import kotlinx.coroutines.withContext

private const val TAG = "AGMcpManagerVM"

// [ALTREX] MCP limits.
private const val CONNECT_TIMEOUT_MS = 10_000L
private const val MAX_TOOL_NAME_CHARS = 64
private const val MAX_TOOL_DESCRIPTION_CHARS = 600
private const val MAX_TOOL_SCHEMA_CHARS = 1_500

data class McpManagerUiState(
  val mcpServers: List<McpServerState> = emptyList(),
  val loadingMcpServer: Boolean = false,
  val error: String? = null,
)

@HiltViewModel
class McpManagerViewModel
@Inject
constructor(
  private val mcpServersDataStore: DataStore<McpServers>,
  private val userDataDataStore: DataStore<UserData>,
  @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel(), McpServersProvider {
  override val mcpServers: List<McpServerState>
    get() = uiState.value.mcpServers

  private val _uiState = MutableStateFlow(McpManagerUiState())
  val uiState = _uiState.asStateFlow()

  private val httpClient = HttpClient(Android) { install(SSE) }

  /** [ALTREX] Whether [loadMcpServers] has completed once (see [refreshFromStore]). */
  @Volatile private var loadedOnce = false

  /**
   * Loads the persisted MCP servers from the DataStore and initializes their client connections.
   */
  suspend fun loadMcpServers() {
    // 1. Set the loading indicator to signal background restoration is in progress.
    _uiState.update { it.copy(loadingMcpServer = true) }
    withContext(Dispatchers.IO) {
      try {
        // 2. Retrieve the list of configured MCP servers previously saved in DataStore.
        val savedServers = mcpServersDataStore.data.first().mcpServerList
        // [ALTREX] Close the previous connections before opening new ones (each reload leaked them).
        closeClients(_uiState.value.mcpServers)
        // [ALTREX] All servers at once, each with a time limit: a dead server delayed the model load.
        val loadedStates = kotlinx.coroutines.coroutineScope {
          savedServers.map { serverProto -> async { loadOne(serverProto) } }.map { it.await() }
        }
        _uiState.update { it.copy(mcpServers = loadedStates, loadingMcpServer = false) }
        loadedOnce = true
        mcpServersDataStore.updateData {
          McpServers.newBuilder()
            .addAllMcpServer(
              loadedStates.mapIndexed { index, state ->
                if (state.error != null) savedServers[index] else state.mcpServer
              }
            )
            .build()
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error reading saved MCP servers", e)
        _uiState.update { it.copy(loadingMcpServer = false) }
      }
    }
  }

  /** Connects one saved server and lists its tools, within [CONNECT_TIMEOUT_MS]. */
  private suspend fun loadOne(serverProto: McpServer): McpServerState =
          try {
            // Map existing tool enablement configurations to preserve user preferences upon
            // reconnect.
            val savedToolsMap = serverProto.toolsList.associate { it.name to it.enabled }
            val savedAlwaysAllowMap = serverProto.toolsList.associate { it.name to it.alwaysAllow }
            val (client, mcpTools) =
              kotlinx.coroutines.withTimeout(CONNECT_TIMEOUT_MS) {
                initializeClientAndLoadTools(serverProto.url, savedToolsMap, savedAlwaysAllowMap)
              }
            val serverVersion = client.serverVersion
            val updatedServerProto =
              serverProto
                .toBuilder()
                .clearTools()
                .addAllTools(mcpTools)
                .setEnabled(serverProto.enabled)
                .apply {
                  serverVersion?.name?.let { setName(it) }
                  serverVersion?.version?.let { setVersion(it) }
                  val desc = mcpTools.joinToString(", ") { it.name }
                  if (desc.isNotEmpty()) {
                    setDescription("Tools: $desc")
                  }
                }
                .build()
            McpServerState(mcpServer = updatedServerProto, client = client, error = null)
          } catch (e: Exception) {
            if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Log.e(TAG, "Error loading MCP server: ${serverProto.url}", e)
            // Fallback: If connection fails during startup, load the server in a disabled state.
            McpServerState(
              mcpServer = serverProto.toBuilder().setEnabled(false).build(),
              client = null,
              error =
                if (e is kotlinx.coroutines.TimeoutCancellationException) "Serveur injoignable (délai dépassé)"
                else e.message ?: "Failed to connect",
            )
          }

  /** [ALTREX] Closes MCP sessions without waiting (best effort). */
  private fun closeClients(states: List<McpServerState>) {
    val clients = states.mapNotNull { it.client }
    if (clients.isEmpty()) return
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
      for (c in clients) runCatching { c.close() }
    }
  }

  override fun onCleared() {
    closeClients(_uiState.value.mcpServers)
    runCatching { httpClient.close() }
    super.onCleared()
  }

  /**
   * Adds a new MCP server by URL, attempts to connect and discover its tools, and persists the
   * updated configuration.
   */
  fun addMcpServer(
    url: String,
    authType: McpAuth.AuthMethodCase,
    headerName: String,
    headerValue: String,
    connectorId: String = "",
  ) {
    // Immediately display a loading spinner to the user while connecting, and clear any prior
    // error.
    _uiState.update { it.copy(loadingMcpServer = true, error = null) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Construct McpAuth for this call to pass to initializeClientAndLoadTools
        val currentAuthBuilder = McpAuth.newBuilder()
        when (authType) {
          McpAuth.AuthMethodCase.REQUEST_HEADER -> {
            currentAuthBuilder.setRequestHeader(
              McpAuth.RequestHeader.newBuilder()
                .setHeaderName(headerName)
                .setHeaderValue(com.google.ai.edge.gallery.security.SecretCipher.encrypt(headerValue))
                .build()
            )
          }
          McpAuth.AuthMethodCase.OAUTH -> {
            currentAuthBuilder.setOauth(McpAuth.OAuth.getDefaultInstance())
          }
          McpAuth.AuthMethodCase.NONE -> {
            currentAuthBuilder.setNone(true)
          }
          else -> {
            currentAuthBuilder.setNone(true)
          }
        }
        val currentAuth = currentAuthBuilder.build()

        // [ALTREX] Reconnecting an existing connector keeps its settings (tools, approvals, policy).
        val previous = mcpServersDataStore.data.first().mcpServerList.find { it.url == url }
        // 1. Connect to the remote MCP server and fetch its list of exported tools.
        val (client, mcpTools) =
          initializeClientAndLoadTools(
            url,
            savedToolsMap = previous?.toolsList?.associate { it.name to it.enabled },
            savedAlwaysAllowMap = previous?.toolsList?.associate { it.name to it.alwaysAllow },
            mcpAuth = currentAuth,
          )
        val serverVersion = client.serverVersion
        val mcpServerProto =
          McpServer.newBuilder()
            .setUrl(url)
            .addAllTools(mcpTools)
            .setEnabled(true)
            .setConnectorId(connectorId.ifEmpty { previous?.connectorId.orEmpty() })
            .apply { previous?.let { setPermissionPolicy(it.permissionPolicy) } }
            .apply {
              serverVersion?.name?.let { setName(it) }
              serverVersion?.version?.let { setVersion(it) }
              val desc = mcpTools.joinToString(", ") { it.name }
              if (desc.isNotEmpty()) {
                setDescription("Tools: $desc")
              }
            }
            .build()

        val newState = McpServerState(mcpServer = mcpServerProto, client = client, error = null)

        // 2. Persist the server and tooling configurations to disk, deduplicating by URL.
        mcpServersDataStore.updateData { currentServers ->
          val filtered = currentServers.mcpServerList.filter { it.url != url }
          McpServers.newBuilder().addAllMcpServer(filtered + mcpServerProto).build()
        }

        // Persist auth data
        userDataDataStore.updateData { currentUserData ->
          currentUserData.toBuilder().putMcpAuths(url, currentAuth).build()
        }

        // 3. Update the local StateFlow so the UI updates dynamically.
        _uiState.update { currentState ->
          val filteredStates = currentState.mcpServers.filter { it.mcpServer.url != url }
          currentState.copy(mcpServers = filteredStates + newState, loadingMcpServer = false)
        }
        Log.d(TAG, "Analytics: mcp_management, action=add_server, status=success")
        firebaseAnalytics?.logEvent(
          GalleryEvent.MCP_MANAGEMENT.id,
          Bundle().apply {
            putString("action", "add_server")
            putString("status", "success")
          },
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error adding MCP server: $url", e)
        // Fallback: Update the UI state with the error message without preserving the server.
        _uiState.update { currentState ->
          currentState.copy(error = e.message ?: "Failed to connect", loadingMcpServer = false)
        }
        Log.d(
          TAG,
          "Analytics: mcp_management, action=add_server, status=failed, error_type=${e.javaClass.simpleName}",
        )
        firebaseAnalytics?.logEvent(
          GalleryEvent.MCP_MANAGEMENT.id,
          Bundle().apply {
            putString("action", "add_server")
            putString("status", "failed")
            putString("error_type", e.javaClass.simpleName)
          },
        )
      }
    }
  }

  /** [ALTREX] Which calls of this connector need approval (see [McpPermissionPolicy]). */
  fun setMcpServerPolicy(url: String, policy: com.google.ai.edge.gallery.proto.McpPermissionPolicy) {
    val updated =
      _uiState.value.mcpServers.map { state ->
        if (state.mcpServer.url == url) {
          state.copy(mcpServer = state.mcpServer.toBuilder().setPermissionPolicy(policy).build())
        } else {
          state
        }
      }
    _uiState.update { it.copy(mcpServers = updated) }
    persistServers(updated)
  }

  /**
   * [ALTREX] Catches up with changes made by another screen (the Connectors screen has its own
   * instance): reconnects when servers were added or removed, else only copies their settings.
   */
  suspend fun refreshFromStore() {
    // Not loaded yet (the model load does it), or being loaded: nothing to catch up with.
    if (!loadedOnce || _uiState.value.loadingMcpServer) return
    val saved = withContext(Dispatchers.IO) { mcpServersDataStore.data.first().mcpServerList }
    val current = _uiState.value.mcpServers
    if (saved.map { it.url }.toSet() != current.map { it.mcpServer.url }.toSet()) {
      loadMcpServers()
      return
    }
    val byUrl = saved.associateBy { it.url }
    _uiState.update { state ->
      state.copy(
        mcpServers =
          state.mcpServers.map { st ->
            val s = byUrl[st.mcpServer.url] ?: return@map st
            val tools = s.toolsList.associateBy { it.name }
            st.copy(
              mcpServer =
                st.mcpServer
                  .toBuilder()
                  .setEnabled(if (st.error != null) false else s.enabled)
                  .setPermissionPolicy(s.permissionPolicy)
                  .clearTools()
                  .addAllTools(
                    st.mcpServer.toolsList.map { t ->
                      tools[t.name]?.let { t.toBuilder().setEnabled(it.enabled).setAlwaysAllow(it.alwaysAllow).build() } ?: t
                    }
                  )
                  .build()
            )
          }
      )
    }
  }

  /** Clears any connection error. */
  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  /** Removes an MCP server by its URL from both the current UI state and persistent DataStore. */
  fun removeMcpServer(url: String) {
    viewModelScope.launch(Dispatchers.IO) {
      mcpServersDataStore.updateData { currentServers ->
        val filtered = currentServers.mcpServerList.filter { it.url != url }
        McpServers.newBuilder().addAllMcpServer(filtered).build()
      }
      // [ALTREX] Its key (or OAuth tokens) goes with it, and its connection is closed.
      userDataDataStore.updateData { it.toBuilder().removeMcpAuths(url).build() }
      com.google.ai.edge.gallery.connectors.McpOAuth.signOut(appContext, url)
      closeClients(_uiState.value.mcpServers.filter { it.mcpServer.url == url })
      _uiState.update { currentState ->
        currentState.copy(mcpServers = currentState.mcpServers.filter { it.mcpServer.url != url })
      }
    }
  }

  /** Returns true if an MCP server with the given URL is already loaded. */
  fun hasMcpServer(url: String): Boolean {
    return _uiState.value.mcpServers.any { it.mcpServer.url == url }
  }

  /** Generates a textual prompt listing all enabled tools from all enabled MCP servers. */
  fun getToolsPrompt(): String {
    return _uiState.value.mcpServers
      .filter { it.mcpServer.enabled }
      .flatMap { it.mcpServer.toolsList }
      .filter { it.enabled }
      .joinToString("\n\n") { tool ->
        // [ALTREX] Descriptions come from the server: chat and tool tags neutralized, length capped,
        // so a server cannot fake a turn or a tool call in the system prompt.
        val name = UntrustedContent.neutralize(tool.name.take(MAX_TOOL_NAME_CHARS))
        val description = UntrustedContent.neutralize(tool.description.take(MAX_TOOL_DESCRIPTION_CHARS))
        val schema = UntrustedContent.neutralize(tool.inputSchema.take(MAX_TOOL_SCHEMA_CHARS))
        "MCP tool name: \"$name\"\n- Description: $description\n- Input schema: $schema"
      }
  }

  /** Generates a summary of enablement state for all servers and tools. */
  fun getSelectedMcpsAndToolsSummary(): String = buildString {
    for (state in _uiState.value.mcpServers) {
      append("${state.mcpServer.url}:${state.mcpServer.enabled};")
      for (tool in state.mcpServer.toolsList) {
        append("${tool.name}:${tool.enabled};")
      }
    }
  }

  /** Enables or disables an entire MCP server, updating both UI state and DataStore persistence. */
  fun setMcpServerEnabled(url: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            state.copy(mcpServer = state.mcpServer.toBuilder().setEnabled(enabled).build())
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Enables or disables a specific tool under a given MCP server URL. */
  fun setMcpToolEnabled(url: String, toolName: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            val updatedTools =
              state.mcpServer.toolsList.map { tool ->
                if (tool.name == toolName) {
                  tool.toBuilder().setEnabled(enabled).build()
                } else {
                  tool
                }
              }
            state.copy(
              mcpServer = state.mcpServer.toBuilder().clearTools().addAllTools(updatedTools).build()
            )
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Enables or disables all loaded MCP servers in batch. */
  fun setAllMcpServerEnabled(enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.error != null) {
            state
          } else {
            state.copy(mcpServer = state.mcpServer.toBuilder().setEnabled(enabled).build())
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Enables or disables all tools under a specific MCP server URL. */
  fun setAllMcpToolsEnabled(url: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            val updatedTools =
              state.mcpServer.toolsList.map { tool -> tool.toBuilder().setEnabled(enabled).build() }
            state.copy(
              mcpServer = state.mcpServer.toBuilder().clearTools().addAllTools(updatedTools).build()
            )
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Sets always allow for a specific tool under a given MCP server URL. */
  fun setMcpToolAlwaysAllow(url: String, toolName: String, alwaysAllow: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            val updatedTools =
              state.mcpServer.toolsList.map { tool ->
                if (tool.name == toolName) {
                  tool.toBuilder().setAlwaysAllow(alwaysAllow).build()
                } else {
                  tool
                }
              }
            state.copy(
              mcpServer = state.mcpServer.toBuilder().clearTools().addAllTools(updatedTools).build()
            )
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /**
   * Initializes a streaming transport client for the given URL and fetches its supported tools,
   * restoring their enabled states if available.
   */
  private suspend fun initializeClientAndLoadTools(
    url: String,
    savedToolsMap: Map<String, Boolean>? = null,
    savedAlwaysAllowMap: Map<String, Boolean>? = null,
    mcpAuth: McpAuth? = null,
  ): Pair<Client, List<McpTool>> {
    Log.d(TAG, "Initializing MCP for $url...")
    val client =
      Client(
        clientInfo =
          Implementation(name = "Altrex", version = BuildConfig.VERSION_NAME)
      )
    // Retrieve authentication details from parameter or DataStore and configure the HTTP transport.
    val resolvedAuth = mcpAuth ?: userDataDataStore.data.first().mcpAuthsMap[url]
    // [ALTREX] Connector signed in with OAuth: its bearer token (refreshed when needed).
    val bearer =
      if (resolvedAuth?.authMethodCase == McpAuth.AuthMethodCase.OAUTH) {
        com.google.ai.edge.gallery.connectors.McpOAuth.accessToken(appContext, url)
          ?: throw IllegalStateException("Connexion expirée : reconnecte-toi à ce connecteur.")
      } else {
        null
      }
    val transport =
      if (bearer != null) {
        StreamableHttpClientTransport(
          client = httpClient,
          url = url,
          requestBuilder = { headers.append("Authorization", "Bearer $bearer") },
        )
      } else if (
        resolvedAuth != null && resolvedAuth.authMethodCase == McpAuth.AuthMethodCase.REQUEST_HEADER
      ) {
        val reqHeader = resolvedAuth.requestHeader
        StreamableHttpClientTransport(
          client = httpClient,
          url = url,
          requestBuilder = { headers.append(reqHeader.headerName, com.google.ai.edge.gallery.security.SecretCipher.decrypt(reqHeader.headerValue)) },
        )
      } else {
        StreamableHttpClientTransport(client = httpClient, url = url)
      }
    client.connect(transport)
    val toolsResponse = client.listTools()
    val mcpTools =
      toolsResponse?.tools.orEmpty().map { tool ->
        val isEnabled = savedToolsMap?.get(tool.name) ?: true
        val isAlwaysAllow = savedAlwaysAllowMap?.get(tool.name) ?: false
        // Manually build a valid JSON schema string using public SDK object properties.
        // This avoids restricted library visibility constraints while ensuring robust JSON
        // formatting
        // for cached tools and UI rendering.
        val propertiesJson = tool.inputSchema.properties.toString()
        val requiredJson =
          tool.inputSchema.required?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } ?: "[]"
        val schemaJson =
          """{"type":"object","properties":$propertiesJson,"required":$requiredJson}"""
        McpTool.newBuilder()
          .setName(tool.name)
          .setDescription(tool.description ?: "")
          .setInputSchema(schemaJson)
          .setEnabled(isEnabled)
          .setAlwaysAllow(isAlwaysAllow)
          // [ALTREX] What the server says the tool does: reads only, or may destroy data.
          .setReadOnly(tool.annotations?.readOnlyHint == true)
          .setDestructive(tool.annotations?.destructiveHint == true && tool.annotations?.readOnlyHint != true)
          .setTitle((tool.title ?: tool.annotations?.title).orEmpty().take(MAX_TOOL_NAME_CHARS))
          .build()
      }
    Log.d(TAG, "Loaded ${mcpTools.size} tools from $url: ${mcpTools.joinToString { it.name }}")
    return Pair(client, mcpTools)
  }

  private fun persistServers(updatedServers: List<McpServerState>) {
    viewModelScope.launch(Dispatchers.IO) {
      mcpServersDataStore.updateData { currentServers ->
        val updatedProtos = updatedServers.map { state ->
          if (state.error != null) {
            currentServers.mcpServerList.find { it.url == state.mcpServer.url } ?: state.mcpServer
          } else {
            state.mcpServer
          }
        }
        McpServers.newBuilder().addAllMcpServer(updatedProtos).build()
      }
    }
  }
}
