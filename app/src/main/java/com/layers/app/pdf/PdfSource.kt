package com.layers.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * A thread-safe wrapper around PdfRenderer (which may only render one page at a time).
 * Page sizes are read once up front so the viewer can lay out every page before it is rendered.
 */
class PdfSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageSizes: List<Pair<Int, Int>>,
) : Closeable {

    private val mutex = Mutex()
    @Volatile
    private var closed = false

    val pageCount: Int get() = pageSizes.size

    /** Renders a page at [widthPx] wide, on a white background. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed || index !in pageSizes.indices) return@withContext null
            val (pw, ph) = pageSizes[index]
            val w = widthPx.coerceIn(64, 4096)
            val h = (w.toFloat() * ph / pw).toInt().coerceIn(64, 6144)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val page = renderer.openPage(index)
            try {
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            bmp
        }
    }

    /** Page text (Android 15+), or "" where unsupported. */
    suspend fun pageText(index: Int): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed || !PdfText.isSupported || index !in pageSizes.indices) return@withContext ""
            val page = renderer.openPage(index)
            try {
                PdfText.pageText(page)
            } finally {
                page.close()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    /** Closes once any render in progress has finished. */
    suspend fun closeSafely() = mutex.withLock { close() }

    companion object {
        suspend fun open(file: File): PdfSource = withContext(Dispatchers.IO) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
            val sizes = (0 until renderer.pageCount).map { i ->
                val p = renderer.openPage(i)
                try {
                    p.width.coerceAtLeast(1) to p.height.coerceAtLeast(1)
                } finally {
                    p.close()
                }
            }
            PdfSource(pfd, renderer, sizes)
        }

        suspend fun pageCount(file: File): Int = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val r = PdfRenderer(pfd)
                    try {
                        r.pageCount
                    } finally {
                        r.close()
                    }
                }
            }.getOrDefault(0)
        }

        /** Renders one page without keeping the document open (thumbnails, layer headers). */
        suspend fun renderPage(file: File, index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                val source = open(file)
                try {
                    source.render(index, widthPx)
                } finally {
                    source.close()
                }
            }.getOrNull()
        }
    }
}
