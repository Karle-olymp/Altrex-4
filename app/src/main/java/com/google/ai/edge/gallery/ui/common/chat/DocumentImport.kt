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

package com.google.ai.edge.gallery.ui.common.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [ALTREX] A document attached to a chat message (its text was extracted on the phone). */
data class AttachedDocument(
  val name: String,
  val isPdf: Boolean,
  val pages: Int,
  /** Text per page (a single entry for a plain-text file). */
  val pageTexts: List<String>,
) {
  val charCount: Int
    get() = pageTexts.sumOf { it.length }

  /** Rough token estimate (about 3 characters per token for French/English). */
  val estimatedTokens: Int
    get() = charCount / 3
}

class DocumentImportException(message: String) : Exception(message)

object DocumentReader {
  private const val MAX_PAGES = 400
  private const val MAX_CHARS = 600_000

  /** Extracts the text of a PDF or text file. Runs on a background thread. */
  suspend fun read(context: Context, uri: Uri): AttachedDocument =
    withContext(Dispatchers.IO) {
      val name = displayName(context, uri) ?: "document"
      val mime = context.contentResolver.getType(uri).orEmpty()
      val isPdf = mime == "application/pdf" || name.lowercase().endsWith(".pdf")
      if (isPdf) readPdf(context, uri, name) else readText(context, uri, name)
    }

  private fun readPdf(context: Context, uri: Uri, name: String): AttachedDocument {
    PDFBoxResourceLoader.init(context.applicationContext)
    val input =
      context.contentResolver.openInputStream(uri)
        ?: throw DocumentImportException("Impossible d'ouvrir ce fichier.")
    val document =
      try {
        input.use { PDDocument.load(it) }
      } catch (e: InvalidPasswordException) {
        throw DocumentImportException("Ce PDF est protégé par un mot de passe.")
      } catch (e: Exception) {
        throw DocumentImportException("Ce PDF est illisible ou endommagé.")
      }
    document.use { doc ->
      val pageCount = doc.numberOfPages
      val stripper = PDFTextStripper().apply { sortByPosition = true }
      val pages = ArrayList<String>()
      var total = 0
      for (page in 1..minOf(pageCount, MAX_PAGES)) {
        stripper.startPage = page
        stripper.endPage = page
        val text = stripper.getText(doc).replace(Regex("[ \\t]+\n"), "\n").trim()
        pages.add(text)
        total += text.length
        if (total > MAX_CHARS) break
      }
      if (pages.all { it.isBlank() }) {
        throw DocumentImportException(
          "Ce PDF ne contient pas de texte sélectionnable (document scanné ou images). " +
            "Altrex ne peut pas encore lire le texte d'une image de page."
        )
      }
      return AttachedDocument(name = name, isPdf = true, pages = pageCount, pageTexts = pages)
    }
  }

  private fun readText(context: Context, uri: Uri, name: String): AttachedDocument {
    val text =
      context.contentResolver.openInputStream(uri)?.use { stream ->
        val bytes = stream.readNBytesCompat(MAX_CHARS * 2)
        String(bytes, Charsets.UTF_8)
      } ?: throw DocumentImportException("Impossible d'ouvrir ce fichier.")
    if (text.isBlank()) throw DocumentImportException("Ce fichier est vide.")
    if (text.count { it == '\u0000' } > 8) {
      throw DocumentImportException("Ce fichier n'est pas un document texte.")
    }
    return AttachedDocument(name = name, isPdf = false, pages = 1, pageTexts = listOf(text.take(MAX_CHARS)))
  }

  private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
      val n = read(buf, 0, minOf(buf.size, limit - out.size()))
      if (n < 0) break
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  /** Code block language for a file name (empty when unknown), as in Argora's training data. */
  fun codeLanguage(fileName: String): String {
    val name = fileName.lowercase().removeSuffix(".txt")
    val ext = name.substringAfterLast('.', "")
    return when {
      name.startsWith("dockerfile") -> "dockerfile"
      name.contains("nginx") -> "nginx"
      name.contains("sshd_config") || name.contains("sudoers") -> ""
      name.contains("docker-compose") -> "yaml"
      ext == "py" -> "python"
      ext == "js" || ext == "mjs" -> "javascript"
      ext == "ts" -> "typescript"
      ext == "sh" || ext == "bash" -> "bash"
      ext == "yml" || ext == "yaml" -> "yaml"
      ext in setOf("php", "java", "go", "json", "toml", "ini", "sql", "xml") -> ext
      ext == "cs" -> "csharp"
      ext == "rb" -> "ruby"
      ext == "env" || name == ".env" -> "dotenv"
      else -> ""
    }
  }

  private fun displayName(context: Context, uri: Uri): String? =
    try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
      }
    } catch (e: Exception) {
      null
    }

  /**
   * Builds the text sent to the model: the document (cut to [maxChars] if needed, whole pages
   * first) followed by the user's question. Returns the prompt and the number of pages kept (or
   * null when everything fits).
   */
  fun buildPrompt(
    doc: AttachedDocument,
    question: String,
    maxChars: Int,
    argoraFormat: Boolean = false,
  ): Pair<String, Int?> {
    val budget = maxChars.coerceAtLeast(2_000)
    val body = StringBuilder()
    var keptPages = 0
    var truncated = false
    for ((index, page) in doc.pageTexts.withIndex()) {
      val block = if (doc.isPdf) "--- Page ${index + 1} ---\n$page\n" else page
      if (body.length + block.length > budget) {
        if (keptPages == 0) {
          body.append(block.take(budget - body.length))
          keptPages = 1
        }
        truncated = true
        break
      }
      body.append(block)
      keptPages++
    }
    if (doc.pageTexts.size < doc.pages) truncated = true
    if (argoraFormat) {
      // [ALTREX] The form Argora saw in training: the instruction, then the file in a code block.
      val fence = if (body.contains("```")) "~~~" else "```"
      val safe = com.google.ai.edge.gallery.security.UntrustedContent.neutralize(body.toString().trimEnd())
      val prompt = "${question.trim()}\n\n$fence${codeLanguage(doc.name)}\n$safe\n$fence"
      return prompt to (if (truncated) keptPages else null)
    }
    val header =
      buildString {
        append("Document joint : ").append(doc.name)
        if (doc.isPdf) append(" (").append(doc.pages).append(" pages)")
        if (truncated) {
          append(" — extrait : ")
          append(if (doc.isPdf) "les $keptPages premières pages" else "le début du texte")
        }
      }
    // [ALTREX] The document is framed as data: instructions hidden inside it are not followed.
    val prompt =
      "$header\n" +
        com.google.ai.edge.gallery.security.UntrustedContent.frame("document « ${doc.name} »", body.toString()) +
        "\n\n$question"
    return prompt to (if (truncated) keptPages else null)
  }
}

/** Card shown above the input (with a remove button) and inside the user's bubble. */
@Composable
fun AttachedDocumentChip(
  doc: AttachedDocument,
  onRemove: (() -> Unit)? = null,
  onBubble: Boolean = false,
) {
  val shape = RoundedCornerShape(16.dp)
  val contentColor = if (onBubble) Color.White else MaterialTheme.colorScheme.onSurface
  val subColor = if (onBubble) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.widthIn(max = 300.dp)
        .clip(shape)
        .background(
          if (onBubble) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer
        )
        .border(
          1.dp,
          if (onBubble) Color.White.copy(alpha = 0.2f)
          else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
          shape,
        )
        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (onRemove != null) 2.dp else 14.dp),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier.size(34.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(
            if (onBubble) Color.White.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
          ),
    ) {
      Icon(
        if (doc.isPdf) Icons.Rounded.PictureAsPdf else Icons.Rounded.Description,
        contentDescription = null,
        tint = if (onBubble) Color.White else MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
      )
    }
    Spacer(Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f, fill = false)) {
      Text(
        doc.name,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
      )
      val details =
        buildString {
          if (doc.isPdf) append(doc.pages).append(if (doc.pages > 1) " pages · " else " page · ")
          append("≈ ").append(formatCount(doc.estimatedTokens)).append(" tokens")
        }
      Text(details, style = MaterialTheme.typography.labelSmall, color = subColor)
    }
    if (onRemove != null) {
      IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
        Icon(
          Icons.Rounded.Close,
          contentDescription = "Retirer le document",
          modifier = Modifier.size(16.dp),
          tint = subColor,
        )
      }
    }
  }
}

private fun formatCount(n: Int): String =
  if (n >= 1000) String.format(java.util.Locale.FRANCE, "%.1f k", n / 1000f) else n.toString()
