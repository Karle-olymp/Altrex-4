package com.google.ai.edge.gallery.notes

import android.content.Context
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.runtime.CloudApi
import com.google.ai.edge.gallery.runtime.QuietCompletion
import com.google.ai.edge.gallery.runtime.SharedModelSession
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The model that can structure notes right now, and whether the text would leave the phone. */
data class StructuringModel(val model: Model, val label: String, val local: Boolean, val where: String)

/**
 * [ALTREX] The voice notes of the app: the store (filesDir/notes, app-private, not backed up), the
 * structuring of a note by the loaded model, and the vectors of meaning. A process-wide object, so
 * a structuring started on one screen finishes even if the user leaves it.
 */
object NotesRepository {
  private const val PREFS = "altrex_notes"
  private const val KEY_AUTO = "auto_structure_local"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Volatile private var store: NoteStore? = null
  @Volatile private var embeddings: NoteEmbeddings? = null

  private val _structuring = MutableStateFlow<Set<String>>(emptySet())
  /** Ids of the notes being structured. */
  val structuring: StateFlow<Set<String>> = _structuring.asStateFlow()

  private val _messages = MutableStateFlow<String?>(null)
  /** Last thing to tell the user (in French), consumed by the screen. */
  val messages: StateFlow<String?> = _messages.asStateFlow()

  fun consumeMessage() {
    _messages.value = null
  }

  fun dir(context: Context) = File(context.applicationContext.filesDir, "notes")

  fun store(context: Context): NoteStore =
    store ?: synchronized(this) { store ?: NoteStore(dir(context)).also { store = it } }

  fun embeddings(context: Context): NoteEmbeddings =
    embeddings
      ?: synchronized(this) {
        embeddings ?: NoteEmbeddings(context.applicationContext, File(dir(context), "cache")).also { embeddings = it }
      }

  /** Structure new notes automatically, with a local model only (on by default). */
  fun isAutoStructure(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

  fun setAutoStructure(context: Context, on: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
  }

  /** The model loaded right now (the chat's), if it can write text. Never loads one. */
  fun currentModel(modelManager: ModelManagerViewModel?): StructuringModel? {
    val state = modelManager?.uiState?.value ?: return null
    val chat = state.tasks.find { it.id == BuiltInTaskId.LLM_CHAT }
    val candidates = chat?.models.orEmpty() + state.tasks.flatMap { it.models }
    val m =
      candidates.firstOrNull { it.instance != null && it.isLlm && it.runtimeType != RuntimeType.AICORE } ?: return null
    val label = m.displayName.ifEmpty { m.name }
    return when (m.runtimeType) {
      RuntimeType.REMOTE ->
        StructuringModel(
          m,
          label,
          local = false,
          where = if (CloudApi.isCloud(m.name)) "API cloud" else "Space Hugging Face",
        )
      else -> StructuringModel(m, label, local = true, where = "sur le téléphone")
    }
  }

  /**
   * Creates the note from what was dictated, right away (the text is safe on disk before anything
   * else), removes the draft, then structures it if wanted and possible. Returns the note.
   */
  suspend fun saveRecording(
    context: Context,
    transcript: String,
    startedAt: Long,
    modelManager: ModelManagerViewModel?,
  ): VoiceNote? =
    withContext(Dispatchers.IO) {
      val s = store(context)
      if (transcript.isBlank()) {
        s.clearDraft()
        return@withContext null
      }
      val note = s.create(transcript, createdAt = startedAt)
      s.clearDraft()
      val model = currentModel(modelManager)
      if (model != null && model.local && isAutoStructure(context)) {
        structure(context, note.id, model)
      } else {
        // No model, or one off the phone (asked explicitly only): the simple shaping, at once.
        applyHeuristics(context, note.id)
      }
      note
    }

  /** Title, summary and tags from the heuristics (when the note has none from a model). */
  fun applyHeuristics(context: Context, id: String) {
    store(context).update(id) { n ->
      val h = NoteStructuring.heuristic(n.rawTranscript.ifBlank { n.text })
      n.copy(
        title = h.title,
        summary = n.summary.ifBlank { h.summary },
        tags = n.tags.ifEmpty { h.tags },
        structuredBy = n.structuredBy ?: STRUCTURED_BY_HEURISTIC,
      )
    }
  }

  /**
   * Asks [model] to structure the note [id] in the background. With a model off the phone, the
   * caller must have asked the user first (the text is sent to it).
   */
  fun structure(context: Context, id: String, model: StructuringModel) {
    val app = context.applicationContext
    if (id in _structuring.value) return
    _structuring.update { it + id }
    scope.launch {
      try {
        val note = store(app).get(id) ?: return@launch
        val raw = note.rawTranscript.ifBlank { note.text }
        if (SharedModelSession.chatGenerating.value) {
          withTimeoutOrNull(120_000) { SharedModelSession.chatGenerating.first { !it } }
        }
        val result = QuietCompletion.run(model.model, NoteStructuring.messages(raw))
        val answer = result.getOrNull()
        val parsed = answer?.let { NoteStructuring.parse(it, raw) }
        if (parsed == null) {
          _messages.value =
            if (answer == null) "Le modèle n'a pas pu structurer la note : mise en forme simple appliquée."
            else "Réponse du modèle illisible : mise en forme simple appliquée."
          // A note already shaped (or edited) stays as it is; a fresh one gets the simple shaping.
          if (store(app).get(id)?.structuredBy == null) applyHeuristics(app, id)
        } else {
          store(app).update(id) { n ->
            n.copy(
              title = parsed.title,
              text = parsed.text,
              summary = parsed.summary,
              tags = parsed.tags,
              structuredBy = model.label,
            )
          }
        }
      } catch (e: Exception) {
        _messages.value = "La structuration a échoué : la note est gardée telle quelle."
      } finally {
        _structuring.update { it - id }
      }
    }
  }
}
