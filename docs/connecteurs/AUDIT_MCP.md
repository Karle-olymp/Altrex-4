# Audit du support MCP d'Altrex (lecture seule)

Dépôt : `/home/user/Altrex-4`, branche `ccr-bd2eca9a-mw717h` (main + PR #30 non fusionnée, plus le commit `a33c470`).
Les chemins sont relatifs à `app/src/main/java/com/google/ai/edge/gallery/` sauf mention contraire.
Légende : **[C]** = constaté dans le code ou le bytecode du SDK ; **[S]** = supposé ou déduit, à vérifier sur l'appareil.

---

## Résumé (10 lignes)

1. Le client MCP utilise le SDK Kotlin officiel `io.modelcontextprotocol:kotlin-sdk:0.15.0`, avec un seul transport : **Streamable HTTP**. Il n'y a ni repli SSE ancien format, ni stdio, ni WebSocket. [C]
2. L'authentification se limite à **un en-tête fixe** (nom + valeur). La valeur est chiffrée par `SecretCipher` (AES-GCM, Keystore). L'option OAuth est grisée (« OAuth (en cours) ») et `McpAuth.OAuth` est un message vide. Il n'y a **aucune** découverte `.well-known`, aucun PKCE ni redirection. [C]
3. Les serveurs ne se connectent qu'au **chargement ou à la réinitialisation du modèle** (`buildAgentSystemPrompt`), l'un après l'autre. Rien ne se fait au lancement de l'appli. Le chargement du modèle **attend** les serveurs. Il n'y a ni bouton « reconnecter », ni reconnexion applicative. [C]
4. Chaque rechargement crée de nouveaux `Client` sans jamais fermer les anciens. Le `HttpClient` n'est jamais fermé non plus. Ce sont des fuites de sessions et de connexions. [C]
5. Le modèle voit les outils sous forme de texte brut dans `___TOOLS___` (nom, description, schéma JSON), sans limite de taille, avec un seul méta-outil `runMcpTool(toolName, input)`. Avec 20 à 50 outils, le prompt dépasse le contexte d'un 2B. [C]
6. Seul le `TextContent` est rendu au modèle. Les images, ressources et `structuredContent` sont ignorés. `isError` est géré. Côté llama.cpp, le résultat est encadré par `UntrustedContent` ; **côté LiteRT, il ne l'est pas**. [C]
7. Les permissions passent par un dialogue Autoriser une fois / Toujours / Refuser, avec `alwaysAllow` par outil persisté. Les correctifs [ALTREX] sont en place : canal nul = refus, et un délai de 5 min compte comme un refus. [C]
8. Bugs notables :
   - `RunMcpTool` appelle un outil **désactivé**, ou situé sur un serveur désactivé (aucun contrôle `enabled`) ;
   - `listTools` ignore la pagination ;
   - les `$defs` du schéma sont perdus ;
   - l'appel bloque en `runBlocking` et ne réagit pas au Stop ;
   - les descriptions d'outils (risque d'injection) ne sont pas encadrées ;
   - le secret reste orphelin après la suppression d'un serveur. [C]
9. Délai : le SDK applique un délai par défaut de **60 s** par requête, `callTool` compris (bytecode). L'appli ne fixe aucun délai à la connexion. La valeur de l'en-tête est saisie en clair à l'écran. [C]
10. Briques réutilisables :
    - le transport et le `Client` du SDK ;
    - `SecretCipher`, `UrlGuard`, `UntrustedContent` ;
    - `ToolResultBudget` et `RepeatCallGuard` ;
    - le dialogue de permission ;
    - **AppAuth 0.11.1, déjà en dépendance** (PKCE natif).

    À créer : OAuth 2.1 (RFC 9728/8414/7591), un modèle « connecteur », un catalogue, la reconnexion, le choix en deux temps et des permissions lecture/action fondées sur les `ToolAnnotations` exposées par le SDK.

---

## 1. Fichiers concernés

| Rôle | Fichier:lignes |
|---|---|
| ViewModel MCP (connexion, liste des outils, état, prompt) | `customtasks/agentchat/McpManagerViewModel.kt:59-467` |
| Interface fournisseur + état d'un serveur | `mcp/McpServersProvider.kt:22-26`. C'est le **seul** fichier du package `mcp` : `McpServerState(mcpServer, client, error)` et `McpServersProvider { val mcpServers }` |
| Outil exécuté par le modèle | `tools/RunMcpTool.kt:40-225` |
| Actions vers l'UI (permission, délai) | `tools/ToolAction.kt:27` (`USER_REPLY_TIMEOUT_MS`), `:121-125` (`AskMcpToolCallPermissionAction`), `:108-112` (`PermissionResult`) |
| Dialogue d'ajout d'un serveur | `customtasks/agentchat/AddMcpServerFromUrlDialog.kt:76-374` |
| Avertissement pour un hôte non approuvé | `customtasks/agentchat/AddMcpDisclaimerDialog.kt` (appelé depuis `AddMcpServerFromUrlDialog.kt:350-362`) |
| Feuille « serveurs MCP » (liste, activation, suppression) | `customtasks/agentchat/McpManagerBottomSheet.kt:113-537` |
| Feuille « outils d'un serveur » (activation, révocation de Toujours autoriser) | `customtasks/agentchat/McpToolManagerBottomSheet.kt:78-299` |
| Dialogue de permission d'appel | `customtasks/agentchat/McpToolCallPermissionDialog.kt:45-121` |
| Branchement dans l'écran de chat | `customtasks/agentchat/AgentChatScreen.kt:138, 151, 189-202, 493-495, 695-718, 744-762, 860-901` |
| Prompt système + `buildAgentSystemPrompt` | `customtasks/agentchat/AgentChatTaskModule.kt:58-100, 249-285` |
| DataStore `mcp_servers.pb` | `customtasks/agentchat/AgentChatTaskModule.kt:234-241` |
| Sérialiseur proto | `customtasks/agentchat/McpServersSerializer.kt:24-39` |
| Proto des serveurs/outils | `app/src/main/proto/mcp.proto:24-58` (`McpServers`, `McpServer`, `McpTool`) |
| Proto de l'authentification | `app/src/main/proto/settings.proto:89-102` (`McpAuth`), `:109` (`UserData.mcp_auths`, map URL → McpAuth) |
| Chiffrement des secrets | `security/SecretCipher.kt:21-92` |
| Conteneur des outils (singleton) | `customtasks/agentchat/AgentTools.kt:42, 62, 73-79, 94-101` |
| Pont llama.cpp | `agent/LlamaCppToolBridge.kt:90-98` (spec), `:142-155` (`availableSpecs`), `:192-198` (dispatch) |
| Boucle d'agent llama.cpp + encadrement | `agent/DefaultAgentRuntimeExecutor.kt:416-549`, `:556-560` (`frameToolResult`) |
| Format Hermes des outils | `ui/llmsingleturn/ToolCalling.kt:62-77, 108-114` |
| Chat principal (outils optionnels) | `ui/llmchat/LlmChatTaskModule.kt:115-127, 146-153` |
| Analytics | `Analytics.kt` (`MCP_MANAGEMENT`, `MCP_EXECUTION`) |
| Test | `app/src/test/.../agent/LlamaCppToolBridgeTest.kt:45`. Il vérifie seulement que la spec `runMcpTool` existe. **Aucun test** ne couvre `McpManagerViewModel` ni `RunMcpTool`. [C] |

`getToolsPrompt()` se trouve dans `McpManagerViewModel.kt:271-279`.

---

## 2. SDK MCP, transport, création du client

**Dépendance [C]**
- `gradle/libs.versions.toml:45` : `mcp = "0.15.0"`
- `:104` : `mcp-kotlin-sdk = { group = "io.modelcontextprotocol", name = "kotlin-sdk", version.ref = "mcp" }`
- `app/build.gradle.kts:160` : `implementation(libs.mcp.kotlin.sdk)`

Les jars `kotlin-sdk-{core,client,server}-jvm-0.15.0` sont présents dans `~/.gradle/caches`. Le jar serveur est donc embarqué sans être utilisé. Ktor est en `3.6.0` (`libs.versions.toml:46`), moteur `ktor-client-android`.

**Transport [C]**
- Le seul transport utilisé est `StreamableHttpClientTransport` (`McpManagerViewModel.kt:413-425`).
- Le `HttpClient(Android) { install(SSE) }` (`:72`) installe SSE uniquement parce que Streamable HTTP peut répondre en flux SSE.
- `SseClientTransport` (ancien protocole HTTP+SSE 2024-11-05), `StdioClientTransport` et `WebSocketClientTransport` existent dans le SDK mais **ne sont pas utilisés**. Il n'y a aucun repli automatique si le serveur ne parle que l'ancien SSE.

**Client [C]**
- Création : `Client(clientInfo = Implementation(name = "google-ai-edge-gallery", version = BuildConfig.VERSION_NAME))` (`:406-410`). Le nom est encore celui de Google, pas « Altrex ».
- Pas de `ClientOptions`, donc capacités par défaut. Aucune capacité `roots`, `sampling` ou `elicitation` n'est déclarée (supposé vide [S] : constructeur `ClientOptions()` par défaut).
- `setElicitationHandler` existe dans le SDK mais n'est pas utilisé.
- `client.connect(transport)` (`:426`) effectue la poignée de main `initialize`.

**Version de protocole [C]** (bytecode `types/CommonKt`)
- `LATEST_PROTOCOL_VERSION = "2025-11-25"`, versions acceptées : `2025-06-18`, `2025-03-26`, `2024-11-05`.
- L'appli ne fixe rien : la négociation est entièrement faite par le SDK.

**Délais du SDK [C]** (bytecode `shared/ProtocolKt`)
- `DEFAULT_REQUEST_TIMEOUT = 60 s`, appliqué par défaut à chaque requête (`initialize`, `tools/list`, `tools/call`).
- `StreamableHttpClientTransport` prend aussi des `ReconnectionOptions` (reconnexion du flux SSE), laissées par défaut.

**Auth dans le SDK [C]** : le jar client 0.15.0 ne contient **aucune** classe auth/OAuth (`unzip -l | grep -i auth` → 0). L'OAuth est donc entièrement à la charge de l'appli.

---

## 3. Ajout d'un serveur, stockage des secrets, OAuth

**Champs saisis [C]** (`AddMcpServerFromUrlDialog.kt`)
- URL (`:145-169`).
- Liste « Autorisation » (`:189-240`) : *Aucune* / *En-tête de requête* / *OAuth (en cours)*. OAuth est **désactivé** (`enabled = false`, `:237`) ; libellé FR dans `res/values-fr/strings.xml:340`.
- Avec « En-tête » : nom (`:250-261`) et valeur (`:268-279`). La valeur s'affiche **en clair**, sans `PasswordVisualTransformation`.
- Un seul en-tête est possible. Un « jeton » se saisit donc comme `Authorization` + `Bearer xxx`.

**Contrôles à l'ajout [C]**
- Doublon d'URL : comparaison exacte de chaîne (`:312`, `McpManagerViewModel.kt:266-268`).
- Hôte « approuvé » : `googleapis.com` et ses sous-domaines (`:73, 365-374`). Pour tout autre hôte, un avertissement est affiché (`AddMcpDisclaimerDialog`).
- Le **schéma** (http ou https) n'est pas vérifié, et `UrlGuard` n'est pas appelé.

**Stockage [C]**
- Le serveur et ses outils vont dans le DataStore proto `mcp_servers.pb` (`AgentChatTaskModule.kt:236-240`), message `McpServer` (`mcp.proto:24-37`) : url, tools, enabled, name, version, description. Il n'y a pas d'identifiant stable : **l'URL sert de clé**.
- L'authentification va dans `UserData.mcp_auths[url]` (`settings.proto:109`), écrit en `McpManagerViewModel.kt:208-210`.
- La valeur de l'en-tête est chiffrée par `SecretCipher.encrypt` (`McpManagerViewModel.kt:165`) et déchiffrée à chaque requête (`:421`).
- `SecretCipher` : AES-256-GCM, clé dans l'AndroidKeyStore, alias `altrex_secrets_v1` (`SecretCipher.kt:24, 65-91`).
- Si le Keystore est en panne, le secret est **gardé en clair** (`:42-46`). Si le déchiffrement échoue, la valeur devient vide (`:53-61`).
- Le nom de l'en-tête est stocké en clair, ce qui est acceptable.
- Les sauvegardes Android sont désactivées (`AndroidManifest.xml:56` `allowBackup="false"` + `data_extraction_rules.xml` [ALTREX]).

**OAuth [C] : confirmé, il n'y en a pas**
- `McpAuth.OAuth {}` est vide (`settings.proto:95`). Choisir OAuth ne fait que `setOauth(getDefaultInstance())` (`McpManagerViewModel.kt:169-171`), et le transport ne gère que `REQUEST_HEADER` (`:414-425`).
- Aucune lecture de `/.well-known/oauth-protected-resource` (RFC 9728) ni de `/.well-known/oauth-authorization-server` (RFC 8414).
- Aucun traitement de `WWW-Authenticate` sur une réponse 401, ni d'enregistrement dynamique de client (RFC 7591).
- Aucun PKCE, aucune redirection, aucun rafraîchissement de jeton.

**À noter [C]**
- La bibliothèque **AppAuth `net.openid:appauth:0.11.1`** est déjà en dépendance (`libs.versions.toml:31, 86`, `build.gradle.kts:136`). Elle sert à la connexion Hugging Face (`ui/modelmanager/ModelManagerViewModel.kt:88-92`, `common/ProjectConfig.kt:20-46`).
- Son schéma de redirection est encore un **gabarit non renseigné** (`build.gradle.kts:42-43` `REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP`, `ProjectConfig.kt:25, 32`). Le flux OAuth HF n'est donc probablement pas fonctionnel non plus [S].

---

## 4. Cycle de vie

**Quand les serveurs se connectent [C]**
- La connexion passe uniquement par `loadMcpServers()` (`McpManagerViewModel.kt:77-141`).
- Elle n'est appelée **qu'à un seul endroit** : `buildAgentSystemPrompt` (`AgentChatTaskModule.kt:255`), lui-même appelé par :
  - `AgentChatTask.initializeModelFn` (`AgentChatTaskModule.kt:160`), à chaque chargement du modèle dans la tâche « Agent Skills » ;
  - `LlmChatTask.initializeModelFn` (`ui/llmchat/LlmChatTaskModule.kt:118`), seulement si « Skills et outils » est actif **et** `agentTools.isReady()`.
- Il n'y a **aucune** connexion au lancement de l'appli ni à l'ouverture de la feuille MCP.
- `resetSessionWithCurrentSkillsAndMcps` (`AgentChatScreen.kt:860-901`) relit `getToolsPrompt()` **sans** recharger.

**Déroulé de `loadMcpServers` [C]**
- Lecture du DataStore (`:83`), puis connexion **séquentielle** (`savedServers.map { … }`, `:84`), avec pour chacun `connect` puis `listTools`.
- Le modèle attend la fin : `skillsJob.join(); mcpJob.join()` (`AgentChatTaskModule.kt:256-257`) a lieu **avant** `executor.initialize` (`:173`).
- Un serveur injoignable **retarde donc le chargement du modèle**, jusqu'aux délais Ktor ou SDK (60 s par requête côté SDK [C] ; délais de connexion du moteur Ktor Android par défaut [S]). Avec N serveurs en panne, les délais s'additionnent.

**Erreurs [C]**
- Un serveur en échec est mis en mémoire en `enabled=false` avec `error=message` (`:109-118`).
- Sur disque, c'est l'état d'origine qui est gardé (`:125-135`), pour réessayer au prochain chargement.
- L'UI affiche l'erreur et grise les interrupteurs (`McpManagerBottomSheet.kt:493-497, 508, 526`).
- Il n'y a **pas de bouton « Réessayer »** : il faut recharger le modèle.

**Reconnexion [C]**
- Rien côté appli. Si la session HTTP expire (`Mcp-Session-Id` invalide) ou si le réseau change, les appels échouent jusqu'au prochain `loadMcpServers`.
- Le SDK reconnecte seulement le flux SSE (`ReconnectionOptions` par défaut).

**listTools et cache [C]**
- `client.listTools()` est appelé sans curseur (`:427`). Seule la **première page** est lue, et `nextCursor` est ignoré.
- La notification `notifications/tools/list_changed` n'est pas gérée.
- La liste est mise en cache dans `mcp_servers.pb` (champ `tools`), avec `enabled` et `alwaysAllow` restaurés par nom d'outil (`:88-91, 430-431`).
- Le cache sert à l'affichage. Un serveur en erreur n'expose pas ses outils au prompt, puisqu'il est désactivé en mémoire.

**Fuites [C]**
- Aucun `client.close()` ni `transport.close()` / `terminateSession()` n'existe dans le code (grep). Chaque `loadMcpServers` crée de nouveaux `Client` et **abandonne** les anciens. C'est le cas à chaque chargement du modèle, chaque bascule « Skills et outils » qui passe par `initializeModelFn`, etc.
- Le `HttpClient` (`:72`) n'est jamais fermé : il n'y a pas d'`onCleared`.
- `removeMcpServer` (`:253-263`) ne ferme pas le client et **ne supprime pas** `mcp_auths[url]`. Le secret chiffré reste orphelin.

**Portée du ViewModel [C]**
- `McpManagerViewModel` est obtenu par `hiltViewModel()` dans `AgentChatScreen` (`:138`). Il y a donc une instance par écran (chat principal, Agent Skills).
- L'instance est recopiée dans le singleton `AgentTools` (`AgentChatScreen.kt:151`, `AgentTools.kt:62`), qui garde une référence au dernier ViewModel, même après que son écran a été détruit.
- Une nouvelle instance a `mcpServers = []` tant que `loadMcpServers` n'a pas tourné.

**Conséquences supposées [S]**
- Dans le chat principal, si le modèle a été chargé « outils désactivés » puis que l'on active l'interrupteur, `resetSessionWithCurrentSkillsAndMcps` lit une liste vide. Les outils MCP sont alors absents du prompt et la feuille MCP paraît vide jusqu'au prochain chargement du modèle.
- Dans `AgentChatTask.initializeModelFn`, `agentTools.mcpManagerViewModel` (lateinit) est lu sans `isReady()`. Si le modèle s'initialise avant la composition de l'écran, on obtient une `UninitializedPropertyAccessException`.

**Concurrence [C]**
- `setMcpServerEnabled`, `setMcpToolEnabled` et les autres appellent `persistServers` (un `launch`) **à l'intérieur** de la lambda `_uiState.update` (`:302, 328, 344, 364, 390`). Cette lambda peut être rejouée en cas de conflit, ce qui donne des écritures en double.
- `loadMcpServers` écrase toute la liste (`:122`) : une bascule faite pendant le chargement est perdue.

---

## 5. Côté modèle

### 5.1 Présentation des outils

**`getToolsPrompt()` [C]** (`McpManagerViewModel.kt:271-279`)
- Pour chaque outil activé d'un serveur activé :
  ```
  MCP tool name: "<name>"
  - Description: <description>
  - Input schema: <schema JSON>
  ```
- Les blocs sont séparés par une ligne vide. Il n'y a ni limite de longueur ni regroupement par serveur. Le nom du serveur n'apparaît pas.

**Schéma [C]**
- Il est reconstruit à la main (`:436-440`) : `{"type":"object","properties":<properties>,"required":[…]}`.
- Les `$defs` (`ToolSchema.getDefs()` existe dans le SDK) et toute autre clé de premier niveau sont perdus. Les `$ref` qui y renvoient deviennent donc invalides.
- Si `properties` est nul, on obtient `"properties":null` [S].
- Le `title`, les `annotations` (readOnlyHint, destructiveHint…), l'`outputSchema` et les `icons` de `Tool` sont ignorés.

**Injection dans le prompt [C]**
- `buildAgentSystemPrompt` (`AgentChatTaskModule.kt:258-269`) et `resetSessionWithCurrentSkillsAndMcps` (`AgentChatScreen.kt:873-887`) remplacent `___SKILLS___` et `___TOOLS___` par simple `String.replace` (`agent/PromptExpander.kt`).
- Le gabarit est choisi par `getEffectiveBaseSystemPrompt` (`AgentChatTaskModule.kt:279-285`) : `DEFAULT_SYSTEM_PROMPT_TRIMMED` s'il y a au moins un outil MCP, sinon `DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED` (seulement si le prompt courant est un prompt par défaut).
- Dans le chat principal, c'est toujours `DEFAULT_SYSTEM_PROMPT_TRIMMED` qui est passé (`LlmChatTaskModule.kt:118`, `AgentChatScreen.kt:201`).

**Texte exact du prompt quand des outils MCP sont actifs [C]**
- Contenu : `DEFAULT_SYSTEM_PROMPT` (`AgentChatTaskModule.kt:58-98`) après `trimIndent()`.
- Taille : 2 044 caractères avant substitution.
- Format des skills (`skills/SkillExtensions.kt:29-35`) : `- Skill name: "<nom>"\n- Description: <desc>`.

```
You are an AI assistant that helps users by answering questions and completing tasks using skills and tools. For EVERY new task, request, or question, you MUST execute the following steps in exact order. You MUST NOT skip any steps.

CRITICAL RULE: You MUST execute all steps silently. Do NOT generate or output any internal thoughts, reasoning, explanations, or intermediate text at ANY step.

1. EVALUATE AND ROUTE:
   Determine if the request should be handled by a "Skill" (requires loading instructions) or directly by an "MCP Tool".
   - If it is a Skill: Go to Step 2.
   - If it is an MCP Tool: Go to Step 4.
   - If nothing is found, output "No skills or tools found" and stop.

--- SKILLS ---
- Skill name: "<skill 1>"
- Description: <…>

- Skill name: "<skill 2>"
- Description: <…>

--- MCP TOOLS ---
MCP tool name: "<outil 1>"
- Description: <description fournie par le serveur>
- Input schema: {"type":"object","properties":{…},"required":["…"]}

MCP tool name: "<outil 2>"
- Description: <…>
- Input schema: {…}

==================================================
FLOW A: SKILL EXECUTION
==================================================

2. Find the most relevant skill from the --- SKILLS --- list. You MUST NOT use `run_intent` or `runMcpTool` under any circumstances at this step.

3. Use the `load_skill` tool to read its instructions. Follow the skill's instructions exactly to complete the task.
   - You MUST NOT output any intermediate thoughts or status updates. No exceptions!
   - Output ONLY the final result when successful. It should contain a one-sentence summary of the action taken and the final result of the skill.
   - Stop here once Flow A is complete.

==================================================
FLOW B: MCP TOOL DIRECT EXECUTION
==================================================

4. Find the most relevant tool from the --- MCP TOOLS --- list.

5. Call the `runMcpTool` tool with the following parameters:
   - `toolName`: The name of the tool to run. Use the exact name from the list above. Do not hallucinate the name. Pay attention to casing and plurals.
   - `input`: The input JSON object that matches the tool's expected input schema.

6. Output ONLY the final result returned by the tool. You MUST NOT output any intermediate thoughts or status updates. No exceptions!
```

**Chemin llama.cpp [C]**
- `runLlamaCppAgentLoop` ajoute à ce texte le bloc Hermes (`DefaultAgentRuntimeExecutor.kt:445` → `ToolCalling.systemPrompt`, `ToolCalling.kt:62-77`).
- Le prompt d'entraînement d'Argora (manifeste) n'est **pas** utilisé dès qu'il existe des outils (`DefaultAgentRuntimeExecutor.kt:441`).
- `availableSpecs` garde les 6 outils (`AgentTools.kt:100` les déclare tous, toujours). **`runMcpTool` est annoncé même si aucun serveur MCP n'est configuré.**

Suite exacte du prompt (bloc Hermes) :

```

# Tools

You may call one or more functions to assist with the user query.

You are provided with function signatures within <tools></tools> XML tags:
<tools>
{"type":"function","function":{"name":"load_skill","description":"Loads a skill's instructions by name. Always call this before using a skill so you learn exactly how to use it.","parameters":{"type":"object","properties":{"skillName":{"type":"string","description":"The name of the skill to load."}},"required":["skillName"]}}}
{"type":"function","function":{"name":"run_js","description":"Runs a JavaScript skill script.","parameters":{"type":"object","properties":{"skillName":{"type":"string","description":"The name of the skill."},"scriptName":{"type":"string","description":"The script name to run. Use 'index.html' if not provided by the user."},"data":{"type":"string","description":"A JSON string to pass to the script. Use an empty string if not provided."}},"required":["skillName"]}}}
{"type":"function","function":{"name":"run_intent","description":"Run an Android intent. It is used to interact with the app to perform certain actions.","parameters":{"type":"object","properties":{"intent":{"type":"string","description":"The intent to run."},"parameters":{"type":"string","description":"A JSON string containing the parameter values required for the intent."}},"required":["intent"]}}}
{"type":"function","function":{"name":"runMcpTool","description":"Run an MCP tool.","parameters":{"type":"object","properties":{"toolName":{"type":"string","description":"The name of the tool to run."},"input":{"type":"string","description":"The parameters passed to the tool as JSON input."}},"required":["toolName","input"]}}}
{"type":"function","function":{"name":"webSearch","description":"Search the web for up-to-date information and return a list of results.","parameters":{"type":"object","properties":{"query":{"type":"string","description":"The search query."}},"required":["query"]}}}
{"type":"function","function":{"name":"run_command","description":"Run a shell command in your own Alpine Linux environment (apk add, python3, git, curl…). The user sees and approves every command. Returns the exit code and the output.","parameters":{"type":"object","properties":{"command":{"type":"string","description":"The shell command to run (sh syntax), from /root."}},"required":["command"]}}}
</tools>

For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{"name": <function-name>, "arguments": <args-json-object>}
</tool_call>
```

Ce bloc a été reconstruit à partir de `LlamaCppToolBridge.kt:43-118` et `ToolCalling.kt:44-77`. L'ordre des clés est celui d'insertion, comme dans le `org.json` d'Android [S].

**Ce que voit le modèle côté LiteRT [C]**
- Le même texte de prompt système, avec les outils passés nativement par `getLiteRtToolProviders()` (`ToolsProvider.kt:20`), à partir des annotations `@Tool`.
- Pour MCP : `@Tool(description = "Run a MCP tool")`, paramètres `toolName` et `input` (`RunMcpTool.kt:49-53`).

### 5.2 Appel d'un outil

**llama.cpp [C]**
- Le modèle écrit `<tool_call>{"name":"runMcpTool","arguments":{"toolName":…,"input":…}}</tool_call>`, analysé par `ToolCalling.parseToolCalls` (`DefaultAgentRuntimeExecutor.kt:493`).
- `LlamaCppToolBridge.dispatch` (`:192-198`) :
  - normalise le nom (casse, `_`) ;
  - accepte `toolName`, `tool_name` ou `name`, et `input` ou `arguments` (`{}` par défaut) ;
  - un objet JSON passé dans `input` est accepté, car `optString` le resérialise [S, comportement org.json].
- Au plus 4 appels par tour (`:516`) et 6 étapes (`:53`), avec `RepeatCallGuard`.
- Le résultat passe par `ToolResultBudget.fit` (`:530`). Un résultat long est tronqué, et le modèle peut lire la suite avec `read_tool_output`.

**LiteRT [C]**
- Le moteur appelle directement `RunMcpTool.runMcpTool(toolName, input)`, sans boucle applicative ni `ToolResultBudget`.

**Dans `RunMcpTool` [C]**
- Le tout s'exécute dans `runBlocking(Dispatchers.IO)` (`:55`).
- Recherche du serveur : **premier** serveur dont la liste contient ce nom (`:56-59`). L'état `enabled` du serveur et de l'outil **n'est pas vérifié**.
- Si l'outil est introuvable : repli « Try to run it as a skill » (`:200-207`).
- Si le client est nul : erreur `"Client not initialized"` (`:67-71`).
- Contrôle de permission (§ 6), puis `client.callTool(CallToolRequest(CallToolRequestParams(name, Json.parseToJsonElement(input).jsonObject)))` (`:119-127`) :
  - aucun `RequestOptions`, donc le délai SDK par défaut de 60 s ;
  - un `input` qui n'est pas un objet JSON lève une exception, rattrapée en `{"error": …}`.

### 5.3 Retour

**Contenu [C]**
- Succès : `{"result": <concaténation des TextContent>, "status": "succeeded"}` (`:160-174`).
- `isError == true` : `{"error": <TextContent>, "status": "failed"}` (`:143-158`).
- Exception : `{"error": e.message, "status": "failed"}` (`:176-190`).
- `ImageContent`, `AudioContent`, `EmbeddedResource`, `ResourceLink` et `structuredContent` sont **jetés** (`filterIsInstance<TextContent>()`). Un outil qui ne renvoie qu'une image produit `result: ""` avec un succès affiché.

**Côté UI [C]**
- Le texte complet est ajouté au panneau de progression (`addItemDescription = successText`, `:170`), sans limite de taille.
- Le succès est envoyé avec `inProgress = true` (`:168`), une petite incohérence d'affichage.

**Journaux [C]**
- L'entrée est masquée par `SecretRedactor` (`:54`).
- **La sortie complète est journalisée en clair** (`Log.d … succeeded:\n$successText`, `:162` ; `Log.e … $errorText`, `:146`).

**Format envoyé au modèle [C]**
- llama.cpp : la map est sérialisée par Gson (`LlamaCppToolBridge.kt:132`), encadrée, puis placée dans `<tool_response>` (`ToolCalling.kt:108-114`).
- LiteRT : la map `Map<String,String>` est rendue par le moteur.

### 5.4 Encadrement « non fiable »

**llama.cpp [C]**
- `frameToolResult` (`DefaultAgentRuntimeExecutor.kt:556-560`) encadre tout sauf `load_skill` et `run_intent` : `UntrustedContent.frame("résultat de l'outil runMcpTool", json)`.
- Le marqueur contient un nonce aléatoire, et les balises de chat ou d'outils sont neutralisées (`security/UntrustedContent.kt:14-50`).

**LiteRT [C]** : **aucun encadrement**. `frameToolResult` n'existe que dans la boucle llama.cpp, et `RunMcpTool` renvoie le texte brut.

**Descriptions et schémas [C]**
- Les descriptions et schémas fournis par le serveur arrivent **bruts dans le prompt système** (`getToolsPrompt`), sans encadrement, sans neutralisation des balises et sans limite. Un serveur malveillant ou compromis peut ainsi faire de l'empoisonnement d'outil (*tool poisoning*).

---

## 6. Permissions

**Mécanisme [C]**
- `RunMcpTool.alwaysAllow = true` (`:45`) concerne le méta-outil. Le vrai contrôle se fait par outil MCP via `McpTool.always_allow` (`mcp.proto:50`, lu en `RunMcpTool.kt:75-76`).
- Si l'outil n'est pas autorisé d'office, `AskMcpToolCallPermissionAction(toolName, argument)` (`ToolAction.kt:121-125`) est envoyé sur l'`actionChannel`.
- L'écran le reçoit (`AgentChatScreen.kt:493-495`) et affiche `McpToolCallPermissionDialog` (`AgentChatScreen.kt:695-718` ; dialogue `McpToolCallPermissionDialog.kt:45-121`), avec :
  - le nom de l'outil et les arguments JSON formatés ;
  - trois boutons : Toujours autoriser / Autoriser une fois / Ne pas autoriser ;
  - un rejet du dialogue qui compte comme un refus (`:53`).
- Le dialogue **n'affiche ni le serveur ni son URL**.

**« Toujours autoriser » [C]**
- Persisté par `setMcpToolAlwaysAllow(url, toolName, true)` (`AgentChatScreen.kt:702-713` → `McpManagerViewModel.kt:370-393`).
- Le serveur est retrouvé par nom d'outil (premier trouvé). En cas de collision de noms entre deux serveurs, la mauvaise entrée peut être marquée.
- Révocation possible dans la feuille Outils (`McpToolManagerBottomSheet.kt:287`, `setMcpToolAlwaysAllow(..., false)`).

**Correctifs [ALTREX] (commit `d11d9cf`, déjà dans `origin/main`) [C]**
- Canal nul : refus immédiat, `"The user cannot be asked for permission here, so the tool was not run."` (`RunMcpTool.kt:80-89`).
- Délai : `withTimeoutOrNull(USER_REPLY_TIMEOUT_MS) { … } ?: PermissionResult.DENY` (`RunMcpTool.kt:91-93`), avec `USER_REPLY_TIMEOUT_MS = 5 * 60_000L` (`ToolAction.kt:23-27`).

**Limites restantes [C]**
- Après les 5 min, le dialogue **reste affiché** : `currentMcpPermissionAction` n'est pas remis à nul et le `CompletableDeferred` n'est pas complété. Si l'utilisateur clique alors « Toujours autoriser », l'autorisation est persistée alors que l'appel a été refusé (`AgentChatScreen.kt:695-716`).
- Il n'y a aucune distinction lecture/écriture : les `ToolAnnotations` (`readOnlyHint`, `destructiveHint`…) du SDK sont ignorées. Le même dialogue sert pour `search_issues` et pour `delete_repository`.
- L'outil désactivé reste appelable (§ 5.2). Le dialogue reste le seul garde-fou, et il saute si `alwaysAllow` est vrai.

---

## 7. Problèmes constatés (par gravité)

### Bugs fonctionnels
1. **[C] Appel d'outils désactivés.** `RunMcpTool.kt:56-59` ne filtre ni `mcpServer.enabled` ni `tool.enabled`. Un modèle qui « devine » un nom peut appeler un outil que l'utilisateur a coupé.
2. **[C] Connexion bloquante et séquentielle au chargement du modèle** (`AgentChatTaskModule.kt:255-257`, `McpManagerViewModel.kt:84`). Un serveur lent ou mort retarde la disponibilité du modèle (≥ 60 s par serveur côté SDK).
3. **[C] Fuites.** Les `Client` ne sont jamais fermés et sont recréés à chaque `loadMcpServers`. Le `HttpClient` n'est jamais fermé. Aucun `terminateSession` côté serveur.
4. **[C] Pas de reconnexion** ni de bouton Réessayer. Une session expirée reste cassée jusqu'au rechargement du modèle.
5. **[C/S] État vide dans une nouvelle instance de ViewModel.** Les outils MCP sont absents si l'interrupteur « Skills et outils » est activé après un chargement sans outils (§ 4).
6. **[C] Pagination de `tools/list` ignorée** (`:427`). **`$defs` perdus** (`:436-440`). `properties` nul non géré [S].
7. **[C] Collisions de noms.** Deux serveurs qui exposent `search` donnent le premier gagnant, pour l'appel comme pour « Toujours autoriser ». Les noms ne sont pas préfixés par le serveur.
8. **[C] Contenus non texte jetés.** Un outil qui renvoie seulement une image ou une ressource est vu comme un succès vide.
9. **[C] Persistance dans la lambda `update`**, rejouable (§ 4). `loadMcpServers` écrase les bascules faites pendant le chargement.
10. **[C] Dialogue de permission orphelin** après le délai de 5 min (§ 6).

### Appels bloquants et délais
11. **[C] `runBlocking(Dispatchers.IO)`** dans `RunMcpTool.kt:55`.
    - Côté llama.cpp, l'appel se fait depuis une coroutine IO (`DefaultAgentRuntimeExecutor.kt:216`). `runBlocking` y **coupe l'annulation** : un Stop pendant un `callTool` ou pendant l'attente du dialogue n'interrompt rien (jusqu'à 60 s ou 5 min).
    - Côté LiteRT, la méthode `@Tool` est synchrone par construction : le blocage y est inévitable, mais il faudrait au moins un délai explicite.
12. **[C] Aucun délai applicatif** sur `connect`, `listTools` ou `callTool`. Seul le défaut SDK de 60 s s'applique (bytecode `ProtocolKt.DEFAULT_REQUEST_TIMEOUT`). Aucun `RequestOptions(timeout=…)` n'est passé. Les délais Ktor (connexion, socket) restent ceux par défaut [S].
13. **[C] `AgentTools.sendToolAction`** fait un `runBlocking(Dispatchers.Default)` (`AgentTools.kt:125-127`), appelé depuis l'UI (`AgentChatScreen.kt:832-855`). Le canal est `UNLIMITED`, donc peu de risque en pratique.
14. **[C] `runBlocking` dans `resetSessionWithCurrentSkillsAndMcps`** (`AgentChatScreen.kt:876-877`), sur le thread principal.

### Sécurité
15. **[C] Descriptions d'outils non encadrées** dans le prompt système (empoisonnement d'outil, § 5.4).
16. **[C] Résultat MCP non encadré côté LiteRT** (§ 5.4).
17. **[C] La sortie des outils MCP est journalisée en clair** (`RunMcpTool.kt:146, 162`). Elle peut contenir des données privées (mails, issues privées…).
18. **[C] Valeur d'en-tête saisie en clair** (`AddMcpServerFromUrlDialog.kt:268-279`). Repli **en clair** si le Keystore échoue (`SecretCipher.kt:42-46`). Secret orphelin après suppression (`McpManagerViewModel.kt:253-263`).
19. **[C] Pas de contrôle du schéma d'URL.** `http://x.googleapis.com` passe même l'avertissement (`isMcpHostApproved` ne regarde que l'hôte, `:365-374`).
    - Avec `targetSdk = 36` (`build.gradle.kts:36`), sans `usesCleartextTraffic` ni configuration de sécurité réseau, le trafic en clair est **refusé par Android**. Une URL http échouera donc à la connexion plutôt que d'envoyer le jeton en clair [S, comportement de la plateforme].
    - Il vaut mieux refuser explicitement `http` (sauf éventuellement en mode développeur), avec un message clair.
20. **[C] `UrlGuard` n'est pas appliqué aux URL MCP.** Le risque SSRF est **faible aujourd'hui** : l'URL est tapée par l'utilisateur, pas choisie par le modèle, et un serveur sur le réseau local peut être voulu.
    - Il deviendra **réel avec OAuth** : `resource_metadata`, `authorization_servers`, `token_endpoint` et `registration_endpoint` sont fournis par le serveur et doivent passer par `UrlGuard` (https obligatoire, pas d'IP privée).
21. **[S] Redirections.** Ktor suit les redirections par défaut. L'en-tête ajouté par `requestBuilder` pourrait être renvoyé vers un autre hôte en cas de 30x. À vérifier, ou à désactiver (`followRedirects = false`) pour les requêtes authentifiées.
22. **[C] Pas de distinction lecture/action** ; « Toujours autoriser » fonctionne par nom d'outil, sans afficher le serveur (§ 6).

### Taille du prompt
23. **[C] Le prompt de base pèse déjà ≈ 4,6 k caractères** : 2 044 pour le gabarit + ≈ 2,2 k pour le bloc Hermes de 6 outils + l'en-tête. Cela fait environ 1,3 à 1,5 k tokens [S, ≈ 3 caractères par token comme le code], **avant** les skills et les outils MCP.
    - Chaque outil MCP ajoute sa description et son schéma complets, sans limite. Un serveur de type GitHub (plusieurs dizaines d'outils, souvent 0,5 à 2 k caractères chacun [S]) ajoute **10 à 30 k tokens**.
    - Le contexte llama.cpp est souvent de 2 048 à 4 096 tokens (`runtime/LlamaCppModelHelper.kt:52, 114-117`).
    - `loopBudgetChars` (`DefaultAgentRuntimeExecutor.kt:458`) ne réduit que l'historique : il descend au plancher de 2 000 caractères et ne tronque **jamais** le prompt système, d'où un débordement de contexte [S sur la réaction exacte de llama.cpp].
    - Le prompt est en anglais et impose « silently / no thoughts », ce qui entre en conflit avec le mode réflexion d'Argora (prompt du manifeste ignoré dès qu'il y a des outils, `:441`).
24. **[C] `runMcpTool` est annoncé même sans serveur MCP** (`AgentTools.kt:100`, `LlamaCppToolBridge.kt:149`), soit ≈ 250 caractères perdus et une tentation d'appel inutile pour un petit modèle.

---

## 8. Ce qui manque pour des connecteurs « grand public »

### Manques
- **OAuth 2.1 complet** :
  1. traiter le 401 et lire `WWW-Authenticate: Bearer resource_metadata=…` ;
  2. récupérer `/.well-known/oauth-protected-resource` (RFC 9728) ;
  3. récupérer `/.well-known/oauth-authorization-server` (RFC 8414), avec repli sur `openid-configuration` ;
  4. faire l'enregistrement dynamique du client (RFC 7591), ou utiliser un `client_id` préenregistré par connecteur du catalogue ;
  5. lancer l'autorisation avec **PKCE S256** et le paramètre `resource` (RFC 8707) dans un Custom Tab ;
  6. traiter la redirection (schéma propre à Altrex ou App Link https) ;
  7. échanger le code, stocker access + refresh token chiffrés (`SecretCipher`), rafraîchir automatiquement, ré-autoriser si besoin et révoquer à la déconnexion.

  Rien de tout cela n'existe. Le SDK 0.15.0 ne fournit rien pour l'auth.
- **Modèle « connecteur »** : identifiant stable (pas l'URL), nom affiché, icône (`Tool.icons` / `serverInfo`), état (connecté, à autoriser, expiré, erreur), date de dernière connexion, portées accordées, type de transport. Le proto actuel n'a ni id ni état.
- **Catalogue** de connecteurs connus (GitHub, Notion, Linear…) : URL officielle, `client_id` si nécessaire, portées, description courte FR, icône, outils « lecture » ou « action » attendus. À distribuer en JSON embarqué ou signé.
- **Écran Connecteurs** dédié, hors feuille de chat : liste, connexion/déconnexion, test, détail des outils, révocation.
- **Cycle de vie robuste** :
  - connexion paresseuse ou en arrière-plan, **parallèle**, avec délai court (5 à 10 s) et sans bloquer le chargement du modèle ;
  - gestionnaire singleton (pas un ViewModel par écran) ;
  - fermeture propre ;
  - reconnexion (session expirée, réseau) ;
  - pagination et `tools/list_changed`.
- **Permissions lecture/action** à partir de `ToolAnnotations` :
  - lecture seule autorisable globalement par connecteur ;
  - action confirmée à chaque fois, avec un avertissement renforcé si `destructiveHint` ;
  - dialogue affichant le **connecteur** et un résumé lisible.
- **Choix en deux temps pour les petits modèles** :
  - étape 1 : le prompt ne liste que les connecteurs actifs (nom + une ligne) ; le modèle (ou un classifieur ou des mots-clés locaux) en choisit un ;
  - étape 2 : seuls les outils de ce connecteur (ou les k plus pertinents) sont injectés, avec des descriptions **tronquées et encadrées** et des schémas simplifiés ;
  - côté llama.cpp, cela peut prendre la forme d'un méta-outil `list_connector_tools(connector)` suivi de `runMcpTool`, ou d'un deuxième passage de prompt.
- **Rendu des résultats** : images (miniature dans le chat et description pour le modèle), `ResourceLink`/`EmbeddedResource` (lien ou texte), `structuredContent` (JSON compact), limite de taille côté LiteRT également.
- **Noms qualifiés** `connecteur.outil` pour éviter les collisions.
- Éventuellement **elicitation** (`Client.setElicitationHandler`) et **roots**.

### Réutilisable tel quel (estimation)

| Brique | Réutilisable | Remarque |
|---|---|---|
| SDK `kotlin-sdk 0.15.0` : `Client`, `StreamableHttpClientTransport` (`requestBuilder` pour `Authorization: Bearer`) | Oui | Ajouter `RequestOptions(timeout)` et `SseClientTransport` en repli |
| `SecretCipher` (`security/SecretCipher.kt`) | Oui | Supprimer le repli en clair pour les jetons OAuth |
| `UrlGuard` (`security/UrlGuard.kt`) | Oui | À appliquer aux URL de métadonnées et aux points de terminaison OAuth |
| `UntrustedContent` | Oui | À appliquer aussi aux descriptions d'outils et au chemin LiteRT |
| `ToolResultBudget`, `RepeatCallGuard` (`agent/AgentGuards.kt`) | Oui | |
| `LlamaCppToolBridge` + `ToolCalling` | Oui, à étendre | Ajouter le méta-outil de sélection |
| Dialogue de permission + `AskMcpToolCallPermissionAction` + délai 5 min | Oui, à enrichir | Afficher le connecteur, distinguer lecture et action, corriger le dialogue orphelin |
| AppAuth 0.11.1 (déjà en dépendance) | Oui | PKCE S256 par défaut, `RegistrationRequest` (RFC 7591), `AuthorizationServiceConfiguration` construit à la main à partir des métadonnées RFC 8414. Configurer un vrai `appAuthRedirectScheme` (aujourd'hui un gabarit) |
| `mcp.proto` / `McpAuth` | Partiellement | `McpAuth.OAuth` à remplir (jetons chiffrés, expiration, `client_id`, AS, portées). Ajouter id et état au serveur |
| `McpManagerViewModel` | Faiblement | Logique à extraire dans un `McpConnectionManager` singleton (Hilt) ; le ViewModel ne garderait que l'état d'UI |
| Feuilles et dialogue d'ajout | Faiblement | Reprendre pour un écran Connecteurs |

Au total, environ **40 %** de la plomberie est réutilisable (transport, sécurité, garde-fous, pont d'outils, dialogue). La partie OAuth, le modèle connecteur, le catalogue, le cycle de vie et la sélection en deux temps sont à écrire.

---

## Liste priorisée de corrections et d'ajouts

### P0 : corrections avant tout connecteur
1. `RunMcpTool` : ne trouver que les outils **activés** de serveurs **activés** (`RunMcpTool.kt:56-59`). Lever l'ambiguïté des noms (préfixe serveur).
2. Encadrer les **descriptions et schémas d'outils** (neutralisation, limite de longueur) dans `getToolsPrompt`. Encadrer le **résultat côté LiteRT** dans `RunMcpTool`.
3. Ne plus journaliser la sortie des outils en clair (`RunMcpTool.kt:146, 162`).
4. Sortir la connexion MCP du chemin critique du chargement du modèle :
   - connexion parallèle avec `withTimeout` court (5 à 10 s) ;
   - le prompt utilise le cache disque si un serveur ne répond pas.
5. Fermer les clients (`client.close()` / `transport.terminateSession()`) avant d'en recréer, à la suppression et dans `onCleared`. Fermer le `HttpClient`.
6. Passer `RequestOptions(timeout = …)` à `callTool`. Remplacer `runBlocking` par un appel `suspend` côté llama.cpp, pour que le Stop annule l'appel.
7. `removeMcpServer` : supprimer aussi `mcp_auths[url]`.
8. Corriger le dialogue de permission orphelin après le délai : fermer le dialogue, et ignorer « Toujours » si le délai est dépassé.

### P1 : solidité
9. Un `McpConnectionManager` **singleton**, injecté dans `AgentTools`, au lieu d'un ViewModel par écran. Il charge les serveurs une fois et sert tous les écrans. Cela règle le cas « interrupteur activé après coup ».
10. Reconnexion automatique (session expirée, 404, changement de réseau) et bouton « Réessayer » dans la feuille et le futur écran.
11. Pagination de `tools/list` et gestion de `notifications/tools/list_changed`.
12. Conserver le schéma complet (`$defs` compris) en le resérialisant avec `McpJson`, plutôt que la reconstruction manuelle.
13. Rendre les contenus non texte : images, ressources, `structuredContent`.
14. Refuser `http://` explicitement, masquer la valeur d'en-tête à la saisie, supprimer le repli en clair de `SecretCipher` pour les jetons.
15. N'annoncer `runMcpTool` que s'il existe au moins un outil MCP actif.
16. Sortir `persistServers` des lambdas `update`.
17. Tests unitaires de `RunMcpTool` et du gestionnaire MCP, avec un faux transport.

### P2 : connecteurs grand public
18. OAuth 2.1 :
    - découverte RFC 9728 puis RFC 8414 (via `UrlGuard`, https obligatoire) ;
    - enregistrement dynamique RFC 7591 ou `client_id` du catalogue ;
    - autorisation PKCE S256 + `resource` (RFC 8707) via AppAuth et Custom Tab ;
    - schéma de redirection Altrex ;
    - jetons chiffrés, rafraîchissement automatique, ré-autorisation sur 401, révocation.
19. Étendre le proto : `McpServer.id`, nom affiché, icône, état, `McpAuth.OAuth { access_token(chiffré), refresh_token(chiffré), expires_at, client_id, as_issuer, scopes }`.
20. Catalogue de connecteurs (JSON embarqué : URL, portées, description FR, icône) et **écran Connecteurs**.
21. Permissions lecture/action fondées sur `ToolAnnotations` (`readOnlyHint`, `destructiveHint`), réglables par connecteur. Le dialogue affiche le connecteur.
22. **Choix en deux temps** pour les petits modèles :
    - étape 1 : connecteurs seulement ;
    - étape 2 : outils du connecteur choisi (top-k, descriptions courtes, schémas simplifiés) ;
    - côté llama.cpp, un méta-outil `list_connector_tools` ;
    - prompt en français, compatible avec le mode réflexion d'Argora (et à documenter dans `docs/FORMAT_OUTILS.md` pour le dataset).
23. Repli `SseClientTransport` pour les serveurs à l'ancien protocole. Renommer `clientInfo` en « Altrex ».
24. Plus tard : elicitation, roots, notifications de progression (`RequestOptions.onProgress`) affichées dans le panneau.
