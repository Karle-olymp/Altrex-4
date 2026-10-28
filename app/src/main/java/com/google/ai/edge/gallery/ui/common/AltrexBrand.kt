/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// [ALTREX] The brace shield (Argora core), in the 256x256 viewport of the logo files
// (design/logo/final). The braces are drawn from their centre lines, stroked at the logo's
// thickness, so they can be traced; once drawn, the exact filled shapes take over.
private const val LEFT_BRACE =
  "M111,37 L72.36,47.36 A16,16 0 0 0 60.5,62.81 L60.5,84.81 C60.5,96 49.2,98 49.2,104.4 " +
    "C49.2,110.8 60.5,113 60.5,123.95 L60.5,135.95 A62,62 0 0 0 86.06,186.1 L128,216.6"
private const val RIGHT_BRACE =
  "M145,37 L183.64,47.36 A16,16 0 0 1 195.5,62.81 L195.5,84.81 C195.5,96 206.8,98 206.8,104.4 " +
    "C206.8,110.8 195.5,113 195.5,123.95 L195.5,135.95 A62,62 0 0 1 169.94,186.1 L128,216.6"
private const val LEFT_FILL =
  "M108.54 27.82 L69.9 38.18 A25.5 25.5 0 0 0 51 62.81 L51 84.81 A3.5 3.5 0 0 1 49.25 87.84 " +
    "A19.1 19.1 0 0 0 49.25 120.92 A3.5 3.5 0 0 1 51 123.95 L51 135.95 A71.5 71.5 0 0 0 80.48 193.8 " +
    "L122.42 224.27 A9.5 9.5 0 0 0 133.58 208.9 L91.65 178.43 A52.5 52.5 0 0 1 70 135.95 L70 123.95 " +
    "A22.5 22.5 0 0 0 58.75 104.47 A0.1 0.1 0 0 1 58.75 104.29 A22.5 22.5 0 0 0 70 84.81 L70 62.81 " +
    "A6.5 6.5 0 0 1 74.82 56.53 L113.46 46.18 A9.5 9.5 0 0 0 108.54 27.82 Z"
private const val RIGHT_FILL =
  "M147.46 27.82 L186.1 38.18 A25.5 25.5 0 0 1 205 62.81 L205 84.81 A3.5 3.5 0 0 0 206.75 87.84 " +
    "A19.1 19.1 0 0 1 206.75 120.92 A3.5 3.5 0 0 0 205 123.95 L205 135.95 A71.5 71.5 0 0 1 175.52 193.8 " +
    "L133.58 224.27 A9.5 9.5 0 0 1 122.42 208.9 L164.35 178.43 A52.5 52.5 0 0 0 186 135.95 L186 123.95 " +
    "A22.5 22.5 0 0 1 197.25 104.47 A0.1 0.1 0 0 0 197.25 104.29 A22.5 22.5 0 0 1 186 84.81 L186 62.81 " +
    "A6.5 6.5 0 0 0 181.18 56.53 L142.54 46.18 A9.5 9.5 0 0 1 147.46 27.82 Z"
private const val CORE = "M128 90.94 L156.06 119 L128 147.06 L99.94 119 Z"
private const val BRACE_WIDTH = 19f
private val CORE_CENTER = Offset(128f, 119f)

// Bounding box of the mark (strokes included).
private const val MARK_LEFT = 39.7f
private const val MARK_TOP = 27.8f
private const val MARK_WIDTH = 176.6f
private const val MARK_HEIGHT = 196.5f

/** Brand gradient of Argora (design/logo/final/GUIDE.md). */
val BrandCyan = Color(0xFF72D6DB)
val BrandGreen = Color(0xFF5FD4A0)

private fun brandBrush(): Brush =
  Brush.linearGradient(
    colors = listOf(BrandCyan, BrandGreen),
    start = Offset(MARK_LEFT, MARK_TOP),
    end = Offset(MARK_LEFT + MARK_WIDTH, MARK_TOP + MARK_HEIGHT),
  )

private class ShieldPaths {
  private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

  val left: Path = parse(LEFT_BRACE)
  val right: Path = parse(RIGHT_BRACE)
  val braceLength: Float = PathMeasure().apply { setPath(left, false) }.length
  val leftFill: Path = parse(LEFT_FILL)
  val rightFill: Path = parse(RIGHT_FILL)
  val core: Path = parse(CORE)
  val all: Path =
    Path().apply {
      addPath(leftFill)
      addPath(rightFill)
      addPath(core)
    }
}

/** Draws [block] in the logo's viewport units, the mark centred and [fraction] of the height. */
private fun DrawScope.inLogoSpace(fraction: Float, block: DrawScope.() -> Unit) {
  val scale = size.minDimension * fraction / MARK_HEIGHT
  withTransform({
    translate(
      left = size.width / 2f - (MARK_LEFT + MARK_WIDTH / 2f) * scale,
      top = size.height / 2f - (MARK_TOP + MARK_HEIGHT / 2f) * scale,
    )
    scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
  }) {
    block()
  }
}

/** Part of [path] from [start] to [stop] (distances), as a new path. */
private fun segment(path: Path, start: Float, stop: Float): Path {
  val out = Path()
  PathMeasure().apply {
    setPath(path, false)
    getSegment(start.coerceAtLeast(0f), stop, out, true)
  }
  return out
}

// Progress of a sub-step running from [start] to [end] (both 0..1) of the whole timeline.
private fun step(t: Float, start: Float, end: Float): Float =
  FastOutSlowInEasing.transform(((t - start) / (end - start)).coerceIn(0f, 1f))

// Ease-out without overshoot, for the braces closing in.
private val CloseEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

private const val INTRO_MS = 3000

/**
 * Opening animation (GUIDE.md, "Mouvement"): the two braces come in from each side, tracing
 * themselves, and close into the shield; the core lights up, a light sweeps across, then the name
 * appears and everything fades into the app. Tapping skips it.
 */
@Composable
fun AltrexIntro(onFinished: () -> Unit) {
  val paths = remember { ShieldPaths() }
  val progress = remember { Animatable(0f) }
  val finish by rememberUpdatedState(onFinished)
  LaunchedEffect(Unit) {
    progress.animateTo(1f, animationSpec = tween(INTRO_MS, easing = LinearEasing))
    finish()
  }

  val t = progress.value
  val background = MaterialTheme.colorScheme.background
  val onBackground = MaterialTheme.colorScheme.onBackground

  // About 1.1 s for the braces, then the core, the sweep and the name, a hold, and the fade-out.
  val close = CloseEasing.transform((t / 0.36f).coerceIn(0f, 1f))
  // The traced braces and the filled shapes coincide: swap them at once, a crossfade of two
  // half-transparent layers looked dimmer and showed the overlapping caps.
  val solid = if (t >= 0.36f) 1f else 0f
  val core = step(t, 0.34f, 0.52f)
  val flash = step(t, 0.40f, 0.62f)
  val sweep = step(t, 0.50f, 0.70f)
  val name = step(t, 0.54f, 0.72f)
  val exit = step(t, 0.86f, 1.00f)

  Box(
    modifier =
      Modifier.fillMaxSize()
        .graphicsLayer {
          alpha = 1f - exit
          val s = 1f + 0.06f * exit
          scaleX = s
          scaleY = s
        }
        .background(background)
        .pointerInput(Unit) { detectTapGestures { finish() } }
        .clearAndSetSemantics {},
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Canvas(modifier = Modifier.size(150.dp)) {
        // Halo, brighter for a moment when the core lights up.
        val glow = 0.22f * core + 0.25f * (1f - flash) * core
        drawCircle(
          brush =
            Brush.radialGradient(
              colors = listOf(BrandGreen.copy(alpha = glow), Color.Transparent),
              center = center,
              // At most half the canvas, else the glow is cut square at its edges.
              radius = size.minDimension * (0.42f + 0.08f * flash),
            )
        )
        inLogoSpace(fraction = 0.78f) {
          val brush = brandBrush()
          // Braces: each slides in from its side while it is traced from the top to the point.
          if (solid < 1f) {
            val shift = 46f * (1f - close)
            val stroke = Stroke(width = BRACE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val drawn = paths.braceLength * close
            withTransform({ translate(left = -shift) }) {
              drawPath(segment(paths.left, 0f, drawn), brush, style = stroke)
            }
            withTransform({ translate(left = shift) }) {
              drawPath(segment(paths.right, 0f, drawn), brush, style = stroke)
            }
          }
          if (solid > 0f) {
            drawPath(paths.leftFill, brush)
            drawPath(paths.rightFill, brush)
          }
          // Core: grows from the centre with a short rotation, then stays lit.
          if (core > 0f) {
            withTransform({
              rotate(degrees = -45f * (1f - core), pivot = CORE_CENTER)
              scale(scaleX = 0.3f + 0.7f * core, scaleY = 0.3f + 0.7f * core, pivot = CORE_CENTER)
            }) {
              drawPath(paths.core, brush, alpha = core)
            }
          }
          // Light sweep across the whole mark.
          if (sweep > 0f && sweep < 1f) {
            clipPath(paths.all) {
              val x = MARK_LEFT - 40f + (MARK_WIDTH + 80f) * sweep
              drawRect(
                brush =
                  Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.6f), Color.Transparent),
                    start = Offset(x - 30f, MARK_TOP),
                    end = Offset(x + 30f, MARK_TOP + MARK_HEIGHT),
                  )
              )
            }
          }
        }
      }
      Spacer(modifier = Modifier.height(20.dp))
      Text(
        "ALTREX",
        modifier = Modifier.graphicsLayer { alpha = name },
        color = onBackground,
        fontSize = 18.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (0.55f - 0.2f * name).em,
      )
    }
  }
}

/**
 * Loading: the shield stays faint while two lights run down the braces, side by side, to meet at
 * the point; the core pulses (1.2 s cycle, GUIDE.md "Argora en analyse").
 */
@Composable
fun AltrexLogoLoader(size: Dp) {
  val paths = remember { ShieldPaths() }
  val transition = rememberInfiniteTransition(label = "altrexLogoLoader")
  val run by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing)),
      label = "run",
    )
  val pulse by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
      label = "pulse",
    )

  Canvas(modifier = Modifier.size(size).clearAndSetSemantics {}) {
    drawCircle(
      brush =
        Brush.radialGradient(
          colors = listOf(BrandGreen.copy(alpha = 0.10f + 0.14f * pulse), Color.Transparent),
          center = center,
          radius = this.size.minDimension * 0.5f,
        )
    )
    inLogoSpace(fraction = 0.74f) {
      val brush = brandBrush()
      drawPath(paths.leftFill, brush, alpha = 0.18f)
      drawPath(paths.rightFill, brush, alpha = 0.18f)

      // Comets: a rounded head followed by a fading tail; butt caps on the tail pieces so that their
      // overlaps do not show up as dots. They fade out as they reach the point.
      val length = paths.braceLength
      val head = length * run
      val tail = length * 0.32f
      val fadeOut = 1f - ((run - 0.85f) / 0.15f).coerceIn(0f, 1f)
      val pieces = 20
      for (brace in listOf(paths.left, paths.right)) {
        for (i in 0 until pieces) {
          val b = head - tail * i / pieces
          val a = head - tail * (i + 1) / pieces
          if (b <= 0f) break
          val style =
            Stroke(
              width = BRACE_WIDTH,
              cap = if (i == 0) StrokeCap.Round else StrokeCap.Butt,
              join = StrokeJoin.Round,
            )
          drawPath(segment(brace, a, b), brush, alpha = (1f - i.toFloat() / pieces) * fadeOut, style = style)
        }
      }

      val s = 0.9f + 0.14f * pulse
      withTransform({ scale(scaleX = s, scaleY = s, pivot = CORE_CENTER) }) {
        drawPath(paths.core, brush, alpha = 0.55f + 0.45f * pulse)
      }
    }
  }
}

/** Full model-loading panel: animated logo, title, model name and elapsed time. */
@Composable
fun AltrexModelLoading(title: String, modelName: String, hint: String, modifier: Modifier = Modifier) {
  var seconds by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(1000)
      seconds++
    }
  }
  val transition = rememberInfiniteTransition(label = "altrexLoadingBar")
  val shimmer by
    transition.animateFloat(
      initialValue = -0.4f,
      targetValue = 1.4f,
      animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing)),
      label = "shimmer",
    )

  Column(
    modifier = modifier.padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    AltrexLogoLoader(size = 120.dp)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      title,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Medium,
      textAlign = TextAlign.Center,
    )
    Text(
      if (seconds > 0) "$modelName · $seconds s" else modelName,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.MiddleEllipsis,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Canvas(
      modifier =
        Modifier.width(160.dp).height(3.dp).clip(RoundedCornerShape(50)).clearAndSetSemantics {}
    ) {
      drawRect(BrandGreen.copy(alpha = 0.15f))
      val x = size.width * shimmer
      drawRect(
        brush =
          Brush.horizontalGradient(
            colors = listOf(Color.Transparent, BrandGreen, Color.Transparent),
            startX = x - size.width * 0.35f,
            endX = x + size.width * 0.35f,
          )
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      hint,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/** Small rounded tile with the shield, shown next to the model's answers. */
@Composable
fun AltrexAvatar(size: Dp = 22.dp) {
  val paths = remember { ShieldPaths() }
  val tint = BrandGreen
  Box(
    modifier =
      Modifier.size(size)
        .clip(RoundedCornerShape(size * 0.32f))
        .background(tint.copy(alpha = 0.12f))
        .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(size * 0.32f))
        .clearAndSetSemantics {},
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      inLogoSpace(fraction = 0.64f) { drawPath(paths.all, brandBrush()) }
    }
  }
}
