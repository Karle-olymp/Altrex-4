package com.google.ai.edge.gallery.forge.engine

import com.google.ai.edge.gallery.agent.AgentContextFitter
import com.google.ai.edge.gallery.agent.AgentToolCall
import com.google.ai.edge.gallery.agent.StreamSplitter
import com.google.ai.edge.gallery.agent.ToolCalling
import com.google.ai.edge.gallery.forge.journal.ForgeEvent
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
import kotlinx.coroutines.CancellationException
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
) {
  /** What the screen shows live, besides the journal: the answer being written, the running tool. */
  data class Live(
    val running: Boolean = false,
    val text: String = "",
    val thinking: String = "",
    val tool: String? = null,
    val toolProgress: String = "",
    val step: Int = 0,
  )

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

  val sessionId: String
    get() = journal.id

  private fun append(e: ForgeEvent) {
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

  /** The Stop button: the answer being written stops, the turn ends after the current tool. */
  fun requestStop() {
    stopRequested = true
    model.stop()
  }

  val isRunning: Boolean
    get() = _live.value.running

  /** Runs one turn for [request]. Returns how it ended ("done", "stopped", "limit", "error", "blocked"). */
  suspend fun runTurn(request: String): String {
    stopRequested = false
    val turn = _state.value.lastTurn + 1
    _live.value = Live(running = true)
    append(ForgeEvent.TurnStart(turn))
    append(ForgeEvent.User(turn, request))
    when {
      mode.isPlan -> append(ForgeEvent.Note(turn, "mode", ForgePrompts.PLAN_REMINDER, forModel = true, shown = false))
      announcedMode != null && announcedMode != mode ->
        append(ForgeEvent.Note(turn, "mode", ForgePrompts.modeReminder(mode), forModel = true, shown = false))
    }
    announcedMode = mode
    return try {
      loop(turn)
    } catch (e: CancellationException) {
      end(turn, "stopped", "Tour annulé.")
      throw e
    } catch (e: Exception) {
      end(turn, "error", e.message ?: e.javaClass.simpleName)
    } finally {
      _live.value = Live()
    }
  }

  private fun end(turn: Int, reason: String, detail: String = ""): String {
    if (_state.value.turnOpen) append(ForgeEvent.TurnEnd(turn, reason, detail))
    return reason
  }

  private suspend fun loop(turn: Int): String {
    val guard = LoopGuard()
    val started = clock()
    val system = ToolCalling.systemPrompt(ForgeToolSpecs.specs, context.systemPrompt())
    var usedChars = 0L
    var steps = 0
    var forceAnswer = false
    var nudgedEmpty = false
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
      val messages = SessionState.modelMessages(journal.snapshot(), turn)
      val requestIndex = requestIndex(messages, turn)
      val fitted = AgentContextFitter.fit(messages, requestIndex, (model.historyBudgetChars - system.length).coerceAtLeast(2_000))
      usedChars += system.length + fitted.sumOf { it.content.length }

      _live.value = _live.value.copy(text = "", thinking = "", tool = null, toolProgress = "", step = steps)
      val splitter = StreamSplitter()
      val shown = StringBuilder()
      val thinking = StringBuilder()
      val raw =
        try {
          model.generate(system, fitted) { chunk ->
            synchronized(splitter) {
              val out = splitter.feed(chunk)
              shown.append(out.text)
              thinking.append(out.thinking)
              _live.value = _live.value.copy(text = shown.toString(), thinking = thinking.toString())
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          if (stopRequested) return end(turn, "stopped", "Arrêté par l'utilisateur.")
          append(ForgeEvent.Note(turn, "erreur", "Erreur du modèle : ${e.message ?: e.javaClass.simpleName}", forModel = false))
          return end(turn, "error", e.message ?: "Erreur du modèle.")
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
        if (display.isBlank() && !nudgedEmpty && !forceAnswer) {
          nudgedEmpty = true
          append(ForgeEvent.Note(turn, "vide", ForgePrompts.EMPTY_ANSWER, forModel = true, shown = false))
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
        append(ForgeEvent.ToolResult(turn, id, name, status, modelText, outcome.summary, outcome.detail.take(MAX_DETAIL), outcome.added, outcome.removed, outcome.path))
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
    return files.commit(w)
  }

  private suspend fun bash(id: String, a: JSONObject): ToolOutcome {
    val command = ForgeToolSpecs.str(a, "command", "cmd").trim()
    if (command.isEmpty()) return ToolOutcome.error("Bash demande une « command » non vide.")
    val background = ForgeToolSpecs.bool(a, "run_in_background", "background")
    val justification = ForgeToolSpecs.str(a, "justification").ifEmpty { null }
    permit(id, "Bash", ForgeAction.RunCommand(command, justification), command, isDiff = false)?.let { return it }
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
    val timeoutS = (ForgeToolSpecs.int(a, "timeout") ?: DEFAULT_TIMEOUT_S).coerceIn(1, MAX_TIMEOUT_S)
    var lastShown = 0L
    val partial = StringBuilder()
    val r =
      shell.run(command, timeoutS * 1000L) { chunk ->
        synchronized(partial) {
          partial.append(chunk)
          if (partial.length > 4_000) partial.delete(0, partial.length - 2_000)
        }
        val now = clock()
        if (now - lastShown > 1_000) {
          lastShown = now
          val line = synchronized(partial) { partial.toString() }.trimEnd().substringAfterLast('\n').take(80)
          if (line.isNotBlank() && !line.startsWith("\u001E")) _live.value = _live.value.copy(toolProgress = line)
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
    val ok = r.exitCode == 0 && !r.timedOut
    return ToolOutcome(
      if (ok) ToolOutcome.Status.OK else ToolOutcome.Status.ERROR,
      ToolOutcome.json(
        "exit_code" to r.exitCode,
        "timed_out" to (if (r.timedOut) true else null),
        "output" to shown.ifEmpty { "(aucune sortie)" },
        "full_output" to spilled,
        "cwd" to r.cwd,
        "note" to
          listOfNotNull(
            if (r.timedOut) "Arrêtée après $timeoutS s. Pour un travail long, utilise run_in_background puis BashOutput." else null,
            spilled?.let { "Sortie complète dans $it : lis-la avec Read (offset, limit) ou cherche dedans avec Grep." },
          ).joinToString(" ").ifEmpty { null },
        "status" to if (ok) "ok" else "error",
      ),
      summary = if (r.timedOut) "délai dépassé" else "code ${r.exitCode}",
      detail = output.takeLast(4_000),
    )
  }

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
    append(ForgeEvent.Plan(turn, id, plan.trim()))
    val review = interactions.reviewPlan(id, plan.trim())
    return when (review.outcome) {
      PlanReview.Outcome.VALIDE -> {
        val next = review.nextMode?.takeIf { !it.isPlan } ?: beforePlan
        val saved = savePlan(plan.trim())
        append(ForgeEvent.PlanDecision(turn, id, approved = true, feedback = review.feedback, mode = next.key()))
        mode = next
        announcedMode = next
        ToolOutcome(ToolOutcome.Status.OK, ToolOutcome.json("result" to ForgePrompts.planApproved(next, saved), "status" to "ok"), summary = "plan validé", path = saved)
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
    const val DEFAULT_TIMEOUT_S = 120
    const val MAX_TIMEOUT_S = 600
    private const val MAX_DETAIL = 6_000
  }
}
