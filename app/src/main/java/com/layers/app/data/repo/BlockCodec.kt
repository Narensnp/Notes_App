package com.layers.app.data.repo

import android.text.Html
import com.layers.app.data.db.BlockEntity
import com.layers.app.data.model.Block
import com.layers.app.data.model.BlockContent
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.TextContent
import com.layers.app.data.model.decodeContent
import com.layers.app.data.model.encodeContent
import com.layers.app.data.model.filePath
import com.layers.app.data.model.typeName
import com.layers.app.security.KeystoreCrypto

/** Converts blocks to and from database rows, encrypting the JSON for locked notes. */
class BlockCodec(private val crypto: KeystoreCrypto) {

    fun toEntity(noteId: String, position: Int, block: Block, encrypted: Boolean, now: Long): BlockEntity {
        val json = encodeContent(block.content)
        val stored = if (encrypted) crypto.encryptString(json) else json
        return BlockEntity(
            id = block.id,
            noteId = noteId,
            position = position,
            type = block.content.typeName,
            content = stored,
            filePath = block.content.filePath,
            updatedAt = now,
        )
    }

    fun fromEntity(entity: BlockEntity): Block {
        val json = if (KeystoreCrypto.isEncryptedString(entity.content)) {
            crypto.decryptString(entity.content)
        } else {
            entity.content
        }
        return Block(entity.id, decodeContent(json))
    }

    fun contentOf(entity: BlockEntity): BlockContent = fromEntity(entity).content

    fun encryptEntity(entity: BlockEntity): BlockEntity =
        if (KeystoreCrypto.isEncryptedString(entity.content)) entity
        else entity.copy(content = crypto.encryptString(entity.content))

    fun decryptEntity(entity: BlockEntity): BlockEntity =
        if (KeystoreCrypto.isEncryptedString(entity.content)) entity.copy(content = crypto.decryptString(entity.content))
        else entity
}

object TextUtil {
    fun htmlToPlain(html: String): String =
        if (html.isBlank()) "" else Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()

    /** A short plain-text preview of a note's first lines. */
    fun preview(blocks: List<Block>, max: Int = 220): String {
        val sb = StringBuilder()
        for (b in blocks) {
            val line = when (val c = b.content) {
                is TextContent -> htmlToPlain(c.html)
                is ChecklistContent -> if (c.text.isBlank()) "" else (if (c.done) "☑ " else "☐ ") + c.text
                else -> ""
            }
            if (line.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
            if (sb.length >= max) break
        }
        return if (sb.length > max) sb.substring(0, max).trimEnd() + "…" else sb.toString()
    }

    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Plain text → simple HTML paragraphs (used for shared-in text). */
    fun plainToHtml(text: String): String =
        text.split("\n").joinToString("") { line ->
            if (line.isBlank()) "<p><br></p>" else "<p>${escapeHtml(line)}</p>"
        }
}
