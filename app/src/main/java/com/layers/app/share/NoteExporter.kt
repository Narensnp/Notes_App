package com.layers.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Html
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StrikethroughSpan
import com.layers.app.data.files.AttachmentStore
import com.layers.app.data.model.AudioContent
import com.layers.app.data.model.Block
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.DrawingContent
import com.layers.app.data.model.FileContent
import com.layers.app.data.model.ImageContent
import com.layers.app.data.model.PdfContent
import com.layers.app.data.model.TextContent
import com.layers.app.data.repo.NoteRepository
import com.layers.app.data.repo.TextUtil
import com.layers.app.drawing.InkRenderer
import com.layers.app.util.BitmapUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

/**
 * Shares notes as plain text, as a PDF (rendered with PdfDocument, including images, drawings and
 * checklists) or as one PNG image per page. Files go through FileProvider.
 */
class NoteExporter(
    private val context: Context,
    private val notes: NoteRepository,
    private val attachments: AttachmentStore,
) {
    private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    private val dateTimeFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

    suspend fun textIntent(noteId: String): Intent = withContext(Dispatchers.IO) {
        val note = notes.getNote(noteId) ?: error("Note not found")
        val blocks = notes.loadBlocks(noteId)
        val sb = StringBuilder()
        if (note.title.isNotBlank()) sb.append(note.title).append("\n\n")
        for (b in blocks) {
            when (val c = b.content) {
                is TextContent -> TextUtil.htmlToPlain(c.html).takeIf { it.isNotBlank() }?.let { sb.append(it).append("\n\n") }
                is ChecklistContent -> {
                    sb.append(if (c.done) "☑ " else "☐ ").append(c.text)
                    c.dueAt?.let { sb.append("  (").append(formatDue(it, c.hasTime)).append(')') }
                    sb.append('\n')
                }
                is ImageContent -> sb.append("[Image]\n")
                is DrawingContent -> sb.append("[Drawing]\n")
                is AudioContent -> sb.append("[Voice recording, ").append(formatDuration(c.durationMs)).append("]\n")
                is PdfContent -> sb.append("[PDF: ").append(c.name).append("]\n")
                is FileContent -> sb.append("[File: ").append(c.name).append("]\n")
            }
        }
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, note.title)
            .putExtra(Intent.EXTRA_TEXT, sb.toString().trim())
    }

    suspend fun pdfIntent(noteId: String): Intent = withContext(Dispatchers.IO) {
        val note = notes.getNote(noteId) ?: error("Note not found")
        val blocks = notes.loadBlocks(noteId)
        val file = attachments.sharedFile(safeName(note.title.ifBlank { "Note" }) + ".pdf")
        val doc = PdfDocument()
        var pageNumber = 0
        val sink = object : PageSink {
            var page: PdfDocument.Page? = null
            override fun begin(): Canvas {
                pageNumber++
                val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
                page = p
                return p.canvas
            }

            override fun end() {
                page?.let { doc.finishPage(it) }
                page = null
            }
        }
        try {
            render(note.title, note.encrypted, blocks, sink)
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        streamIntent("application/pdf", attachments.uriForShared(file), note.title)
    }

    suspend fun imagesIntent(noteId: String): Intent = withContext(Dispatchers.IO) {
        val note = notes.getNote(noteId) ?: error("Note not found")
        val blocks = notes.loadBlocks(noteId)
        val uris = ArrayList<Uri>()
        val scale = 2f
        val base = safeName(note.title.ifBlank { "Note" })
        val sink = object : PageSink {
            var bitmap: Bitmap? = null
            override fun begin(): Canvas {
                val bmp = Bitmap.createBitmap((PAGE_W * scale).toInt(), (PAGE_H * scale).toInt(), Bitmap.Config.ARGB_8888)
                bitmap = bmp
                val c = Canvas(bmp)
                c.drawColor(Color.WHITE)
                c.scale(scale, scale)
                return c
            }

            override fun end() {
                val bmp = bitmap ?: return
                val f = attachments.sharedFile("$base-${uris.size + 1}.png")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                bitmap = null
                uris += attachments.uriForShared(f)
            }
        }
        render(note.title, note.encrypted, blocks, sink)
        if (uris.size == 1) {
            streamIntent("image/png", uris[0], note.title)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/png")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Shares one attachment (decrypted copy for locked notes). */
    suspend fun attachmentIntent(noteId: String, block: Block): Intent? = withContext(Dispatchers.IO) {
        val note = notes.getNote(noteId) ?: return@withContext null
        val (path, name, mime) = when (val c = block.content) {
            is ImageContent -> Triple(c.path, "image.jpg", "image/*")
            is AudioContent -> Triple(c.path, "recording.m4a", "audio/mp4")
            is PdfContent -> Triple(c.path, c.name.ifBlank { "document.pdf" }, "application/pdf")
            is FileContent -> Triple(c.path, c.name, c.mime)
            else -> return@withContext null
        }
        val uri = attachments.shareUri(path, note.encrypted, name)
        streamIntent(mime, uri, name)
    }

    private fun streamIntent(mime: String, uri: Uri, subject: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    // ---------- Page layout ----------

    private interface PageSink {
        fun begin(): Canvas
        fun end()
    }

    private class Cursor(var canvas: Canvas, var y: Float)

    private fun render(title: String, encrypted: Boolean, blocks: List<Block>, sink: PageSink) {
        val contentWidth = PAGE_W - 2 * MARGIN
        val cursor = Cursor(sink.begin(), MARGIN.toFloat())
        fun ensure(height: Float) {
            if (cursor.y + height > PAGE_H - MARGIN && cursor.y > MARGIN + 1) {
                sink.end()
                cursor.canvas = sink.begin()
                cursor.y = MARGIN.toFloat()
            }
        }

        fun drawLayout(layout: StaticLayout) {
            var line = 0
            while (line < layout.lineCount) {
                val available = PAGE_H - MARGIN - cursor.y
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - layout.getLineTop(line) <= available) end++
                if (end == line) {
                    if (cursor.y > MARGIN + 1) {
                        sink.end()
                        cursor.canvas = sink.begin()
                        cursor.y = MARGIN.toFloat()
                        continue
                    }
                    end = line + 1
                }
                val top = layout.getLineTop(line).toFloat()
                val bottom = layout.getLineBottom(end - 1).toFloat()
                val c = cursor.canvas
                c.save()
                c.translate(MARGIN.toFloat(), cursor.y - top)
                c.clipRect(0f, top, contentWidth.toFloat(), bottom)
                layout.draw(c)
                c.restore()
                cursor.y += bottom - top
                line = end
            }
        }

        fun layoutOf(text: CharSequence, paint: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f
            color = INK
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = INK
            typeface = Typeface.SANS_SERIF
        }
        val mutedPaint = TextPaint(bodyPaint).apply { color = MUTED; textSize = 10f }
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.6f
            color = 0xFFD9D3C9.toInt()
        }

        if (title.isNotBlank()) {
            drawLayout(layoutOf(title, titlePaint))
            cursor.y += 14f
        }

        for (b in blocks) {
            when (val c = b.content) {
                is TextContent -> {
                    if (c.html.isBlank()) continue
                    val spanned = Html.fromHtml(c.html, Html.FROM_HTML_MODE_COMPACT)
                    if (spanned.isBlank()) continue
                    drawLayout(layoutOf(trimTrailing(spanned), bodyPaint))
                    cursor.y += 8f
                }
                is ChecklistContent -> {
                    val label = (if (c.done) "☑  " else "☐  ") + c.text +
                        (c.dueAt?.let { "   · " + formatDue(it, c.hasTime) } ?: "")
                    val text = SpannableString(label)
                    if (c.done) text.setSpan(StrikethroughSpan(), 3, label.length, 0)
                    drawLayout(layoutOf(text, bodyPaint))
                    cursor.y += 4f
                }
                is ImageContent -> {
                    val file = runCatching { attachments.readable(c.path, encrypted) }.getOrNull() ?: continue
                    val bmp = BitmapUtil.decodeUpright(file, 1600) ?: continue
                    val maxH = (PAGE_H - 2 * MARGIN) * 0.7f
                    val scale = min(contentWidth / bmp.width.toFloat(), maxH / bmp.height.toFloat())
                    val w = bmp.width * scale
                    val h = bmp.height * scale
                    ensure(h)
                    cursor.canvas.drawBitmap(bmp, null, RectF(MARGIN.toFloat(), cursor.y, MARGIN + w, cursor.y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    bmp.recycle()
                    cursor.y += h + 10f
                }
                is DrawingContent -> {
                    if (c.strokes.isEmpty()) continue
                    val maxH = (PAGE_H - 2 * MARGIN) * 0.8f
                    val w = min(contentWidth.toFloat(), maxH / c.aspect)
                    val h = w * c.aspect
                    ensure(h)
                    val canvas = cursor.canvas
                    canvas.save()
                    canvas.translate(MARGIN.toFloat(), cursor.y)
                    canvas.drawRoundRect(RectF(0f, 0f, w, h), 6f, 6f, framePaint)
                    canvas.clipRect(0f, 0f, w, h)
                    InkRenderer.drawStrokes(canvas, c.strokes, w)
                    canvas.restore()
                    cursor.y += h + 10f
                }
                is AudioContent -> {
                    drawLayout(layoutOf("🎙  Voice recording · ${formatDuration(c.durationMs)}", mutedPaint))
                    cursor.y += 6f
                }
                is PdfContent -> {
                    val thumb = runCatching { firstPdfPage(attachments.readable(c.path, encrypted), contentWidth / 2) }.getOrNull()
                    if (thumb != null) {
                        val h = thumb.height.toFloat() * (contentWidth / 2f) / thumb.width
                        ensure(h + 16f)
                        cursor.canvas.drawBitmap(thumb, null, RectF(MARGIN.toFloat(), cursor.y, MARGIN + contentWidth / 2f, cursor.y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                        cursor.canvas.drawRect(RectF(MARGIN.toFloat(), cursor.y, MARGIN + contentWidth / 2f, cursor.y + h), framePaint)
                        thumb.recycle()
                        cursor.y += h + 4f
                    }
                    drawLayout(layoutOf("PDF · ${c.name} · ${c.pageCount} pages", mutedPaint))
                    cursor.y += 8f
                }
                is FileContent -> {
                    drawLayout(layoutOf("📎  ${c.name} · ${formatSize(c.size)}", mutedPaint))
                    cursor.y += 6f
                }
            }
        }
        sink.end()
    }

    private fun trimTrailing(text: CharSequence): CharSequence {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        return text.subSequence(0, end)
    }

    private fun firstPdfPage(file: File, targetWidth: Int): Bitmap? {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (renderer.pageCount == 0) return null
                val page = renderer.openPage(0)
                try {
                    val w = targetWidth * 2
                    val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        }
    }

    private fun formatDue(at: Long, hasTime: Boolean): String {
        val dt = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return if (hasTime) dt.format(dateTimeFormat) else dt.format(dateFormat)
    }

    companion object {
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val MARGIN = 48
        private val INK = 0xFF1E1D1B.toInt()
        private val MUTED = 0xFF5D5A54.toInt()

        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(60).ifBlank { "Note" }

        fun formatDuration(ms: Long): String {
            val total = ms / 1000
            return "%d:%02d".format(total / 60, total % 60)
        }

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
