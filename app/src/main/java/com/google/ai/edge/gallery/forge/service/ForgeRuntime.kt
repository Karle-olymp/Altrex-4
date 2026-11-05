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
import kotlinx.coroutines.CompletableDeferred
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
        else -> "LiteRT-LM, protocole texte (conversation reconstruite à chaque étape)"
      }

  val limits: com.google.ai.edge.gallery.forge.engine.ForgeLimits
    get() = com.google.ai.edge.gallery.forge.engine.ForgeLimits.forModel(RuntimeModelPort(model))

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

  private fun buildEngine(m: Model) =
    ForgeEngine(journal, RuntimeModelPort(m), workspace.paths(project), LinuxShellPort(appContext, project), interactions, context)

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

  /** Sends [text] as the next turn (ignored while a turn runs). */
  fun send(text: String) {
    if (_running.value || text.isBlank()) return
    _running.value = true
    ForgeRuntime.turnStarted(appContext, this)
    ForgeRuntime.scope.launch {
      try {
        _engine.value.runTurn(text.trim())
      } finally {
        _running.value = false
        ForgeRuntime.turnEnded(appContext, this@ForgeSessionController)
      }
    }
  }

  /** Stop: the answer being written stops, what waits for the user is refused. */
  fun stop() {
    _engine.value.requestStop()
    _pendingApproval.value?.answer?.complete(ApprovalAnswer(ApprovalAnswer.Choice.ARRETER))
    _pendingQuestion.value?.answer?.complete(null)
    _pendingPlan.value?.answer?.complete(PlanReview(PlanReview.Outcome.REFUSE))
  }

  /** A note in the feed (a /doctor or /scan report); [forModel] adds it to the next request. */
  fun note(kind: String, text: String, forModel: Boolean) = _engine.value.note(kind, text, forModel)

  /** The user trusts this project: its ALTREX.md is read from the next session prompt on. */
  fun trustProject() {
    workspace.setTrusted(project, true)
    context.invalidate()
  }

  val waitingForUser: Boolean
    get() = _pendingApproval.value != null || _pendingQuestion.value != null || _pendingPlan.value != null
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
