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

import com.google.ai.edge.gallery.ui.theme.codeFontFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.theme.customColors
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle

/** Composable function to display Markdown-formatted text. */
@Composable
fun MarkdownText(
  text: String,
  modifier: Modifier = Modifier,
  smallFontSize: Boolean = false,
  textColor: Color = MaterialTheme.colorScheme.onSurface,
  linkColor: Color = MaterialTheme.customColors.linkColor,
) {
  // [ALTREX] Fenced code blocks are drawn as cards with a header (language + copy button); the
  // text around them stays Markdown.
  val segments = remember(text) { splitFencedCode(text) }
  if (segments.size == 1 && segments[0].language == null) {
    MarkdownSegment(text, modifier, smallFontSize, textColor, linkColor)
    return
  }
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    for (segment in segments) {
      if (segment.language == null) {
        MarkdownSegment(segment.text, Modifier, smallFontSize, textColor, linkColor)
      } else {
        CodeBlockCard(language = segment.language, code = segment.text)
      }
    }
  }
}

@Composable
private fun MarkdownSegment(
  text: String,
  modifier: Modifier,
  smallFontSize: Boolean,
  textColor: Color,
  linkColor: Color,
) {
  val fontSize =
    if (smallFontSize) MaterialTheme.typography.bodyMedium.fontSize
    else MaterialTheme.typography.bodyLarge.fontSize
  CompositionLocalProvider {
    ProvideTextStyle(
      value =
        TextStyle(
          fontSize = fontSize,
          lineHeight = fontSize * if (smallFontSize) 1.4f else 1.55f,
          color = textColor,
          letterSpacing = 0.1.sp,
        )
    ) {
      RichText(
        modifier = modifier,
        style =
          RichTextStyle(
            codeBlockStyle =
              CodeBlockStyle(
                textStyle =
                  TextStyle(
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontFamily = codeFontFamily,
                    lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.4f,
                  )
              ),
            stringStyle =
              RichTextStringStyle(linkStyle = TextLinkStyles(style = SpanStyle(color = linkColor))),
          ),
      ) {
        Markdown(content = text)
      }
    }
  }
}

/** A piece of a Markdown message: plain Markdown ([language] null) or a fenced code block. */
private class TextSegment(val text: String, val language: String?)

/**
 * Splits [text] on ``` fences. A fence still open at the end (the model is still writing) gives a
 * code segment too, so the card appears while the code streams.
 */
private fun splitFencedCode(text: String): List<TextSegment> {
  if (!text.contains("```")) return listOf(TextSegment(text, null))
  val segments = ArrayList<TextSegment>()
  val plain = StringBuilder()
  val code = StringBuilder()
  var language: String? = null
  for (line in text.split("\n")) {
    val trimmed = line.trimStart()
    if (language == null && trimmed.startsWith("```")) {
      if (plain.isNotBlank()) segments.add(TextSegment(plain.toString().trimEnd(), null))
      plain.clear()
      language = trimmed.removePrefix("```").trim()
    } else if (language != null && trimmed.startsWith("```")) {
      segments.add(TextSegment(code.toString().trimEnd('\n'), language))
      code.clear()
      language = null
    } else if (language != null) {
      code.append(line).append('\n')
    } else {
      plain.append(line).append('\n')
    }
  }
  if (language != null) segments.add(TextSegment(code.toString().trimEnd('\n'), language))
  if (plain.isNotBlank()) segments.add(TextSegment(plain.toString().trimEnd(), null))
  return segments
}

@Composable
private fun CodeBlockCard(language: String, code: String) {
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1500)
      copied = false
    }
  }
  val shape = RoundedCornerShape(14.dp)
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
  ) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceContainerLow)
          .padding(start = 14.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        language.ifEmpty { "code" },
        fontFamily = codeFontFamily,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      TextButton(
        onClick = {
          clipboard.setText(AnnotatedString(code))
          copied = true
        },
        contentPadding = PaddingValues(horizontal = 10.dp),
      ) {
        Icon(
          if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          if (copied) "Copié" else "Copier",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Text(
      code,
      fontFamily = codeFontFamily,
      fontSize = 12.5.sp,
      lineHeight = 20.sp,
      color = MaterialTheme.colorScheme.onSurface,
      softWrap = false,
      modifier =
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
    )
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MarkdownTextPreview() {
//   GalleryTheme {
//     MarkdownText(text = "*Hello World*\n**Good morning!!**")
//   }
// }
