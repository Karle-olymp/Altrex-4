package com.google.ai.edge.gallery.forge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.forge.engine.ApprovalAnswer
import com.google.ai.edge.gallery.forge.engine.PlanReview
import com.google.ai.edge.gallery.forge.engine.Preset
import com.google.ai.edge.gallery.forge.service.ForgeRuntime
import com.google.ai.edge.gallery.forge.service.ForgeSessionController
import com.google.ai.edge.gallery.forge.workspace.ForgeCommands
import com.google.ai.edge.gallery.forge.workspace.ForgeDoctor
import com.google.ai.edge.gallery.forge.workspace.ForgeWorkspace
import com.google.ai.edge.gallery.forge.workspace.ProjectImporter
import com.google.ai.edge.gallery.ui.common.ModelPicker
import com.google.ai.edge.gallery.ui.common.chat.ModelDownloadStatusInfoPanel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.security.SecurCodeClient
import com.google.ai.edge.gallery.ui.security.SecurityBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [ALTREX] Altrex Forge's screen: the home (projects, sessions) or one session. The agent itself
 * runs in [ForgeRuntime]; this screen reads the session journal and answers what the agent asks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgeScreen(modelManagerViewModel: ModelManagerViewModel, navigateUp: () -> Unit, onMenuClicked: (() -> Unit)?) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_PROMPT_LAB) ?: return
  val mm by modelManagerViewModel.uiState.collectAsState()
  val model = mm.selectedModel
  val workspace = remember { ForgeRuntime.workspace(context) }

  // [ALTREX] Nothing loads by opening Forge: "Charger" (bar or model picker) or sending a message.
  val download = mm.modelDownloadStatus[model.name]
  val initStatus = mm.modelInitializationStatus[model.name]

  var project by rememberSaveable { mutableStateOf<String?>(null) }
  var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
  var startPlan by rememberSaveable { mutableStateOf(false) }
  var refresh by remember { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf<String?>(null) }

  val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  fun askNotifications() {
    if (Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  CompositionLocalProvider(LocalForgePalette provides ForgePalette.B) {
    Column(Modifier.fillMaxSize().background(ForgePalette.B.bg).safeDrawingPadding().imePadding()) {
      com.google.ai.edge.gallery.ui.common.ModelLoadBar(
        task = task,
        model = model,
        modelManagerViewModel = modelManagerViewModel,
        conversationActive = project != null,
      )
      Box(Modifier.fillMaxWidth().weight(1f)) {
      val proj = project
      if (proj == null) {
        var showNew by remember { mutableStateOf(false) }
        var showClone by remember { mutableStateOf(false) }
        val folderPicker =
          rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
              busy = "Copie du dossier…"
              scope.launch {
                val r = withContext(Dispatchers.IO) { ProjectImporter.importFolder(context, workspace, uri) }
                busy = null
                r.onSuccess { (name, rep) ->
                  Toast.makeText(context, "« $name » : ${rep.files} fichiers copiés" + if (rep.truncated) " (limite atteinte)" else "", Toast.LENGTH_LONG).show()
                  refresh++
                }.onFailure { Toast.makeText(context, "Import impossible : ${it.message}", Toast.LENGTH_LONG).show() }
              }
            }
          }
        val data by androidx.compose.runtime.produceState(Triple(emptyList<ForgeWorkspace.ProjectInfo>(), emptyList<ForgeWorkspace.SessionInfo>(), null as String?), refresh) {
          value = withContext(Dispatchers.IO) { Triple(workspace.list(), workspace.sessions(), ProjectImporter.pendingOldProject(context)?.second) }
        }
        ForgeHome(
          projects = data.first,
          sessions = data.second,
          oldProjectName = data.third,
          busy = busy,
          now = System.currentTimeMillis(),
          onMenu = onMenuClicked ?: navigateUp,
          onBuild = { showNew = true },
          onImport = { folderPicker.launch(null) },
          onClone = { showClone = true },
          onMigrate = {
            busy = "Import de l'ancien projet Code…"
            scope.launch {
              val r = withContext(Dispatchers.IO) { ProjectImporter.migrateOldProject(context, workspace) }
              busy = null
              r.onFailure { Toast.makeText(context, "Import impossible : ${it.message}", Toast.LENGTH_LONG).show() }
              refresh++
            }
          },
          onDismissMigration = {
            ProjectImporter.dismissOldProject(context)
            refresh++
          },
          onOpenProject = { name ->
            project = name
            sessionId = null
          },
          onOpenSession = { s ->
            project = s.project
            sessionId = s.id
          },
          onDeleteProject = { name ->
            scope.launch {
              withContext(Dispatchers.IO) { workspace.delete(name) }
              refresh++
            }
          },
        )
        if (showNew) NewProjectDialog(workspace, onDismiss = { showNew = false }) { name, idea ->
          showNew = false
          project = name
          sessionId = null
          startPlan = true
          pendingIdea = idea
        }
        if (showClone) CloneDialog(onDismiss = { showClone = false }) { url ->
          showClone = false
          busy = "Clonage…"
          scope.launch {
            val r = ProjectImporter.clone(context, workspace, url) { busy = it }
            busy = null
            r.onSuccess { refresh++ }.onFailure { Toast.makeText(context, it.message ?: "Clonage impossible.", Toast.LENGTH_LONG).show() }
          }
        }
      } else {
        BackHandler {
          project = null
          sessionId = null
          refresh++
        }
        val controller =
          remember(proj, sessionId, model.name) {
            ForgeRuntime.open(context, proj, sessionId, model, if (startPlan) Preset.PLAN.mode else com.google.ai.edge.gallery.forge.engine.ForgeMode.DEFAULT)
          }
        if (controller == null) {
          project = null
        } else {
          LaunchedEffect(controller.sessionId) {
            sessionId = controller.sessionId
            ForgeRuntime.trim(controller.sessionId)
            val idea = pendingIdea
            if (idea != null) {
              pendingIdea = null
              startPlan = false
              // [ALTREX] The Build card opens the Build mode: plan, autonomous build, proof, proposals.
              controller.openBuild()
              if (idea.isNotBlank()) {
                // [ALTREX] Creating a project with an idea is an explicit request: load if needed.
                val error = modelManagerViewModel.ensureLoaded(context, task, model)
                if (error == null) {
                  askNotifications()
                  controller.send(idea)
                } else {
                  controller.note("info", "Ton idée, à envoyer une fois le modèle prêt :\n\n$idea", forModel = false)
                }
              }
            }
          }
          SessionScreen(
            controller = controller,
            modelReady = initStatus?.status == ModelInitializationStatusType.INITIALIZED,
            ensureModel = { modelManagerViewModel.ensureLoaded(context, task, model) },
            onBack = {
              project = null
              sessionId = null
              refresh++
            },
            onNewSession = { sessionId = null },
            askNotifications = ::askNotifications,
            onPickModel = { showModelPicker = true },
          )
        }
        if (download?.status != ModelDownloadStatusType.SUCCEEDED) {
          Box(Modifier.fillMaxSize().background(ForgePalette.B.bg.copy(alpha = 0.96f)).padding(16.dp)) {
            ModelDownloadStatusInfoPanel(model = model, task = task, modelManagerViewModel = modelManagerViewModel)
          }
        }
      }
      }
    }
    if (showModelPicker) {
      ModalBottomSheet(onDismissRequest = { showModelPicker = false }) {
        ModelPicker(task = task, modelManagerViewModel = modelManagerViewModel, conversationActive = project != null) { m ->
          showModelPicker = false
          val prev = model
          scope.launch(Dispatchers.Default) {
            if (prev.name != m.name) modelManagerViewModel.cleanupModel(context = context, task = task, model = prev)
            modelManagerViewModel.selectModel(model = m)
          }
        }
      }
    }
    // Dialogs of the SecurCode scan (API key, consent to send the project).
    SecurityBar(showStatus = false)
  }
}

// Kept outside the composition: they survive the switch from the home to the session.
private var pendingIdea: String? = null
private var showModelPicker by mutableStateOf(false)

@Composable
private fun SessionScreen(
  controller: ForgeSessionController,
  modelReady: Boolean,
  ensureModel: suspend () -> String?,
  onBack: () -> Unit,
  onNewSession: () -> Unit,
  askNotifications: () -> Unit,
  onPickModel: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val engine by controller.engine.collectAsState()
  val state by engine.state.collectAsState()
  val live by engine.live.collectAsState()
  val ratio by engine.contextRatio.collectAsState()
  val running by controller.running.collectAsState()
  val approval by controller.pendingApproval.collectAsState()
  val question by controller.pendingQuestion.collectAsState()
  val plan by controller.pendingPlan.collectAsState()
  var trustTick by remember { mutableIntStateOf(0) }
  val info = remember(controller.project, trustTick, state.lastTurn) { controller.workspace.info(controller.project) }
  var preview by remember { mutableStateOf<String?>(null) }
  var showPreview by remember { mutableStateOf(false) }
  var diffView by remember { mutableStateOf<Pair<String, List<Pair<com.google.ai.edge.gallery.forge.checkpoints.FileChange, String>>>?>(null) }
  var undoPlan by remember { mutableStateOf<com.google.ai.edge.gallery.forge.checkpoints.Checkpoints.UndoPlan?>(null) }
  var showCheckpoints by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }
  var memoryText by remember { mutableStateOf<String?>(null) }
  val queue by controller.queue.collectAsState()
  var attachTo by remember { mutableStateOf<((String) -> Unit)?>(null) }
  val attachLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      val insert = attachTo
      attachTo = null
      if (uri != null && insert != null) {
        scope.launch {
          val r = withContext(Dispatchers.IO) { ProjectImporter.attach(context, info.dir, uri) }
          r.onSuccess { insert("@$it") }.onFailure { Toast.makeText(context, "Pièce jointe impossible : ${it.message}", Toast.LENGTH_LONG).show() }
        }
      }
    }

  fun showDiff(session: Boolean) {
    scope.launch {
      controller.diff(session)
        .onSuccess { diffView = (if (session) "Modifications de la session" else "Modifications du dernier tour") to it }
        .onFailure { controller.note("info", it.message ?: "Diff impossible.", forModel = false) }
    }
  }

  fun planUndo() {
    scope.launch {
      controller.planUndo().onSuccess { undoPlan = it }.onFailure { controller.note("info", it.message ?: "Annulation impossible.", forModel = false) }
    }
  }
  com.google.ai.edge.gallery.ui.common.KeepScreenOn(active = running)

  fun send(text: String) {
    if (modelReady) {
      askNotifications()
      controller.send(text)
      return
    }
    // [ALTREX] Sending is an explicit request: the model loads (no Auto measurement), then the
    // message leaves.
    Toast.makeText(context, "Chargement du modèle…", Toast.LENGTH_SHORT).show()
    scope.launch {
      val error = ensureModel()
      if (error == null) {
        askNotifications()
        controller.send(text)
      } else {
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
      }
    }
  }

  fun command(text: String) {
    when (val c = ForgeCommands.parse(text)) {
      null -> send(text)
      is ForgeCommands.Parsed.Unknown -> controller.note("info", "Commande inconnue « /${c.name} ».\n\n" + ForgeCommands.helpText(), forModel = false)
      is ForgeCommands.Parsed.Run ->
        when (c.name) {
          "aide" -> controller.note("info", ForgeCommands.helpText(), forModel = false)
          "init" -> send(ForgeCommands.INIT_PROMPT)
          "plan" -> {
            controller.setMode(Preset.PLAN.mode)
            if (c.arg.isNotBlank()) send(c.arg)
          }
          "mode" ->
            ForgeCommands.presetFor(c.arg)?.let { controller.setMode(it.mode) }
              ?: controller.note("info", "Modes : /mode demander, /mode auto, /mode plan, /mode tout.", forModel = false)
          "arreter" -> controller.stop()
          "nouvelle" -> onNewSession()
          "sessions" -> onBack()
          "confiance" -> {
            controller.trustProject()
            trustTick++
          }
          "apercu" -> showPreview = true
          "undo" -> planUndo()
          "diff" -> showDiff(session = c.arg.trim().lowercase().startsWith("session"))
          "checkpoints" -> showCheckpoints = true
          "reglages" -> showSettings = true
          "apprendre" -> controller.setLearning(c.arg.trim().lowercase() !in setOf("non", "off", "fin", "stop", "0"))
          "build" ->
            if (c.arg.trim().lowercase() in setOf("fin", "non", "off", "stop", "quitter")) {
              controller.closeBuild()
            } else {
              controller.openBuild()
              if (c.arg.isNotBlank()) send(c.arg)
            }
          "limites" -> {
            val l = controller.limits
            controller.note(
              "info",
              "Limites d'un tour avec ce modèle : ${l.maxSteps} étapes, ≈ ${l.maxTokens / 1000} k jetons, ${l.maxDurationMs / 60_000} min. " +
                "Rappel anti-boucle à 3, 5 et 8 appels identiques. Bouton Arrêter à tout moment.",
              forModel = false,
            )
          }
          "doctor" ->
            scope.launch {
              controller.note("info", "Vérification en cours…", forModel = false)
              val report =
                withContext(Dispatchers.IO) {
                  ForgeDoctor.run(context, controller.project, controller.modelName, engineBudget(controller), engineProtocol(controller))
                }
              controller.note("doctor", report, forModel = true)
            }
          "scan" ->
            scope.launch {
              val page = c.arg.toIntOrNull() ?: 1
              controller.note("info", "Analyse SecurCode…", forModel = false)
              val r =
                withContext(Dispatchers.IO) {
                  SecurCodeClient.scanProject(context, info.dir, "forge:${controller.project}", controller.project, page)
                }
              controller.note("scan", r.text, forModel = false)
            }
        }
    }
  }

  ForgeSessionView(
    ui =
      SessionUi(
        project = controller.project,
        branch = info.branch,
        model = controller.modelName,
        local = engineLocal(controller),
        state = state,
        live = live,
        running = running,
        contextRatio = ratio,
        trusted = info.trusted,
        approval = approval?.request,
        questionId = question?.callId,
        planId = plan?.callId,
        projectRoot = info.dir,
        queue = queue,
      ),
    actions =
      SessionActions(
        onBack = onBack,
        onSend = ::send,
        onStop = controller::stop,
        onMode = controller::setMode,
        onApprove = { a: ApprovalAnswer -> approval?.answer?.complete(a) },
        onAnswer = { s -> question?.answer?.complete(s) },
        onPlanValidate = { plan?.answer?.complete(PlanReview(PlanReview.Outcome.VALIDE)) },
        onPlanChanges = { f -> plan?.answer?.complete(PlanReview(PlanReview.Outcome.A_MODIFIER, f)) },
        onPlanRefuse = { plan?.answer?.complete(PlanReview(PlanReview.Outcome.REFUSE)) },
        onTrust = {
          controller.trustProject()
          trustTick++
        },
        onPickModel = onPickModel,
        onPreview = { f ->
          preview = f
          showPreview = true
        },
        onCommand = ::command,
        onAttach = { insert ->
          attachTo = insert
          attachLauncher.launch("*/*")
        },
        listFiles = { com.google.ai.edge.gallery.forge.tools.Mentions.listFiles(info.dir) },
        onLearning = controller::setLearning,
        onBuild = { on -> if (on) controller.openBuild() else controller.closeBuild() },
        onSteer = controller::steer,
        onEnqueue = controller::enqueue,
        onDequeue = controller::dequeue,
      ),
  )
  if (showSettings) {
    ForgeSettingsSheet(
      settings = controller.settings,
      current = controller.limits,
      light = engine.light,
      local = controller.modelIsLocal,
      onSave = {
        controller.applySettings(it)
        showSettings = false
        Toast.makeText(context, if (running) "Enregistré : s'applique après ce tour." else "Réglages enregistrés.", Toast.LENGTH_SHORT).show()
      },
      onEditMemory = {
        scope.launch {
          memoryText = withContext(Dispatchers.IO) { runCatching { controller.workspace.globalMemory.readText() }.getOrDefault("") }
        }
      },
      onDismiss = { showSettings = false },
    )
  }
  memoryText?.let { initial ->
    GlobalMemoryDialog(initial, onSave = { t ->
      memoryText = null
      scope.launch {
        withContext(Dispatchers.IO) {
          com.google.ai.edge.gallery.forge.tools.FileTools.writeAtomically(controller.workspace.globalMemory, t.toByteArray(Charsets.UTF_8))
        }
        controller.memoryChanged()
        Toast.makeText(context, "ALTREX.md global enregistré (lu au prochain tour).", Toast.LENGTH_SHORT).show()
      }
    }, onDismiss = { memoryText = null })
  }
  diffView?.let { (title, entries) -> DiffSheet(title, entries) { diffView = null } }
  undoPlan?.let { plan ->
    UndoDialog(plan, onConfirm = {
      undoPlan = null
      scope.launch {
        controller.applyUndo(plan).onFailure { controller.note("erreur", it.message ?: "Annulation impossible.", forModel = false) }
      }
    }, onDismiss = { undoPlan = null })
  }
  if (showCheckpoints) {
    CheckpointsSheet(state, controller.snapshots.kind, onUndo = {
      showCheckpoints = false
      planUndo()
    }, onDiff = {
      showCheckpoints = false
      showDiff(session = true)
    }, onDismiss = { showCheckpoints = false })
  }
  val fileChanges by engine.fileChanges.collectAsState()
  if (showPreview) PreviewSheet(info.dir, preview, reloadSignal = fileChanges) { showPreview = false }
}

private fun engineLocal(c: ForgeSessionController): Boolean = c.modelIsLocal

private fun engineBudget(c: ForgeSessionController): Int = c.modelBudgetChars

private fun engineProtocol(c: ForgeSessionController): String = c.modelProtocol

@Composable
private fun NewProjectDialog(workspace: ForgeWorkspace, onDismiss: () -> Unit, onCreated: (String, String) -> Unit) {
  var idea by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val p = ForgePalette.B
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = p.surface,
    title = { Text("Build : nouveau projet", color = p.text) },
    text = {
      CompositionLocalProvider(LocalForgePalette provides p) {
        Column {
          Text("Décris ton idée. Forge propose une architecture à valider (rien n'est écrit avant), construit ensuite seul, vérifie le résultat (fichiers, tests, aperçu), puis te propose des améliorations une seule fois, à la fin.", style = ForgeType.body, color = p.muted)
          androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
          ForgeTextField(idea, {
            idea = it
            if (name.isEmpty() || name == ForgeWorkspace.slug(idea.dropLast(1)).take(40)) name = ForgeWorkspace.slug(it.take(30))
          }, "Un site vitrine avec formulaire de contact…", minLines = 3)
          androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
          ForgeTextField(name, { name = it.lowercase() }, "nom-du-projet")
          error?.let { Text(it, style = ForgeType.body, color = p.del) }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = {
        val n = name.ifBlank { workspace.freeName(idea.take(30).ifBlank { "projet" }) }
        workspace.create(n).onSuccess { onCreated(it, idea.trim()) }.onFailure { error = it.message }
      }) { Text("Créer", color = p.accent) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = p.muted) } },
  )
}

@Composable
private fun CloneDialog(onDismiss: () -> Unit, onClone: (String) -> Unit) {
  var url by remember { mutableStateOf("") }
  val p = ForgePalette.B
  val error = if (url.isBlank()) null else ProjectImporter.checkRepoUrl(url)
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = p.surface,
    title = { Text("Cloner un dépôt", color = p.text) },
    text = {
      CompositionLocalProvider(LocalForgePalette provides p) {
        Column {
          Text(
            "Git clone dans le Linux du modèle (git est installé s'il manque). Pour un dépôt privé de github.com, le jeton GitHub des Réglages est utilisé. Le projet démarre « non approuvé ».",
            style = ForgeType.body,
            color = p.muted,
          )
          androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
          ForgeTextField(url, { url = it.trim() }, "https://github.com/moi/mon-projet.git")
          error?.let { Text(it, style = ForgeType.body, color = p.del) }
        }
      }
    },
    confirmButton = { TextButton(enabled = url.isNotBlank() && error == null, onClick = { onClone(url) }) { Text("Cloner", color = p.accent) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = p.muted) } },
  )
}
