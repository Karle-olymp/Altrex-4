package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AgentCallType {
  READ,
  LIST,
  DELETE,
}

data class AgentCall(val type: AgentCallType, val path: String)

/**
 * Agent loop: after each model answer, executes the actions it contains (read / list / delete a
 * file, write file blocks), then feeds the results back to the model, up to MAX_ROUNDS extra times.
 */
object AgentRunner {
  // Number of follow-up generations after the first one (each one costs a full prefill).
  private const val MAX_ROUNDS = 2
  private val FENCE = "`".repeat(3)

  // Paths that small models copy from examples: ignored when they do not really exist.
  private val EXAMPLE_PATHS =
    setOf("README.md", "notes/ancien.txt", "chemin/fichier", "chemin/dossier")

  private val CALL_REGEX =
    Regex("^[\\s*_`>\\-]*ACTION\\s*:\\s*([A-Za-z]+)\\s+(.+?)\\s*$", RegexOption.MULTILINE)

  // "ACTION: créer notes/a.txt" followed by a code block without the "file:" tag.
  private val LOOSE_WRITE_REGEX =
    Regex(
      "ACTION\\s*:\\s*(?:cr[ée]er|[ée]crire|[ée]cris|create|write|modifier|edit)\\s+" +
        "(?:le\\s+)?(?:fichier\\s+)?(\\S+)[^\\n]*\\n+" +
        FENCE +
        "[^\\n]*\\n(.*?)" +
        FENCE,
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

  private val READ_WORDS = setOf("lire", "ouvrir", "read", "open", "cat")
  private val LIST_WORDS = setOf("lister", "list", "ls", "dir")
  private val DELETE_WORDS = setOf("supprimer", "delete", "remove", "rm", "effacer")

  /** Finds "ACTION: verb path" lines outside of code blocks. */
  fun parseCalls(response: String): List<AgentCall> {
    val withoutBlocks =
      response.replace(Regex(FENCE + ".*?" + FENCE, RegexOption.DOT_MATCHES_ALL), "")
    val calls = LinkedHashSet<AgentCall>()
    for (m in CALL_REGEX.findAll(withoutBlocks)) {
      val verb = m.groupValues[1].lowercase()
      val raw = m.groupValues[2]
      val type =
        when {
          verb in READ_WORDS -> AgentCallType.READ
          verb in LIST_WORDS -> AgentCallType.LIST
          verb in DELETE_WORDS -> AgentCallType.DELETE
          else -> null
        } ?: continue
      val path =
        if (type == AgentCallType.LIST) FileActions.normalizeDir(raw) else FileActions.normalize(raw)
      if (path != null) calls.add(AgentCall(type, path))
      if (calls.size >= 6) break
    }
    return calls.toList()
  }

  /** File blocks (tagged "file:path") plus loose "ACTION: créer path" + untagged code block. */
  fun parseWrites(response: String): List<FileAction> {
    val tagged = FileActions.parse(response)
    val taggedPaths = tagged.map { it.path }.toSet()
    val loose = ArrayList<FileAction>()
    for (m in LOOSE_WRITE_REGEX.findAll(response)) {
      val path = FileActions.normalize(m.groupValues[1].removePrefix("file:")) ?: continue
      if (path in taggedPaths) continue
      loose.add(FileAction(path, FileActions.cleanCode(m.groupValues[2])))
    }
    return tagged + loose
  }

  private fun appendRootListing(context: Context, feedback: StringBuilder) {
    val root = FileActions.listDir(context, "") ?: return
    feedback.append("Voici la racine du projet :\n${root.joinToString("\n")}\n\n")
  }

  private fun finish(
    viewModel: LlmSingleTurnViewModel,
    model: Model,
    instruction: String,
    summary: String,
  ) {
    if (summary.isNotBlank()) viewModel.recordTurn(model, instruction, summary)
    viewModel.setInProgress(false)
  }

  fun generate(
    scope: CoroutineScope,
    context: Context,
    viewModel: LlmSingleTurnViewModel,
    task: Task,
    model: Model,
    input: String,
    instruction: String,
    promptBudget: Int,
    round: Int = 0,
    responsePrefix: String = "",
    previousSignature: Set<String> = emptySet(),
  ) {
    if (round == 0) viewModel.clearResponse(model)
    viewModel.generateResponse(
      task = task,
      model = model,
      input = input,
      instruction = instruction,
      maxPromptChars = promptBudget,
      responsePrefix = responsePrefix,
      useHistory = round == 0,
      onDone = done@{ response ->
        if (!AgentSettings.autoMode) return@done false
        if (!viewModel.uiState.value.inProgress) return@done false
        val calls = parseCalls(response)
        val writes = parseWrites(response)
        if (calls.isEmpty() && writes.isEmpty()) return@done false
        scope.launch(Dispatchers.IO) {
          runStep(
            scope = scope,
            context = context,
            viewModel = viewModel,
            task = task,
            model = model,
            instruction = instruction,
            promptBudget = promptBudget,
            round = round,
            previousSignature = previousSignature,
            calls = calls,
            writes = writes,
          )
        }
        true
      },
    )
  }

  private suspend fun runStep(
    scope: CoroutineScope,
    context: Context,
    viewModel: LlmSingleTurnViewModel,
    task: Task,
    model: Model,
    instruction: String,
    promptBudget: Int,
    round: Int,
    previousSignature: Set<String>,
    calls: List<AgentCall>,
    writes: List<FileAction>,
  ) {
    // Ignore example paths copied from the instructions, and writes that conflict with a delete.
    val deletePaths = calls.filter { it.type == AgentCallType.DELETE }.map { it.path }.toSet()
    val usableCalls =
      calls.filter { c -> !(c.path in EXAMPLE_PATHS && !FileActions.exists(context, c.path)) }
    val usableWrites =
      writes.filter { w ->
        w.path !in deletePaths && !(w.content.isBlank() && FileActions.exists(context, w.path))
      }
    if (usableCalls.isEmpty() && usableWrites.isEmpty()) {
      viewModel.appendToResponse(model, "\n\n(Aucune action exécutable.)")
      finish(viewModel, model, instruction, "")
      return
    }

    val log = StringBuilder()
    val feedback = StringBuilder()
    var needsModel = false
    val lookups = usableCalls.filter { it.type != AgentCallType.DELETE }

    if (lookups.isNotEmpty()) {
      // Lookups first: ignore writes/deletes of this turn (the model has not seen the files).
      val cap = ((promptBudget - 1500) / (lookups.size * 2)).coerceIn(1200, 6000)
      for (c in lookups) {
        when (c.type) {
          AgentCallType.READ -> {
            var path = c.path
            if (!FileActions.exists(context, path)) {
              ProjectContextBuilder.findByName(context, path.substringAfterLast('/'))?.let {
                path = it
              }
            }
            val text = FileActions.readNumbered(context, path, cap)
            if (text == null) {
              log.append("✗ Introuvable : $path\n")
              feedback.append("Fichier introuvable : $path\n")
              appendRootListing(context, feedback)
            } else {
              log.append("📄 Lu : $path\n")
              feedback.append("Contenu de $path (numéros de ligne à ne pas recopier) :\n$text\n\n")
            }
          }
          AgentCallType.LIST -> {
            val shown = if (c.path.isEmpty()) "(racine)" else c.path
            val names = FileActions.listDir(context, c.path)
            if (names == null) {
              log.append("✗ Dossier introuvable : $shown\n")
              feedback.append("Dossier introuvable : $shown\n")
              appendRootListing(context, feedback)
            } else {
              log.append("📂 Listé : $shown (${names.size} éléments)\n")
              feedback.append("Contenu du dossier $shown :\n${names.joinToString("\n")}\n\n")
            }
          }
          AgentCallType.DELETE -> {}
        }
      }
      if (usableWrites.isNotEmpty() || usableCalls.any { it.type == AgentCallType.DELETE }) {
        feedback.append(
          "(Tes écritures et suppressions de ce tour ont été ignorées : refais-les " +
            "maintenant que tu as lu les fichiers.)\n"
        )
      }
      needsModel = true
    } else {
      for (w in usableWrites) {
        val r = FileActions.apply(context, w)
        if (r.ok) {
          log.append("✓ ${r.message} : ${w.path}\n")
          feedback.append("OK ${r.message} : ${w.path}\n")
        } else {
          log.append("✗ ${w.path} : ${r.message}\n")
          feedback.append("ERREUR ${w.path} : ${r.message}\n")
          needsModel = true
        }
      }
      for (c in usableCalls) {
        val r = FileActions.delete(context, c.path)
        if (r.ok) {
          log.append("🗑 Supprimé : ${c.path} (annulable avec ↩)\n")
          feedback.append("OK supprimé : ${c.path}\n")
        } else {
          log.append("✗ ${c.path} : ${r.message}\n")
          feedback.append("ERREUR ${c.path} : ${r.message}\n")
          needsModel = true
        }
      }
    }

    viewModel.appendToResponse(model, "\n\n" + log.toString().trimEnd())
    val signature = usableCalls.map { "${it.type}:${it.path}" }.toSet()
    val repeated = needsModel && signature.isNotEmpty() && signature == previousSignature
    val stillRunning = viewModel.uiState.value.inProgress

    if (needsModel && !repeated && round < MAX_ROUNDS && stillRunning) {
      delay(800)
      val feedbackText = feedback.toString().take((promptBudget - 1200).coerceAtLeast(1200))
      val next =
        "Demande initiale : $instruction\n\n[Résultats des actions]\n$feedbackText\n" +
          "Continue la tâche. Quand elle est terminée, réponds sans ACTION, en 2 phrases."
      val prefix = viewModel.currentResponse(model) + "\n\n---\n\n"
      viewModel.appendToResponse(model, "\n\n⏳ Étape ${round + 2}…")
      generate(
        scope = scope,
        context = context,
        viewModel = viewModel,
        task = task,
        model = model,
        input = next,
        instruction = instruction,
        promptBudget = promptBudget,
        round = round + 1,
        responsePrefix = prefix,
        previousSignature = signature,
      )
    } else {
      if (needsModel && stillRunning) {
        val why =
          if (repeated) {
            "(Même action répétée sans succès : arrêt.)"
          } else {
            "(Limite de ${MAX_ROUNDS + 1} étapes atteinte.)"
          }
        viewModel.appendToResponse(model, "\n\n$why")
      }
      finish(viewModel, model, instruction, "Actions : " + log.toString().trim().take(600))
    }
  }
}
