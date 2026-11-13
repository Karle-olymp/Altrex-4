package com.google.ai.edge.gallery.forge.service

import android.content.Context
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.forge.engine.ApprovalAnswer
import com.google.ai.edge.gallery.forge.engine.ApprovalRequest
import com.google.ai.edge.gallery.forge.engine.ForgeEngine
import com.google.ai.edge.gallery.forge.engine.ForgeInteractions
import com.google.ai.edge.gallery.forge.engine.ForgeMode
import com.google.ai.edge.gallery.forge.engine.PlanReview
import com.google.ai.edge.gallery.forge.engine.RuntimeModelPort
import com.google.ai.edge.gallery.forge.journal.SessionJournal
import com.google.ai.edge.gallery.forge.workspace.ForgeWorkspace
import com.google.ai.edge.gallery.forge.workspace.LinuxShellPort
import com.google.ai.edge.gallery.forge.workspace.ProjectContext
import com.google.ai.edge.gallery.terminal.LinuxEnv
import com.google.ai.edge.gallery.tools.USER_REPLY_TIMEOUT_MS
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ALTREX] One open Forge session: its engine, and what it waits for from the user (an approval,
 * an answer, a plan to validate). The engine runs in [ForgeRuntime]'s scope, not in the screen:
 * leaving the screen or turning the phone off does not stop a turn, and the foreground service
 * ([ForgeService]) keeps the app alive meanwhile. The screen only reads the journal and answers.
 */
class ForgeSessionController(
  private val appContext: Context,
  val workspace: ForgeWorkspace,
  val project: String,
  private val journal: SessionJournal,
  model: Model,
) {
  data class PendingApproval(val request: ApprovalRequest, val answer: CompletableDeferred<ApprovalAnswer>)

  data class PendingQuestion(val callId: String, val question: String, val options: List<String>, val answer: CompletableDeferred<String?>)

  data class PendingPlan(val callId: String, val markdown: String, val answer: CompletableDeferred<PlanReview>)

  val sessionId: String
    get() = journal.id

  private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
  val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()
  private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
  val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion.asStateFlow()
  private val _pendingPlan = MutableStateFlow<PendingPlan?>(null)
  val pendingPlan: StateFlow<PendingPlan?> = _pendingPlan.asStateFlow()

  private val _running = MutableStateFlow(false)
  val running: StateFlow<Boolean> = _running.asStateFlow()

  @Volatile private var model: Model = model
  val modelName: String
    get() = model.displayName.ifEmpty { model.name }

  val modelIsLocal: Boolean
    get() = model.runtimeType != com.google.ai.edge.gallery.data.RuntimeType.REMOTE

  val modelBudgetChars: Int
    get() = RuntimeModelPort(model).historyBudgetChars

  val modelProtocol: String
    get() =
      when {
        model.instance is com.google.ai.edge.gallery.runtime.CloudModelInstance -> "cloud, appels d'outils natifs"
        model.runtimeType == com.google.ai.edge.gallery.data.RuntimeType.LLAMA_CPP -> "llama.cpp, protocole texte <tool_call>"
        model.runtimeType == com.google.ai.edge.gallery.data.RuntimeType.REMOTE -> "Space distant, protocole texte"
        else -> "LiteRT-LM, protocole texte (conversation gardée d'une étape à l'autre quand c'est possible)"
      } + if (_engine.value.light) " · mode léger" else ""

  /** « Réglages de Forge » (light mode, limits), read at each new engine. */
  @Volatile var settings: com.google.ai.edge.gallery.forge.workspace.ForgeSettings = com.google.ai.edge.gallery.forge.workspace.ForgeSettings.load(workspace)
    private set

  val limits: com.google.ai.edge.gallery.forge.engine.ForgeLimits
    get() {
      val port = RuntimeModelPort(model)
      return com.google.ai.edge.gallery.forge.engine.ForgeLimits.forModel(port, com.google.ai.edge.gallery.forge.engine.LightMode.decide(port, settings.light), settings)
    }

  val context = ProjectContext(workspace, project, { modelName })

  private val interactions =
    object : ForgeInteractions {
      override suspend fun approve(request: ApprovalRequest): ApprovalAnswer {
        val d = CompletableDeferred<ApprovalAnswer>()
        _pendingApproval.value = PendingApproval(request, d)
        ForgeRuntime.publish(appContext)
        val a = withTimeoutOrNull(USER_REPLY_TIMEOUT_MS) { d.await() } ?: ApprovalAnswer(ApprovalAnswer.Choice.SANS_REPONSE)
        _pendingApproval.value = null
        ForgeRuntime.publish(appContext)
        return a
      }

      override suspend fun ask(callId: String, question: String, options: List<String>): String? {
        val d = CompletableDeferred<String?>()
        _pendingQuestion.value = PendingQuestion(callId, question, options, d)
        ForgeRuntime.publish(appContext)
        val a = withTimeoutOrNull(USER_REPLY_TIMEOUT_MS) { d.await() }
        _pendingQuestion.value = null
        ForgeRuntime.publish(appContext)
        return a
      }

      override suspend fun reviewPlan(callId: String, markdown: String): PlanReview {
        val d = CompletableDeferred<PlanReview>()
        _pendingPlan.value = PendingPlan(callId, markdown, d)
        ForgeRuntime.publish(appContext)
        // A plan is read at leisure: a longer wait, still a refusal at the end.
        val a = withTimeoutOrNull(USER_REPLY_TIMEOUT_MS * 3) { d.await() } ?: PlanReview(PlanReview.Outcome.A_MODIFIER, "pas de réponse de l'utilisateur")
        _pendingPlan.value = null
        ForgeRuntime.publish(appContext)
        return a
      }
    }

  /** Shadow snapshots of the project (git of the Linux, or the app's copies). */
  val snapshots = com.google.ai.edge.gallery.forge.checkpoints.AutoSnapshotStore.forProject(appContext, workspace, project)
  private val checkpoints = com.google.ai.edge.gallery.forge.checkpoints.Checkpoints(snapshots)

  private val web =
    com.google.ai.edge.gallery.forge.web.HttpWeb(
      braveKey = { com.google.ai.edge.gallery.tools.WebSettings.getBraveKey(appContext) },
      takeQuota = { com.google.ai.edge.gallery.tools.WebSettings.takeSearchQuota(appContext) },
    )

  private fun buildEngine(m: Model): ForgeEngine {
    val port = RuntimeModelPort(m, appContext)
    val light = com.google.ai.edge.gallery.forge.engine.LightMode.decide(port, settings.light)
    return ForgeEngine(
      journal, port, workspace.paths(project), LinuxShellPort(appContext, project), interactions, context,
      limits = com.google.ai.edge.gallery.forge.engine.ForgeLimits.forModel(port, light, settings),
      snapshots = snapshots,
      web = web,
      light = light,
    )
  }

  private val _engine = MutableStateFlow(buildEngine(model))
  val engine: StateFlow<ForgeEngine> = _engine.asStateFlow()

  /** The model chosen in the picker; the engine follows it between turns. */
  fun bindModel(m: Model) {
    if (m.name == model.name || _running.value) return
    val mode = _engine.value.mode
    model = m
    context.invalidate()
    _engine.value = buildEngine(m).also { it.setMode(mode) }
  }

  fun setMode(mode: ForgeMode) = _engine.value.setMode(mode)

  /** New settings: saved, and the engine rebuilt (between turns) so the light mode and limits apply. */
  fun applySettings(s: com.google.ai.edge.gallery.forge.workspace.ForgeSettings) {
    com.google.ai.edge.gallery.forge.workspace.ForgeSettings.save(workspace, s)
    settings = s
    if (_running.value) {
      rebuildAfterTurn = true
      return
    }
    rebuild()
  }

  @Volatile private var rebuildAfterTurn = false

  private fun rebuild() {
    rebuildAfterTurn = false
    val mode = _engine.value.mode
    _engine.value = buildEngine(model).also { it.setMode(mode) }
  }

  /** The user's global ALTREX.md was edited: the next prompt reads it. */
  fun memoryChanged() = context.invalidate()

  // ------------------------------------------------------------------------- typing while it works

  private val _queue = MutableStateFlow<List<String>>(emptyList())
  /** Messages to send after the running turn, in order (« Après »). */
  val queue: StateFlow<List<String>> = _queue.asStateFlow()

  /** « Orienter »: the message reaches the agent at its next step; otherwise it is a normal send. */
  fun steer(text: String) {
    if (text.isBlank()) return
    if (_running.value) _engine.value.steer(text) else send(text)
  }

  /** « Après »: queued, sent when the running turn ends well (not after Stop). */
  fun enqueue(text: String) {
    if (text.isBlank()) return
    if (_running.value) _queue.value = _queue.value + text.trim() else send(text)
  }

  fun dequeue(index: Int) {
    _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
  }

  // --------------------------------------------------------------- Apprentissage and Build modes

  private fun buildRunner(engine: ForgeEngine) =
    com.google.ai.edge.gallery.forge.engine.BuildRunner(
      engine,
      com.google.ai.edge.gallery.forge.engine.CompletionJudge(workspace.paths(project), LinuxShellPort(appContext, project)),
      maxTurns = settings.buildMaxTurns ?: com.google.ai.edge.gallery.forge.engine.BuildRunner.DEFAULT_MAX_TURNS,
      maxTries = settings.buildMaxTries ?: com.google.ai.edge.gallery.forge.engine.BuildRunner.DEFAULT_MAX_TRIES,
    )

  fun setLearning(on: Boolean) = _engine.value.setLearning(on)

  /** Opens the Build mode (Plan until the plan is validated); the next message is the idea. */
  fun openBuild() {
    if (!_running.value) _engine.value.openBuild()
  }

  fun closeBuild() = _engine.value.closeBuild()

  @Volatile private var turnJob: Job? = null
  /** Number of the current turn: a stuck turn that ends late never closes the next one. */
  @Volatile private var turnSeq = 0

  /** Sends [text] as the next turn (ignored while a turn runs). */
  fun send(text: String) {
    if (_running.value || text.isBlank()) return
    _running.value = true
    ForgeRuntime.turnStarted(appContext, this)
    val engine = _engine.value
    val seq = synchronized(this) { ++turnSeq }
    turnJob =
      ForgeRuntime.scope.launch {
        try {
          if (engine.state.value.build?.active == true) buildRunner(engine).run(text.trim()) else engine.runTurn(text.trim())
        } catch (e: CancellationException) {
          // Stop: the engine has closed the turn in the journal.
        } finally {
          finishTurn(seq)
        }
      }
  }

  private fun finishTurn(seq: Int) {
    synchronized(this) {
      if (seq != turnSeq || !_running.value) return
      _running.value = false
    }
    turnJob = null
    clearPending()
    ForgeRuntime.turnEnded(appContext, this@ForgeSessionController)
    onTurnFinished()
  }

  /** After a turn: the next queued message, unless the user stopped the turn. */
  private fun onTurnFinished() {
    if (rebuildAfterTurn) rebuild()
    val end = _engine.value.state.value.lastEnd
    val next = _queue.value.firstOrNull() ?: return
    if (end == "stopped" || end == "interrupted") return
    _queue.value = _queue.value.drop(1)
    send(next)
  }

  private fun clearPending() {
    _pendingApproval.value = null
    _pendingQuestion.value = null
    _pendingPlan.value = null
  }

  /**
   * Stop (the screen's button, `/arreter`, the notification): always answers within 2 s. The
   * answer being written stops, what waits for the user is refused, the turn's coroutine is
   * cancelled (a running command's process tree is killed with it), and if it is still not over
   * after [STOP_GRACE_MS], the turn is closed anyway ([ForgeEngine.forceStop]).
   */
  fun stop() {
    val engine = _engine.value
    engine.requestStop()
    _pendingApproval.value?.answer?.complete(ApprovalAnswer(ApprovalAnswer.Choice.ARRETER))
    _pendingQuestion.value?.answer?.complete(null)
    _pendingPlan.value?.answer?.complete(PlanReview(PlanReview.Outcome.REFUSE))
    val job = turnJob ?: return
    val seq = turnSeq
    job.cancel(CancellationException("Arrêté par l'utilisateur."))
    ForgeRuntime.scope.launch {
      delay(STOP_GRACE_MS)
      if (job.isActive && seq == turnSeq) {
        engine.forceStop()
        finishTurn(seq)
      }
    }
  }

  /** A note in the feed (a /doctor or /scan report); [forModel] adds it to the next request. */
  fun note(kind: String, text: String, forModel: Boolean) = _engine.value.note(kind, text, forModel)

  /** The user trusts this project: its ALTREX.md is read from the next session prompt on. */
  fun trustProject() {
    workspace.setTrusted(project, true)
    context.invalidate()
  }

  // ----------------------------------------------------------------------------------- /undo, /diff

  /** What /undo would do for the last turn that can be undone (a backup snapshot is taken), or why it cannot. */
  suspend fun planUndo(): Result<com.google.ai.edge.gallery.forge.checkpoints.Checkpoints.UndoPlan> = runCatching {
    if (_running.value) throw IllegalStateException("Attends la fin du tour (ou Arrêter) avant d'annuler.")
    val cp = _engine.value.state.value.undoable ?: throw IllegalStateException("Rien à annuler : aucun changement de l'agent enregistré dans cette session.")
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { checkpoints.planUndo(cp.turn, cp.pre, cp.post) }
  }

  /** Applies [plan] (after the user's confirmation), records it and tells the agent at its next turn. */
  suspend fun applyUndo(plan: com.google.ai.edge.gallery.forge.checkpoints.Checkpoints.UndoPlan): Result<com.google.ai.edge.gallery.forge.checkpoints.Checkpoints.UndoResult> = runCatching {
    if (_running.value) throw IllegalStateException("Un tour a démarré : annulation abandonnée.")
    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { checkpoints.applyUndo(plan) }
    val e = _engine.value
    e.record(com.google.ai.edge.gallery.forge.journal.ForgeEvent.Undo(plan.turn, r.restored, r.skipped, r.backup, r.after))
    e.forgetReads(r.restored)
    e.note("annulation", com.google.ai.edge.gallery.forge.checkpoints.UndoTexts.forUser(plan.turn, r), forModel = false)
    e.noteForNextTurn(com.google.ai.edge.gallery.forge.checkpoints.UndoTexts.forModel(plan.turn, r))
    r
  }

  /** Changes of the last turn ([session] false) or of the whole session, up to now. */
  suspend fun diff(session: Boolean): Result<List<Pair<com.google.ai.edge.gallery.forge.checkpoints.FileChange, String>>> = runCatching {
    val cps = _engine.value.state.value.checkpoints
    val from = (if (session) cps.firstOrNull() else cps.lastOrNull()) ?: throw IllegalStateException("Aucun changement de l'agent dans cette session.")
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      if (!session && from.post != null) checkpoints.diff(from.pre, from.post) else checkpoints.diffToNow(from.pre).second
    }
  }

  val waitingForUser: Boolean
    get() = _pendingApproval.value != null || _pendingQuestion.value != null || _pendingPlan.value != null

  companion object {
    /** After Stop, how long the turn's coroutine has to end before the turn is closed anyway. */
    const val STOP_GRACE_MS = 1_200L
  }
}

/**
 * [ALTREX] Forge's sessions for the whole app (one is usually open, several may be: the engine
 * is re-entrant). Its scope outlives the screens; [ForgeService] keeps the process in the
 * foreground while a turn runs.
 */
object ForgeRuntime {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val sessions = ConcurrentHashMap<String, ForgeSessionController>()

  /** What the notification shows. */
  data class Status(val running: Int = 0, val waiting: Boolean = false, val project: String = "", val step: String = "")

  private val _status = MutableStateFlow(Status())
  val status: StateFlow<Status> = _status.asStateFlow()

  @Volatile private var workspace: ForgeWorkspace? = null

  fun workspace(context: Context): ForgeWorkspace =
    workspace ?: synchronized(this) { workspace ?: ForgeWorkspace(LinuxEnv.forgeHostDir(context)).also { it.ensure(); workspace = it } }

  /** The controller of session [sessionId] of [project] (opened, or kept from before). */
  fun open(context: Context, project: String, sessionId: String?, model: Model, mode: ForgeMode): ForgeSessionController? {
    val ws = workspace(context)
    if (sessionId != null) sessions[sessionId]?.let { c -> c.bindModel(model); return c }
    val journal =
      (if (sessionId != null) ws.openSession(project, sessionId) else null)
        ?: ws.newSession(project, model.displayName.ifEmpty { model.name }, mode.key())
    val c = ForgeSessionController(context.applicationContext, ws, project, journal, model)
    sessions[c.sessionId] = c
    return c
  }

  /** Forgets idle sessions other than [keep] (memory). */
  fun trim(keep: String?) {
    for ((id, c) in sessions) if (id != keep && !c.running.value) sessions.remove(id)
  }

  fun stopAll() {
    for (c in sessions.values) c.stop()
  }

  internal fun turnStarted(context: Context, c: ForgeSessionController) {
    publish(context, c)
    ForgeService.start(context)
  }

  internal fun turnEnded(context: Context, c: ForgeSessionController) {
    publish(context, c)
    ForgeNotifications.turnFinished(context, c.project, c.engine.value.state.value.lastEnd)
  }

  /** Recomputes the notification status. */
  fun publish(context: Context, last: ForgeSessionController? = null) {
    val running = sessions.values.filter { it.running.value }
    val focus = last?.takeIf { it.running.value } ?: running.firstOrNull()
    _status.value =
      Status(
        running = running.size,
        waiting = running.any { it.waitingForUser },
        project = focus?.project.orEmpty(),
        step = focus?.engine?.value?.live?.value?.tool.orEmpty(),
      )
    if (_status.value.waiting) ForgeNotifications.waiting(context, focus?.project.orEmpty())
  }
}
