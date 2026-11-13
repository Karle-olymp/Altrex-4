package com.google.ai.edge.gallery.forge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.theme.appFontFamily
import com.google.ai.edge.gallery.ui.theme.codeFontFamily

/**
 * [ALTREX] Forge's look (docs/forge/maquettes): « B, technique sobre » every day, « C, plan
 * d'architecte » in Plan mode and Blueprint. The rest of the app keeps its own theme; Forge always
 * draws on its night background, whatever the system theme.
 */
@Immutable
data class ForgePalette(
  val bg: Color,
  val surface: Color,
  val surface2: Color,
  val line: Color,
  val line2: Color,
  val text: Color,
  val muted: Color,
  val faint: Color,
  val accent: Color,
  val onAccent: Color,
  val ok: Color,
  val del: Color,
  val warn: Color,
  /** C only: the paper's grid, drawn behind the feed. */
  val grid: Boolean,
  /** Corner radius scale: B 8/6/4, C 2. */
  val radius: Int,
) {
  companion object {
    /** B « Technique sobre » (B-technique/tokens.md). */
    val B =
      ForgePalette(
        bg = Color(0xFF0C151B),
        surface = Color(0xFF101B22),
        surface2 = Color(0xFF15232B),
        line = Color(0xFF1F2F38),
        line2 = Color(0xFF2B3F4A),
        text = Color(0xFFE3EBEF),
        muted = Color(0xFF90A4AF),
        faint = Color(0xFF5C707B),
        accent = Color(0xFF72D6DB),
        onAccent = Color(0xFF05191B),
        ok = Color(0xFF5FD4A0),
        del = Color(0xFFF08F8F),
        warn = Color(0xFFE6C46A),
        grid = false,
        radius = 8,
      )

    /** C « Plan d'architecte » (C-plan/tokens.md): Plan mode and Blueprint. */
    val C =
      ForgePalette(
        bg = Color(0xFF0A1C2E),
        surface = Color(0xEB0C2136),
        surface2 = Color(0xFF0F2840),
        line = Color(0xFF2F5373),
        line2 = Color(0xFF6F98BB),
        text = Color(0xFFEAF3FA),
        muted = Color(0xFFA3BFD6),
        faint = Color(0xFF5F84A4),
        accent = Color(0xFF72D6DB),
        onAccent = Color(0xFF04202A),
        ok = Color(0xFF5FD4A0),
        del = Color(0xFFFF9C8F),
        warn = Color(0xFFF2D27C),
        grid = true,
        radius = 2,
      )
  }
}

val LocalForgePalette = staticCompositionLocalOf { ForgePalette.B }

object ForgeType {
  val title = TextStyle(fontFamily = appFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
  val body = TextStyle(fontFamily = appFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
  val bodyStrong = TextStyle(fontFamily = appFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
  val mono = TextStyle(fontFamily = codeFontFamily, fontSize = 13.sp, lineHeight = 18.sp)
  val monoSmall = TextStyle(fontFamily = codeFontFamily, fontSize = 12.sp, lineHeight = 16.sp)
  /** Section headers: mono capitals, spaced (11 px, +0.12 em). */
  val section = TextStyle(fontFamily = codeFontFamily, fontSize = 11.sp, letterSpacing = 0.12.em)
}

/** C's paper: a fine grid every 12 dp, a strong one every 60 dp. */
@Composable
fun BlueprintGrid(modifier: Modifier = Modifier) {
  Canvas(modifier.fillMaxSize()) {
    val fine = Color(150, 195, 235).copy(alpha = 0.05f)
    val strong = Color(150, 195, 235).copy(alpha = 0.12f)
    val step = 12.dp.toPx()
    var i = 0
    var x = 0f
    while (x < size.width) {
      drawLine(if (i % 5 == 0) strong else fine, Offset(x, 0f), Offset(x, size.height), 1f)
      x += step
      i++
    }
    i = 0
    var y = 0f
    while (y < size.height) {
      drawLine(if (i % 5 == 0) strong else fine, Offset(0f, y), Offset(size.width, y), 1f)
      y += step
      i++
    }
  }
}
