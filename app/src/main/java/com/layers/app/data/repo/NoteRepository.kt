package com.layers.app.data.repo

import androidx.room.withTransaction
import com.layers.app.data.db.LayersDatabase
import com.layers.app.data.db.NoteEntity
import com.layers.app.data.db.PinEntity
import com.layers.app.data.files.AttachmentStore
import com.layers.app.data.model.Block
import com.layers.app.data.model.BlockTypes
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.Crumb
import com.layers.app.data.model.CrumbKind
import com.layers.app.data.model.ImageContent
import com.layers.app.data.model.NoteListItem
import com.layers.app.data.model.OutlineNode
import com.layers.app.data.model.PdfContent
import com.layers.app.data.model.TextContent
import com.layers.app.data.model.encodeContent
import com.layers.app.data.model.filePath
import com.layers.app.data.model.newId
import com.layers.app.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Notes, their blocks, pins and sub-layers. */
class NoteRepository(
    private val db: LayersDatabase,
    private val codec: BlockCodec,
    private val attachments: AttachmentStore,
    private val tasks: TaskRepository,
    private val indexer: SearchIndexer,
    private val trash: TrashRepository,
    private val lockManager: LockManager,
    private val work: WorkScheduler,
) {
    private val saveMutex = Mutex()

    /** Fingerprints of what was last written per block, so autosave only writes blocks that changed. */
    private val savedFingerprint = ConcurrentHashMap<String, Long>()

    // ---------- Observing ----------

    fun observeNotesInFolder(folderId: String?): Flow<List<NoteListItem>> =
        db.notes().observeInFolder(folderId).map { list -> list.map { NoteListItem(it.note, it.layerCount) } }

    fun observeNotesWithTag(tagId: String): Flow<List<NoteListItem>> =
        db.notes().observeWithTag(tagId).map { list -> list.map { NoteListItem(it.note, it.layerCount) } }

    fun observeRecentNotes(): Flow<List<NoteEntity>> = db.notes().observeRecentTopLevel()

    fun observeNote(noteId: String): Flow<NoteEntity?> = db.notes().observe(noteId)

    fun observePins(noteId: String): Flow<List<PinEntity>> = db.pins().observeForNote(noteId)

    fun observeLayerCounts(noteId: String): Flow<Map<String, Int>> =
        db.notes().observeLayerCounts(noteId).map { list -> list.associate { it.blockId to it.count } }

    fun observePageLayers(blockId: String): Flow<List<NoteEntity>> = db.notes().observePageLayers(blockId)

    fun observeChildren(noteId: String): Flow<List<NoteEntity>> = db.notes().observeChildren(noteId)

    suspend fun getNote(noteId: String): NoteEntity? = withContext(Dispatchers.IO) { db.notes().get(noteId) }

    // ---------- Creating ----------

    suspend fun createNote(
        folderId: String?,
        title: String = "",
        blocks: List<Block> = listOf(Block(newId(), TextContent())),
    ): String = withContext(Dispatchers.IO) {
        val id = newId()
        val now = System.currentTimeMillis()
        val encrypted = lockManager.isFolderLocked(folderId)
        db.notes().upsert(
            NoteEntity(id = id, folderId = folderId, title = title, encrypted = encrypted, createdAt = now, updatedAt = now)
        )
        saveBlocks(id, blocks)
        id
    }

    /** The layer hanging under a whole block (image, drawing, PDF, file, recording). Created on first use. */
    suspend fun openOrCreateBlockLayer(parentNoteId: String, blockId: String): String = withContext(Dispatchers.IO) {
        db.notes().blockLayer(blockId)?.let { return@withContext it.id }
        val parent = db.notes().get(parentNoteId) ?: error("Parent note missing")
        createChild(parent, blockId, pinId = null, page = null, x = null, y = null).id
    }

    suspend fun existingBlockLayer(blockId: String): String? = withContext(Dispatchers.IO) { db.notes().blockLayer(blockId)?.id }

    /** The layer under one page of a PDF block. */
    suspend fun openOrCreatePageLayer(parentNoteId: String, blockId: String, page: Int): String = withContext(Dispatchers.IO) {
        db.notes().pageLayer(blockId, page)?.let { return@withContext it.id }
        val parent = db.notes().get(parentNoteId) ?: error("Parent note missing")
        createChild(parent, blockId, pinId = null, page = page, x = null, y = null).id
    }

    /** Drops a numbered pin on an image and creates its sub-note. Returns the sub-note id. */
    suspend fun addPin(noteId: String, blockId: String, x: Float, y: Float): String = withContext(Dispatchers.IO) {
        val parent = db.notes().get(noteId) ?: error("Note missing")
        val number = db.pins().maxNumber(blockId) + 1
        val pinId = newId()
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        val child = createChild(parent, blockId, pinId = pinId, page = null, x = nx, y = ny, title = "Pin $number")
        db.pins().upsert(
            PinEntity(
                id = pinId, blockId = blockId, noteId = noteId, subNoteId = child.id,
                number = number, x = nx, y = ny, createdAt = System.currentTimeMillis(),
            )
        )
        child.id
    }

    /** Removes a pin; its sub-note (and everything under it) goes to the trash. */
    suspend fun deletePin(pinId: String) = withContext(Dispatchers.IO) {
        val pin = db.pins().get(pinId) ?: return@withContext
        db.pins().delete(pinId)
        trash.trashNote(pin.subNoteId)
    }

    private suspend fun createChild(
        parent: NoteEntity,
        blockId: String,
        pinId: String?,
        page: Int?,
        x: Float?,
        y: Float?,
        title: String = "",
    ): NoteEntity {
        val now = System.currentTimeMillis()
        val child = NoteEntity(
            id = newId(),
            folderId = parent.folderId,
            parentNoteId = parent.id,
            anchorBlockId = blockId,
            anchorPinId = pinId,
            anchorPage = page,
            pinX = x,
            pinY = y,
            title = title,
            encrypted = parent.encrypted || parent.locked,
            createdAt = now,
            updatedAt = now,
        )
        db.notes().upsert(child)
        saveBlocks(child.id, listOf(Block(newId(), TextContent())))
        return child
    }

    // ---------- Blocks ----------

    suspend fun loadBlocks(noteId: String): List<Block> = withContext(Dispatchers.IO) {
        db.blocks().forNote(noteId).map { e ->
            val block = codec.fromEntity(e)
            savedFingerprint[e.id] = fingerprint(e.position, encodeContent(block.content))
            block
        }
    }

    /**
     * Saves the full, ordered block list of a note: writes changed blocks, removes deleted ones
     * (their sub-layers go to the trash), refreshes the preview, tasks, reminders and search index.
     */
    suspend fun saveBlocks(noteId: String, blocks: List<Block>) = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            val note = db.notes().get(noteId) ?: return@withLock
            val now = System.currentTimeMillis()
            val existing = db.blocks().forNote(noteId)
            val existingById = existing.associateBy { it.id }
            val newIds = blocks.map { it.id }.toHashSet()
            val removedIds = existing.filter { it.id !in newIds }.map { it.id }

            if (note.encrypted) {
                blocks.mapNotNull { it.content.filePath }.forEach { attachments.encryptInPlace(it) }
            }

            val changed = blocks.mapIndexedNotNull { index, block ->
                val json = encodeContent(block.content)
                val fp = fingerprint(index, json)
                val old = existingById[block.id]
                if (old != null && savedFingerprint[block.id] == fp &&
                    old.position == index && (note.encrypted == isStoredEncrypted(old.content))
                ) {
                    null
                } else {
                    savedFingerprint[block.id] = fp
                    codec.toEntity(noteId, index, block, note.encrypted, now)
                }
            }

            val firstImage = blocks.firstNotNullOfOrNull { (it.content as? ImageContent)?.path }
            val updatedNote = note.copy(
                updatedAt = if (changed.isEmpty() && removedIds.isEmpty()) note.updatedAt else now,
                preview = if (note.encrypted) "" else TextUtil.preview(blocks),
                thumbnailPath = if (note.encrypted) null else firstImage,
                hasTasks = blocks.any { it.content is ChecklistContent },
                hasImages = blocks.any { it.content is ImageContent },
                hasPdfs = blocks.any { it.content is PdfContent },
            )

            db.withTransaction {
                if (changed.isNotEmpty()) db.blocks().upsertAll(changed)
                if (removedIds.isNotEmpty()) {
                    db.blocks().deleteIds(removedIds)
                    db.pins().deleteForBlocks(removedIds)
                    removedIds.forEach { db.extractedText().deleteForBlock(it) }
                }
                if (updatedNote != note) db.notes().upsert(updatedNote)
            }
            removedIds.forEach { savedFingerprint.remove(it) }

            // Sub-layers of removed blocks go to the trash with them.
            for (id in removedIds) {
                db.notes().layersForBlock(id).forEach { trash.trashNote(it.id) }
            }

            tasks.syncNote(updatedNote, blocks)
            if (changed.isNotEmpty() || removedIds.isNotEmpty()) indexer.reindex(noteId)

            if (!note.encrypted) {
                for (b in blocks) {
                    val c = b.content
                    if (c is ImageContent || c is PdfContent) {
                        val old = existingById[b.id]
                        if (old == null || old.filePath != c.filePath) work.enqueueExtraction(noteId, b.id)
                    }
                }
            }
        }
    }

    /** Changes one block (used by the drawing and PDF screens, which edit a block outside the editor). */
    suspend fun updateBlock(noteId: String, blockId: String, transform: (com.layers.app.data.model.BlockContent) -> com.layers.app.data.model.BlockContent) =
        withContext(Dispatchers.IO) {
            val blocks = loadBlocks(noteId)
            if (blocks.none { it.id == blockId }) return@withContext
            saveBlocks(noteId, blocks.map { if (it.id == blockId) it.copy(content = transform(it.content)) else it })
        }

    suspend fun loadBlock(blockId: String): Block? = withContext(Dispatchers.IO) {
        db.blocks().get(blockId)?.let { runCatching { codec.fromEntity(it) }.getOrNull() }
    }

    suspend fun appendBlocks(noteId: String, newBlocks: List<Block>) = withContext(Dispatchers.IO) {
        val current = loadBlocks(noteId)
        val trimmed = if (current.size == 1 && (current[0].content as? TextContent)?.html?.let { TextUtil.htmlToPlain(it).isBlank() } == true) {
            emptyList()
        } else current
        saveBlocks(noteId, trimmed + newBlocks)
    }

    private fun isStoredEncrypted(content: String): Boolean =
        com.layers.app.security.KeystoreCrypto.isEncryptedString(content)

    private fun fingerprint(position: Int, json: String): Long =
        (json.hashCode().toLong() shl 32) xor (json.length.toLong() shl 12) xor position.toLong()

    // ---------- Note properties ----------

    suspend fun updateTitle(noteId: String, title: String) = withContext(Dispatchers.IO) {
        val note = db.notes().get(noteId) ?: return@withContext
        if (note.title == title) return@withContext
        db.notes().upsert(note.copy(title = title, updatedAt = System.currentTimeMillis()))
        indexer.reindex(noteId)
    }

    suspend fun setColor(noteId: String, color: Long?) = withContext(Dispatchers.IO) {
        val note = db.notes().get(noteId) ?: return@withContext
        db.notes().upsert(note.copy(color = color))
    }

    suspend fun setPinned(noteId: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        val note = db.notes().get(noteId) ?: return@withContext
        db.notes().upsert(note.copy(pinned = pinned))
    }

    /** Moves a note (with all its sub-layers) to another folder; folder locks are applied or lifted. */
    suspend fun moveNote(noteId: String, folderId: String?) = withContext(Dispatchers.IO) {
        val note = db.notes().get(noteId) ?: return@withContext
        val all = listOf(note) + trash.descendantNotes(noteId)
        db.notes().upsertAll(all.map { it.copy(folderId = folderId) })
        lockManager.reconcile(listOf(noteId))
    }

    suspend fun setLocked(noteId: String, locked: Boolean) = withContext(Dispatchers.IO) {
        val note = db.notes().get(noteId) ?: return@withContext
        db.notes().upsert(note.copy(locked = locked))
        lockManager.reconcile(listOf(noteId))
        // Previews and fingerprints changed shape; forget cached fingerprints for this note's blocks.
        db.blocks().forNote(noteId).forEach { savedFingerprint.remove(it.id) }
    }

    suspend fun isEffectivelyLocked(noteId: String): Boolean = withContext(Dispatchers.IO) {
        val note = db.notes().get(noteId) ?: return@withContext false
        lockManager.isEffectivelyLocked(note)
    }

    suspend fun trashNote(noteId: String) = trash.trashNote(noteId)

    /** How many sub-layers (at any depth) live under this note. */
    suspend fun countLayersUnder(noteId: String): Int = trash.descendantNotes(noteId).size

    /** How many sub-layers (at any depth) hang under this block, including pins and PDF pages. */
    suspend fun countLayersForBlock(blockId: String): Int = withContext(Dispatchers.IO) {
        val direct = db.notes().layersForBlock(blockId)
        direct.size + direct.sumOf { trash.descendantNotes(it.id).size }
    }

    // ---------- Paths & outline ----------

    /** Home › Folder › … › Note › Photo · Pin 2 › Sub-note. */
    suspend fun breadcrumb(noteId: String): List<Crumb> = withContext(Dispatchers.IO) {
        val reversed = ArrayList<Crumb>()
        var note = db.notes().get(noteId) ?: return@withContext listOf(Crumb("Home", CrumbKind.HOME, null))
        val seen = HashSet<String>()
        while (seen.add(note.id)) {
            reversed += Crumb(displayTitle(note), CrumbKind.NOTE, note.id)
            val parentId = note.parentNoteId ?: break
            val parent = db.notes().get(parentId) ?: break
            reversed += Crumb(anchorLabel(note), CrumbKind.ANCHOR, parent.id, note.anchorBlockId)
            note = parent
        }
        val seenFolders = HashSet<String>()
        var folder = note.folderId?.let { db.folders().get(it) }
        while (folder != null && seenFolders.add(folder.id)) {
            reversed += Crumb(folder.name, CrumbKind.FOLDER, folder.id)
            folder = folder.parentId?.let { db.folders().get(it) }
        }
        reversed += Crumb("Home", CrumbKind.HOME, null)
        reversed.reversed()
    }

    /** The whole tree of sub-layers under a note, depth-first. */
    suspend fun outline(noteId: String): List<OutlineNode> = withContext(Dispatchers.IO) {
        val result = ArrayList<OutlineNode>()
        val seen = HashSet<String>()
        suspend fun visit(id: String, depth: Int) {
            if (!seen.add(id)) return
            val children = db.notes().children(id)
            for (child in children) {
                val grandChildren = db.notes().children(child.id).size
                result += OutlineNode(child, anchorLabel(child), depth, grandChildren)
                visit(child.id, depth + 1)
            }
        }
        visit(noteId, 0)
        result
    }

    suspend fun anchorLabel(note: NoteEntity): String {
        val blockLabel = note.anchorBlockId?.let { id ->
            when (db.blocks().get(id)?.type) {
                BlockTypes.IMAGE -> "Photo"
                BlockTypes.DRAWING -> "Drawing"
                BlockTypes.PDF -> "PDF"
                BlockTypes.AUDIO -> "Recording"
                BlockTypes.FILE -> "File"
                BlockTypes.CHECK -> "Task"
                BlockTypes.TEXT -> "Text"
                else -> "Block"
            }
        } ?: "Layer"
        val pinNumber = note.anchorPinId?.let { db.pins().get(it)?.number }
        return when {
            pinNumber != null -> "$blockLabel · Pin $pinNumber"
            note.anchorPage != null -> "$blockLabel · Page ${note.anchorPage + 1}"
            else -> blockLabel
        }
    }

    companion object {
        fun displayTitle(note: NoteEntity): String =
            note.title.ifBlank { if (note.parentNoteId != null) "Untitled layer" else "Untitled note" }
    }
}
