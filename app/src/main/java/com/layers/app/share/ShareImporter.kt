package com.layers.app.share

import android.net.Uri
import com.layers.app.AppContainer
import com.layers.app.data.model.Block
import com.layers.app.data.model.BlockContent
import com.layers.app.data.model.FileContent
import com.layers.app.data.model.ImageContent
import com.layers.app.data.model.PdfContent
import com.layers.app.data.model.TextContent
import com.layers.app.data.model.newId
import com.layers.app.data.repo.TextUtil
import com.layers.app.pdf.PdfSource

/** What another app shared into Layers. */
data class SharedContent(val text: String?, val subject: String?, val uris: List<Uri>)

/** Turns shared text, links, images, PDFs and files into blocks, and saves them. */
class ShareImporter(private val c: AppContainer) {

    /** Imports attachments (copying them into Layers) and returns the blocks plus an empty text block to type in. */
    private suspend fun blocksFor(content: SharedContent): Pair<List<Block>, String> {
        val blocks = ArrayList<Block>()
        val text = content.text?.trim()
        if (!text.isNullOrEmpty()) blocks += Block(newId(), TextContent(TextUtil.plainToHtml(text)))
        for (uri in content.uris) {
            val imported = runCatching { c.attachments.importUri(uri, "Shared file") }.getOrNull() ?: continue
            val block: BlockContent = when {
                imported.mime.startsWith("image/") -> {
                    val (w, h) = c.attachments.imageSize(imported.relativePath, false)
                    ImageContent(imported.relativePath, w, h)
                }
                imported.mime == "application/pdf" || imported.displayName.endsWith(".pdf", true) -> {
                    PdfContent(imported.relativePath, imported.displayName, PdfSource.pageCount(c.attachments.file(imported.relativePath)))
                }
                else -> FileContent(imported.relativePath, imported.displayName, imported.mime, imported.size)
            }
            blocks += Block(newId(), block)
        }
        val tail = Block(newId(), TextContent())
        blocks += tail
        return blocks to tail.id
    }

    /** Creates a note and returns (noteId, id of the block where the cursor should go). */
    suspend fun saveAsNewNote(content: SharedContent, folderId: String?): Pair<String, String> {
        val (blocks, focus) = blocksFor(content)
        val title = content.subject?.trim()?.take(120).orEmpty()
        c.settings.touchRecentFolder(folderId)
        val noteId = c.notes.createNote(folderId, title, blocks)
        return noteId to focus
    }

    /** Appends to an existing note and returns the id of the block where the cursor should go. */
    suspend fun addToNote(content: SharedContent, noteId: String): String {
        val (blocks, focus) = blocksFor(content)
        c.notes.appendBlocks(noteId, blocks)
        return focus
    }
}
