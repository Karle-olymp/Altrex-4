package com.google.ai.edge.gallery.notes

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Value of [VoiceNote.structuredBy] when the note was put in shape without a model. */
const val STRUCTURED_BY_HEURISTIC = "heuristique"

/**
 * [ALTREX] One voice note. [text] is what the user reads and edits (cleaned up by the model when
 * it was structured); [rawTranscript] is exactly what the recognizer heard, kept so the note can be
 * structured again. [structuredBy] is the model's name, [STRUCTURED_BY_HEURISTIC], or null when
 * the note was never structured.
 */
@Serializable
data class VoiceNote(
  val id: String,
  val createdAt: Long,
  val updatedAt: Long,
  val title: String,
  val text: String,
  val rawTranscript: String,
  val summary: String = "",
  val tags: List<String> = emptyList(),
  val structuredBy: String? = null,
)

/** A recording in progress, saved as it goes so that a crash or a kill does not lose it. */
@Serializable
data class NoteDraft(val startedAt: Long, val updatedAt: Long, val transcript: String)

/**
 * [ALTREX] The notes, one JSON file per note in [dir] (app-private storage), with an in-memory
 * index. Every write goes to a temporary file first, then is renamed over the old one, so a note
 * on disk is always either the old or the new version, never half written. A file that cannot be
 * read is skipped (and left in place) instead of breaking the whole list.
 *
 * Thread-safe: every access to the index and the files holds this object's lock. Note contents are
 * private: nothing here logs them.
 */
class NoteStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
  }
  private val index = LinkedHashMap<String, VoiceNote>()
  private val _notes = MutableStateFlow<List<VoiceNote>>(emptyList())

  /** All notes, newest first. */
  val notes: StateFlow<List<VoiceNote>> = _notes.asStateFlow()

  /** Files skipped by the last [load] because they could not be read. */
  @Volatile
  var skippedFiles: Int = 0
    private set

  private var loaded = false

  /** Reads every note from disk (once; later calls are no-ops unless [force]). */
  @Synchronized
  fun load(force: Boolean = false) {
    if (loaded && !force) return
    loaded = true
    index.clear()
    var skipped = 0
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(NOTE_SUFFIX) }.orEmpty()
    for (f in files) {
      val note =
        try {
          json.decodeFromString(VoiceNote.serializer(), f.readText())
        } catch (e: Exception) {
          null
        }
      if (note == null || note.id.isBlank() || fileFor(note.id).name != f.name) {
        skipped++
        continue
      }
      index[note.id] = note
    }
    skippedFiles = skipped
    publish()
  }

  @Synchronized fun get(id: String): VoiceNote? {
    load()
    return index[id]
  }

  /** Creates a note from a raw transcript (title and tags filled in by the heuristics). */
  @Synchronized
  fun create(rawTranscript: String, createdAt: Long = clock()): VoiceNote {
    load()
    val raw = rawTranscript.trim()
    val note =
      VoiceNote(
        id = newId(),
        createdAt = createdAt,
        updatedAt = clock(),
        title = NoteText.heuristicTitle(raw),
        text = NoteText.tidyTranscript(raw),
        rawTranscript = raw,
        summary = "",
        tags = emptyList(),
        structuredBy = null,
      )
    save(note)
    return note
  }

  /** Writes [note] (atomically) and updates the index. */
  @Synchronized
  fun save(note: VoiceNote) {
    require(isValidId(note.id)) { "Identifiant de note invalide." }
    dir.mkdirs()
    writeAtomically(fileFor(note.id), json.encodeToString(VoiceNote.serializer(), note))
    index[note.id] = note
    publish()
  }

  /** Applies [change] to the note [id] and saves it with a new [VoiceNote.updatedAt]. */
  @Synchronized
  fun update(id: String, change: (VoiceNote) -> VoiceNote): VoiceNote? {
    load()
    val old = index[id] ?: return null
    val updated = change(old).copy(id = old.id, createdAt = old.createdAt, updatedAt = clock())
    save(updated)
    return updated
  }

  @Synchronized
  fun delete(id: String): Boolean {
    load()
    if (!isValidId(id)) return false
    val existed = index.remove(id) != null
    val deleted = fileFor(id).delete()
    publish()
    return existed || deleted
  }

  // --- Draft of the recording in progress -------------------------------------------------------

  @Synchronized
  fun saveDraft(draft: NoteDraft) {
    dir.mkdirs()
    writeAtomically(File(dir, DRAFT_FILE), json.encodeToString(NoteDraft.serializer(), draft))
  }

  @Synchronized
  fun readDraft(): NoteDraft? {
    val f = File(dir, DRAFT_FILE)
    if (!f.isFile) return null
    return try {
      json.decodeFromString(NoteDraft.serializer(), f.readText()).takeIf { it.transcript.isNotBlank() }
    } catch (e: Exception) {
      null
    }
  }

  @Synchronized
  fun clearDraft() {
    File(dir, DRAFT_FILE).delete()
  }

  /** Turns the draft into a note and removes it, in that order (the text is never lost). */
  @Synchronized
  fun commitDraft(): VoiceNote? {
    val draft = readDraft() ?: return null
    val note = create(draft.transcript, createdAt = draft.startedAt)
    clearDraft()
    return note
  }

  // --- Internals ---------------------------------------------------------------------------------

  private fun publish() {
    _notes.value = index.values.sortedWith(compareByDescending<VoiceNote> { it.createdAt }.thenBy { it.id })
  }

  private fun fileFor(id: String) = File(dir, id + NOTE_SUFFIX)

  private fun writeAtomically(target: File, content: String) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(tmp).use { out ->
      out.write(content.toByteArray(Charsets.UTF_8))
      out.flush()
      // On disk before the rename, so a power loss cannot leave an empty file behind the name.
      out.fd.sync()
    }
    try {
      Files.move(
        tmp.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (e: AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  companion object {
    const val NOTE_SUFFIX = ".json"
    const val DRAFT_FILE = "draft.recording"

    private val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")

    fun isValidId(id: String) = ID_PATTERN.matches(id)

    fun newId(): String = UUID.randomUUID().toString()
  }
}
