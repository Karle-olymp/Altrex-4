package com.google.ai.edge.gallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.QuietCompletion
import com.google.ai.edge.gallery.runtime.SharedModelSession
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nehuatl.llamacpp.LlamaHelper

/** What the panel is doing. */
sealed class PanelPhase {
  data object Idle : PanelPhase()

  data class Preparing(val label: String) : PanelPhase()

  data object Writing : PanelPhase()

  data object Ready : PanelPhase()

  data class Failed(val message: String) : PanelPhase()
}

/** One question of the panel and its answer (filled in while it is written). */
class PanelTurn(val label: String, val prompt: String) {
  var answer by mutableStateOf("")
}

/**
 * [ALTREX] The assistant panel's short conversation with the model already used by the chat (or
 * the one the chat would use, loaded here when needed). It stays outside the chat history.
 */
class AskAltrexPanelSession(
  private val context: Context,
  private val scope: CoroutineScope,
  private val modelManager: ModelManagerViewModel,
) {
  var phase by mutableStateOf<PanelPhase>(PanelPhase.Idle)
    private set

  val turns = mutableStateListOf<PanelTurn>()

  /** Name of the model that answers, for the header. */
  var modelName by mutableStateOf<String?>(null)
    private set

  private var model: Model? = null
  private var job: Job? = null

  val busy: Boolean
    get() = phase is PanelPhase.Preparing || phase == PanelPhase.Writing

  /** Shows which model will answer, without loading it. */
  fun peekModel() {
    scope.launch {
      modelManager.loadModelAllowlistIfNeeded()
      val ready = withTimeoutOrNull(15_000) { modelManager.uiState.first { it.tasks.isNotEmpty() } }
      if (ready != null) pickModel()?.let { (_, m) -> modelName = m.displayName.ifEmpty { m.name } }
    }
  }

  fun ask(label: String, prompt: String, argoraMode: String? = null) {
    if (busy) return
    val turn = PanelTurn(label = label, prompt = prompt)
    turns.add(turn)
    job =
      scope.launch {
        val m = prepareModel()
        if (m == null) {
          turns.remove(turn)
          return@launch
        }
        if (SharedModelSession.chatGenerating.value) {
          phase = PanelPhase.Preparing("Altrex termine une réponse dans le chat…")
          withTimeoutOrNull(120_000) { SharedModelSession.chatGenerating.first { !it } }
        }
        phase = PanelPhase.Writing
        val manifest = m.argoraManifest
        val mode = argoraMode?.takeIf { manifest?.modes?.containsKey(it) == true }
        val system =
          (manifest?.systemPromptFor(mode?.let { manifest.modes[it] }) ?: LlamaHelper.getDefaultSystemPrompt()) +
            "\n\nTu réponds dans un petit panneau ouvert par-dessus une autre application : " +
            "va droit au but, en quelques phrases ou une courte liste."
        val messages = buildList {
          add("system" to system)
          for (t in turns) {
            add("user" to t.prompt)
            if (t !== turn) add("assistant" to t.answer)
          }
        }
        val result = QuietCompletion.run(m, messages, argoraMode = mode) { partial -> turn.answer = partial }
        phase =
          result.fold(
            onSuccess = {
              turn.answer = it.trim()
              PanelPhase.Ready
            },
            onFailure = {
              if (turn.answer.isEmpty()) turns.remove(turn)
              PanelPhase.Failed(it.message ?: "La réponse a échoué.")
            },
          )
      }
  }

  fun stop() {
    job?.cancel()
    model?.let { if (phase == PanelPhase.Writing) QuietCompletion.stop(it) }
    phase = if (turns.any { it.answer.isNotEmpty() }) PanelPhase.Ready else PanelPhase.Idle
  }

  /** The model to use: the one loaded, else the selected one, else the first downloaded one. */
  private fun pickModel(): Pair<Task, Model>? {
    val state = modelManager.uiState.value
    val chat = state.tasks.find { it.id == BuiltInTaskId.LLM_CHAT } ?: return null
    // llama.cpp and LiteRT-LM models; AICore runs outside the app.
    fun usable(m: Model) = m.isLlm && m.runtimeType != RuntimeType.AICORE
    fun downloaded(m: Model) = state.modelDownloadStatus[m.name]?.status == ModelDownloadStatusType.SUCCEEDED
    val loaded = chat.models.firstOrNull { it.instance != null && usable(it) }
    val selected = chat.models.firstOrNull { it.name == state.selectedModel.name && usable(it) && downloaded(it) }
    val first = chat.models.firstOrNull { usable(it) && downloaded(it) }
    return (loaded ?: selected ?: first)?.let { chat to it }
  }

  /** Waits for the model list, then loads the model if the chat has not already done it. */
  private suspend fun prepareModel(): Model? {
    phase = PanelPhase.Preparing("Préparation…")
    modelManager.loadModelAllowlistIfNeeded()
    val listed = withTimeoutOrNull(30_000) { modelManager.uiState.first { it.tasks.isNotEmpty() } }
    val picked = if (listed != null) pickModel() else null
    if (picked == null) {
      phase = PanelPhase.Failed("Aucun modèle téléchargé. Ouvre Altrex pour en télécharger ou en importer un.")
      return null
    }
    val (task, m) = picked
    model = m
    modelName = m.displayName.ifEmpty { m.name }
    if (m.instance == null) {
      phase = PanelPhase.Preparing("Chargement de ${modelName}…")
      modelManager.initializeModel(context = context, task = task, model = m)
      val status =
        withTimeoutOrNull(180_000) {
          modelManager.uiState.first {
            val s = it.modelInitializationStatus[m.name]?.status
            (s == ModelInitializationStatusType.INITIALIZED && m.instance != null) ||
              s == ModelInitializationStatusType.ERROR
          }
        }
      val error = status?.modelInitializationStatus?.get(m.name)
      if (m.instance == null) {
        phase =
          PanelPhase.Failed(
            error?.error?.takeIf { it.isNotBlank() } ?: "Le modèle n'a pas pu être chargé."
          )
        return null
      }
    }
    return m
  }
}
