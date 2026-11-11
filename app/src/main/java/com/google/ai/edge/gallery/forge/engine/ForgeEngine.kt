package com.google.ai.edge.gallery.forge.engine

import com.google.ai.edge.gallery.agent.AgentContextFitter
import com.google.ai.edge.gallery.agent.AgentToolCall
import com.google.ai.edge.gallery.agent.StreamSplitter
import com.google.ai.edge.gallery.agent.ToolCalling
import com.google.ai.edge.gallery.forge.checkpoints.SnapshotStore
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
import com.google.ai.edge.gallery.forge.tools.Mentions
import com.google.ai.edge.gallery.forge.web.WebPort
import com.google.ai.edge.gallery.tools.AgentShell
import com.google.ai.edge.gallery.forge.journal.SessionJournal
import com.google.ai.edge.gallery.forge.journal.SessionState
import com.google.ai.edge.gallery.forge.journal.apply
import com.google.ai.edge.gallery.forge.tools.FileTools
import com.google.ai.edge.gallery.forge.tools.ForgeToolSpecs
import com.google.ai.edge.gallery.forge.tools.ProjectPaths
import com.google.ai.edge.gallery.forge.tools.SearchTools
import com.google.ai.edge.gallery.forge.tools.TodoTool
import com.google.ai.edge.gallery.forge.tools.ToolOutcome
import com.google.ai.edge.gallery.security.SecretRedactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.ai.edge.gallery.tools.CommandWait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * [ALTREX] The Forge agent loop for one session. Re-entrant by design: all its state (journal,
 * history, guards, grants, mode) belongs to the instance, and everything outside comes through
 * the ports ([ModelPort], [ShellPort], [ForgeInteractions], [ForgeContext]); several engines can
 * exist at once (sub-agents at step 4 will be engines run as a tool).
 *
 * Each step: the history is rebuilt from the journal, fitted to the context, sent to the model
 * with Forge's fixed tool list (text protocol: `<tool_call>` blocks, turned into native calls for
 * cloud models by CloudTools), then each call goes through the loop guard, the permission policy
 * and the user's approval, runs, and its result is written to the journal before the next step.
 */
class ForgeEngine(
  private val journal: SessionJournal,
  private val model: ModelPort,
  private val paths: ProjectPaths,
  private val shell: ShellPort,
  private val interactions: ForgeInteractions,
  private val context: ForgeContext,
  private val limits: ForgeLimits = ForgeLimits.forModel(model),
  private val clock: () -> Long = System::currentTimeMillis,
  /** Shadow snapshots before the agent's changes (/undo, /diff); null: none (tests, no storage). */
  private val snapshots: SnapshotStore? = null,
  /** WebFetch and WebSearch; null: those tools answer that the web is not available. */
  private val web: WebPort? = null,
  /** Light mode (slow local models): 7 tools in a compact block, short prompt ([LightMode]). */
  val light: Boolean = false,
) {
  /** What the screen shows live, besides the journal: the answer being written, the running tool. */
  data class Live(
    val running: Boolean = false,
    val text: String = "",
    val thinking: String = "",
    val tool: String? = null,
    val toolProgress: String = "",
    val step: Int = 0,
    /** Speed of the answer being written (3 characters ≈ 1 token), for local models. */
    val tokensPerSecond: Float = 0f,
  )

  private val _contextRatio = MutableStateFlow(0f)
  /** Share of the model's context used by the last request (status line). */
  val contextRatio: StateFlow<Float> = _contextRatio.asStateFlow()

  private val tracker = com.google.ai.edge.gallery.forge.tools.FileTracker()
  private val files = FileTools(paths, tracker)
  private val search = SearchTools(paths)

  private val _state = MutableStateFlow(SessionState.fold(journal.snapshot()))
  val state: StateFlow<SessionState> = _state.asStateFlow()

  private val _live = MutableStateFlow(Live())
  val live: StateFlow<Live> = _live.asStateFlow()

  private val grants = _state.value.grants
  @Volatile var mode: ForgeMode = _state.value.mode
    private set
  /** The mode to go back to when a plan is validated. */
  private var beforePlan: ForgeMode = if (mode.isPlan) ForgeMode.DEFAULT else mode
  private var announcedMode: ForgeMode? = null

  @Volatile private var stopRequested = false

  /** The turn was closed by [forceStop] (its coroutine did not end in time): nothing more is written for it. */
  @Volatile private var forced = false

  /** The tool call running now (id, name), closed with a result if the turn is stopped during it. */
  @Volatile private var openCall: Pair<String, String>? = null

  private var turnStartedAt = 0L

  /** Snapshot « avant » of the current turn, taken before its first change. */
  @Volatile private var preRev: String? = null

  /** Notes for the model at the start of the next turn (an /undo done meanwhile). */
  private val nextTurnNotes = java.util.concurrent.ConcurrentLinkedQueue<String>()

  /** Messages typed while the agent works (« Orienter »): given to the model at its next step. */
  private val steering = java.util.concurrent.ConcurrentLinkedQueue<String>()

  /** « Orienter »: [text] reaches the model at the next step of the running turn. */
  fun steer(text: String) {
    if (text.isNotBlank()) steering.add(text.trim())
  }

  private fun drainSteering(turn: Int): Boolean {
    var any = false
    while (true) {
      val t = steering.poll() ?: break
      append(ForgeEvent.Note(turn, SessionState.ORIENTATION, t, forModel = true, shown = true))
      any = true
    }
    return any
  }

  // --------------------------------------------------------------- Apprentissage and Build modes

  val learning: Boolean
    get() = _state.value.learning

  /** Apprentissage on or off; takes effect at the next turn. */
  fun setLearning(on: Boolean) {
    if (on == learning) return
    append(ForgeEvent.Learning(on))
    append(ForgeEvent.Note(_state.value.lastTurn, "info", if (on) "Mode Apprentissage activé : Forge explique ce qu'il fait, propose des exercices et te laisse faire." else "Mode Apprentissage désactivé.", forModel = false))
  }

  /** Starts a Build: Plan mode until the plan (with its recipe) is validated ([BuildRunner]). */
  fun openBuild() {
    if (_state.value.build?.active == true) return
    // Building alone needs file edits without a question each time; commands are still asked.
    if (!mode.isPlan) setMode(Preset.PLAN.mode)
    if (beforePlan == Preset.DEMANDER.mode) beforePlan = Preset.EDITIONS_AUTO.mode
    append(ForgeEvent.Build(_state.value.lastTurn, ForgeEvent.Build.OPEN, text = "Mode Build : décris ton idée. Forge propose une architecture à valider, construit seul, vérifie, puis propose des améliorations une seule fois."))
  }

  /** Leaves the Build mode (`/build fin`). */
  fun closeBuild() {
    if (_state.value.build?.active != true) return
    append(ForgeEvent.Build(_state.value.lastTurn, ForgeEvent.Build.CLOSED, text = "Mode Build quitté."))
  }

  /** A step of the Build, written by [BuildRunner]. */
  fun buildEvent(state: String, tour: Int, essai: Int, text: String) {
    append(ForgeEvent.Build(_state.value.lastTurn, state, tour, essai, text))
  }

  /** What Forge does between two turns of a Build (the judge's checks), on the status line. */
  fun progress(text: String) {
    _live.value = if (text.isEmpty()) Live() else Live(running = true, toolProgress = text)
  }

  /** The current turn may only read (the proposals of a finished Build). */
  @Volatile private var readOnlyTurn = false

  /** Adds [text] for the model to the next request (e.g. « the user undid turn 3 »). */
  fun noteForNextTurn(text: String) {
    nextTurnNotes.add(text)
  }

  /** Writes an event of the screen's own (an /undo) into the journal. */
  fun record(e: ForgeEvent) = append(e)

  /** Files whose content the model has just been given whole (@fichier): they count as read. */
  fun markRead(rel: String, bytes: ByteArray) = tracker.record(rel, bytes)

  /** After /undo, the agent must read these files again before changing them. */
  fun forgetReads(paths: List<String>) = paths.forEach { tracker.forget(it) }

  private val _fileChanges = MutableStateFlow(0)
  /** Bumped after each successful Write, Edit or Bash: the Aperçu reloads its page. */
  val fileChanges: StateFlow<Int> = _fileChanges.asStateFlow()

  val sessionId: String
    get() = journal.id

  private fun append(e: ForgeEvent) {
    if (forced && e !is ForgeEvent.TurnEnd) return
    journal.append(e)
    _state.value = SessionState.fold(journal.snapshot())
  }

  /** Changes the mode (the chips). Takes effect at the next tool call. */
  fun setMode(m: ForgeMode) {
    if (m == mode) return
    if (!m.isPlan) beforePlan = m else if (!mode.isPlan) beforePlan = mode
    mode = m
    append(ForgeEvent.Mode(m.key()))
  }

  /** Adds a note to the feed (and, if [forModel], to the model's next message). */
  fun note(kind: String, text: String, forModel: Boolean) {
    append(ForgeEvent.Note(_state.value.lastTurn, kind, text, forModel))
  }

  /**
   * The Stop button: the answer being written stops and the turn ends. The caller also cancels the
   * turn's coroutine: every wait (model, command, user) is cancellable, and a running command's
   * process tree is killed.
   */
  fun requestStop() {
    stopRequested = true
    model.stop()
  }

  /**
   * Last resort, when the turn's coroutine did not end shortly after Stop: the turn is closed in
   * the journal now (the open tool call gets a result), and whatever the stuck code does later is
   * not written.
   */
  fun forceStop() {
    synchronized(this) {
      if (!_state.value.turnOpen) return
      val turn = _state.value.lastTurn
      closeOpenCall(turn)
      end(turn, "stopped", "Arrêté par l'utilisateur.")
      forced = true
    }
    _live.value = Live()
  }

  private fun closeOpenCall(turn: Int) {
    val (id, name) = openCall ?: return
    openCall = null
    append(
      ForgeEvent.ToolResult(
        turn,
        id,
        name,
        "error",
        ToolOutcome.json("error" to "Arrêté par l'utilisateur pendant cet appel.", "status" to "error"),
        "arrêté",
      )
    )
  }

  val isRunning: Boolean
    get() = _live.value.running

  /** Runs one turn for [request]. Returns how it ended ("done", "stopped", "limit", "error", "blocked"). */
  suspend fun runTurn(request: String, auto: Boolean = false, readOnly: Boolean = false): String {
    readOnlyTurn = readOnly
    stopRequested = false
    forced = false
    openCall = null
    turnStartedAt = clock()
    val turn = _state.value.lastTurn + 1
    _live.value = Live(running = true)
    preRev = null
    append(ForgeEvent.TurnStart(turn))
    append(ForgeEvent.User(turn, request, auto = auto))
    while (true) {
      val n = nextTurnNotes.poll() ?: break
      append(ForgeEvent.Note(turn, "contexte", n, forModel = true, shown = false))
    }
    runCatching { Mentions.expand(request, paths) }.getOrNull()?.let { x ->
      append(ForgeEvent.Note(turn, "contexte", x.text, forModel = true, shown = false))
      for ((rel, bytes) in x.wholeFiles) tracker.record(rel, bytes)
    }
    val build = _state.value.build?.takeIf { it.active }
    when {
      mode.isPlan && build?.state == ForgeEvent.Build.OPEN -> append(ForgeEvent.Note(turn, "mode", ForgePrompts.BUILD_PLAN_REMINDER, forModel = true, shown = false))
      mode.isPlan -> append(ForgeEvent.Note(turn, "mode", ForgePrompts.PLAN_REMINDER, forModel = true, shown = false))
      announcedMode != null && announcedMode != mode ->
        append(ForgeEvent.Note(turn, "mode", ForgePrompts.modeReminder(mode), forModel = true, shown = false))
    }
    announcedMode = mode
    val recipe = build?.recipe
    if (recipe != null && !auto && !readOnly) append(ForgeEvent.Note(turn, "mode", ForgePrompts.buildWorkReminder(recipe), forModel = true, shown = false))
    if (learning && !readOnly) append(ForgeEvent.Note(turn, "mode", ForgePrompts.LEARNING_REMINDER, forModel = true, shown = false))
    return try {
      loop(turn).also { afterChanges(turn) }
    } catch (e: CancellationException) {
      synchronized(this) {
        closeOpenCall(turn)
        end(turn, "stopped", if (stopRequested) "Arrêté par l'utilisateur." else "Tour annulé.")
      }
      throw e
    } catch (e: Exception) {
      end(turn, "error", e.message ?: e.javaClass.simpleName).also { runCatching { afterChanges(turn) } }
    } finally {
      readOnlyTurn = false
      _live.value = Live()
    }
  }

  /** Before the turn's first change (Write, Edit, MultiEdit, a command that may write): the « avant » snapshot. */
  private suspend fun beforeChange(turn: Int) {
    val store = snapshots ?: return
    if (preRev != null) return
    _live.value = _live.value.copy(toolProgress = "Instantané du projet…")
    val rev = try {
      store.snapshot("Forge : avant le tour $turn")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      preRev = ""
      append(ForgeEvent.Note(turn, "info", "Instantané impossible avant ce tour (${e.message ?: e.javaClass.simpleName}) : /undo ne pourra pas l'annuler.", forModel = false))
      null
    }
    _live.value = _live.value.copy(toolProgress = "")
    if (rev != null) {
      preRev = rev
      append(ForgeEvent.Checkpoint(turn, "avant", rev, store.kind))
    }
  }

  /** End of a turn that changed something: the « après » snapshot and the files it changed. */
  private suspend fun afterChanges(turn: Int) {
    val store = snapshots ?: return
    val pre = preRev?.takeIf { it.isNotEmpty() } ?: return
    preRev = null
    if (forced) return
    try {
      val post = store.snapshot("Forge : après le tour $turn")
      val files = store.changes(pre, post).filter { it.kind != "D" }.map { it.path }
      append(ForgeEvent.Checkpoint(turn, "apres", post, store.kind, files))
      if (files.isNotEmpty()) _fileChanges.value++
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Without « après », /undo compares with the state of the moment.
    }
  }

  private fun end(turn: Int, reason: String, detail: String = ""): String {
    synchronized(this) { if (_state.value.turnOpen && !forced) append(ForgeEvent.TurnEnd(turn, reason, detail)) }
    return reason
  }

  private suspend fun loop(turn: Int): String {
    val guard = LoopGuard()
    val started = clock()
    val system =
      if (light) context.lightPrompt().trim() + "\n\n" + (if (mode.isPlan) ForgePrompts.LIGHT_TOOLS.replace("</tools>", ForgePrompts.LIGHT_PLAN_TOOLS + "\n</tools>") else ForgePrompts.LIGHT_TOOLS)
      else ToolCalling.systemPrompt(ForgeToolSpecs.specs, context.systemPrompt())
    var usedChars = 0L
    var steps = 0
    var forceAnswer = false
    var nudgedEmpty = false
    var nudgedTodos = false
    var callCounter = 0
    while (true) {
      if (stopRequested) return end(turn, "stopped", "Arrêté par l'utilisateur.")
      if (clock() - started > limits.maxDurationMs) {
        return end(turn, "limit", "Durée maximale du tour atteinte (${limits.maxDurationMs / 60_000} min).")
      }
      if (usedChars / 3 > limits.maxTokens) {
        return end(turn, "limit", "Budget de jetons du tour atteint (≈ ${limits.maxTokens / 1000} k).")
      }
      if (steps >= limits.maxSteps && !forceAnswer) {
        forceAnswer = true
        append(ForgeEvent.Note(turn, "limite", ForgePrompts.STEP_LIMIT, forModel = true, shown = false))
      }
      steps++
      drainSteering(turn)
      val budget = (model.historyBudgetChars - system.length).coerceAtLeast(2_000)
      var messages = SessionState.modelMessages(journal.snapshot(), turn)
      if (compactIfNeeded(turn, messages, budget)) messages = SessionState.modelMessages(journal.snapshot(), turn)
      val requestIndex = if (journal.snapshot().any { it is ForgeEvent.Summary }) 0 else requestIndex(messages, turn)
      val fitted = AgentContextFitter.fit(messages, requestIndex, budget)
      val promptChars = system.length + fitted.sumOf { it.content.length }
      usedChars += promptChars
      _contextRatio.value = promptChars.toFloat() / (model.historyBudgetChars.coerceAtLeast(1))
      val genStart = clock()

      var splitter = StreamSplitter()
      val shown = StringBuilder()
      val thinking = StringBuilder()
      var attempt = 0
      var raw = ""
      while (true) {
        _live.value = _live.value.copy(text = "", thinking = "", tool = null, toolProgress = "", step = steps)
        splitter = StreamSplitter()
        shown.setLength(0)
        thinking.setLength(0)
        try {
          raw =
            model.generate(system, fitted) { chunk ->
              synchronized(splitter) {
                val out = splitter.feed(chunk)
                shown.append(out.text)
                thinking.append(out.thinking)
                val secs = (clock() - genStart) / 1000f
                val tps = if (secs > 0.5f) (shown.length + thinking.length) / 3f / secs else 0f
                _live.value = _live.value.copy(text = shown.toString(), thinking = thinking.toString(), tokensPerSecond = tps)
              }
            }
          break
        } catch (e: CancellationException) {
          throw e
        } catch (e: RateLimitedException) {
          if (stopRequested) return end(turn, "stopped", "Arrêté par l'utilisateur.")
          attempt++
          val wait = RateLimit.delayMs(e.retryAfterMs, attempt)
          if (attempt > RateLimit.MAX_RETRIES || wait == null) {
            append(ForgeEvent.Note(turn, "erreur", RateLimit.giveUp(e.provider, e.retryAfterMs), forModel = false))
            return end(turn, "error", e.message ?: "Limite du fournisseur.")
          }
          append(ForgeEvent.Note(turn, "attente", RateLimit.waiting(e.provider, wait, attempt), forModel = false))
          _live.value = _live.value.copy(toolProgress = "Nouvel essai dans ${(wait + 999) / 1000} s…")
          // Small steps: Stop answers at once even if the coroutine is not cancelled.
          val until = clock() + wait
          while (clock() < until) {
            if (stopRequested) return end(turn, "stopped", "Arrêté par l'utilisateur.")
            delay(minOf(250L, (until - clock()).coerceAtLeast(1L)))
          }
          if (stopRequested) return end(turn, "stopped", "Arrêté par l'utilisateur.")
        } catch (e: Exception) {
          if (stopRequested) return end(turn, "stopped", "Arrêté par l'utilisateur.")
          append(ForgeEvent.Note(turn, "erreur", "Erreur du modèle : ${e.message ?: e.javaClass.simpleName}", forModel = false))
          return end(turn, "error", e.message ?: "Erreur du modèle.")
        }
      }
      synchronized(splitter) {
        val out = splitter.finish()
        shown.append(out.text)
        thinking.append(out.thinking)
      }
      usedChars += raw.length
      val clean = ToolCalling.stripThinking(raw).trim()
      val display = ToolCalling.displayText(raw).trim()
      val calls = if (forceAnswer || stopRequested) emptyList() else ToolCalling.parseToolCalls(clean)
      append(ForgeEvent.Assistant(turn, clean, display, thinking.toString().trim()))
      if (stopRequested) return end(turn, "stopped", "Arrêté par l'utilisateur.")
      when (val s = guard.afterText(display)) {
        is LoopGuard.StallAction.Stop -> return end(turn, "blocked", s.reason)
        is LoopGuard.StallAction.Replan -> append(ForgeEvent.Note(turn, "blocage", s.note, forModel = true))
        null -> Unit
      }

      if (calls.isEmpty()) {
        // The user wrote meanwhile (« Orienter »): the agent reads it before ending its turn.
        if (!forceAnswer && !stopRequested && steering.isNotEmpty()) continue
        if (display.isBlank() && !nudgedEmpty && !forceAnswer) {
          nudgedEmpty = true
          append(ForgeEvent.Note(turn, "vide", ForgePrompts.EMPTY_ANSWER, forModel = true, shown = false))
          continue
        }
        // The agent is done, but its task list says otherwise: once per turn, a reminder.
        val openTodos = _state.value.todos.filter { it.status != "completed" }
        if (openTodos.isNotEmpty() && !nudgedTodos && !forceAnswer && !stopRequested) {
          nudgedTodos = true
          append(ForgeEvent.Note(turn, "taches", ForgePrompts.todosOpen(openTodos.map { it.content }), forModel = true, shown = false))
          continue
        }
        return end(turn, if (forceAnswer) "limit" else "done", if (forceAnswer) "Limite de ${limits.maxSteps} étapes atteinte." else "")
      }

      for (call in calls.take(MAX_CALLS_PER_STEP)) {
        if (stopRequested) return end(turn, "stopped", "Arrêté par l'utilisateur.")
        val name = ForgeToolSpecs.canonicalName(call.name) ?: call.name
        val named = call.copy(name = name)
        val id = "c$turn-${++callCounter}"
        append(ForgeEvent.ToolCall(turn, id, name, call.arguments.toString()))
        openCall = id to name
        _live.value = _live.value.copy(tool = name, toolProgress = "")
        val check = guard.beforeCall(named)
        val outcome =
          when (check.verdict) {
            LoopGuard.Verdict.STOP -> {
              append(ForgeEvent.ToolResult(turn, id, name, "refused", ToolOutcome.refused(check.message!!).modelText, "arrêt"))
              return end(turn, "blocked", check.message)
            }
            LoopGuard.Verdict.REFUSE -> ToolOutcome.refused(check.message!!)
            else -> {
              val o = execute(turn, id, named)
              if (check.verdict == LoopGuard.Verdict.RUN_WITH_REMINDER) {
                o.copy(modelText = o.modelText + "\n" + ToolOutcome.json("rappel" to check.message))
              } else {
                o
              }
            }
          }
        val status = when (outcome.status) {
          ToolOutcome.Status.OK -> "ok"
          ToolOutcome.Status.ERROR -> "error"
          ToolOutcome.Status.REFUSED -> "refused"
        }
        val modelText = clip(outcome.modelText)
        openCall = null
        append(ForgeEvent.ToolResult(turn, id, name, status, modelText, outcome.summary, outcome.detail.take(MAX_DETAIL), outcome.added, outcome.removed, outcome.path))
        if (outcome.status == ToolOutcome.Status.OK && (name in SessionState.WRITERS || name == ForgeToolSpecs.BASH)) {
          _fileChanges.value++
        }
        if (outcome.conflict || (outcome.status == ToolOutcome.Status.ERROR && com.google.ai.edge.gallery.forge.tools.FileTracker.isChangedError(outcome.detail))) {
          val file = outcome.path ?: ForgeToolSpecs.str(call.arguments, "file_path", "path", "file")
          append(ForgeEvent.Note(turn, "conflit", "Conflit sur $file : le fichier a changé en dehors de Forge depuis que l'agent l'a lu. Rien n'a été écrasé ; l'agent doit le relire.", forModel = false))
        }
        if (outcome.status == ToolOutcome.Status.OK) {
          outcome.path?.let { p -> context.onPathTouched(p)?.let { append(ForgeEvent.Note(turn, "memoire", it, forModel = true, shown = false)) } }
        }
        val completed = name == ForgeToolSpecs.TODO_WRITE && outcome.status == ToolOutcome.Status.OK && completedTaskInLastTodos(turn)
        when (val s = guard.afterCall(named, outcome.status == ToolOutcome.Status.OK, completed)) {
          is LoopGuard.StallAction.Stop -> return end(turn, "blocked", s.reason)
          is LoopGuard.StallAction.Replan -> append(ForgeEvent.Note(turn, "blocage", s.note, forModel = true))
          null -> Unit
        }
        if (outcome.stopTurn) return end(turn, "stopped", "Arrêté depuis une demande de l'agent.")
      }
      _live.value = _live.value.copy(tool = null, toolProgress = "")
    }
  }

  // ------------------------------------------------------------------------------- compaction

  /** Last level 2 summary's position, so that summaries are not made one after the other. */
  private var lastSummaryAt = 0

  /**
   * Two levels (docs/forge/FORGE.md §4): over 60 % of the budget, the largest old results of the
   * turn become tombstones (their full text is kept in `.altrex/sorties/`, Read results are read
   * again); still over 85 %, the model summarizes what came before its last step. The journal
   * keeps everything. Returns true when the history changed.
   */
  private suspend fun compactIfNeeded(turn: Int, messages: List<com.google.ai.edge.gallery.agent.AgentMsg>, budget: Int): Boolean {
    val size = messages.sumOf { it.content.length + 16 }
    if (size <= budget * SOFT) return false
    val events = journal.snapshot()
    val done = events.filterIsInstance<ForgeEvent.Compaction>().flatMapTo(HashSet()) { it.ids }
    val results = events.filterIsInstance<ForgeEvent.ToolResult>().filter { it.turn == turn && it.id !in done }
    val candidates = results.dropLast(KEEP_RECENT).filter { it.modelText.length > MIN_TOMBSTONE }.sortedByDescending { it.modelText.length }
    var toSave = size - (budget * TARGET).toInt()
    val ids = ArrayList<String>()
    for (r in candidates) {
      if (toSave <= 0) break
      if (r.name != ForgeToolSpecs.READ) {
        runCatching {
          val f = File(paths.root, SessionState.spillPath(sessionId, r.id))
          f.parentFile?.mkdirs()
          val ignore = File(f.parentFile, ".gitignore")
          if (!ignore.exists()) ignore.writeText("*\n")
          if (!f.exists()) FileTools.writeAtomically(f, r.modelText.toByteArray(Charsets.UTF_8))
        }
      }
      ids.add(r.id)
      toSave -= r.modelText.length - 200
    }
    var changed = false
    if (ids.isNotEmpty()) {
      append(ForgeEvent.Compaction(turn, ids))
      changed = true
      if (toSave <= 0) return true
    }
    val now = if (changed) SessionState.modelMessages(journal.snapshot(), turn) else messages
    if (now.sumOf { it.content.length + 16 } <= budget * HARD) return changed
    return summarize(turn, now, budget) || changed
  }

  private suspend fun summarize(turn: Int, messages: List<com.google.ai.edge.gallery.agent.AgentMsg>, budget: Int): Boolean {
    val events = journal.snapshot()
    val lastAssistant = events.indexOfLast { it is ForgeEvent.Assistant && it.turn == turn }
    val turnStart = events.indexOfLast { it is ForgeEvent.TurnStart && it.turn == turn }
    val upto = if (lastAssistant > turnStart) lastAssistant else turnStart
    val previous = maxOf(lastSummaryAt, events.filterIsInstance<ForgeEvent.Summary>().lastOrNull()?.upto ?: 0)
    if (upto <= previous + 4 || upto <= 0) return false
    _live.value = _live.value.copy(toolProgress = "Résumé du contexte…")
    val transcript = StringBuilder()
    for (m in messages) transcript.append("### ").append(if (m.role == "user") "utilisateur / outils" else "Forge").append('\n').append(m.content.take(4_000)).append("\n\n")
    val room = (budget * 0.7).toInt().coerceAtLeast(4_000)
    val text = if (transcript.length > room) transcript.takeLast(room).toString() else transcript.toString()
    val raw =
      try {
        model.generate(SUMMARY_SYSTEM, listOf(com.google.ai.edge.gallery.agent.AgentMsg("user", text + "\n\n" + ForgePrompts.SUMMARY_REQUEST))) {}
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        ""
      }
    _live.value = _live.value.copy(toolProgress = "")
    val summary = ToolCalling.displayText(ToolCalling.stripThinking(raw)).trim().take(MAX_SUMMARY)
    if (summary.length < 40) return false
    lastSummaryAt = upto
    append(ForgeEvent.Summary(turn, upto, summary))
    append(ForgeEvent.Note(turn, "info", "Contexte presque plein : les échanges précédents ont été résumés pour le modèle (le journal complet est gardé).", forModel = false))
    return true
  }

  private fun completedTaskInLastTodos(turn: Int): Boolean {
    val all = journal.snapshot().filterIsInstance<ForgeEvent.Todos>()
    if (all.isEmpty()) return false
    val previous = if (all.size >= 2) all[all.size - 2].items else emptyList()
    return TodoTool.completedOne(previous, all.last().items)
  }

  /** Index of the current turn's request in [messages] (the first message of the turn). */
  private fun requestIndex(messages: List<com.google.ai.edge.gallery.agent.AgentMsg>, turn: Int): Int {
    // Earlier turns are condensed to one request and one answer each.
    val earlier = journal.snapshot().filterIsInstance<ForgeEvent.User>().map { it.turn }.distinct().count { it < turn }
    return (earlier * 2).coerceAtMost((messages.size - 1).coerceAtLeast(0))
  }

  private fun clip(text: String): String {
    val max = limits.maxResultChars
    if (text.length <= max) return text
    val head = text.take(max * 2 / 3)
    val tail = text.takeLast(max / 4)
    return "$head\n[… ${text.length - head.length - tail.length} caractères coupés …]\n$tail"
  }

  // ------------------------------------------------------------------------------------- tools

  private suspend fun execute(turn: Int, id: String, call: AgentToolCall): ToolOutcome {
    if (call.name.isEmpty()) return ToolOutcome.error(ForgePrompts.BAD_CALL)
    val a = call.arguments
    return try {
      when (call.name) {
        ForgeToolSpecs.READ ->
          files.read(
            ForgeToolSpecs.str(a, "file_path", "path", "file"),
            ForgeToolSpecs.int(a, "offset"),
            ForgeToolSpecs.int(a, "limit") ?: limits.readLines,
          )
        ForgeToolSpecs.WRITE -> {
          val content = ForgeToolSpecs.text(a, "content", "text") ?: return ToolOutcome.error("Write demande « content ».")
          writeChecked(id, "Write", files.prepareWrite(ForgeToolSpecs.str(a, "file_path", "path", "file"), content))
        }
        ForgeToolSpecs.EDIT ->
          writeChecked(
            id,
            "Edit",
            files.prepareEdit(
              ForgeToolSpecs.str(a, "file_path", "path", "file"),
              ForgeToolSpecs.text(a, "old_string", "oldString", "old").orEmpty(),
              ForgeToolSpecs.text(a, "new_string", "newString", "new").orEmpty(),
              ForgeToolSpecs.bool(a, "replace_all", "replaceAll"),
            ),
          )
        ForgeToolSpecs.MULTI_EDIT -> {
          val arr = ForgeToolSpecs.array(a, "edits", "changes")
          val edits =
            (0 until (arr?.length() ?: 0)).mapNotNull { i ->
              val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
              FileTools.EditSpec(
                ForgeToolSpecs.text(o, "old_string", "oldString", "old").orEmpty(),
                ForgeToolSpecs.text(o, "new_string", "newString", "new").orEmpty(),
                ForgeToolSpecs.bool(o, "replace_all", "replaceAll"),
              )
            }
          writeChecked(id, "MultiEdit", files.prepareMultiEdit(ForgeToolSpecs.str(a, "file_path", "path", "file"), edits))
        }
        ForgeToolSpecs.WEB_FETCH -> {
          val url = ForgeToolSpecs.str(a, "url", "href", "link").trim()
          if (url.isEmpty()) return ToolOutcome.error("WebFetch demande une « url ».")
          val w = web ?: return ToolOutcome.error("Le web n'est pas disponible dans cette session.")
          _live.value = _live.value.copy(toolProgress = "Lecture de la page…")
          w.fetch(url, ForgeToolSpecs.str(a, "prompt", "question").ifEmpty { null }, limits.maxResultChars - 400)
        }
        ForgeToolSpecs.WEB_SEARCH -> {
          val q = ForgeToolSpecs.str(a, "query", "q", "search").trim()
          if (q.isEmpty()) return ToolOutcome.error("WebSearch demande une « query ».")
          val w = web ?: return ToolOutcome.error("Le web n'est pas disponible dans cette session.")
          _live.value = _live.value.copy(toolProgress = "Recherche…")
          w.search(q, limits.maxResultChars - 400)
        }
        ForgeToolSpecs.GLOB -> search.glob(ForgeToolSpecs.str(a, "pattern", "glob"), ForgeToolSpecs.str(a, "path").ifEmpty { null })
        ForgeToolSpecs.GREP ->
          search.grep(
            SearchTools.GrepArgs(
              pattern = ForgeToolSpecs.str(a, "pattern", "regex", "query"),
              path = ForgeToolSpecs.str(a, "path").ifEmpty { null },
              glob = ForgeToolSpecs.str(a, "glob", "include").ifEmpty { null },
              outputMode = ForgeToolSpecs.str(a, "output_mode", "mode").ifEmpty { null },
              caseInsensitive = ForgeToolSpecs.bool(a, "case_insensitive", "-i", "ignore_case"),
              context = ForgeToolSpecs.int(a, "context", "-C") ?: 0,
              headLimit = ForgeToolSpecs.int(a, "head_limit", "limit"),
            )
          )
        ForgeToolSpecs.BASH -> bash(id, a)
        ForgeToolSpecs.BASH_OUTPUT, ForgeToolSpecs.KILL_SHELL -> {
          val job = ForgeToolSpecs.str(a, "job_id", "bash_id", "shell_id", "id").trim()
          if (job.isEmpty()) return ToolOutcome.error("${call.name} demande « job_id ».")
          val r = shell.checkJob(job, stop = call.name == ForgeToolSpecs.KILL_SHELL, maxChars = limits.maxResultChars)
          val ok = r["status"] == "succeeded"
          val out = LinkedHashMap<String, Any?>(r.filterKeys { it != "status" })
          out["status"] = if (ok) "ok" else "error"
          ToolOutcome(
            if (ok) ToolOutcome.Status.OK else ToolOutcome.Status.ERROR,
            ToolOutcome.json(*out.map { it.key to it.value }.toTypedArray()),
            summary = r["state"]?.let { stateLabel(it) } ?: "erreur",
            detail = (r["new_output"] ?: r["error"]).orEmpty().takeLast(4_000),
          )
        }
        ForgeToolSpecs.TODO_WRITE ->
          when (val p = TodoTool.parse(ForgeToolSpecs.array(a, "todos", "items", "tasks"))) {
            is TodoTool.Parsed.Fail -> ToolOutcome.error(p.message)
            is TodoTool.Parsed.Ok -> {
              append(ForgeEvent.Todos(turn, p.items))
              ToolOutcome(ToolOutcome.Status.OK, TodoTool.result(p.items), summary = "${p.items.count { it.status == "completed" }}/${p.items.size}")
            }
          }
        ForgeToolSpecs.EXIT_PLAN_MODE -> exitPlan(turn, id, ForgeToolSpecs.str(a, "plan", "markdown", "content"))
        ForgeToolSpecs.ASK_USER_QUESTION -> askUser(turn, id, a)
        else -> ToolOutcome.error(ForgePrompts.unknownTool(call.name, ForgeToolSpecs.names))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ToolOutcome.error("${call.name} a échoué : ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun stateLabel(s: String) =
    when (s) {
      "running" -> "en cours"
      "finished" -> "terminée"
      "stopped" -> "arrêtée"
      else -> s
    }

  /** Asks the policy (and maybe the user) for [action]; null when it may go on. */
  private suspend fun permit(id: String, tool: String, action: ForgeAction, detail: String, isDiff: Boolean): ToolOutcome? {
    if (readOnlyTurn && PermissionPolicy.decide(Preset.PLAN.mode, grants, action) !is Decision.Allow) return ToolOutcome.refused(ForgePrompts.PROPOSALS_ONLY)
    when (val d = PermissionPolicy.decide(mode, grants, action)) {
      Decision.Allow -> return null
      is Decision.Deny -> return ToolOutcome.refused(d.reason)
      is Decision.Ask -> {
        _live.value = _live.value.copy(toolProgress = "En attente de ton accord…")
        val answer =
          interactions.approve(
            ApprovalRequest(
              callId = id,
              tool = tool,
              title = d.title,
              detail = detail,
              isDiff = isDiff,
              risky = d.risky,
              escalation = d.escalation,
              justification = d.justification,
              grantLabel = d.grant?.let { if (it == Decision.GRANT_EDITS) "les modifications de fichiers" else "« $it »" },
            )
          )
        _live.value = _live.value.copy(toolProgress = "")
        val grant = if (answer.choice == ApprovalAnswer.Choice.TOUJOURS) d.grant else null
        val event = ForgeEvent.Approval(_state.value.lastTurn, id, answer.choice.journal, answer.reason.trim(), grant, action.key())
        grants.apply(event)
        append(event)
        return when (answer.choice) {
          ApprovalAnswer.Choice.OUI, ApprovalAnswer.Choice.TOUJOURS -> null
          ApprovalAnswer.Choice.NON ->
            ToolOutcome.refused("L'utilisateur a refusé" + (answer.reason.trim().takeIf { it.isNotEmpty() }?.let { " : $it" } ?: "."))
          ApprovalAnswer.Choice.SANS_REPONSE -> ToolOutcome.refused("Pas de réponse de l'utilisateur : refusé par défaut.")
          ApprovalAnswer.Choice.ARRETER ->
            ToolOutcome.refused("L'utilisateur a refusé et arrêté le tour.").copy(stopTurn = true)
        }
      }
    }
  }

  private suspend fun writeChecked(id: String, tool: String, prepared: FileTools.Prepared): ToolOutcome {
    val w =
      when (prepared) {
        is FileTools.Prepared.Failed -> return prepared.outcome
        is FileTools.Prepared.Ready -> prepared.write
      }
    permit(id, tool, ForgeAction.WriteFile(w.rel, w.isNew), w.diff.text, isDiff = true)?.let { return it.copy(path = w.rel) }
    // The user may have changed the file while the approval waited: never overwrite it silently.
    files.changedSincePrepared(w)?.let { return ToolOutcome.error(it).copy(path = w.rel, conflict = true) }
    beforeChange(_state.value.lastTurn)
    return files.commit(w)
  }

  private suspend fun bash(id: String, a: JSONObject): ToolOutcome {
    val command = ForgeToolSpecs.str(a, "command", "cmd").trim()
    if (command.isEmpty()) return ToolOutcome.error("Bash demande une « command » non vide.")
    val background = ForgeToolSpecs.bool(a, "run_in_background", "background")
    val justification = ForgeToolSpecs.str(a, "justification").ifEmpty { null }
    permit(id, "Bash", ForgeAction.RunCommand(command, justification), command, isDiff = false)?.let { return it }
    if (!AgentShell.isReadOnly(command)) beforeChange(_state.value.lastTurn)
    shell.ensureReady { p -> _live.value = _live.value.copy(toolProgress = p) }?.let { return ToolOutcome.error(it) }
    if (background) {
      val job = shell.startJob(command)
      if (job.startsWith("error:")) return ToolOutcome.error(job.removePrefix("error:").trim())
      return ToolOutcome(
        ToolOutcome.Status.OK,
        ToolOutcome.json(
          "job_id" to job,
          "note" to "Lancée en arrière-plan. Lis sa sortie avec BashOutput(\"$job\"), arrête-la avec KillShell(\"$job\").",
          "status" to "ok",
        ),
        summary = "en arrière-plan ($job)",
      )
    }
    val remaining = limits.maxDurationMs - (clock() - turnStartedAt)
    val wait = CommandWait.policy(command, ForgeToolSpecs.int(a, "timeout"), capMs = remaining)
    var lastShown = 0L
    val partial = StringBuilder()
    val r =
      shell.run(command, wait) { chunk ->
        synchronized(partial) {
          partial.append(chunk)
          if (partial.length > 4_000) partial.delete(0, partial.length - 2_000)
        }
        val now = clock()
        if (now - lastShown > 500) {
          lastShown = now
          val lines = progressLines(synchronized(partial) { partial.toString() })
          if (lines.isNotBlank()) _live.value = _live.value.copy(toolProgress = lines)
        }
      }
    val output = SecretRedactor.redact(r.output)
    val max = limits.maxResultChars
    var spilled: String? = null
    val shown =
      if (output.length > max) {
        spilled = spill(id, output)
        "[… ${output.length - max} caractères du début omis …]\n" + output.takeLast(max)
      } else {
        output
      }
    val lockHint =
      if (output.contains("Unable to lock database") || output.contains("unable to lock database")) {
        "Une autre commande apk tient encore le verrou de la base des paquets (souvent une installation passée en arrière-plan) : " +
          "attends-la avec BashOutput, ou arrête-la avec KillShell, puis relance. Ne supprime pas le fichier de verrou."
      } else {
        null
      }
    r.jobId?.let { job ->
      val why =
        if (r.detached == "cap") "Toujours en cours après ${wait.capMs / 60_000} min"
        else "Toujours en cours, sans nouvelle sortie depuis ${wait.idleMs / 1000} s"
      return ToolOutcome(
        ToolOutcome.Status.OK,
        ToolOutcome.json(
          "still_running" to true,
          "job_id" to job,
          "output" to shown.ifEmpty { "(aucune sortie pour l'instant)" },
          "full_output" to spilled,
          "note" to
            "$why : la commande n'est pas arrêtée, elle continue en arrière-plan ($job). Suis-la avec BashOutput(\"$job\") " +
              "jusqu'à state=finished et vérifie son exit_code ; arrête-la avec KillShell(\"$job\"). Ne la relance pas.",
          "status" to "ok",
        ),
        summary = "en arrière-plan ($job)",
        detail = output.takeLast(4_000),
      )
    }
    val ok = r.exitCode == 0 && !r.timedOut
    return ToolOutcome(
      if (ok) ToolOutcome.Status.OK else ToolOutcome.Status.ERROR,
      ToolOutcome.json(
        "exit_code" to r.exitCode,
        "timed_out" to (if (r.timedOut) true else null),
        "output" to shown.ifEmpty { "(aucune sortie)" },
        "full_output" to spilled,
        "cwd" to r.cwd,
        "hint" to lockHint,
        "note" to
          listOfNotNull(
            if (r.timedOut) "Arrêtée : délai dépassé. Pour un travail long, utilise run_in_background puis BashOutput." else null,
            spilled?.let { "Sortie complète dans $it : lis-la avec Read (offset, limit) ou cherche dedans avec Grep." },
          ).joinToString(" ").ifEmpty { null },
        "status" to if (ok) "ok" else "error",
      ),
      summary = if (r.timedOut) "délai dépassé" else "code ${r.exitCode}",
      detail = output.takeLast(4_000),
    )
  }

  /** The last lines of a running command, for its card (4 at most, each cut). */
  private fun progressLines(text: String): String =
    text.replace("\r\n", "\n").split('\n', '\r')
      .map { it.trimEnd() }
      .filter { it.isNotBlank() && !it.startsWith("\u001E") }
      .takeLast(PROGRESS_LINES)
      .joinToString("\n") { it.take(120) }

  /** A long output, kept whole in .altrex/sorties/ (ignored by Git) for Read and Grep. */
  private fun spill(id: String, output: String): String? =
    runCatching {
      val dir = File(paths.root, ".altrex/sorties").apply { mkdirs() }
      val ignore = File(dir, ".gitignore")
      if (!ignore.exists()) ignore.writeText("*\n")
      val name = "$sessionId-$id.txt".replace(Regex("[^A-Za-z0-9._-]"), "_")
      FileTools.writeAtomically(File(dir, name), output.toByteArray(Charsets.UTF_8))
      ".altrex/sorties/$name"
    }.getOrNull()

  private suspend fun exitPlan(turn: Int, id: String, plan: String): ToolOutcome {
    if (!mode.isPlan) return ToolOutcome.error(ForgePrompts.NOT_IN_PLAN)
    if (plan.trim().length < 20) return ToolOutcome.error("Le plan est vide ou trop court : donne le plan complet en Markdown.")
    // Build mode: the plan carries the recipe Forge will check; without a proof it is sent back.
    val building = _state.value.build?.state == ForgeEvent.Build.OPEN
    val recipe = if (building) BuildRecipe.parse(plan) else null
    if (building) {
      if (recipe == null || !recipe.hasProof) return ToolOutcome.error(ForgePrompts.BUILD_NEEDS_RECIPE)
      recipe.checks.firstOrNull { PermissionPolicy.isDangerous(it) }?.let { return ToolOutcome.error(ForgePrompts.buildDangerousCheck(it)) }
    }
    append(ForgeEvent.Plan(turn, id, plan.trim()))
    val review = interactions.reviewPlan(id, plan.trim())
    return when (review.outcome) {
      PlanReview.Outcome.VALIDE -> {
        val next = review.nextMode?.takeIf { !it.isPlan } ?: beforePlan
        val saved = savePlan(plan.trim())
        append(ForgeEvent.PlanDecision(turn, id, approved = true, feedback = review.feedback, mode = next.key()))
        mode = next
        announcedMode = next
        if (recipe != null) append(ForgeEvent.Build(turn, ForgeEvent.Build.RECIPE, tour = 1, text = recipe.describe(), recipe = recipe.toJson()))
        val text = ForgePrompts.planApproved(next, saved) + if (recipe != null) " " + ForgePrompts.BUILD_GO else ""
        ToolOutcome(ToolOutcome.Status.OK, ToolOutcome.json("result" to text, "status" to "ok"), summary = "plan validé", path = saved)
      }
      PlanReview.Outcome.A_MODIFIER -> {
        append(ForgeEvent.PlanDecision(turn, id, approved = false, feedback = review.feedback))
        ToolOutcome(ToolOutcome.Status.OK, ToolOutcome.json("result" to ForgePrompts.planChanges(review.feedback), "status" to "ok"), summary = "à modifier")
      }
      PlanReview.Outcome.REFUSE -> {
        append(ForgeEvent.PlanDecision(turn, id, approved = false, feedback = "refusé"))
        ToolOutcome.refused(ForgePrompts.PLAN_REJECTED).copy(stopTurn = true)
      }
    }
  }

  /** A validated plan is kept in the project: .altrex/plans/<date>-<titre>.md. */
  private fun savePlan(markdown: String): String? =
    runCatching {
      val title = markdown.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trimStart('#', ' ').take(40)
      val slug = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "plan" }
      val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(clock()))
      val rel = ".altrex/plans/$stamp-$slug.md"
      FileTools.writeAtomically(File(paths.root, rel), (markdown + "\n").toByteArray(Charsets.UTF_8))
      rel
    }.getOrNull()

  private suspend fun askUser(turn: Int, id: String, a: JSONObject): ToolOutcome {
    val question = ForgeToolSpecs.str(a, "question", "text").trim()
    if (question.isEmpty()) return ToolOutcome.error("AskUserQuestion demande une « question ».")
    val arr = ForgeToolSpecs.array(a, "options", "choices")
    val options =
      (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        when (val v = arr!!.opt(i)) {
          is String -> v
          is JSONObject -> v.optString("label").ifEmpty { v.optString("text") }
          else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
      }.distinct().take(4)
    append(ForgeEvent.Question(turn, id, question.take(500), options))
    _live.value = _live.value.copy(toolProgress = "En attente de ta réponse…")
    val answer = interactions.ask(id, question, options)?.trim()?.takeIf { it.isNotEmpty() }
    append(ForgeEvent.Answer(turn, id, answer ?: ""))
    return if (answer == null) {
      ToolOutcome(ToolOutcome.Status.OK, ToolOutcome.json("answer" to null, "note" to "Pas de réponse de l'utilisateur. Ne suppose rien : arrête-toi ou continue avec ce qui ne dépend pas de ce choix.", "status" to "ok"), summary = "sans réponse")
    } else {
      ToolOutcome(ToolOutcome.Status.OK, ToolOutcome.json("answer" to answer, "status" to "ok"), summary = answer.take(40))
    }
  }

  companion object {
    const val MAX_CALLS_PER_STEP = 4
    private const val SOFT = 0.6
    private const val TARGET = 0.45
    private const val HARD = 0.85
    private const val KEEP_RECENT = 2
    private const val MIN_TOMBSTONE = 400
    private const val MAX_SUMMARY = 3_000
    private const val SUMMARY_SYSTEM = "Tu résumes une session de travail de Forge, l'agent de programmation d'Altrex, pour qu'elle continue sans les anciens messages."
    const val DEFAULT_TIMEOUT_S = CommandWait.DEFAULT_IDLE_S
    const val MAX_TIMEOUT_S = CommandWait.MAX_IDLE_S
    private const val PROGRESS_LINES = 4
    private const val MAX_DETAIL = 6_000
  }
}
