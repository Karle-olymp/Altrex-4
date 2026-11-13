package com.google.ai.edge.gallery.notes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.ui.common.BrandCyan
import com.google.ai.edge.gallery.ui.common.BrandGreen
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val NOTES_ENTRY_ID = "altrex_voice_notes"

/** What the notes space shows. */
private sealed class NotesPage {
  data object Home : NotesPage()

  data object Recording : NotesPage()

  data class Note(val id: String) : NotesPage()
}

/**
 * [ALTREX] "Notes vocales": dictate an idea, the app transcribes it and keeps it as a note
 * (structured by the loaded model if wanted), shown as a list or as a graph of linked notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
  navigateUp: () -> Unit,
  modelManagerViewModel: ModelManagerViewModel?,
  viewModel: NotesViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val notes by viewModel.notes.collectAsState()
  val graph by viewModel.graph.collectAsState()
  val structuring by viewModel.structuring.collectAsState()
  val message by viewModel.messages.collectAsState()
  val draft by viewModel.recoveredDraft.collectAsState()
  val recording by viewModel.recorder.state.collectAsState()
  val availability by viewModel.recorder.availability.collectAsState()
  val reducedMotion = rememberReducedMotion()
  val snackbar = remember { SnackbarHostState() }
  // Read so that loading or unloading a model updates the "Structurer" card.
  val modelState = modelManagerViewModel?.uiState?.collectAsState()
  val currentModel = remember(modelState?.value) { NotesRepository.currentModel(modelManagerViewModel) }
  var autoStructure by remember { mutableStateOf(NotesRepository.isAutoStructure(context)) }

  var pageKey by rememberSaveable { mutableStateOf("home") }
  val page: NotesPage =
    when {
      pageKey == "record" -> NotesPage.Recording
      pageKey.startsWith("note:") -> NotesPage.Note(pageKey.removePrefix("note:"))
      else -> NotesPage.Home
    }
  var showGraph by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(showGraph) { if (showGraph) viewModel.startSemanticLinks() }

  LaunchedEffect(message) {
    message?.let {
      snackbar.showSnackbar(it)
      viewModel.consumeMessage()
    }
  }

  // Microphone permission, asked when the user wants to record.
  val permission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        pageKey = "record"
      } else {
        scope.launch { snackbar.showSnackbar("Le micro est nécessaire pour dicter une note.") }
      }
    }
  val record: () -> Unit = {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      pageKey = "record"
    } else {
      permission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  AnimatedContent(
    targetState = page,
    transitionSpec = { fadeIn(tween(if (reducedMotion) 0 else 180)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 120)) },
    contentKey = { it::class },
    label = "notesPage",
  ) { p ->
    when (p) {
      NotesPage.Home ->
        NotesHome(
          notes = notes,
          graph = graph,
          structuring = structuring,
          draft = draft,
          showGraph = showGraph,
          onShowGraph = { showGraph = it },
          onBack = navigateUp,
          onRecord = record,
          onOpen = { pageKey = "note:$it" },
          onDelete = { viewModel.delete(it) },
          onShare = { id -> notes.find { it.id == id }?.let { context.startActivity(NotesExport.shareIntent(it, notes)) } },
          onExport = { only -> scope.launch { snackbar.showSnackbar(viewModel.export(only)) } },
          autoStructure = autoStructure,
          onAutoStructure = {
            autoStructure = it
            NotesRepository.setAutoStructure(context, it)
          },
          onResumeDraft = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
              viewModel.resumeDraft()
              pageKey = "record"
            } else {
              viewModel.saveRecoveredDraft()
            }
          },
          onSaveDraft = { viewModel.saveRecoveredDraft() },
          onDiscardDraft = { viewModel.discardRecoveredDraft() },
          snackbar = snackbar,
          reducedMotion = reducedMotion,
        )
      NotesPage.Recording -> {
        RecordingPage(
          viewModel = viewModel,
          modelManager = modelManagerViewModel,
          state = recording,
          availability = availability,
          reducedMotion = reducedMotion,
          onDone = { id -> pageKey = if (id != null) "note:$id" else "home" },
          onCancel = { pageKey = "home" },
        )
      }
      is NotesPage.Note -> {
        val note = notes.find { it.id == p.id }
        if (note == null) {
          // Deleted, or not loaded yet: back to the list unless it shows up.
          LaunchedEffect(p.id) {
            delay(400)
            pageKey = "home"
          }
        } else {
          NoteEditor(
            note = note,
            graph = graph,
            structuringNow = note.id in structuring,
            model = currentModel,
            onBack = { pageKey = "home" },
            onChange = { change -> viewModel.update(note.id, change) },
            onStructure = { m -> viewModel.structure(note.id, m) },
            onDelete = {
              viewModel.delete(note.id)
              pageKey = "home"
            },
            onShare = { context.startActivity(NotesExport.shareIntent(note, notes)) },
            onExport = { scope.launch { snackbar.showSnackbar(viewModel.export(listOf(note.id))) } },
            onOpen = { pageKey = "note:$it" },
            snackbar = snackbar,
          )
        }
      }
    }
  }

  // Recording: back is handled by the page itself (it may need a confirmation).
  BackHandler(enabled = page is NotesPage.Note) { pageKey = "home" }
}

@Composable
private fun RecordingPage(
  viewModel: NotesViewModel,
  modelManager: ModelManagerViewModel?,
  state: RecordingState,
  availability: RecognizerAvailability,
  reducedMotion: Boolean,
  onDone: (String?) -> Unit,
  onCancel: () -> Unit,
) {
  val view = LocalView.current
  var confirmDiscard by remember { mutableStateOf(false) }
  // The screen stays on while dictating.
  DisposableEffect(Unit) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }
  LaunchedEffect(Unit) { if (!state.recording) viewModel.prepareRecording() }
  // In the background the microphone is not ours any more: pause (the text and draft are kept).
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer =
      androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.recorder.pause()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  // Starts by itself once on-device recognition is ready (the user already asked to record).
  LaunchedEffect(availability) {
    if (!viewModel.recorder.state.value.recording && availability is RecognizerAvailability.OnDevice) {
      viewModel.startRecording()
    }
  }
  val close = {
    if (state.fullText.isNotBlank()) confirmDiscard = true
    else {
      viewModel.discardRecording()
      onCancel()
    }
  }
  BackHandler { close() }
  RecordingContent(
    state = state,
    availability = availability,
    reducedMotion = reducedMotion,
    onToggle = {
      when {
        !state.recording -> viewModel.startRecording()
        state.paused -> viewModel.recorder.resume()
        else -> viewModel.recorder.pause()
      }
    },
    onFinish = { viewModel.finishRecording(modelManager) { id -> onDone(id) } },
    onDiscard = { confirmDiscard = true },
    onClose = close,
    onDownloadLanguage = { viewModel.recorder.downloadLanguage() },
    onUseSystemService = {
      viewModel.recorder.allowSystemService()
      viewModel.startRecording()
    },
    onRetryCheck = { viewModel.prepareRecording() },
  )
  if (confirmDiscard) {
    AlertDialog(
      onDismissRequest = { confirmDiscard = false },
      title = { Text("Abandonner cette note ?") },
      text = { Text("Le texte dicté sera perdu.") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmDiscard = false
            viewModel.discardRecording()
            onCancel()
          }
        ) {
          Text("Abandonner", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Continuer") } },
    )
  }
}

// --- Home: list or graph ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesHome(
  notes: List<VoiceNote>,
  graph: NoteGraph,
  structuring: Set<String>,
  draft: NoteDraft?,
  showGraph: Boolean,
  onShowGraph: (Boolean) -> Unit,
  onBack: () -> Unit,
  onRecord: () -> Unit,
  onOpen: (String) -> Unit,
  onDelete: (String) -> Unit,
  onShare: (String) -> Unit,
  onExport: (Collection<String>?) -> Unit,
  autoStructure: Boolean,
  onAutoStructure: (Boolean) -> Unit,
  onResumeDraft: () -> Unit,
  onSaveDraft: () -> Unit,
  onDiscardDraft: () -> Unit,
  snackbar: SnackbarHostState = remember { SnackbarHostState() },
  reducedMotion: Boolean = false,
  graphContent: (@Composable (Modifier) -> Unit)? = null,
) {
  var menu by remember { mutableStateOf(false) }
  var auto by remember { mutableStateOf(autoStructure) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Notes vocales") },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour") }
        },
        actions = {
          ViewToggle(showGraph = showGraph, onShowGraph = onShowGraph)
          Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Plus d'options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              DropdownMenuItem(
                text = { Text("Exporter toutes les notes (Markdown)") },
                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                enabled = notes.isNotEmpty(),
                onClick = {
                  menu = false
                  onExport(null)
                },
              )
              DropdownMenuItem(
                text = {
                  Column {
                    Text("Structurer automatiquement")
                    Text(
                      "Avec le modèle chargé, s'il est local",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                },
                trailingIcon = { Checkbox(checked = auto, onCheckedChange = null) },
                onClick = {
                  auto = !auto
                  onAutoStructure(auto)
                },
              )
            }
          }
        },
      )
    },
    floatingActionButton = { RecordFab(onRecord) },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      draft?.let { DraftBanner(it, onResumeDraft, onSaveDraft, onDiscardDraft) }
      if (showGraph) {
        if (graphContent != null) {
          graphContent(Modifier.weight(1f))
        } else {
          NotesGraphView(graph = graph, onOpenNote = onOpen, onRecord = onRecord, modifier = Modifier.weight(1f), reducedMotion = reducedMotion)
        }
      } else {
        NotesList(notes, structuring, onOpen, onDelete, onShare, onExport, onRecord, Modifier.weight(1f))
      }
    }
  }
}

/** List / graph switch: two icons in one quiet pill, the chosen one lit. */
@Composable
private fun ViewToggle(showGraph: Boolean, onShowGraph: (Boolean) -> Unit) {
  Row(
    Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(3.dp)
  ) {
    for ((graph, icon, label) in listOf(Triple(false, Icons.AutoMirrored.Outlined.ViewList, "Liste"), Triple(true, Icons.Outlined.Hub, "Graphe"))) {
      val selected = showGraph == graph
      Box(
        Modifier.size(width = 40.dp, height = 32.dp)
          .clip(RoundedCornerShape(9.dp))
          .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
          .clickable(onClickLabel = label) { onShowGraph(graph) },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          icon,
          contentDescription = label,
          modifier = Modifier.size(18.dp),
          tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
      }
    }
  }
}

/** The record button of the space, in the brand gradient. */
@Composable
private fun RecordFab(onRecord: () -> Unit) {
  val shape = RoundedCornerShape(20.dp)
  Row(
    Modifier.shadow(10.dp, shape, ambientColor = BrandCyan, spotColor = BrandCyan)
      .clip(shape)
      .background(Brush.linearGradient(listOf(BrandCyan, BrandGreen)))
      .clickable(onClickLabel = "Enregistrer une note", onClick = onRecord)
      .padding(horizontal = 22.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.Outlined.Mic, contentDescription = null, tint = Color(0xFF0C151B))
    Spacer(Modifier.width(10.dp))
    Text("Enregistrer", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF0C151B))
  }
}

@Composable
private fun DraftBanner(draft: NoteDraft, onResume: () -> Unit, onSave: () -> Unit, onDiscard: () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.tertiaryContainer,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Column(Modifier.padding(16.dp)) {
      Text("Un enregistrement interrompu a été retrouvé", style = MaterialTheme.typography.titleSmall)
      Text(
        draft.transcript.take(160) + if (draft.transcript.length > 160) "…" else "",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
      )
      Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDiscard) { Text("Supprimer") }
        TextButton(onClick = onResume) { Text("Reprendre") }
        TextButton(onClick = onSave) { Text("Garder comme note") }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesList(
  notes: List<VoiceNote>,
  structuring: Set<String>,
  onOpen: (String) -> Unit,
  onDelete: (String) -> Unit,
  onShare: (String) -> Unit,
  onExport: (Collection<String>?) -> Unit,
  onRecord: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var query by rememberSaveable { mutableStateOf("") }
  var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
  var toDelete by remember { mutableStateOf<VoiceNote?>(null) }

  if (notes.isEmpty()) {
    NotesEmptyState(onRecord, modifier)
    return
  }
  // Tags by how many notes carry them.
  val topTags =
    remember(notes) {
      notes.flatMap { n -> n.tags.distinct() }.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(14)
        .map { it.key }
    }
  val shown =
    remember(notes, query, tagFilter) {
      val q = NoteText.fold(query)
      notes.filter { n ->
        (tagFilter == null || n.tags.any { NoteText.fold(it) == NoteText.fold(tagFilter!!) }) &&
          (q.isEmpty() ||
            NoteText.fold(n.title).contains(q) || NoteText.fold(n.text).contains(q) ||
            NoteText.fold(n.summary).contains(q) || n.tags.any { NoteText.fold(it).contains(q) })
      }
    }

  LazyColumn(
    modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item(key = "search") {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Rechercher dans les notes") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
          if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Effacer") }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions.Default,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (topTags.isNotEmpty()) {
      item(key = "tags") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (t in topTags) {
            FilterChip(
              selected = tagFilter == t,
              onClick = { tagFilter = if (tagFilter == t) null else t },
              label = { Text("#$t") },
            )
          }
        }
      }
    }
    if (shown.isEmpty()) {
      item(key = "none") {
        Text(
          "Aucune note ne correspond.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 24.dp),
        )
      }
    }
    items(shown, key = { it.id }) { n ->
      NoteCard(
        note = n,
        structuring = n.id in structuring,
        onOpen = { onOpen(n.id) },
        onShare = { onShare(n.id) },
        onExport = { onExport(listOf(n.id)) },
        onDelete = { toDelete = n },
      )
    }
  }

  toDelete?.let { n ->
    AlertDialog(
      onDismissRequest = { toDelete = null },
      title = { Text("Supprimer la note ?") },
      text = { Text("« ${n.title} » sera supprimée de ce téléphone. Les exports déjà faits ne sont pas touchés.") },
      confirmButton = {
        TextButton(
          onClick = {
            onDelete(n.id)
            toDelete = null
          }
        ) {
          Text("Supprimer", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
    )
  }
}

@Composable
private fun NoteCard(
  note: VoiceNote,
  structuring: Boolean,
  onOpen: () -> Unit,
  onShare: () -> Unit,
  onExport: () -> Unit,
  onDelete: () -> Unit,
) {
  var menu by remember { mutableStateOf(false) }
  Card(
    onClick = onOpen,
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 4.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
          Text(
            note.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.createdAt)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
        Box {
          IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Actions de la note") }
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Partager") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; onShare() })
            DropdownMenuItem(text = { Text("Exporter (Markdown)") }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) }, onClick = { menu = false; onExport() })
            DropdownMenuItem(
              text = { Text("Supprimer", color = MaterialTheme.colorScheme.error) },
              leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
              onClick = { menu = false; onDelete() },
            )
          }
        }
      }
      val preview = note.summary.ifBlank { note.text }
      if (preview.isNotBlank()) {
        Text(
          preview,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 8.dp, end = 14.dp),
        )
      }
      if (note.tags.isNotEmpty() || structuring) {
        Row(Modifier.padding(top = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (structuring) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text("Structuration…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          for (t in note.tags.take(3)) TagPill(t)
        }
      }
    }
  }
}

@Composable
private fun NotesEmptyState(onRecord: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      Modifier.size(84.dp).clip(CircleShape).background(Brush.linearGradient(listOf(BrandCyan.copy(alpha = 0.22f), BrandGreen.copy(alpha = 0.22f)))),
      contentAlignment = Alignment.Center,
    ) {
      Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
    Spacer(Modifier.height(24.dp))
    Text("Aucune note pour l'instant", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
      "Appuie sur Enregistrer et parle : Altrex écoute, transcrit et range ton idée. Rien ne quitte le téléphone.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    FilledTonalButton(onClick = onRecord) {
      Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text("Enregistrer une note")
    }
  }
}

// --- One note --------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(
  note: VoiceNote,
  graph: NoteGraph,
  structuringNow: Boolean,
  model: StructuringModel?,
  onBack: () -> Unit,
  onChange: ((VoiceNote) -> VoiceNote) -> Unit,
  onStructure: (StructuringModel?) -> Unit,
  onDelete: () -> Unit,
  onShare: () -> Unit,
  onExport: () -> Unit,
  onOpen: (String) -> Unit,
  snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
  // Local copies while typing, saved shortly after the last keystroke.
  var title by remember(note.id, note.structuredBy, structuringNow) { mutableStateOf(note.title) }
  var summary by remember(note.id, note.structuredBy, structuringNow) { mutableStateOf(note.summary) }
  var text by remember(note.id, note.structuredBy, structuringNow) { mutableStateOf(note.text) }
  var newTag by remember { mutableStateOf("") }
  var confirmDelete by remember { mutableStateOf(false) }
  var confirmRemote by remember { mutableStateOf(false) }
  var showRaw by remember { mutableStateOf(false) }

  LaunchedEffect(title, summary, text) {
    if (title == note.title && summary == note.summary && text == note.text) return@LaunchedEffect
    delay(600)
    onChange { it.copy(title = title.ifBlank { it.title }, summary = summary, text = text) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {},
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour") } },
        actions = {
          IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = "Partager") }
          IconButton(onClick = onExport) { Icon(Icons.Outlined.FileDownload, contentDescription = "Exporter en Markdown") }
          IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Supprimer") }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      val plain =
        TextFieldDefaults.colors(
          focusedContainerColor = Color.Transparent,
          unfocusedContainerColor = Color.Transparent,
          disabledContainerColor = Color.Transparent,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
          disabledIndicatorColor = Color.Transparent,
        )
      TextField(
        value = title,
        onValueChange = { title = it },
        enabled = !structuringNow,
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        colors = plain,
        placeholder = { Text("Titre", style = MaterialTheme.typography.headlineSmall) },
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(note.createdAt)) +
          when (note.structuredBy) {
            null -> ""
            STRUCTURED_BY_HEURISTIC -> " · mise en forme simple"
            else -> " · structurée par ${note.structuredBy}"
          },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp),
      )

      StructureCard(
        structured = note.structuredBy != null && note.structuredBy != STRUCTURED_BY_HEURISTIC,
        structuringNow = structuringNow,
        model = model,
        onStructure = { if (model != null && !model.local) confirmRemote = true else onStructure(model) },
      )

      // Tags.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in note.tags) {
          InputChip(
            selected = false,
            onClick = {},
            label = { Text("#$t") },
            enabled = !structuringNow,
            trailingIcon = {
              Icon(
                Icons.Outlined.Close,
                contentDescription = "Retirer $t",
                modifier = Modifier.size(16.dp).clickable(enabled = !structuringNow) { onChange { n -> n.copy(tags = n.tags - t) } },
              )
            },
          )
        }
        OutlinedTextField(
          value = newTag,
          onValueChange = { newTag = it.take(32) },
          placeholder = { Text("+ tag") },
          singleLine = true,
          enabled = !structuringNow,
          shape = RoundedCornerShape(12.dp),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions =
            KeyboardActions(
              onDone = {
                val add = NoteText.normalizeTags(note.tags + newTag)
                if (add != note.tags) onChange { n -> n.copy(tags = add) }
                newTag = ""
              }
            ),
          modifier = Modifier.width(140.dp),
        )
      }

      OutlinedTextField(
        value = summary,
        onValueChange = { summary = it },
        enabled = !structuringNow,
        label = { Text("Résumé") },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        enabled = !structuringNow,
        label = { Text("Texte") },
        supportingText = { Text("Lie une autre note en écrivant [[son titre]].") },
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
      )

      // Linked notes.
      val index = graph.notes.indexOfFirst { it.id == note.id }
      if (index >= 0 && graph.neighbours[index].isNotEmpty()) {
        Text("Notes liées", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          for (j in graph.neighbours[index].take(8)) {
            val other = graph.notes[j]
            Row(
              Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(other.id) }
                .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(Icons.Outlined.Hub, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
              Spacer(Modifier.width(10.dp))
              Text(other.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }

      if (note.rawTranscript.isNotBlank() && note.rawTranscript.trim() != note.text.trim()) {
        TextButton(onClick = { showRaw = !showRaw }) {
          Text(if (showRaw) "Masquer la transcription brute" else "Voir la transcription brute")
        }
        if (showRaw) {
          Text(
            note.rawTranscript,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
          )
        }
      }
      Spacer(Modifier.height(32.dp))
    }
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Supprimer la note ?") },
      text = { Text("Elle sera supprimée de ce téléphone.") },
      confirmButton = {
        TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
  }
  if (confirmRemote && model != null) {
    AlertDialog(
      onDismissRequest = { confirmRemote = false },
      icon = { Icon(Icons.Outlined.CloudQueue, contentDescription = null) },
      title = { Text("Envoyer la note hors du téléphone ?") },
      text = {
        Text(
          "Le modèle chargé, ${model.label}, ne tourne pas sur le téléphone (${model.where}). " +
            "Pour structurer la note, son texte lui sera envoyé."
        )
      },
      confirmButton = { TextButton(onClick = { confirmRemote = false; onStructure(model) }) { Text("Envoyer") } },
      dismissButton = { TextButton(onClick = { confirmRemote = false }) { Text("Annuler") } },
    )
  }
}

@Composable
private fun StructureCard(structured: Boolean, structuringNow: Boolean, model: StructuringModel?, onStructure: () -> Unit) {
  Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      if (structuringNow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(12.dp))
          Text("Structuration par ${model?.label ?: "le modèle"}…", style = MaterialTheme.typography.bodyMedium)
        }
        return@Column
      }
      if (model != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            if (model.local) Icons.Outlined.Lock else Icons.Outlined.CloudQueue,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.width(6.dp))
          Text(model.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
          if (model.local) "Sur le téléphone : rien n'est envoyé." else "Hors du téléphone (${model.where}) : le texte sera envoyé.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 2.dp),
        )
      } else {
        Text(
          "Aucun modèle chargé. Charge un modèle dans Discuter pour structurer la note avec lui.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
        if (model != null) {
          FilledTonalButton(onClick = onStructure) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (structured) "Re-structurer" else "Structurer avec le modèle")
          }
        } else {
          OutlinedButton(onClick = onStructure) { Text("Mise en forme simple") }
        }
      }
    }
  }
}
