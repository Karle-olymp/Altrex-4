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
import androidx.compose.ui.graphics.PathFillType
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

// The Altrex "A", in the 108x108 viewport of ic_launcher_foreground.xml. The two contours are kept
// apart because PathMeasure only walks one contour at a time.
private const val A_OUTER = "M54,28L76,78L65,78L60.5,67L47.5,67L43,78L32,78Z"
private const val A_INNER = "M54,41L49.5,59L58.5,59Z"
private const val A_LEFT = 32f
private const val A_TOP = 28f
private const val A_WIDTH = 44f
private const val A_HEIGHT = 50f

private class AltrexLogoPaths {
  val outer: Path = PathParser().parsePathString(A_OUTER).toPath()
  val inner: Path = PathParser().parsePathString(A_INNER).toPath()
  val filled: Path =
    Path().apply {
      fillType = PathFillType.EvenOdd
      addPath(outer)
      addPath(inner)
    }
  val outerLength: Float = PathMeasure().apply { setPath(outer, false) }.length
  val innerLength: Float = PathMeasure().apply { setPath(inner, false) }.length
}

/** Draws [block] in the logo's viewport units, the "A" centered and [fraction] of the height. */
private fun DrawScope.inLogoSpace(fraction: Float, block: DrawScope.() -> Unit) {
  val scale = size.minDimension * fraction / A_HEIGHT
  withTransform({
    translate(
      left = size.width / 2f - (A_LEFT + A_WIDTH / 2f) * scale,
      top = size.height / 2f - (A_TOP + A_HEIGHT / 2f) * scale,
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
    getSegment(start, stop, out, true)
  }
  return out
}

// Progress of a sub-step running from [start] to [end] (both 0..1) of the whole timeline.
private fun step(t: Float, start: Float, end: Float): Float =
  FastOutSlowInEasing.transform(((t - start) / (end - start)).coerceIn(0f, 1f))

private const val INTRO_MS = 3000

/**
 * Altrex opening animation: the "A" is traced, filled, crossed by a light sweep, then the name
 * appears and everything fades into the app. Tapping skips it.
 */
@Composable
fun AltrexIntro(onFinished: () -> Unit) {
  val paths = remember { AltrexLogoPaths() }
  val progress = remember { Animatable(0f) }
  val finish by rememberUpdatedState(onFinished)
  LaunchedEffect(Unit) {
    progress.animateTo(1f, animationSpec = tween(INTRO_MS, easing = LinearEasing))
    finish()
  }

  val t = progress.value
  val primary = MaterialTheme.colorScheme.primary
  val tertiary = MaterialTheme.colorScheme.tertiary
  val background = MaterialTheme.colorScheme.background
  val onBackground = MaterialTheme.colorScheme.onBackground

  // About 1.1 s of tracing, then fill, light sweep and name, a short hold, and the fade-out.
  val trace = step(t, 0.00f, 0.38f)
  val fill = step(t, 0.30f, 0.52f)
  val sweep = step(t, 0.46f, 0.68f)
  val name = step(t, 0.50f, 0.70f)
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
        // Soft halo behind the letter.
        drawCircle(
          brush =
            Brush.radialGradient(
              colors = listOf(primary.copy(alpha = 0.28f * fill), Color.Transparent),
              center = center,
              radius = size.minDimension * 0.6f,
            )
        )
        inLogoSpace(fraction = 0.72f) {
          val gradient =
            Brush.linearGradient(
              colors = listOf(tertiary, primary),
              start = Offset(A_LEFT, A_TOP),
              end = Offset(A_LEFT + A_WIDTH, A_TOP + A_HEIGHT),
            )
          val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
          if (trace > 0f) {
            drawPath(segment(paths.outer, 0f, paths.outerLength * trace), gradient, style = stroke)
            drawPath(segment(paths.inner, 0f, paths.innerLength * trace), gradient, style = stroke)
          }
          if (fill > 0f) {
            drawPath(paths.filled, gradient, alpha = fill)
          }
          if (sweep > 0f && sweep < 1f) {
            clipPath(paths.filled) {
              val x = A_LEFT - 20f + (A_WIDTH + 40f) * sweep
              drawRect(
                brush =
                  Brush.linearGradient(
                    colors =
                      listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                    start = Offset(x - 10f, A_TOP),
                    end = Offset(x + 10f, A_TOP + A_HEIGHT),
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

/** The "A" outline with a light running along it, for model loading. */
@Composable
fun AltrexLogoLoader(size: Dp) {
  val paths = remember { AltrexLogoPaths() }
  val transition = rememberInfiniteTransition(label = "altrexLogoLoader")
  val run by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
      label = "run",
    )
  val breath by
    transition.animateFloat(
      initialValue = 0.10f,
      targetValue = 0.26f,
      animationSpec = infiniteRepeatable(tween(1400), repeatMode = RepeatMode.Reverse),
      label = "breath",
    )
  val primary = MaterialTheme.colorScheme.primary
  val tertiary = MaterialTheme.colorScheme.tertiary

  Canvas(modifier = Modifier.size(size).clearAndSetSemantics {}) {
    drawCircle(
      brush =
        Brush.radialGradient(
          colors = listOf(primary.copy(alpha = breath), Color.Transparent),
          center = center,
          radius = this.size.minDimension * 0.55f,
        )
    )
    inLogoSpace(fraction = 0.7f) {
      val faint = Stroke(width = 1.3f, join = StrokeJoin.Round)
      drawPath(paths.outer, primary.copy(alpha = 0.18f), style = faint)
      drawPath(paths.inner, primary.copy(alpha = 0.18f), style = faint)

      // Comet: a head followed by a fading tail, wrapping around the closed outline. Butt caps on
      // the tail pieces so that their overlaps do not show up as dots; only the head is rounded.
      val length = paths.outerLength
      val tail = length * 0.30f
      val head = length * run
      val pieces = 24
      for (i in 0 until pieces) {
        val a = head - tail * (i + 1) / pieces
        val b = head - tail * i / pieces
        val alpha = 1f - i.toFloat() / pieces
        val color = if (i < pieces / 2) primary else tertiary
        val style =
          Stroke(
            width = 2.2f,
            cap = if (i == 0) StrokeCap.Round else StrokeCap.Butt,
            join = StrokeJoin.Round,
          )
        if (a >= 0f) {
          drawPath(segment(paths.outer, a, b), color, alpha = alpha, style = style)
        } else if (b <= 0f) {
          drawPath(segment(paths.outer, length + a, length + b), color, alpha = alpha, style = style)
        } else {
          drawPath(segment(paths.outer, length + a, length), color, alpha = alpha, style = style)
          drawPath(segment(paths.outer, 0f, b), color, alpha = alpha, style = style)
        }
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
  val primary = MaterialTheme.colorScheme.primary

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
      drawRect(primary.copy(alpha = 0.15f))
      val x = size.width * shimmer
      drawRect(
        brush =
          Brush.horizontalGradient(
            colors = listOf(Color.Transparent, primary, Color.Transparent),
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

/** Small rounded tile with the "A", shown next to the model's answers. */
@Composable
fun AltrexAvatar(size: Dp = 22.dp) {
  val paths = remember { AltrexLogoPaths() }
  val primary = MaterialTheme.colorScheme.primary
  Box(
    modifier =
      Modifier.size(size)
        .clip(RoundedCornerShape(size * 0.32f))
        .background(primary.copy(alpha = 0.12f))
        .border(1.dp, primary.copy(alpha = 0.28f), RoundedCornerShape(size * 0.32f))
        .clearAndSetSemantics {},
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      inLogoSpace(fraction = 0.5f) { drawPath(paths.filled, primary) }
    }
  }
}
