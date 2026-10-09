/*
 * Copyright 2025 Google LLC
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

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Altrex loader: a gradient ring that keeps turning around the Altrex "A", which pulses gently.
 * The "A" is hidden on small sizes (inline "thinking" indicators), where only the ring remains.
 */
@Composable
fun RotationalLoader(size: Dp) {
  val infiniteTransition = rememberInfiniteTransition(label = "altrexLoader")
  val rotation by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(animation = tween(1300, easing = LinearEasing)),
      label = "altrexRotation",
    )
  val pulse by
    infiniteTransition.animateFloat(
      initialValue = 0.82f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(900, easing = EaseInOut),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "altrexPulse",
    )
  val primary = MaterialTheme.colorScheme.primary
  val tertiary = MaterialTheme.colorScheme.tertiary

  Box(
    modifier = Modifier.size(size).clearAndSetSemantics {},
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val strokeWidth = this.size.minDimension * 0.08f
      rotate(degrees = rotation) {
        drawArc(
          brush = Brush.sweepGradient(listOf(Color.Transparent, tertiary, primary)),
          startAngle = 0f,
          sweepAngle = 300f,
          useCenter = false,
          topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
          size = Size(this.size.width - strokeWidth, this.size.height - strokeWidth),
          style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
      }
    }
    if (size >= 28.dp) {
      Image(
        painter = painterResource(id = R.drawable.ic_launcher_foreground),
        contentDescription = null,
        colorFilter = ColorFilter.tint(primary),
        modifier =
          Modifier.size(size * 0.9f).graphicsLayer {
            scaleX = pulse
            scaleY = pulse
          },
      )
    }
  }
}
