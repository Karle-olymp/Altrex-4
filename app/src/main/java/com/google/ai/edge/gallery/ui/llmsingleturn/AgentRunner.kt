package com.google.ai.edge.gallery.ui.llmsingleturn

import android.content.Context
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Agent loop based on native tool calling (Qwen / Hermes format): the model calls tools
 * (list / read / write / edit / delete files), the app runs them, sends the results back, and the
 * loop continues until the model answers without a tool call.
 */
object AgentRunner {
  private const val MAX_STEPS = 6
  private const val MAX_SESSION_MESSAGES = 6

  // Past exchanges (compact), per model.
  private val sessions = HashMap<String, MutableList<AgentMsg>>()

  fun clearSession(model: Model) {
    synchronized(sessions) { sessions.remove(model.name) }
  }

  private fun instructions(openedFileName: String?): String {
    val opened =
      if (openedFileName != null) {
        " Fichier ouvert dans l'éditeur : « $openedFileName » (son contenu est dans le message)."
      } else {
        ""
      }
    return "Tu es un agent de code intégré à un éditeur de projet. Réponds toujours en français. " +
      "Tu peux lister, lire, créer, modifier et supprimer des fichiers du projet avec les outils. " +
      "Ne devine jamais le contenu d'un fichier : lis-le d'abord avec read_file, sauf s'il est " +
      "déjà dans le message. Les lignes lues sont préfixées par leur numéro et « | » : ne " +
      "recopie jamais ces préfixes. Pour changer quelques lignes, utilise replace_lines plutôt " +
      "que de réécrire tout le fichier. Quand tu as fini, réponds en 2 phrases au maximum.$opened"
  }

  /** Manual mode: one generation, no tools. */
  fun generate(
    viewModel: LlmSingleTurnViewModel,
    task: Task,
    model: Model,
    input: String,
    instruction: String,
    promptBudget: Int,
  ) {
    viewModel.clearResponse(model)
    viewModel.generateResponse(
      task = task,
      model = model,
      input = input,
      instruction = instruction,
      maxPromptChars = promptBudget,
    )
  }

  /** Agent mode: runs the tool-calling loop for one user request. */
  fun run(
    scope: CoroutineScope,
    context: Context,
    viewModel: LlmSingleTurnViewModel,
    task: Task,
    model: Model,
    userMessage: String,
    instruction: String,
    openedFileName: String?,
    promptBudget: Int,
  ) {
    viewModel.clearResponse(model)
    val system = ToolCalling.systemPrompt(CodeTools.specs, instructions(openedFileName))
    val past = synchronized(sessions) { sessions[model.name]?.toList() ?: emptyList() }
    val messages = ArrayList<AgentMsg>(past)
    messages.add(AgentMsg("user", userMessage))
    step(
      scope = scope,
      context = context,
      viewModel = viewModel,
      task = task,
      model = model,
      system = system,
      messages = messages,
      pastCount = past.size,
      instruction = instruction,
      promptBudget = promptBudget,
      stepNo = 0,
      displayPrefix = "",
    )
  }

  // Drops the oldest past messages until the prompt fits the budget.
  private fun fit(
    system: String,
    messages: List<AgentMsg>,
    pastCount: Int,
    budget: Int,
  ): List<AgentMsg> {
    val list = messages.toMutableList()
    var removable = pastCount
    while (removable > 0 && ToolCalling.buildPrompt(system, list).length > budget) {
      list.removeAt(0)
      removable--
    }
    return list
  }

  private fun setDisplay(viewModel: LlmSingleTurnViewModel, model: Model, text: String) {
    viewModel.updateResponse(
      model = model,
      promptTemplateType = viewModel.uiState.value.selectedPromptTemplateType,
      response = text,
    )
  }

  private fun step(
    scope: CoroutineScope,
    context: Context,
    viewModel: LlmSingleTurnViewModel,
    task: Task,
    model: Model,
    system: String,
    messages: MutableList<AgentMsg>,
    pastCount: Int,
    instruction: String,
    promptBudget: Int,
    stepNo: Int,
    displayPrefix: String,
  ) {
    val prompt = ToolCalling.buildPrompt(system, fit(system, messages, pastCount, promptBudget))
    viewModel.generateResponse(
      task = task,
      model = model,
      input = prompt,
      instruction = instruction,
      responsePrefix = displayPrefix,
      useHistory = false,
      onDone = done@{ response ->
        // The user pressed stop: nothing more to do.
        if (!viewModel.uiState.value.inProgress) return@done true
        scope.launch(Dispatchers.IO) {
          afterGeneration(
            scope = scope,
            context = context,
            viewModel = viewModel,
            task = task,
            model = model,
            system = system,
            messages = messages,
            pastCount = pastCount,
            instruction = instruction,
            promptBudget = promptBudget,
            stepNo = stepNo,
            displayPrefix = displayPrefix,
            response = response,
          )
        }
        true
      },
    )
  }

  private suspend fun afterGeneration(
    scope: CoroutineScope,
    context: Context,
    viewModel: LlmSingleTurnViewModel,
    task: Task,
    model: Model,
    system: String,
    messages: MutableList<AgentMsg>,
    pastCount: Int,
    instruction: String,
    promptBudget: Int,
    stepNo: Int,
    displayPrefix: String,
    response: String,
  ) {
    val calls = ToolCalling.parseToolCalls(response)
    val visible = ToolCalling.stripToolCalls(response)

    if (calls.isEmpty()) {
      // Final answer.
      setDisplay(viewModel, model, displayPrefix + visible)
      saveSession(model, instruction, displayPrefix, visible)
      viewModel.setInProgress(false)
      return
    }

    val log = StringBuilder()
    val results = ArrayList<String>()
    val used = calls.take(4)
    val cap = ((promptBudget - 3000) / (used.size * 2)).coerceIn(1500, 6000)
    for (c in used) {
      val outcome = CodeTools.execute(context, c, cap)
      log.append(outcome.log).append("\n")
      results.add(outcome.result)
    }
    messages.add(AgentMsg("assistant", response.trim()))
    messages.add(ToolCalling.toolResponseMessage(results))

    val newDisplay =
      buildString {
        append(displayPrefix)
        if (visible.isNotEmpty()) append(visible).append("\n\n")
        append(log.toString().trimEnd())
      }
    setDisplay(viewModel, model, newDisplay)

    val stillRunning = viewModel.uiState.value.inProgress
    if (stepNo + 1 >= MAX_STEPS || !stillRunning) {
      if (stillRunning) {
        setDisplay(viewModel, model, newDisplay + "\n\n(Limite de $MAX_STEPS étapes atteinte.)")
      }
      saveSession(model, instruction, newDisplay, "")
      viewModel.setInProgress(false)
      return
    }

    delay(500)
    step(
      scope = scope,
      context = context,
      viewModel = viewModel,
      task = task,
      model = model,
      system = system,
      messages = messages,
      pastCount = pastCount,
      instruction = instruction,
      promptBudget = promptBudget,
      stepNo = stepNo + 1,
      displayPrefix = newDisplay + "\n\n",
    )
  }

  // Keeps a compact record of the exchange for the next requests.
  private fun saveSession(model: Model, instruction: String, actions: String, answer: String) {
    val summary = (actions.trim() + "\n" + answer.trim()).trim().take(800)
    synchronized(sessions) {
      val list = sessions.getOrPut(model.name) { ArrayList() }
      list.add(AgentMsg("user", instruction.take(400)))
      list.add(AgentMsg("assistant", summary))
      while (list.size > MAX_SESSION_MESSAGES) list.removeAt(0)
    }
  }
}
