package com.google.ai.edge.gallery.ui.common.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a chat conversation to a PDF with Android's built-in PdfDocument (no extra library). */
object ChatPdfExporter {
  private const val PAGE_W = 595
  private const val PAGE_H = 842
  private const val MARGIN = 44f

  /** True when there is at least one user or assistant text message to export. */
  fun hasContent(messages: List<ChatMessage>): Boolean =
    messages.any { it is ChatMessageText && it.side != ChatSide.SYSTEM && it.content.isNotBlank() }

  fun write(
    context: Context,
    uri: Uri,
    title: String,
    modelName: String,
    messages: List<ChatMessage>,
  ): Boolean {
    val doc = PdfDocument()
    try {
      val writer = PageWriter(doc)
      writer.header(title, modelName)
      for (m in messages) {
        if (m !is ChatMessageText || m.side == ChatSide.SYSTEM || m.content.isBlank()) continue
        val user = m.side == ChatSide.USER
        writer.message(if (user) "Toi" else "Altrex", m.content, user)
      }
      writer.finish()
      val out = context.contentResolver.openOutputStream(uri) ?: return false
      out.use { doc.writeTo(it) }
      return true
    } catch (e: Exception) {
      return false
    } finally {
      doc.close()
    }
  }

  private class PageWriter(private val doc: PdfDocument) {
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = MARGIN
    private var pageNumber = 0
    private val contentWidth = (PAGE_W - 2 * MARGIN).toInt()

    private val bodyPaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        color = Color.rgb(20, 30, 40)
      }
    private val monoPaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f
        typeface = Typeface.MONOSPACE
        color = Color.rgb(20, 30, 40)
      }
    private val labelPaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
      }
    private val titlePaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        color = Color.rgb(14, 107, 168)
      }
    private val subtitlePaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f
        color = Color.rgb(100, 115, 128)
      }
    private val codeBackground = Paint().apply { color = Color.rgb(236, 243, 248) }
    private val linePaint =
      Paint().apply {
        color = Color.rgb(205, 218, 228)
        strokeWidth = 0.7f
      }

    private fun newPage() {
      finishPage()
      pageNumber++
      val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()
      val p = doc.startPage(info)
      page = p
      canvas = p.canvas
      y = MARGIN
    }

    private fun finishPage() {
      val p = page ?: return
      canvas?.drawText("Altrex · page $pageNumber", MARGIN, PAGE_H - 22f, subtitlePaint)
      doc.finishPage(p)
      page = null
      canvas = null
    }

    private fun ensure(height: Float) {
      if (page == null || y + height > PAGE_H - MARGIN) newPage()
    }

    fun header(title: String, modelName: String) {
      newPage()
      val c = canvas ?: return
      c.drawText("Altrex", MARGIN, y + 18f, titlePaint)
      y += 28f
      val date = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
      c.drawText("$title · $modelName · $date", MARGIN, y + 8f, subtitlePaint)
      y += 20f
      c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, linePaint)
      y += 16f
    }

    fun message(label: String, content: String, user: Boolean) {
      labelPaint.color = if (user) Color.rgb(14, 107, 168) else Color.rgb(15, 138, 143)
      ensure(40f)
      canvas?.drawText(label.uppercase(), MARGIN, y + 9f, labelPaint)
      y += 16f
      var inCode = false
      for (rawLine in content.replace("\r", "").split("\n")) {
        if (rawLine.trimStart().startsWith("```")) {
          inCode = !inCode
          continue
        }
        val paint = if (inCode) monoPaint else bodyPaint
        val line = if (rawLine.isEmpty()) " " else rawLine
        val layout =
          StaticLayout.Builder.obtain(line, 0, line.length, paint, contentWidth)
            .setLineSpacing(0f, 1.15f)
            .build()
        for (i in 0 until layout.lineCount) {
          val lineTop = layout.getLineTop(i)
          val lineHeight = (layout.getLineBottom(i) - lineTop).toFloat()
          ensure(lineHeight)
          val c = canvas ?: return
          if (inCode) {
            c.drawRect(MARGIN - 4f, y - 1f, PAGE_W - MARGIN + 4f, y + lineHeight, codeBackground)
          }
          c.drawText(
            line,
            layout.getLineStart(i),
            layout.getLineEnd(i),
            MARGIN,
            y - lineTop + layout.getLineBaseline(i),
            paint,
          )
          y += lineHeight
        }
      }
      y += 8f
      val c = canvas
      if (c != null && y < PAGE_H - MARGIN) {
        c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, linePaint)
      }
      y += 12f
    }

    fun finish() {
      if (page == null && pageNumber == 0) newPage()
      finishPage()
    }
  }
}
