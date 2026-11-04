package com.google.ai.edge.gallery.notes

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * [ALTREX] Export of the notes as Markdown files that Obsidian reads as they are (front matter,
 * `[[wikilinks]]` between notes, one file per note named after its title). They go to the folder
 * the app already shares with the user (Android/media/<app>/Linux, seen as /root/partage in the
 * terminal), under "Notes": no storage permission is needed, and any file manager or Obsidian
 * (pointed at that folder) can read them. Existing files with the same name are replaced; nothing
 * else in the folder is touched.
 */
object NotesExport {
  fun folder(context: Context): File? {
    @Suppress("DEPRECATION")
    val media = context.externalMediaDirs.firstOrNull() ?: return null
    return File(media, "Linux/Notes")
  }

  /** The folder as the user finds it in a file manager. */
  fun folderLabel(context: Context): String = "Android/media/${context.packageName}/Linux/Notes"

  /**
   * Writes [only] (or every note when null) to the export folder. Returns the number of files
   * written, or null when the shared storage is not available.
   */
  fun export(context: Context, all: List<VoiceNote>, only: Collection<String>? = null): Int? {
    val dir = folder(context) ?: return null
    if (!dir.isDirectory && !dir.mkdirs()) return null
    val graph = NoteGraph.build(all)
    val names = NoteText.exportNames(all)
    var written = 0
    for (n in all) {
      if (only != null && n.id !in only) continue
      val md = NoteText.toMarkdown(n, names, NoteLinks.linkedIds(all, graph.edges, n.id))
      val target = File(dir, names.getValue(n.id) + ".md")
      val tmp = File(dir, ".${target.name}.tmp")
      try {
        tmp.writeText(md)
        if (!tmp.renameTo(target)) {
          target.delete()
          if (!tmp.renameTo(target)) {
            tmp.delete()
            continue
          }
        }
        written++
      } catch (e: Exception) {
        tmp.delete()
      }
    }
    return written
  }

  /** The share sheet with the note in Markdown, as text (the user picks where it goes). */
  fun shareIntent(note: VoiceNote, all: List<VoiceNote>): Intent {
    val graph = NoteGraph.build(all)
    val md = NoteText.toMarkdown(note, NoteText.exportNames(all), NoteLinks.linkedIds(all, graph.edges, note.id))
    val send =
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, note.title)
        putExtra(Intent.EXTRA_TEXT, md)
      }
    return Intent.createChooser(send, "Partager la note")
  }
}
