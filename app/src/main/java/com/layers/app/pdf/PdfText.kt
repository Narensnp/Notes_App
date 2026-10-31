package com.layers.app.pdf

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Text extraction from PDFs using PdfRenderer.Page.getTextContents(), available from Android 15
 * (API 35). The call goes through reflection so the app also builds and runs unchanged on older
 * versions, where the feature is simply hidden.
 */
object PdfText {

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    fun allPages(file: File): List<String> {
        if (!isSupported) return emptyList()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                return (0 until renderer.pageCount).map { index ->
                    val page = renderer.openPage(index)
                    try {
                        pageText(page)
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        }
    }

    fun pageText(page: PdfRenderer.Page): String {
        if (!isSupported) return ""
        return runCatching {
            val contents = page.javaClass.getMethod("getTextContents").invoke(page) as? List<*> ?: return ""
            contents.joinToString("\n") { item ->
                if (item == null) "" else (item.javaClass.getMethod("getText").invoke(item) as? String).orEmpty()
            }.trim()
        }.getOrDefault("")
    }
}
