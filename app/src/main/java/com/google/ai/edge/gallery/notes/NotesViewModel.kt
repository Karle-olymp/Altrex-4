package com.google.ai.edge.gallery.notes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [ALTREX] The "Notes vocales" space: the notes, the recording in progress (with its draft on
 * disk), the graph, and the actions on a note. The notes themselves live in [NotesRepository].
 */
@HiltViewModel
class NotesViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
  private val store = NotesRepository.store(context)

  /** Draft writes, one at a time and in order. */
  private val draftDispatcher = Dispatchers.IO.limitedParallelism(1)

  val notes: StateFlow<List<VoiceNote>> = store.notes
  val structuring: StateFlow<Set<String>> = NotesRepository.structuring
  val messages: StateFlow<String?> = NotesRepository.messages

  private val _vectors = MutableStateFlow<Map<String, FloatArray>>(emptyMap())

  /** The graph of the notes, rebuilt off the main thread when they (or their vectors) change. */
  val graph: StateFlow<NoteGraph> =
    combine(store.notes, _vectors) { n, v -> NoteGraph.build(n, v) }
      .flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteGraph(emptyList(), emptyList()))

  private val _recoveredDraft = MutableStateFlow<NoteDraft?>(null)
  /** A recording interrupted by a crash or a kill, found on disk. */
  val recoveredDraft: StateFlow<NoteDraft?> = _recoveredDraft.asStateFlow()

  /** True while a semantic link computation runs (the "similar" links, optional). */
  private val _semanticAvailable = MutableStateFlow(false)
  val semanticAvailable: StateFlow<Boolean> = _semanticAvailable.asStateFlow()

  private var recordingStartedAt = 0L
  private var lastDraft = ""
  private var semanticJob: Job? = null

  val recorder: VoiceRecorder = VoiceRecorder(context) { text -> saveDraft(text) }

  init {
    viewModelScope.launch(Dispatchers.IO) {
      store.load()
      _recoveredDraft.value = store.readDraft()
    }
    // Words still being recognized go to the draft too, every couple of seconds.
    viewModelScope.launch {
      var lastSave = 0L
      recorder.state.collect { s ->
        if (s.recording && System.currentTimeMillis() - lastSave > 2_000) {
          lastSave = System.currentTimeMillis()
          saveDraft(s.fullText)
        }
      }
    }
  }

  // --- Recording ---------------------------------------------------------------------------------

  fun prepareRecording() {
    recorder.checkAvailability()
  }

  fun startRecording() {
    recordingStartedAt = System.currentTimeMillis()
    lastDraft = ""
    recorder.start()
  }

  /** Continues an interrupted recording from its draft. */
  fun resumeDraft() {
    val d = _recoveredDraft.value ?: return
    _recoveredDraft.value = null
    recordingStartedAt = d.startedAt
    lastDraft = d.transcript
    recorder.startWith(d.transcript)
  }

  private fun saveDraft(text: String) {
    if (text.isBlank() || text == lastDraft) return
    lastDraft = text
    val started = recordingStartedAt
    viewModelScope.launch(draftDispatcher) {
      try {
        store.saveDraft(NoteDraft(startedAt = started, updatedAt = System.currentTimeMillis(), transcript = text))
      } catch (_: Exception) {}
    }
  }

  /** Stops, saves the note at once, then structures it if wanted. [onSaved] gets its id (or null). */
  fun finishRecording(modelManager: ModelManagerViewModel?, onSaved: (String?) -> Unit) {
    val text = recorder.finish()
    val started = recordingStartedAt
    viewModelScope.launch {
      // Pending draft writes first, so the clean-up below is the last word.
      withContext(draftDispatcher) {}
      val note = NotesRepository.saveRecording(context, text, started, modelManager)
      onSaved(note?.id)
    }
  }

  /** Throws the recording away (the user confirmed). */
  fun discardRecording() {
    recorder.finish()
    lastDraft = ""
    viewModelScope.launch(draftDispatcher) { store.clearDraft() }
  }

  fun saveRecoveredDraft() {
    _recoveredDraft.value = null
    viewModelScope.launch(Dispatchers.IO) {
      store.commitDraft()?.let { NotesRepository.applyHeuristics(context, it.id) }
    }
  }

  fun discardRecoveredDraft() {
    _recoveredDraft.value = null
    viewModelScope.launch(draftDispatcher) { store.clearDraft() }
  }

  // --- Notes ---------------------------------------------------------------------------------------

  fun update(id: String, change: (VoiceNote) -> VoiceNote) {
    viewModelScope.launch(Dispatchers.IO) { store.update(id, change) }
  }

  fun delete(id: String) {
    viewModelScope.launch(Dispatchers.IO) { store.delete(id) }
  }

  fun structure(id: String, model: StructuringModel?) {
    if (model == null) {
      viewModelScope.launch(Dispatchers.IO) {
        NotesRepository.applyHeuristics(context, id)
      }
      return
    }
    NotesRepository.structure(context, id, model)
  }

  fun consumeMessage() = NotesRepository.consumeMessage()

  /** Writes the notes as Markdown in the shared folder. Returns a message for the user. */
  suspend fun export(only: Collection<String>?): String =
    withContext(Dispatchers.IO) {
      val count = NotesExport.export(context, notes.value, only)
      when {
        count == null -> "Le dossier partagé n'est pas disponible."
        count == 0 -> "Aucune note exportée."
        else -> (if (count == 1) "1 note exportée" else "$count notes exportées") + " dans ${NotesExport.folderLabel(context)}"
      }
    }

  // --- Similar links (optional) -------------------------------------------------------------------

  /** Computes the vectors of meaning when the local memory's model is there (graph shown). */
  fun startSemanticLinks() {
    val emb = NotesRepository.embeddings(context)
    if (semanticJob?.isActive == true) return
    semanticJob =
      viewModelScope.launch(Dispatchers.IO) {
        _semanticAvailable.value = emb.isAvailable()
        if (!emb.isAvailable()) return@launch
        val v = emb.vectorsFor(notes.value)
        _vectors.value = v
      }
  }

  override fun onCleared() {
    // A recording left behind keeps its draft: it is offered back next time.
    recorder.release()
    super.onCleared()
  }
}
