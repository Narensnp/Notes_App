package com.layers.app.data.repo

import com.layers.app.data.db.LayersDatabase
import com.layers.app.data.db.NoteEntity
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.FileContent
import com.layers.app.data.model.HitKind
import com.layers.app.data.model.PdfContent
import com.layers.app.data.model.SearchFilters
import com.layers.app.data.model.SearchHit
import com.layers.app.data.model.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the FTS4 index in sync. Each note gets one row per searchable piece (title, text block,
 * checklist item, file name, OCR text of an image, text of a PDF page). Locked and trashed notes
 * get no rows at all, so they can never show up in search.
 */
class SearchIndexer(
    private val db: LayersDatabase,
    private val codec: BlockCodec,
) {
    suspend fun reindex(noteId: String) = withContext(Dispatchers.IO) {
        val note = db.notes().get(noteId)
        db.search().deleteForNote(noteId)
        if (note == null || note.deletedAt != null || note.encrypted || note.locked) return@withContext
        if (note.title.isNotBlank()) {
            db.search().insert(noteId, "", KIND_TITLE, note.title, note.title)
        }
        val blocks = db.blocks().forNote(noteId).mapNotNull { runCatching { codec.fromEntity(it) }.getOrNull() }
        for (b in blocks) {
            when (val c = b.content) {
                is TextContent -> {
                    val plain = TextUtil.htmlToPlain(c.html)
                    if (plain.isNotBlank()) db.search().insert(noteId, b.id, KIND_TEXT, "", plain)
                }
                is ChecklistContent -> if (c.text.isNotBlank()) db.search().insert(noteId, b.id, KIND_TASK, "", c.text)
                is FileContent -> db.search().insert(noteId, b.id, KIND_FILE, "", c.name)
                is PdfContent -> db.search().insert(noteId, b.id, KIND_FILE, "", c.name)
                else -> Unit
            }
        }
        for (row in db.extractedText().forNote(noteId)) {
            if (row.text.isBlank()) continue
            val kind = if (row.kind == EXTRACT_OCR) KIND_IMAGE else KIND_PDF
            db.search().insert(noteId, row.blockId, kind, "", row.text)
        }
    }

    suspend fun removeNotes(noteIds: List<String>) = withContext(Dispatchers.IO) {
        if (noteIds.isNotEmpty()) db.search().deleteForNotes(noteIds)
    }

    suspend fun rebuildAll() = withContext(Dispatchers.IO) {
        db.search().deleteAll()
        db.notes().getAllActive().forEach { reindex(it.id) }
    }

    companion object {
        const val KIND_TITLE = "title"
        const val KIND_TEXT = "text"
        const val KIND_TASK = "task"
        const val KIND_FILE = "file"
        const val KIND_IMAGE = "image"
        const val KIND_PDF = "pdf"
        const val EXTRACT_OCR = "ocr"
        const val EXTRACT_PDF = "pdf"
    }
}

class SearchRepository(
    private val db: LayersDatabase,
    private val notes: NoteRepository,
    private val folders: FolderRepository,
) {
    /** Turns what the user typed into an FTS4 prefix query: "trip pho" → `trip* pho*`. */
    private fun toMatch(query: String): String? {
        val tokens = query.lowercase()
            .split(Regex("\\s+"))
            .map { t -> t.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    fun terms(query: String): List<String> = query.lowercase()
        .split(Regex("\\s+"))
        .map { t -> t.filter { it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }

    suspend fun search(query: String, filters: SearchFilters): List<SearchHit> = withContext(Dispatchers.IO) {
        val match = toMatch(query)
        if (match == null && !filters.isActive) return@withContext emptyList()

        // Collect candidate notes, with the best-matching row for each.
        data class Best(val blockId: String?, val kind: String, val snippet: String)
        val best = LinkedHashMap<String, Best>()
        if (match != null) {
            val rows = runCatching { db.search().search(match) }.getOrDefault(emptyList())
            for (r in rows) {
                val existing = best[r.noteId]
                val score = rank(r.kind, r.snippet)
                if (existing == null || score > rank(existing.kind, existing.snippet)) {
                    best[r.noteId] = Best(r.blockId.ifEmpty { null }, r.kind, r.snippet)
                }
            }
        }

        val candidates: List<NoteEntity> = if (match != null) {
            db.notes().getMany(best.keys.toList())
        } else {
            db.notes().getAllActive()
        }

        val folderScope: Set<String>? = filters.folderId?.let { id -> folders.descendantFolderIds(id) + id }
        val tagScope: Set<String>? = filters.tagId?.let { db.tags().noteIdsWithTag(it).toSet() }

        val filtered = candidates.filter { n ->
            n.deletedAt == null && !n.encrypted && !n.locked &&
                (folderScope == null || (n.folderId != null && n.folderId in folderScope)) &&
                (tagScope == null || n.id in tagScope || rootIdInScope(n, tagScope)) &&
                (!filters.hasTasks || n.hasTasks) &&
                (!filters.hasImages || n.hasImages) &&
                (!filters.hasPdfs || n.hasPdfs) &&
                (filters.fromMillis == null || n.updatedAt >= filters.fromMillis) &&
                (filters.toMillis == null || n.updatedAt <= filters.toMillis)
        }

        val ordered = if (match != null) {
            val order = best.keys.withIndex().associate { it.value to it.index }
            filtered.sortedWith(compareBy<NoteEntity> { order[it.id] ?: Int.MAX_VALUE })
        } else {
            filtered.sortedByDescending { it.updatedAt }
        }

        ordered.take(150).map { n ->
            val b = best[n.id]
            val crumbs = notes.breadcrumb(n.id)
            val depth = crumbs.count { it.kind == com.layers.app.data.model.CrumbKind.ANCHOR }
            SearchHit(
                noteId = n.id,
                title = n.title.ifBlank { if (n.parentNoteId != null) "Untitled layer" else "Untitled note" },
                snippet = when {
                    b == null -> n.preview
                    b.kind == SearchIndexer.KIND_TITLE -> n.preview.ifBlank { "" }
                    else -> b.snippet
                },
                kind = when (b?.kind) {
                    SearchIndexer.KIND_TITLE -> HitKind.TITLE
                    SearchIndexer.KIND_TEXT -> HitKind.TEXT
                    SearchIndexer.KIND_TASK -> HitKind.TASK
                    SearchIndexer.KIND_IMAGE -> HitKind.IMAGE
                    SearchIndexer.KIND_PDF -> HitKind.PDF
                    SearchIndexer.KIND_FILE -> HitKind.FILE
                    else -> HitKind.NOTE
                },
                breadcrumb = crumbs.dropLast(1).joinToString(" › ") { it.label },
                blockId = b?.blockId,
                updatedAt = n.updatedAt,
                layerDepth = depth,
            )
        }
    }

    /** A sub-layer counts as tagged when its top-level note carries the tag. */
    private suspend fun rootIdInScope(note: NoteEntity, scope: Set<String>): Boolean {
        var current: NoteEntity? = note
        var guard = 0
        while (current?.parentNoteId != null && guard < 64) {
            current = db.notes().get(current.parentNoteId!!)
            if (current != null && current.id in scope) return true
            guard++
        }
        return false
    }

    private fun rank(kind: String, snippet: String): Int {
        val hasMark = snippet.contains("[[")
        return when (kind) {
            SearchIndexer.KIND_TITLE -> 50
            SearchIndexer.KIND_TEXT, SearchIndexer.KIND_TASK -> if (hasMark) 40 else 10
            SearchIndexer.KIND_FILE -> if (hasMark) 30 else 8
            else -> if (hasMark) 20 else 5
        }
    }
}
