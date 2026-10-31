package com.layers.app.work

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.layers.app.LayersApplication
import com.layers.app.data.db.ExtractedTextEntity
import com.layers.app.data.model.ImageContent
import com.layers.app.data.model.PdfContent
import com.layers.app.data.repo.SearchIndexer
import com.layers.app.ocr.Ocr
import com.layers.app.pdf.PdfText

/**
 * Runs in the background after an image or PDF is added to an unlocked note:
 *  - images: on-device ML Kit text recognition (bundled Latin model, nothing leaves the phone);
 *  - PDFs (Android 15+): PdfRenderer's built-in text extraction, one row per page.
 * The text goes into the search index so photos of whiteboards and receipts are searchable.
 */
class TextExtractionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LayersApplication).container
        val db = container.db
        val noteId = inputData.getString(KEY_NOTE_ID) ?: return Result.success()
        val blockId = inputData.getString(KEY_BLOCK_ID) ?: return Result.success()
        val note = db.notes().get(noteId) ?: return Result.success()
        if (note.encrypted || note.locked || note.deletedAt != null) return Result.success()
        val entity = db.blocks().get(blockId) ?: return Result.success()
        val content = runCatching { container.codec.contentOf(entity) }.getOrNull() ?: return Result.success()

        val rows: List<ExtractedTextEntity> = try {
            when (content) {
                is ImageContent -> {
                    val file = container.attachments.file(content.path)
                    if (!file.exists()) return Result.success()
                    val text = Ocr.recognize(file)
                    listOf(ExtractedTextEntity("$blockId#ocr", blockId, noteId, SearchIndexer.EXTRACT_OCR, -1, text))
                }
                is PdfContent -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return Result.success()
                    val file = container.attachments.file(content.path)
                    if (!file.exists()) return Result.success()
                    PdfText.allPages(file).mapIndexed { page, text ->
                        ExtractedTextEntity("$blockId#$page", blockId, noteId, SearchIndexer.EXTRACT_PDF, page, text)
                    }.filter { it.text.isNotBlank() }
                }
                else -> return Result.success()
            }
        } catch (e: Exception) {
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }

        // The note may have been locked while we worked: never store its text in plain form then.
        val fresh = db.notes().get(noteId)
        if (fresh == null || fresh.encrypted || fresh.locked || fresh.deletedAt != null) return Result.success()
        db.extractedText().deleteForBlock(blockId)
        if (rows.isNotEmpty()) db.extractedText().upsertAll(rows)
        container.indexer.reindex(noteId)
        return Result.success()
    }

    companion object {
        const val KEY_NOTE_ID = "noteId"
        const val KEY_BLOCK_ID = "blockId"
    }
}

/** Daily: permanently removes trash older than 30 days and unused attachment files. */
class TrashPurgeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LayersApplication).container
        return try {
            container.trash.purgeExpired()
            container.attachments.clearSharedCache()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
