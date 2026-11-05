package com.google.ai.edge.gallery.notes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [ALTREX] The notes graph, ready to lay out and draw: one node per note (same order as [notes]),
 * its links, how many each node has, its size and its colour group.
 */
class NoteGraph(
  val notes: List<VoiceNote>,
  val edges: List<NoteEdge>,
) {
  val size: Int = notes.size
  val degree: IntArray = IntArray(size).also { d -> for (e in edges) { d[e.a]++; d[e.b]++ } }
  val neighbours: Array<IntArray> = NoteLinks.neighbours(size, edges)

  /** Radius of each node in world units (dp at zoom 1): grows with its number of links. */
  val radius: FloatArray = FloatArray(size) { i -> min(5f + 2.4f * sqrt(degree[i].toFloat()), 16f) }

  /** Colour group of each node: index in the palette (0 = the most common main tag), -1 = no tag. */
  val group: IntArray

  /** The tag each group stands for (index = group), most common first, as the user wrote it. */
  val groupTags: List<String>

  init {
    val (g, tags) = groups(notes)
    group = g
    groupTags = tags
  }

  companion object {
    /** How many tags get their own colour; the others share the last one. */
    const val COLOURED_GROUPS = 5

    fun build(notes: List<VoiceNote>, vectors: Map<String, FloatArray> = emptyMap()): NoteGraph =
      NoteGraph(notes, NoteLinks.compute(notes, vectors))

    /**
     * Main tag of each note: of its tags, the one found on the most notes (ties: the note's own
     * order). Groups are numbered by how many notes have that main tag; past [COLOURED_GROUPS] they
     * share the last colour.
     */
    fun groups(notes: List<VoiceNote>): Pair<IntArray, List<String>> {
      val df = HashMap<String, Int>()
      for (n in notes) for (t in n.tags.map(NoteText::fold).toSet()) df[t] = (df[t] ?: 0) + 1
      val main = notes.map { n -> n.tags.map(NoteText::fold).filter { it.isNotEmpty() }.maxByOrNull { df[it] ?: 0 } }
      val count = HashMap<String, Int>()
      for (m in main) if (m != null) count[m] = (count[m] ?: 0) + 1
      val ranked = count.keys.sortedWith(compareByDescending<String> { count.getValue(it) }.thenBy { it })
      val rank = HashMap<String, Int>()
      ranked.forEachIndexed { i, t -> rank[t] = min(i, COLOURED_GROUPS) }
      val g = IntArray(notes.size) { i -> main[i]?.let { rank[it] } ?: -1 }
      // Shown with its accents: the first spelling met.
      val display = HashMap<String, String>()
      for (n in notes) for (t in n.tags) display.putIfAbsent(NoteText.fold(t), t.trim().lowercase())
      return g to ranked.take(COLOURED_GROUPS).map { display[it] ?: it }
    }
  }
}

/**
 * [ALTREX] Force-directed layout of the notes graph (velocity Verlet, in the manner of d3-force):
 * links pull their notes to a rest length, every node repels every other (Barnes–Hut quadtree,
 * O(n log n)), and a weak gravity keeps loose notes near the centre. The "temperature" [alpha]
 * decays at each [step] until [isSettled]: the layout then stops, so it costs nothing at rest.
 *
 * Deterministic: the start positions come from a sunflower spiral in the order of the nodes (or
 * from [initial] positions, for a gentle re-warm when the notes change), never from a random draw.
 * No allocation per step. Not thread-safe: one thread steps it, others read a copy ([copyInto]).
 */
class ForceLayout(
  private val graph: NoteGraph,
  initial: Map<String, Pair<Float, Float>> = emptyMap(),
  startAlpha: Float = 1f,
) {
  private val n = graph.size
  val x = FloatArray(n)
  val y = FloatArray(n)
  private val vx = FloatArray(n)
  private val vy = FloatArray(n)
  private val charge = FloatArray(n) { i -> -(REPULSION + 9f * graph.radius[i]) }

  // Links: ends, rest length, strength and how the pull is shared between the two ends.
  private val la = IntArray(graph.edges.size)
  private val lb = IntArray(graph.edges.size)
  private val lDist = FloatArray(graph.edges.size)
  private val lStrength = FloatArray(graph.edges.size)
  private val lBias = FloatArray(graph.edges.size)

  var alpha: Float = startAlpha.coerceIn(ALPHA_MIN, 1f)
    private set

  val isSettled: Boolean
    get() = alpha < ALPHA_MIN || n == 0

  var steps: Int = 0
    private set

  init {
    val deg = graph.degree
    for ((k, e) in graph.edges.withIndex()) {
      la[k] = e.a
      lb[k] = e.b
      lDist[k] = 34f + 46f * (1f - e.weight) + graph.radius[e.a] + graph.radius[e.b]
      lStrength[k] = (0.25f + 0.75f * e.weight) / min(deg[e.a], deg[e.b]).coerceAtLeast(1)
      lBias[k] = deg[e.a].toFloat() / (deg[e.a] + deg[e.b]).coerceAtLeast(1)
    }
    // Known nodes keep their place; new ones start near their placed neighbours, else on the spiral.
    val placed = BooleanArray(n)
    for (i in 0 until n) {
      val p = initial[graph.notes[i].id] ?: continue
      if (p.first.isFinite() && p.second.isFinite()) {
        x[i] = p.first
        y[i] = p.second
        placed[i] = true
      }
    }
    for (i in 0 until n) {
      if (placed[i]) continue
      var sx = 0f
      var sy = 0f
      var c = 0
      for (j in graph.neighbours[i]) if (placed[j]) {
        sx += x[j]
        sy += y[j]
        c++
      }
      val r = SPIRAL_STEP * sqrt(0.5f + i)
      val a = i * GOLDEN_ANGLE
      if (c > 0) {
        x[i] = sx / c + 18f * cos(a)
        y[i] = sy / c + 18f * sin(a)
      } else {
        x[i] = r * cos(a)
        y[i] = r * sin(a)
      }
    }
  }

  /** Runs [count] steps (fewer if it settles). Returns true once settled. */
  fun step(count: Int = 1): Boolean {
    var k = 0
    while (k < count && !isSettled) {
      tick()
      k++
    }
    return isSettled
  }

  /** Steps until settled or [maxSteps], for a layout shown only once done (reduced motion, tests). */
  fun settle(maxSteps: Int = 1_000): Int {
    var k = 0
    while (!isSettled && k < maxSteps) {
      tick()
      k++
    }
    return k
  }

  /** Kinetic energy of the last step (mean squared speed), to check convergence. */
  fun energy(): Float {
    if (n == 0) return 0f
    var e = 0f
    for (i in 0 until n) e += vx[i] * vx[i] + vy[i] * vy[i]
    return e / n
  }

  /** Copies the positions into [out] as x0, y0, x1, y1... ([out] holds at least 2n floats). */
  fun copyInto(out: FloatArray) {
    for (i in 0 until n) {
      out[2 * i] = x[i]
      out[2 * i + 1] = y[i]
    }
  }

  /** Current positions by note id, to re-warm a new layout from this one. */
  fun positions(): Map<String, Pair<Float, Float>> = (0 until n).associate { graph.notes[it].id to (x[it] to y[it]) }

  private fun tick() {
    steps++
    alpha += (0f - alpha) * ALPHA_DECAY
    applyLinks()
    applyRepulsion()
    for (i in 0 until n) {
      // Weak gravity, so separate groups and loose notes stay in view; weaker vertically, so the
      // graph takes the shape of a phone held upright.
      vx[i] -= x[i] * GRAVITY_X * alpha
      vy[i] -= y[i] * GRAVITY_Y * alpha
      vx[i] *= 1f - VELOCITY_DECAY
      vy[i] *= 1f - VELOCITY_DECAY
      val s2 = vx[i] * vx[i] + vy[i] * vy[i]
      if (s2 > MAX_SPEED * MAX_SPEED) {
        val f = MAX_SPEED / sqrt(s2)
        vx[i] *= f
        vy[i] *= f
      }
      if (!vx[i].isFinite() || !vy[i].isFinite()) {
        vx[i] = 0f
        vy[i] = 0f
      }
      x[i] += vx[i]
      y[i] += vy[i]
    }
  }

  private fun applyLinks() {
    for (k in la.indices) {
      val a = la[k]
      val b = lb[k]
      var dx = x[b] + vx[b] - x[a] - vx[a]
      var dy = y[b] + vy[b] - y[a] - vy[a]
      if (dx == 0f && dy == 0f) {
        dx = jiggle(k)
        dy = jiggle(k + 1)
      }
      val l = sqrt(dx * dx + dy * dy)
      val f = (l - lDist[k]) / l * alpha * lStrength[k]
      dx *= f
      dy *= f
      val bias = lBias[k]
      vx[b] -= dx * bias
      vy[b] -= dy * bias
      vx[a] += dx * (1f - bias)
      vy[a] += dy * (1f - bias)
    }
  }

  // --- Barnes–Hut quadtree, in flat arrays reused from step to step -----------------------------

  private var cap = 0
  private var child = IntArray(0) // 4 per cell, -1 = none
  private var point = IntArray(0) // node of a leaf, -1 = none (or an inner cell)
  private var cellX0 = FloatArray(0)
  private var cellY0 = FloatArray(0)
  private var cellSize = FloatArray(0)
  private var cellCharge = FloatArray(0)
  private var cellCx = FloatArray(0)
  private var cellCy = FloatArray(0)
  private var cells = 0
  private val next = IntArray(n) // nodes sharing a leaf (same position, or too deep)
  private var stack = IntArray(64)

  private fun ensureCapacity(c: Int) {
    if (c <= cap) return
    val newCap = maxOf(c, cap * 2, 16)
    child = child.copyOf(newCap * 4)
    point = point.copyOf(newCap)
    cellX0 = cellX0.copyOf(newCap)
    cellY0 = cellY0.copyOf(newCap)
    cellSize = cellSize.copyOf(newCap)
    cellCharge = cellCharge.copyOf(newCap)
    cellCx = cellCx.copyOf(newCap)
    cellCy = cellCy.copyOf(newCap)
    cap = newCap
  }

  private fun newCell(x0: Float, y0: Float, size: Float): Int {
    ensureCapacity(cells + 1)
    val c = cells++
    child[4 * c] = -1
    child[4 * c + 1] = -1
    child[4 * c + 2] = -1
    child[4 * c + 3] = -1
    point[c] = -1
    cellX0[c] = x0
    cellY0[c] = y0
    cellSize[c] = size
    return c
  }

  private fun buildTree() {
    cells = 0
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in 0 until n) {
      if (x[i] < minX) minX = x[i]
      if (y[i] < minY) minY = y[i]
      if (x[i] > maxX) maxX = x[i]
      if (y[i] > maxY) maxY = y[i]
      next[i] = -1
    }
    val size = maxOf(maxX - minX, maxY - minY, 1f) * 1.0001f + 1f
    newCell(minX, minY, size)
    for (i in 0 until n) insert(i)
    accumulate(0)
  }

  private fun insert(p: Int) {
    var c = 0
    var depth = 0
    while (true) {
      val inner = child[4 * c] >= 0 || child[4 * c + 1] >= 0 || child[4 * c + 2] >= 0 || child[4 * c + 3] >= 0
      if (!inner) {
        val q = point[c]
        if (q < 0) {
          point[c] = p
          return
        }
        // Same place, or deep enough: share the leaf.
        if (depth >= MAX_DEPTH || (x[q] == x[p] && y[q] == y[p])) {
          next[p] = next[q]
          next[q] = p
          return
        }
        // Split: the point already here goes one level down, then the new one is placed.
        point[c] = -1
        val qc = quadrant(c, q)
        val sub = childCell(c, qc)
        // q keeps its chain of nodes at the same place (next[] is per node).
        point[sub] = q
      }
      val quad = quadrant(c, p)
      val existing = child[4 * c + quad]
      c = if (existing >= 0) existing else childCell(c, quad)
      depth++
    }
  }

  private fun quadrant(c: Int, p: Int): Int {
    val half = cellSize[c] / 2f
    val right = if (x[p] >= cellX0[c] + half) 1 else 0
    val bottom = if (y[p] >= cellY0[c] + half) 2 else 0
    return right + bottom
  }

  private fun childCell(c: Int, quad: Int): Int {
    val existing = child[4 * c + quad]
    if (existing >= 0) return existing
    val half = cellSize[c] / 2f
    val x0 = cellX0[c] + if (quad and 1 != 0) half else 0f
    val y0 = cellY0[c] + if (quad and 2 != 0) half else 0f
    val nc = newCell(x0, y0, half)
    child[4 * c + quad] = nc
    return nc
  }

  /** Total charge and charge-weighted centre of each cell (children first). */
  private fun accumulate(c: Int) {
    var q = 0f
    var sx = 0f
    var sy = 0f
    var p = point[c]
    while (p >= 0) {
      q += charge[p]
      sx += charge[p] * x[p]
      sy += charge[p] * y[p]
      p = next[p]
    }
    for (k in 0 until 4) {
      val ch = child[4 * c + k]
      if (ch < 0) continue
      accumulate(ch)
      q += cellCharge[ch]
      sx += cellCharge[ch] * cellCx[ch]
      sy += cellCharge[ch] * cellCy[ch]
    }
    cellCharge[c] = q
    if (q != 0f) {
      cellCx[c] = sx / q
      cellCy[c] = sy / q
    } else {
      cellCx[c] = cellX0[c] + cellSize[c] / 2f
      cellCy[c] = cellY0[c] + cellSize[c] / 2f
    }
  }

  private fun applyRepulsion() {
    if (n < 2) return
    buildTree()
    for (i in 0 until n) {
      var top = 0
      stack[top++] = 0
      while (top > 0) {
        val c = stack[--top]
        if (cellCharge[c] == 0f) continue
        var dx = cellCx[c] - x[i]
        var dy = cellCy[c] - y[i]
        val w = cellSize[c]
        var l = dx * dx + dy * dy
        val isLeaf = child[4 * c] < 0 && child[4 * c + 1] < 0 && child[4 * c + 2] < 0 && child[4 * c + 3] < 0
        if (!isLeaf && w * w / THETA2 < l) {
          // Far enough: the whole cell acts as one charge.
          if (l < DIST_MIN2) l = sqrt(DIST_MIN2 * l)
          val f = cellCharge[c] * alpha / l
          vx[i] += dx * f
          vy[i] += dy * f
          continue
        }
        if (isLeaf) {
          var p = point[c]
          while (p >= 0) {
            if (p != i) {
              dx = x[p] - x[i]
              dy = y[p] - y[i]
              if (dx == 0f && dy == 0f) {
                // Same place: a fixed, tiny push (never random) separates them.
                dx = jiggle(i * 31 + p)
                dy = jiggle(i * 17 + p * 7)
              }
              var lp = dx * dx + dy * dy
              if (lp < DIST_MIN2) lp = sqrt(DIST_MIN2 * lp)
              val f = charge[p] * alpha / lp
              vx[i] += dx * f
              vy[i] += dy * f
            }
            p = next[p]
          }
          continue
        }
        if (top + 4 > stack.size) stack = stack.copyOf(stack.size * 2)
        for (k in 0 until 4) {
          val ch = child[4 * c + k]
          if (ch >= 0) stack[top++] = ch
        }
      }
    }
  }

  companion object {
    const val ALPHA_MIN = 0.002f
    /** About 300 steps from a cold start to rest. */
    val ALPHA_DECAY: Float = 1f - Math.pow(ALPHA_MIN.toDouble(), 1.0 / 300).toFloat()
    const val VELOCITY_DECAY = 0.42f
    const val REPULSION = 70f
    const val GRAVITY_X = 0.07f
    const val GRAVITY_Y = 0.035f
    const val MAX_SPEED = 60f
    private const val THETA2 = 0.81f
    private const val DIST_MIN2 = 4f
    private const val MAX_DEPTH = 32
    private const val SPIRAL_STEP = 14f
    private val GOLDEN_ANGLE = (PI * (3 - sqrt(5.0))).toFloat()

    /** A small fixed offset in [-0.5e-2, 0.5e-2], from an index. */
    private fun jiggle(k: Int): Float = ((((k * 2654435761L) ushr 8) and 0xFF).toFloat() / 255f - 0.5f) * 1e-2f
  }
}
