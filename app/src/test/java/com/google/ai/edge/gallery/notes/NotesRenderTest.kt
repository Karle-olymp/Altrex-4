package com.google.ai.edge.gallery.notes

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The voice notes screens drawn frame after frame (graph layout running, selection, recording,
 * list, editor): any crash in their drawing code fails here. With NOTES_PREVIEW_DIR set, some
 * frames are also written there as PNG, to look at them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w392dp-h820dp-xhdpi")
class NotesRenderTest {
  @get:Rule val compose = createComposeRule()

  @After
  fun resetTheme() {
    ThemeSettings.themeOverride.value = Theme.THEME_AUTO
  }

  private fun setContent(dark: Boolean = true, content: @Composable () -> Unit) {
    ThemeSettings.themeOverride.value = if (dark) Theme.THEME_DARK else Theme.THEME_LIGHT
    compose.mainClock.autoAdvance = false
    compose.setContent { GalleryTheme { Box(Modifier.fillMaxSize()) { content() } } }
  }

  /** Advances [frames] frames of 16 ms, drawing every [captureEvery]th for real. */
  private fun runFrames(frames: Int, captureEvery: Int = 10) {
    for (f in 1..frames) {
      compose.mainClock.advanceTimeBy(16)
      if (f % captureEvery == 0) {
        compose.waitForIdle()
        compose.onRoot().captureToImage()
      }
    }
    compose.waitForIdle()
  }

  private fun save(name: String) {
    val image = compose.onRoot().captureToImage()
    val dir = System.getenv("NOTES_PREVIEW_DIR")?.takeIf { it.isNotBlank() } ?: return
    File(dir).mkdirs()
    File(dir, "$name.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun graph(notes: List<VoiceNote>) = NoteGraph.build(notes)

  @Test
  fun graphEmpty() {
    setContent { NotesGraphView(graph(emptyList<VoiceNote>()), onOpenNote = {}, onRecord = {}, reducedMotion = false) }
    runFrames(20)
    save("graph-empty")
  }

  @Test
  fun graphOneNote() {
    setContent {
      NotesGraphView(
        graph(SampleNotes.notes.take(1)),
        onOpenNote = {},
        reducedMotion = false,
        layoutDispatcher = Dispatchers.Unconfined,
      )
    }
    runFrames(200, captureEvery = 50)
    save("graph-one")
  }

  @Test
  fun graphFortyNotesSettles() {
    setContent {
      NotesGraphView(graph(SampleNotes.notes), onOpenNote = {}, reducedMotion = false, layoutDispatcher = Dispatchers.Unconfined)
    }
    // Mid-layout frames, then the settled graph.
    runFrames(60, captureEvery = 15)
    save("graph-40-settling")
    runFrames(200, captureEvery = 50)
    save("graph-40-dark")
  }

  @Test
  fun graphFortyNotesLight() {
    setContent(dark = false) {
      NotesGraphView(graph(SampleNotes.notes), onOpenNote = {}, reducedMotion = true, layoutDispatcher = Dispatchers.Unconfined)
    }
    runFrames(30)
    save("graph-40-light")
  }

  @Test
  fun graphWithSelectedNote() {
    val notes = SampleNotes.notes
    val g = graph(notes)
    val hub = (0 until g.size).maxBy { g.degree[it] }
    setContent {
      NotesGraphView(
        g,
        onOpenNote = {},
        initialSelectedId = notes[hub].id,
        reducedMotion = true,
        layoutDispatcher = Dispatchers.Unconfined,
      )
    }
    runFrames(40)
    save("graph-40-selected")
  }

  @Test
  fun graphThreeHundredNotes() {
    setContent {
      NotesGraphView(
        graph(NoteGraphTest.sample(300)),
        onOpenNote = {},
        reducedMotion = false,
        layoutDispatcher = Dispatchers.Unconfined,
      )
    }
    runFrames(220, captureEvery = 55)
    save("graph-300")
  }

  @Test
  fun recordingListening() {
    setContent {
      RecordingContent(
        state =
          RecordingState(
            recording = true,
            committed =
              "Idée pour l'appli : quand je dicte une note, la relier automatiquement aux notes qui parlent du même projet,",
            partial = "et proposer un titre",
            level = 0.6f,
            elapsedMs = 83_000,
          ),
        availability = RecognizerAvailability.OnDevice,
        reducedMotion = false,
        onToggle = {},
        onFinish = {},
        onDiscard = {},
        onClose = {},
        onDownloadLanguage = {},
        onUseSystemService = {},
        onRetryCheck = {},
      )
    }
    runFrames(60)
    save("recording")
  }

  @Test
  fun recordingWithoutOnDeviceRecognition() {
    setContent {
      RecordingContent(
        state = RecordingState(),
        availability = RecognizerAvailability.OnDeviceUnavailable("La reconnaissance sur l'appareil ne prend pas en charge la langue « français ».", true),
        reducedMotion = true,
        onToggle = {},
        onFinish = {},
        onDiscard = {},
        onClose = {},
        onDownloadLanguage = {},
        onUseSystemService = {},
        onRetryCheck = {},
      )
    }
    runFrames(20)
    save("recording-unavailable")
  }

  @Test
  fun listAndEmptyList() {
    setContent { home(SampleNotes.notes, showGraph = false) }
    runFrames(20)
    save("list")
  }

  @Test
  fun listEmpty() {
    setContent { home(emptyList<VoiceNote>(), showGraph = false) }
    runFrames(20)
    save("list-empty")
  }

  @Test
  fun homeWithGraphAndDraftBanner() {
    setContent {
      home(
        SampleNotes.notes,
        showGraph = true,
        draft = NoteDraft(0, 0, "une idée dictée avant que l'appli ne soit fermée"),
      )
    }
    runFrames(30)
    save("home-graph")
  }

  @Test
  fun editor() {
    val notes = SampleNotes.notes
    setContent {
      NoteEditor(
        note = notes[3],
        graph = graph(notes),
        structuringNow = false,
        model = null,
        onBack = {},
        onChange = {},
        onStructure = {},
        onDelete = {},
        onShare = {},
        onExport = {},
        onOpen = {},
      )
    }
    runFrames(20)
    save("editor")
  }

  @Composable
  private fun home(notes: List<VoiceNote>, showGraph: Boolean, draft: NoteDraft? = null) {
    NotesHome(
      notes = notes,
      graph = graph(notes),
      structuring = setOf(notes.firstOrNull()?.id ?: ""),
      draft = draft,
      showGraph = showGraph,
      onShowGraph = {},
      onBack = {},
      onRecord = {},
      onOpen = {},
      onDelete = {},
      onShare = {},
      onExport = {},
      autoStructure = true,
      onAutoStructure = {},
      onResumeDraft = {},
      onSaveDraft = {},
      onDiscardDraft = {},
      reducedMotion = true,
      graphContent = { m ->
        NotesGraphView(graph(notes), onOpenNote = {}, modifier = m, reducedMotion = true, layoutDispatcher = Dispatchers.Unconfined)
      },
    )
  }
}

/** About forty notes as a user would dictate them, with tags and a few [[links]]. */
object SampleNotes {
  private val raw =
    listOf(
      "Chiffrer les sauvegardes du NAS" to listOf("sécurité", "sauvegarde", "chiffrement"),
      "Tester la restauration chaque mois" to listOf("sauvegarde", "procédure"),
      "Changer la clé API du dépôt" to listOf("sécurité", "secrets", "github"),
      "Idée : graphe des notes vocales" to listOf("altrex", "idée", "graphe"),
      "Relier les notes par le sens" to listOf("altrex", "embeddings", "graphe"),
      "Mode hors ligne pour la dictée" to listOf("altrex", "dictée", "vie privée"),
      "Audit des ports ouverts du routeur" to listOf("réseau", "sécurité", "audit"),
      "Segmenter le Wi-Fi invités" to listOf("réseau", "wi-fi"),
      "VPN WireGuard à la maison" to listOf("réseau", "vpn"),
      "Lire « The Phoenix Project »" to listOf("lecture", "devops"),
      "Notes sur Designing Data-Intensive Applications" to listOf("lecture", "architecture"),
      "Architecture du moteur d'inférence" to listOf("altrex", "architecture", "llama.cpp"),
      "Quantification Q5_K_M sur mobile" to listOf("llama.cpp", "mobile", "performance"),
      "Mesurer la batterie pendant l'inférence" to listOf("mobile", "performance", "batterie"),
      "Fine-tuning LoRA d'Argora" to listOf("argora", "entraînement", "lora"),
      "Dataset cybersécurité défensive" to listOf("argora", "dataset", "sécurité"),
      "Évaluer Argora contre le modèle de base" to listOf("argora", "évaluation"),
      "Recette du pain au levain" to listOf("cuisine", "pain"),
      "Courses pour le week-end" to listOf("cuisine", "courses"),
      "Programme de course à pied" to listOf("sport", "santé"),
      "Rendez-vous chez le dentiste" to listOf("santé", "agenda"),
      "Rotation des mots de passe" to listOf("sécurité", "secrets"),
      "Gestionnaire de mots de passe familial" to listOf("sécurité", "famille"),
      "Cadeau d'anniversaire pour Léa" to listOf("famille", "idée"),
      "Plan de vacances en Bretagne" to listOf("voyage", "famille"),
      "Liste de lecture de l'été" to listOf("lecture", "voyage"),
      "Corriger la fuite mémoire du chat" to listOf("altrex", "bug", "performance"),
      "Tests de rendu Robolectric" to listOf("altrex", "tests"),
      "Scanner les dépendances npm" to listOf("sécurité", "dépendances", "audit"),
      "Durcir la configuration SSH" to listOf("sécurité", "ssh", "durcissement"),
      "Journalisation centralisée" to listOf("réseau", "logs", "audit"),
      "Idée de podcast sur la vie privée" to listOf("idée", "vie privée"),
      "Comparer Obsidian et Logseq" to listOf("outils", "notes"),
      "Exporter les notes en Markdown" to listOf("altrex", "notes", "markdown"),
      "Raccourcis clavier du terminal" to listOf("outils", "terminal"),
      "Alpine Linux dans l'appli" to listOf("altrex", "terminal", "linux"),
      "Réparer le vélo" to listOf("maison"),
      "Peinture de la chambre" to listOf("maison", "famille"),
      "Méditation du matin" to listOf("santé"),
      "Une pensée sans tag",
    )

  val notes: List<VoiceNote> =
    raw.mapIndexed { i, item ->
      val (title, tags) = if (item is Pair<*, *>) (item.first as String) to (item.second as List<*>).map { it as String } else (item as String) to emptyList()
      val text =
        when (i) {
          3 -> "Afficher les notes comme un graphe, voir aussi [[Relier les notes par le sens]] et [[Exporter les notes en Markdown]]."
          1 -> "Voir [[Chiffrer les sauvegardes du NAS]]."
          16 -> "Reprendre les questions de [[Fine-tuning LoRA d'Argora]]."
          else -> "Note dictée : $title."
        }
      VoiceNote(
        id = "note-$i",
        createdAt = 1_760_000_000_000L + i * 3_600_000L,
        updatedAt = 1_760_000_000_000L + i * 3_600_000L,
        title = title,
        text = text,
        rawTranscript = text.lowercase(),
        summary = if (i % 3 == 0) "Résumé de la note « $title »." else "",
        tags = tags,
        structuredBy = if (i % 2 == 0) "Qwen3.5-2B" else STRUCTURED_BY_HEURISTIC,
      )
    }
}
