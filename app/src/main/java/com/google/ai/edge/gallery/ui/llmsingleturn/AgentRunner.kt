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
 * file, write file blocks), then feeds the results back to the model, up to MAX_ROUNDS times.
 */
object AgentRunner {
  private const val MAX_ROUNDS = 4
  private val FENCE = "`".repeat(3)

  private val CALL_REGEX =
    Regex("^[\\s*_`>\\-]*ACTION\\s*:\\s*([A-Za-z]+)\\s+(.+?)\\s*$", RegexOption.MULTILINE)

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
  ) {
    viewModel.generateResponse(
      task = task,
      model = model,
      input = input,
      instruction = instruction,
      maxPromptChars = promptBudget,
      responsePrefix = responsePrefix,
      onDone = done@{ response ->
        if (!AgentSettings.autoMode) return@done false
        if (!viewModel.uiState.value.inProgress) return@done false
        val calls = parseCalls(response)
        val writes = FileActions.parse(response)
        if (calls.isEmpty() && writes.isEmpty()) return@done false
        scope.launch(Dispatchers.IO) {
          val log = StringBuilder()
          val feedback = StringBuilder()
          var needsModel = false
          val lookups = calls.filter { it.type != AgentCallType.DELETE }

          if (lookups.isNotEmpty()) {
            // Lookups first: ignore writes/deletes of this turn (the model has not seen the files).
            val cap = ((promptBudget - 1500) / (lookups.size * 2)).coerceIn(1200, 6000)
            for (c in lookups) {
              val shown = if (c.path.isEmpty()) "(racine)" else c.path
              when (c.type) {
                AgentCallType.READ -> {
                  val text = FileActions.readNumbered(context, c.path, cap)
                  if (text == null) {
                    log.append("✗ Introuvable : $shown\n")
                    feedback.append("Fichier introuvable : $shown\n")
                  } else {
                    log.append("📄 Lu : $shown\n")
                    feedback.append(
                      "Contenu de $shown (numéros de ligne à ne pas recopier) :\n$text\n\n"
                    )
                  }
                }
                AgentCallType.LIST -> {
                  val names = FileActions.listDir(context, c.path)
                  if (names == null) {
                    log.append("✗ Dossier introuvable : $shown\n")
                    feedback.append("Dossier introuvable : $shown\n")
                  } else {
                    log.append("📂 Listé : $shown (${names.size} éléments)\n")
                    feedback.append("Contenu du dossier $shown :\n${names.joinToString("\n")}\n\n")
                  }
                }
                AgentCallType.DELETE -> {}
              }
            }
            if (writes.isNotEmpty() || calls.any { it.type == AgentCallType.DELETE }) {
              feedback.append(
                "(Tes écritures et suppressions de ce tour ont été ignorées : refais-les " +
                  "maintenant que tu as lu les fichiers.)\n"
              )
            }
            needsModel = true
          } else {
            for (w in writes) {
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
            for (c in calls) {
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
          val stillRunning = viewModel.uiState.value.inProgress
          if (needsModel && round < MAX_ROUNDS && stillRunning) {
            delay(800)
            val feedbackText = feedback.toString().take((promptBudget - 1200).coerceAtLeast(1200))
            val next =
              "Demande initiale : $instruction\n\n[Résultats des actions]\n$feedbackText\n" +
                "Continue la tâche. Quand elle est terminée, réponds sans ACTION, en 2 phrases."
            generate(
              scope = scope,
              context = context,
              viewModel = viewModel,
              task = task,
              model = model,
              input = next,
              instruction = "[suite ${round + 1}] $instruction",
              promptBudget = promptBudget,
              round = round + 1,
              responsePrefix = viewModel.currentResponse(model) + "\n\n---\n\n",
            )
          } else {
            if (needsModel && stillRunning) {
              viewModel.appendToResponse(model, "\n\n(Limite de $MAX_ROUNDS étapes atteinte.)")
            }
            viewModel.setInProgress(false)
          }
        }
        true
      },
    )
  }
}
