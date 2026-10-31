package com.layers.app.data.model

import com.layers.app.data.db.FolderEntity
import com.layers.app.data.db.NoteEntity
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** A note shows as locked if it is locked itself or inherits a lock (and is therefore encrypted). */
val NoteEntity.isLocked: Boolean get() = locked || encrypted

val NoteEntity.isLayer: Boolean get() = parentNoteId != null

enum class CrumbKind { HOME, FOLDER, NOTE, ANCHOR }

/** One step of a breadcrumb path: Home › Work › Trip notes › Photo › Sub-note. */
data class Crumb(
    val label: String,
    val kind: CrumbKind,
    /** Folder id for FOLDER (null for HOME), note id for NOTE, parent note id for ANCHOR. */
    val targetId: String?,
    val blockId: String? = null,
)

data class NoteListItem(
    val note: NoteEntity,
    val layerCount: Int,
)

data class FolderNode(
    val folder: FolderEntity,
    val depth: Int,
)

/** One row of the Layers outline tree. */
data class OutlineNode(
    val note: NoteEntity,
    val anchorLabel: String,
    val depth: Int,
    val childCount: Int,
)

data class SearchFilters(
    val folderId: String? = null,
    val tagId: String? = null,
    val hasTasks: Boolean = false,
    val hasImages: Boolean = false,
    val hasPdfs: Boolean = false,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
) {
    val isActive: Boolean
        get() = folderId != null || tagId != null || hasTasks || hasImages || hasPdfs ||
            fromMillis != null || toMillis != null
}

enum class HitKind { TITLE, TEXT, TASK, IMAGE, PDF, FILE, NOTE }

data class SearchHit(
    val noteId: String,
    val title: String,
    /** Snippet with [[ and ]] around matched words. */
    val snippet: String,
    val kind: HitKind,
    val breadcrumb: String,
    val blockId: String?,
    val updatedAt: Long,
    val layerDepth: Int,
)

object FolderPalette {
    /** Muted, paper-friendly accent colors for folders, notes and tags. */
    val colors: List<Long> = listOf(
        0xFF6B7F94, // slate
        0xFF4F7A73, // sage teal
        0xFF7A8F5A, // olive
        0xFFC28B3C, // ochre
        0xFFB8664F, // terracotta
        0xFFA95C78, // mulberry
        0xFF7B6AA8, // lavender ink
        0xFF3F6E9E, // ink blue
        0xFF8A7765, // walnut
        0xFF5E6268, // graphite
    )
    val default: Long get() = colors.first()
}
