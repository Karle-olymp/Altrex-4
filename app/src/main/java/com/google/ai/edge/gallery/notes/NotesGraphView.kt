package com.google.ai.edge.gallery.notes

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.common.BrandCyan
import com.google.ai.edge.gallery.ui.common.BrandGreen
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Colours of the graph, for the night (brand) or day theme. */
@Stable
private class GraphPalette(val dark: Boolean) {
  val bgCenter = if (dark) Color(0xFF12222C) else Color(0xFFFBFCFD)
  val bgEdge = if (dark) Color(0xFF0C151B) else Color(0xFFEDF2F5)
  /** Brand cyan and green first (the two biggest groups), then quiet companions. */
  val groups =
    if (dark) {
      listOf(BrandCyan, BrandGreen, Color(0xFF8DB8F2), Color(0xFFB3A6F2), Color(0xFFE6C77F), Color(0xFF9AAAB5))
    } else {
      listOf(Color(0xFF0F8A8F), Color(0xFF23966A), Color(0xFF3D72C2), Color(0xFF6F5FC8), Color(0xFFA9822A), Color(0xFF6A7A86))
    }
  val untagged = if (dark) Color(0xFF64737E) else Color(0xFFA3AEB6)
  val edge = if (dark) Color(0xFFA9C2CF) else Color(0xFF51626E)
  val label = if (dark) Color(0xFFDDE8F0) else Color(0xFF1A2832)
  val ring = if (dark) Color.White else Color(0xFF0B1620)
  val accent = if (dark) BrandCyan else Color(0xFF0F8A8F)

  fun colorOf(group: Int): Color = if (group < 0) untagged else groups[min(group, groups.size - 1)]
}

/** True when the user asked the system for less motion (animations off). */
@Composable
fun rememberReducedMotion(): Boolean {
  val context = LocalContext.current
  return remember {
    try {
      Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) {
      false
    }
  }
}

/** What the graph view keeps between frames, read by the drawing code (no allocation there). */
private class GraphViewState {
  /** Positions shown (x0, y0, x1, y1...), copied from the layout on the main thread. */
  var shown = FloatArray(0)
  var back = FloatArray(0)
  var frame by mutableIntStateOf(0)
  var camX by mutableFloatStateOf(0f)
  var camY by mutableFloatStateOf(0f)
  var zoom by mutableFloatStateOf(1f)
  /** Zoom that frames the whole graph: labels are revealed relative to it. */
  var fitZoom = 1f
  var selected by mutableIntStateOf(-1)
  var userMoved = false
  var viewW = 0f
  var viewH = 0f
  /** Ids of the nodes in [shown], to start the next layout from where the notes are. */
  var ids: List<String> = emptyList()
  var flingJob: Job? = null
}

/**
 * [ALTREX] The notes as a constellation: each note is a glowing point sized by its links and
 * coloured by its main tag, links are thin curved strokes. The force layout runs off the main
 * thread and stops once settled; pan and pinch with inertia, double tap to re-centre, tap a note
 * to light it and its neighbours and see it below. A search box lights the matching notes.
 *
 * [layoutDispatcher] is where the layout is computed (tests pass an immediate one).
 */
@Composable
fun NotesGraphView(
  graph: NoteGraph,
  onOpenNote: (String) -> Unit,
  modifier: Modifier = Modifier,
  onRecord: (() -> Unit)? = null,
  initialSelectedId: String? = null,
  reducedMotion: Boolean = rememberReducedMotion(),
  layoutDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val palette = remember(dark) { GraphPalette(dark) }
  Box(
    modifier
      .fillMaxSize()
      .background(Brush.verticalGradient(listOf(palette.bgCenter, palette.bgEdge)))
  ) {
    if (graph.size == 0) {
      GraphEmptyState(palette, onRecord)
    } else {
      GraphCanvas(graph, palette, onOpenNote, initialSelectedId, reducedMotion, layoutDispatcher)
    }
  }
}

@Composable
private fun GraphCanvas(
  graph: NoteGraph,
  palette: GraphPalette,
  onOpenNote: (String) -> Unit,
  initialSelectedId: String?,
  reducedMotion: Boolean,
  layoutDispatcher: CoroutineDispatcher,
) {
  val density = LocalDensity.current.density
  val state = remember { GraphViewState() }
  val scope = rememberCoroutineScope()
  val touchSlop = LocalViewConfiguration.current.touchSlop
  var query by remember { mutableStateOf("") }

  // Notes matching the search (folded, in title, tags and summary).
  val matches =
    remember(graph, query) {
      val q = NoteText.fold(query)
      if (q.length < 2) null
      else
        BooleanArray(graph.size) { i ->
          val n = graph.notes[i]
          NoteText.fold(n.title).contains(q) || n.tags.any { NoteText.fold(it).contains(q) } ||
            NoteText.fold(n.summary).contains(q)
        }
    }
  val matchCount = matches?.count { it } ?: 0

  // Per-graph drawing resources: label layouts, glow sprites, drawing order.
  val textMeasurer = rememberTextMeasurer(cacheSize = 0)
  val labelStyle =
    MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
  val labels: Array<TextLayoutResult> =
    remember(graph, palette) {
      Array(graph.size) { i ->
        textMeasurer.measure(
          graph.notes[i].title,
          style = labelStyle.copy(color = palette.label),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          constraints = Constraints(maxWidth = (150 * density).toInt()),
        )
      }
    }
  val sprites = remember(palette) { (palette.groups + palette.untagged).map { glowSprite(it, palette.dark) } }
  // Labels of the best-linked notes first, so they win when labels would overlap.
  val order = remember(graph) { (0 until graph.size).sortedWith(compareByDescending<Int> { graph.degree[it] }.thenBy { it }).toIntArray() }
  val labelBoxes = remember(graph) { FloatArray(4 * min(graph.size, MAX_LABELS)) }
  val isNeighbour = remember(graph) { BooleanArray(graph.size) }

  LaunchedEffect(graph) {
    if (initialSelectedId != null) state.selected = graph.notes.indexOfFirst { it.id == initialSelectedId }
    else if (state.selected >= graph.size) state.selected = -1
  }

  // Layout: from the previous positions when the notes change (gentle re-warm), else cold.
  LaunchedEffect(graph, reducedMotion) {
    val n = graph.size
    val previous =
      if (state.shown.size == 2 * state.ids.size) {
        state.ids.withIndex().associate { (k, id) -> id to (state.shown[2 * k] to state.shown[2 * k + 1]) }
      } else {
        emptyMap()
      }
    val layout = ForceLayout(graph, initial = previous, startAlpha = if (previous.isNotEmpty()) 0.3f else 1f)
    state.back = FloatArray(2 * n)
    // First positions straight away (known notes where they were, new ones near their neighbours).
    state.shown = FloatArray(2 * n).also { layout.copyInto(it) }
    state.ids = graph.notes.map { it.id }
    if (reducedMotion) {
      withContext(layoutDispatcher) {
        layout.settle()
        layout.copyInto(state.back)
      }
      state.shown = state.back.copyOf()
      fitCamera(state, graph, density, animate = false)
      state.frame++
    } else {
      var fitFrames = 0
      while (!layout.isSettled || fitFrames < 30) {
        if (!layout.isSettled) {
          withContext(layoutDispatcher) {
            layout.step(STEPS_PER_FRAME)
            layout.copyInto(state.back)
          }
          System.arraycopy(state.back, 0, state.shown, 0, 2 * n)
        } else {
          fitFrames++
        }
        if (!state.userMoved) fitCamera(state, graph, density, animate = true)
        state.frame++
        withFrameNanos {}
      }
    }
  }

  fun toWorldX(px: Float) = state.camX + (px - state.viewW / 2f) / (state.zoom * density)

  fun toWorldY(py: Float) = state.camY + (py - state.viewH / 2f) / (state.zoom * density)

  Box(Modifier.fillMaxSize()) {
    androidx.compose.foundation.layout.Spacer(
      Modifier.fillMaxSize()
        .onSizeChanged {
          val first = state.viewW == 0f
          state.viewW = it.width.toFloat()
          state.viewH = it.height.toFloat()
          // The layout may be done before the size is known (reduced motion): frame it now.
          if (first && !state.userMoved) {
            fitCamera(state, graph, density, animate = false)
            if (state.selected >= 0) focusTarget(state, graph, density, state.selected)?.let { (x, y, z) ->
              state.camX = x
              state.camY = y
              state.zoom = z
            }
            state.frame++
          }
        }
        .semantics { contentDescription = "Graphe des notes : ${graph.size} notes, ${graph.edges.size} liens" }
        .pointerInput(graph) {
          detectTapGestures(
            onDoubleTap = {
              state.userMoved = false
              state.flingJob?.cancel()
              state.flingJob = scope.launch { animateFit(state, graph, density, reducedMotion) }
            },
            onTap = { p ->
              val wx = toWorldX(p.x)
              val wy = toWorldY(p.y)
              var best = -1
              var bestD = Float.MAX_VALUE
              val slack = 16f / state.zoom
              for (i in 0 until graph.size) {
                val dx = state.shown[2 * i] - wx
                val dy = state.shown[2 * i + 1] - wy
                val d = sqrt(dx * dx + dy * dy)
                if (d < graph.radius[i] + slack && d < bestD) {
                  best = i
                  bestD = d
                }
              }
              state.selected = if (best == state.selected) -1 else best
              // Bring the chosen note above the card, a little closer.
              if (state.selected >= 0) {
                state.flingJob?.cancel()
                state.flingJob = scope.launch { animateFocus(state, graph, density, state.selected, reducedMotion) }
              }
            },
          )
        }
        .pointerInput(graph) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            state.flingJob?.cancel()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var moved = false
            var travelled = 0f
            var zoomTotal = 1f
            var multiTouch = false
            while (true) {
              val event = awaitPointerEvent()
              if (event.changes.none { it.pressed }) break
              if (event.changes.count { it.pressed } > 1) multiTouch = true
              val zoomChange = event.calculateZoom()
              val pan = event.calculatePan()
              val centroid = event.calculateCentroid(useCurrent = true)
              if (!moved) {
                travelled += pan.getDistance()
                zoomTotal *= zoomChange
                if (travelled > touchSlop || abs(zoomTotal - 1f) > 0.04f) moved = true
              }
              if (moved) {
                state.userMoved = true
                // Pan, then zoom around the fingers (the point under them stays put).
                state.camX -= pan.x / (state.zoom * density)
                state.camY -= pan.y / (state.zoom * density)
                if (zoomChange != 1f && centroid != Offset.Unspecified) {
                  val wx = toWorldX(centroid.x)
                  val wy = toWorldY(centroid.y)
                  state.zoom = (state.zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                  state.camX = wx - (centroid.x - state.viewW / 2f) / (state.zoom * density)
                  state.camY = wy - (centroid.y - state.viewH / 2f) / (state.zoom * density)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
              }
              event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
            }
            if (moved && !multiTouch && !reducedMotion) {
              val v = tracker.calculateVelocity()
              state.flingJob = scope.launch { fling(state, density, v.x, v.y) }
            }
          }
        }
        .drawWithCache {
          val edgeNormal = Path()
          val edgeStrong = Path()
          val edgeLit = Path()
          val thin = Stroke(width = 0.9f * density, cap = StrokeCap.Round)
          val medium = Stroke(width = 1.3f * density, cap = StrokeCap.Round)
          val litStroke = Stroke(width = 1.6f * density, cap = StrokeCap.Round)
          val ring = Stroke(width = 1f * density)
          val selRing = Stroke(width = 1.6f * density)
          // A soft halo of the background behind labels, so they stay readable over links.
          val labelHalo = Shadow(color = palette.bgEdge.copy(alpha = 0.9f), offset = Offset.Zero, blurRadius = 6f * density)
          val halo =
            Brush.radialGradient(
              listOf(palette.accent.copy(alpha = if (palette.dark) 0.07f else 0.05f), Color.Transparent),
              center = Offset(size.width / 2f, size.height * 0.45f),
              radius = max(size.width, size.height) * 0.7f,
            )
          onDrawBehind {
            // Read so that a new layout frame or camera move redraws (and nothing else does).
            state.frame
            val zoom = state.zoom
            val scale = zoom * density
            val cx = state.camX
            val cy = state.camY
            val hw = size.width / 2f
            val hh = size.height / 2f
            val pos = state.shown
            if (pos.size < 2 * graph.size) return@onDrawBehind
            val sel = state.selected.takeIf { it in 0 until graph.size } ?: -1
            val m = matches

            drawRect(halo)

            // Neighbours of the selected note.
            java.util.Arrays.fill(isNeighbour, false)
            if (sel >= 0) for (j in graph.neighbours[sel]) isNeighbour[j] = true

            // Links: three batched paths (normal, strong, lit), slightly curved.
            edgeNormal.reset()
            edgeStrong.reset()
            edgeLit.reset()
            val margin = 40f * density
            for (e in graph.edges) {
              val x1 = hw + (pos[2 * e.a] - cx) * scale
              val y1 = hh + (pos[2 * e.a + 1] - cy) * scale
              val x2 = hw + (pos[2 * e.b] - cx) * scale
              val y2 = hh + (pos[2 * e.b + 1] - cy) * scale
              if ((x1 < -margin && x2 < -margin) || (x1 > size.width + margin && x2 > size.width + margin)) continue
              if ((y1 < -margin && y2 < -margin) || (y1 > size.height + margin && y2 > size.height + margin)) continue
              // Control point off the middle, perpendicular, always on the same side.
              val mx = (x1 + x2) / 2f - (y2 - y1) * CURVE
              val my = (y1 + y2) / 2f + (x2 - x1) * CURVE
              val path =
                when {
                  sel >= 0 && (e.a == sel || e.b == sel) -> edgeLit
                  e.weight >= 0.5f -> edgeStrong
                  else -> edgeNormal
                }
              path.moveTo(x1, y1)
              path.quadraticTo(mx, my, x2, y2)
            }
            val dimmed = sel >= 0 || m != null
            val edgeAlpha = if (dimmed) 0.35f else 1f
            drawPath(edgeNormal, palette.edge, alpha = (if (palette.dark) 0.13f else 0.16f) * edgeAlpha, style = thin)
            drawPath(edgeStrong, palette.edge, alpha = (if (palette.dark) 0.26f else 0.3f) * edgeAlpha, style = medium)
            drawPath(edgeLit, palette.accent, alpha = 0.8f, style = litStroke)

            // Nodes: glow, core, fine ring.
            for (i in 0 until graph.size) {
              val sx = hw + (pos[2 * i] - cx) * scale
              val sy = hh + (pos[2 * i + 1] - cy) * scale
              val r = graph.radius[i] * scale.coerceAtMost(density * 1.6f).coerceAtLeast(density * 0.55f)
              if (sx < -r * 4 || sx > size.width + r * 4 || sy < -r * 4 || sy > size.height + r * 4) continue
              val lit = i == sel || isNeighbour[i] || (m != null && m[i])
              val a = if (!dimmed || lit) 1f else 0.2f
              val colorIndex = if (graph.group[i] < 0) sprites.size - 1 else min(graph.group[i], sprites.size - 2)
              val g = (r * 3.4f).toInt().coerceAtLeast(2)
              drawImage(
                sprites[colorIndex],
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(SPRITE, SPRITE),
                dstOffset = IntOffset(sx.toInt() - g, sy.toInt() - g),
                dstSize = IntSize(2 * g, 2 * g),
                alpha = a * (if (palette.dark) 0.75f else 0.45f),
              )
              val c = palette.colorOf(graph.group[i])
              drawCircle(c, radius = r, center = Offset(sx, sy), alpha = a)
              drawCircle(palette.ring, radius = r, center = Offset(sx, sy), alpha = a * 0.22f, style = ring)
              if (i == sel || (m != null && m[i])) {
                drawCircle(palette.accent, radius = r + 4.5f * density, center = Offset(sx, sy), alpha = 0.9f, style = selRing)
              }
            }

            // Labels: revealed as one zooms in, the best-linked notes first (at the framing zoom,
            // about the top quarter), never on top of each other nor of a lit note.
            var boxes = 0
            val maxBoxes = labelBoxes.size / 4
            val few = graph.size <= 12
            val reveal = zoom / state.fitZoom.coerceAtLeast(0.01f)
            if (sel >= 0) {
              for (i in 0 until graph.size) {
                if ((i != sel && !isNeighbour[i]) || boxes >= maxBoxes) continue
                val sx = hw + (pos[2 * i] - cx) * scale
                val sy = hh + (pos[2 * i + 1] - cy) * scale
                val r = graph.radius[i] * scale.coerceAtMost(density * 1.6f).coerceAtLeast(density * 0.55f) + 3f * density
                labelBoxes[4 * boxes] = sx - r
                labelBoxes[4 * boxes + 1] = sy - r
                labelBoxes[4 * boxes + 2] = sx + r
                labelBoxes[4 * boxes + 3] = sy + r
                boxes++
              }
            }
            for (k in order.indices) {
              val i = order[k]
              val lit = i == sel || isNeighbour[i] || (m != null && m[i])
              val threshold = 0.8f + 1.6f * k / graph.size
              var a = if (few || lit) 1f else smoothstep(threshold - 0.2f, threshold + 0.1f, reveal)
              if (dimmed && !lit) a *= 0.2f
              if (a < 0.03f) continue
              val layout = labels[i]
              val w = layout.size.width.toFloat()
              val h = layout.size.height.toFloat()
              val sx = hw + (pos[2 * i] - cx) * scale
              val sy = hh + (pos[2 * i + 1] - cy) * scale
              val r = graph.radius[i] * scale.coerceAtMost(density * 1.6f).coerceAtLeast(density * 0.55f)
              val left = sx - w / 2f
              val top = sy + r + 4f * density
              if (left > size.width || left + w < 0 || top > size.height || top + h < 0) continue
              var overlaps = false
              for (b in 0 until boxes) {
                if (left < labelBoxes[4 * b + 2] && left + w > labelBoxes[4 * b] &&
                    top < labelBoxes[4 * b + 3] && top + h > labelBoxes[4 * b + 1]) {
                  overlaps = true
                  break
                }
              }
              if (overlaps && i != sel) continue
              if (boxes < maxBoxes) {
                labelBoxes[4 * boxes] = left - 3f * density
                labelBoxes[4 * boxes + 1] = top
                labelBoxes[4 * boxes + 2] = left + w + 3f * density
                labelBoxes[4 * boxes + 3] = top + h
                boxes++
              }
              drawText(layout, topLeft = Offset(left, top), alpha = a, shadow = labelHalo)
            }
          }
        }
    )

    // Search, top.
    GraphSearchBox(
      query = query,
      onQuery = { query = it },
      matchCount = if (matches != null) matchCount else null,
      palette = palette,
      modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 12.dp),
    )

    // Legend of the colours, bottom left, when nothing is selected.
    val sel = state.selected
    if (sel < 0 && graph.groupTags.isNotEmpty()) {
      GraphLegend(graph, palette, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 96.dp, bottom = 16.dp))
    }
    if (graph.edges.isEmpty() && sel < 0) {
      Text(
        "Les liens apparaissent quand des notes partagent des tags ou se citent avec [[Titre]].",
        style = MaterialTheme.typography.bodySmall,
        color = palette.label.copy(alpha = 0.55f),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 32.dp, end = 32.dp, bottom = 72.dp),
      )
    }

    // The selected note, bottom.
    AnimatedVisibility(
      visible = sel in 0 until graph.size,
      enter = if (reducedMotion) fadeIn(tween(0)) else slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(200)),
      exit = if (reducedMotion) fadeOut(tween(0)) else slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(160)),
      modifier = Modifier.align(Alignment.BottomCenter),
    ) {
      val i = state.selected.coerceIn(0, graph.size - 1)
      SelectedNoteCard(
        note = graph.notes[i],
        links = graph.degree[i],
        color = palette.colorOf(graph.group[i]),
        onOpen = { onOpenNote(graph.notes[i].id) },
        onClose = { state.selected = -1 },
      )
    }
  }

  DisposableEffect(Unit) { onDispose { state.flingJob?.cancel() } }
}

@Composable
private fun GraphSearchBox(
  query: String,
  onQuery: (String) -> Unit,
  matchCount: Int?,
  palette: GraphPalette,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(22.dp)
  Row(
    modifier
      .fillMaxWidth()
      .height(44.dp)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surface.copy(alpha = if (palette.dark) 0.72f else 0.9f))
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
      .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(10.dp))
    Box(Modifier.weight(1f)) {
      if (query.isEmpty()) {
        Text("Chercher dans le graphe", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
      }
      BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(palette.accent),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (matchCount != null) {
      Text(
        if (matchCount == 0) "aucune" else if (matchCount == 1) "1 note" else "$matchCount notes",
        style = MaterialTheme.typography.labelMedium,
        color = palette.accent,
      )
    }
    if (query.isNotEmpty()) {
      IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Outlined.Close, contentDescription = "Effacer la recherche", modifier = Modifier.size(16.dp))
      }
    }
  }
}

@Composable
private fun GraphLegend(graph: NoteGraph, palette: GraphPalette, modifier: Modifier = Modifier) {
  Row(
    modifier
      .clip(RoundedCornerShape(14.dp))
      .background(MaterialTheme.colorScheme.surface.copy(alpha = if (palette.dark) 0.55f else 0.8f))
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    val rows = graph.groupTags.mapIndexed { g, tag -> g to tag }.toMutableList()
    if (graph.group.any { it >= NoteGraph.COLOURED_GROUPS }) rows.add(NoteGraph.COLOURED_GROUPS to "autres tags")
    if (graph.group.any { it < 0 }) rows.add(-1 to "sans tag")
    for ((g, tag) in rows) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(palette.colorOf(g)))
        Spacer(Modifier.width(6.dp))
        Text(tag, style = MaterialTheme.typography.labelSmall, color = palette.label.copy(alpha = 0.7f))
      }
    }
  }
}

@Composable
private fun SelectedNoteCard(note: VoiceNote, links: Int, color: Color, onOpen: () -> Unit, onClose: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 2.dp,
    shadowElevation = 12.dp,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
  ) {
    Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(
          note.title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Fermer") }
      }
      Text(
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.createdAt)) +
          " · " + (if (links == 0) "aucun lien" else if (links == 1) "1 lien" else "$links liens"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 2.dp),
      )
      val summary = note.summary.ifBlank { NoteText.heuristicSummary(note.text) }
      if (summary.isNotBlank()) {
        Text(
          summary,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 10.dp, end = 8.dp),
        )
      }
      Row(Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          for (t in note.tags.take(4)) TagPill(t)
        }
        Spacer(Modifier.width(8.dp))
        Button(
          onClick = onOpen,
          colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF0C151B)),
        ) {
          Text("Ouvrir", fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

/** A small tag, quiet. */
@Composable
fun TagPill(tag: String, modifier: Modifier = Modifier) {
  Text(
    "#$tag",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    modifier =
      modifier
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        .padding(horizontal = 8.dp, vertical = 3.dp),
  )
}

@Composable
private fun GraphEmptyState(palette: GraphPalette, onRecord: (() -> Unit)?) {
  Column(
    Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    // A quiet constellation, drawn once.
    Canvas(Modifier.size(width = 220.dp, height = 140.dp)) {
      val pts =
        listOf(0.12f to 0.62f, 0.3f to 0.3f, 0.48f to 0.55f, 0.62f to 0.2f, 0.78f to 0.48f, 0.9f to 0.78f, 0.4f to 0.85f)
          .map { (x, y) -> Offset(x * size.width, y * size.height) }
      val links = listOf(0 to 1, 1 to 2, 2 to 3, 2 to 4, 4 to 5, 2 to 6, 0 to 6)
      for ((a, b) in links) drawLine(palette.edge, pts[a], pts[b], strokeWidth = 1.dp.toPx(), alpha = 0.22f)
      for ((k, p) in pts.withIndex()) {
        val c = palette.colorOf(if (k % 3 == 2) -1 else k % 2)
        drawCircle(c, radius = (if (k == 2) 7 else 4).dp.toPx() * 2.4f, center = p, alpha = 0.12f)
        drawCircle(c, radius = (if (k == 2) 7 else 4).dp.toPx(), center = p, alpha = 0.9f)
      }
    }
    Spacer(Modifier.height(28.dp))
    Text("Ton graphe apparaîtra ici", style = MaterialTheme.typography.titleLarge, color = palette.label)
    Spacer(Modifier.height(8.dp))
    Text(
      "Chaque note vocale devient un point. Les notes qui partagent des tags ou se citent se relient.",
      style = MaterialTheme.typography.bodyMedium,
      color = palette.label.copy(alpha = 0.65f),
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    if (onRecord != null) {
      Spacer(Modifier.height(24.dp))
      FilledTonalButton(onClick = onRecord) {
        Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Enregistrer une note")
      }
    }
  }
}

// --- Camera -------------------------------------------------------------------------------------

/** Centre and zoom that frame every note (target), eased towards it when [animate]. */
private fun fitCamera(state: GraphViewState, graph: NoteGraph, density: Float, animate: Boolean) {
  val pos = state.shown
  if (graph.size == 0 || pos.size < 2 * graph.size || state.viewW <= 0f || state.viewH <= 0f) return
  var minX = Float.MAX_VALUE
  var minY = Float.MAX_VALUE
  var maxX = -Float.MAX_VALUE
  var maxY = -Float.MAX_VALUE
  for (i in 0 until graph.size) {
    val r = graph.radius[i] + 24f
    minX = min(minX, pos[2 * i] - r)
    maxX = max(maxX, pos[2 * i] + r)
    minY = min(minY, pos[2 * i + 1] - r)
    maxY = max(maxY, pos[2 * i + 1] + r)
  }
  val w = max(maxX - minX, 1f)
  val h = max(maxY - minY, 1f)
  // Room for the search box above and the legend / card below.
  val targetZoom =
    min(state.viewW * 0.88f / (w * density), state.viewH * 0.74f / (h * density)).coerceIn(MIN_ZOOM, 1.6f)
  val tx = (minX + maxX) / 2f
  val ty = (minY + maxY) / 2f
  state.fitZoom = targetZoom
  if (!animate) {
    state.camX = tx
    state.camY = ty
    state.zoom = targetZoom
  } else {
    val k = 0.12f
    state.camX += (tx - state.camX) * k
    state.camY += (ty - state.camY) * k
    state.zoom += (targetZoom - state.zoom) * k
  }
}

private suspend fun animateFit(state: GraphViewState, graph: NoteGraph, density: Float, reducedMotion: Boolean) {
  if (reducedMotion) {
    fitCamera(state, graph, density, animate = false)
    return
  }
  repeat(36) {
    fitCamera(state, graph, density, animate = true)
    withFrameNanos {}
  }
  fitCamera(state, graph, density, animate = false)
}

/** Camera (x, y, zoom) that shows note [i] in the upper part of the view, with its neighbours. */
private fun focusTarget(state: GraphViewState, graph: NoteGraph, density: Float, i: Int): Triple<Float, Float, Float>? {
  if (i !in 0 until graph.size || state.viewH <= 0f || state.shown.size < 2 * graph.size) return null
  val zoom = max(state.zoom, min(state.fitZoom * 1.5f, 1.6f)).coerceIn(MIN_ZOOM, MAX_ZOOM)
  // The card covers about the lower third: centre the note in what is left.
  val shiftPx = state.viewH * 0.17f
  return Triple(state.shown[2 * i], state.shown[2 * i + 1] + shiftPx / (zoom * density), zoom)
}

private suspend fun animateFocus(state: GraphViewState, graph: NoteGraph, density: Float, i: Int, reducedMotion: Boolean) {
  val (tx, ty, tz) = focusTarget(state, graph, density, i) ?: return
  state.userMoved = true
  if (reducedMotion) {
    state.camX = tx
    state.camY = ty
    state.zoom = tz
    return
  }
  repeat(28) {
    val k = 0.16f
    state.camX += (tx - state.camX) * k
    state.camY += (ty - state.camY) * k
    state.zoom += (tz - state.zoom) * k
    withFrameNanos {}
  }
}

/** Inertia after a pan: the speed decays exponentially until the graph stops. */
private suspend fun fling(state: GraphViewState, density: Float, vx0: Float, vy0: Float) {
  var vx = vx0
  var vy = vy0
  var last = withFrameNanos { it }
  while (vx * vx + vy * vy > 400f) {
    val now = withFrameNanos { it }
    val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
    last = now
    state.camX -= vx * dt / (state.zoom * density)
    state.camY -= vy * dt / (state.zoom * density)
    val decay = exp(-4.5f * dt)
    vx *= decay
    vy *= decay
  }
}

// --- Drawing helpers ------------------------------------------------------------------------------

private const val SPRITE = 64
private const val MAX_LABELS = 160
private const val STEPS_PER_FRAME = 2
private const val CURVE = 0.1f
private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 4f

/** A soft round glow of [color], drawn once and stamped under each node. */
private fun glowSprite(color: Color, dark: Boolean): ImageBitmap {
  val img = ImageBitmap(SPRITE, SPRITE)
  val canvas = androidx.compose.ui.graphics.Canvas(img)
  val c = Offset(SPRITE / 2f, SPRITE / 2f)
  val paint =
    Paint().apply {
      shader =
        RadialGradientShader(
          center = c,
          radius = SPRITE / 2f,
          colors = listOf(color.copy(alpha = if (dark) 0.55f else 0.4f), color.copy(alpha = 0.16f), color.copy(alpha = 0f)),
          colorStops = listOf(0f, 0.38f, 1f),
        )
    }
  canvas.drawCircle(c, SPRITE / 2f, paint)
  return img
}

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
  val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
  return t * t * (3f - 2f * t)
}
