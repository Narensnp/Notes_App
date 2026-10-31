package com.layers.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A folder. Folders nest without limit through the self-referencing [parentId] (null = Home).
 * Trash: [deletedAt] marks the folder as trashed, [trashRootId] groups everything that was
 * deleted together so it can be restored together.
 */
@Serializable
@Entity(tableName = "folders", indices = [Index("parentId")])
data class FolderEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
    val color: Long,
    val icon: String,
    val locked: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val trashRootId: String? = null,
)

/**
 * A note. A note with a [parentNoteId] is a sub-layer: it hangs underneath a block
 * ([anchorBlockId]) of its parent, optionally underneath a pin on an image ([anchorPinId],
 * [pinX]/[pinY] as normalized 0–1 coordinates) or a page of a PDF ([anchorPage]).
 * Sub-layers share the folder of their top-level note so folder filters and folder locks reach them.
 */
@Serializable
@Entity(
    tableName = "notes",
    indices = [Index("folderId"), Index("parentNoteId"), Index("anchorBlockId"), Index("trashRootId")]
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val parentNoteId: String? = null,
    val anchorBlockId: String? = null,
    val anchorPinId: String? = null,
    val anchorPage: Int? = null,
    val pinX: Float? = null,
    val pinY: Float? = null,
    val title: String = "",
    val color: Long? = null,
    val pinned: Boolean = false,
    /** Locked explicitly by the user (sub-layers and folder contents inherit it). */
    val locked: Boolean = false,
    /** True while the note's block contents and attachment files are stored encrypted. */
    val encrypted: Boolean = false,
    /** Plain-text preview for lists. Always empty while [encrypted]. */
    val preview: String = "",
    val thumbnailPath: String? = null,
    val hasTasks: Boolean = false,
    val hasImages: Boolean = false,
    val hasPdfs: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val trashRootId: String? = null,
)

/**
 * One block of a note. [content] is JSON (see BlockContent) — or, for encrypted notes,
 * AES-GCM ciphertext of that JSON. [filePath] is the relative attachment path, kept in plain text
 * (it is only a random UUID) so unused files can be cleaned up without decrypting anything.
 */
@Serializable
@Entity(tableName = "blocks", indices = [Index("noteId")])
data class BlockEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val position: Int,
    val type: String,
    val content: String,
    val filePath: String? = null,
    val updatedAt: Long,
)

/** A numbered pin on an image. Coordinates are normalized (0–1) so they survive resizing. */
@Serializable
@Entity(tableName = "pins", indices = [Index("blockId"), Index("noteId")])
data class PinEntity(
    @PrimaryKey val id: String,
    val blockId: String,
    val noteId: String,
    val subNoteId: String,
    val number: Int,
    val x: Float,
    val y: Float,
    val createdAt: Long,
)

/**
 * Index of checklist items that have a due date. The checklist block is the source of truth;
 * this table lets the calendar, widget and alarms query tasks quickly.
 */
@Serializable
@Entity(tableName = "tasks", indices = [Index("noteId"), Index("dueAt")])
data class TaskEntity(
    @PrimaryKey val blockId: String,
    val noteId: String,
    val title: String,
    val done: Boolean,
    val dueAt: Long?,
    val hasTime: Boolean,
    val reminderAt: Long?,
    val repeat: String,
    val locked: Boolean,
    val updatedAt: Long,
)

@Serializable
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Long,
)

@Serializable
@Entity(tableName = "note_tags", primaryKeys = ["noteId", "tagId"], indices = [Index("tagId")])
data class NoteTagCrossRef(
    val noteId: String,
    val tagId: String,
)

/** Text found inside images (OCR, kind = "ocr") and PDFs (kind = "pdf", one row per page). */
@Serializable
@Entity(tableName = "extracted_text", indices = [Index("noteId"), Index("blockId")])
data class ExtractedTextEntity(
    @PrimaryKey val id: String,
    val blockId: String,
    val noteId: String,
    val kind: String,
    val page: Int,
    val text: String,
)

/** Full-text search index (FTS4). Rows are rebuilt per note; locked notes never get rows. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["noteId", "blockId", "kind"])
@Entity(tableName = "search_fts")
data class SearchFtsEntity(
    // Rows are inserted with an INSERT query that leaves rowid to SQLite.
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val noteId: String,
    val blockId: String,
    val kind: String,
    val title: String,
    val body: String,
)
