package com.layers.app.data.repo

import androidx.room.withTransaction
import com.layers.app.data.db.FolderEntity
import com.layers.app.data.db.LayersDatabase
import com.layers.app.data.db.NoteEntity
import com.layers.app.data.files.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Something in the trash, as shown on the Trash screen. */
sealed interface TrashItem {
    val id: String
    val deletedAt: Long

    data class Note(val note: NoteEntity, val itemCount: Int) : TrashItem {
        override val id: String get() = note.id
        override val deletedAt: Long get() = note.deletedAt ?: 0L
    }

    data class Folder(val folder: FolderEntity, val itemCount: Int) : TrashItem {
        override val id: String get() = folder.id
        override val deletedAt: Long get() = folder.deletedAt ?: 0L
    }
}

/**
 * Deleting moves things to the trash for 30 days. Everything deleted in one action (a note with all
 * its sub-layers, or a folder with everything inside) shares a trashRootId so it comes back together.
 */
class TrashRepository(
    private val db: LayersDatabase,
    private val attachments: AttachmentStore,
    private val indexer: SearchIndexer,
    private val tasks: TaskRepository,
) {
    fun observeNoteRoots(): Flow<List<NoteEntity>> = db.notes().observeTrashRoots()
    fun observeFolderRoots(): Flow<List<FolderEntity>> = db.folders().observeTrashRoots()

    suspend fun countInGroup(rootId: String): Int = withContext(Dispatchers.IO) {
        db.notes().countInTrashGroup(rootId) + db.folders().inTrashGroup(rootId).size
    }

    /** All active sub-layers below a note, at any depth. */
    suspend fun descendantNotes(noteId: String): List<NoteEntity> = withContext(Dispatchers.IO) {
        val result = ArrayList<NoteEntity>()
        val queue = ArrayDeque<String>().apply { add(noteId) }
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            for (child in db.notes().children(id)) {
                result += child
                queue += child.id
            }
        }
        result
    }

    /** Moves a note and every sub-layer under it to the trash. */
    suspend fun trashNote(noteId: String) = withContext(Dispatchers.IO) {
        val ids = listOf(noteId) + descendantNotes(noteId).map { it.id }
        val now = System.currentTimeMillis()
        db.notes().setDeleted(ids, now, noteId)
        indexer.removeNotes(ids)
        tasks.cancelForNotes(ids)
    }

    /** Moves a folder, its sub-folders and every note (and sub-layer) inside them to the trash. */
    suspend fun trashFolder(folderId: String, subFolderIds: List<String>) = withContext(Dispatchers.IO) {
        val folderIds = listOf(folderId) + subFolderIds
        val noteIds = db.notes().inFolders(folderIds).map { it.id }
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.folders().setDeleted(folderIds, now, folderId)
            if (noteIds.isNotEmpty()) db.notes().setDeleted(noteIds, now, folderId)
        }
        indexer.removeNotes(noteIds)
        tasks.cancelForNotes(noteIds)
    }

    suspend fun restore(rootId: String) = withContext(Dispatchers.IO) {
        val folders = db.folders().inTrashGroup(rootId)
        val notes = db.notes().inTrashGroup(rootId)
        val folderIds = folders.map { it.id }.toSet()
        val noteIds = notes.map { it.id }.toSet()
        db.withTransaction {
            val restoredFolders = folders.map { f ->
                val parentOk = f.parentId == null || f.parentId in folderIds ||
                    db.folders().get(f.parentId)?.deletedAt == null && db.folders().get(f.parentId) != null
                f.copy(deletedAt = null, trashRootId = null, parentId = if (parentOk) f.parentId else null)
            }
            if (restoredFolders.isNotEmpty()) db.folders().upsertAll(restoredFolders)
            val restoredNotes = notes.map { n ->
                var updated = n.copy(deletedAt = null, trashRootId = null)
                // A sub-layer whose parent note or anchor block is gone becomes a regular note.
                if (n.parentNoteId != null && n.parentNoteId !in noteIds) {
                    val parent = db.notes().get(n.parentNoteId)
                    val anchorExists = n.anchorBlockId?.let { db.blocks().get(it) } != null
                    if (parent == null || parent.deletedAt != null || !anchorExists) {
                        updated = updated.copy(
                            parentNoteId = null, anchorBlockId = null, anchorPinId = null,
                            anchorPage = null, pinX = null, pinY = null,
                        )
                    }
                }
                val folderId = updated.folderId
                if (folderId != null && folderId !in folderIds) {
                    val folder = db.folders().get(folderId)
                    if (folder == null || folder.deletedAt != null) updated = updated.copy(folderId = null)
                }
                updated
            }
            if (restoredNotes.isNotEmpty()) db.notes().upsertAll(restoredNotes)
        }
        noteIds.forEach { indexer.reindex(it) }
        tasks.rescheduleForNotes(noteIds.toList())
    }

    /** Permanently deletes one trash group, including attachment files. */
    suspend fun deleteForever(rootId: String) = withContext(Dispatchers.IO) {
        val folders = db.folders().inTrashGroup(rootId)
        val notes = db.notes().inTrashGroup(rootId)
        val noteIds = notes.map { it.id }
        val blocks = if (noteIds.isEmpty()) emptyList() else db.blocks().forNotes(noteIds)
        db.withTransaction {
            if (noteIds.isNotEmpty()) {
                db.blocks().deleteForNotes(noteIds)
                db.pins().deleteForNotes(noteIds)
                db.pins().deleteForSubNotes(noteIds)
                db.tasks().deleteForNotes(noteIds)
                db.tags().removeRefsForNotes(noteIds)
                db.extractedText().deleteForNotes(noteIds)
                db.search().deleteForNotes(noteIds)
                db.notes().deleteIds(noteIds)
            }
            if (folders.isNotEmpty()) db.folders().deleteIds(folders.map { it.id })
        }
        blocks.mapNotNull { it.filePath }.forEach { attachments.delete(it) }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val roots = (db.notes().allTrashRoots() + db.folders().allTrashRoots()).toSet()
        roots.forEach { deleteForever(it) }
        collectGarbage()
    }

    /** Deletes trash older than 30 days and attachment files no block uses any more. */
    suspend fun purgeExpired() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val roots = (db.notes().expiredTrashRoots(cutoff) + db.folders().expiredTrashRoots(cutoff)).toSet()
        roots.forEach { deleteForever(it) }
        collectGarbage()
    }

    private suspend fun collectGarbage() {
        val used = db.blocks().allFilePaths().map { it.substringAfterLast('/') }.toSet()
        val dayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        attachments.allAttachmentFiles()
            .filter { it.name !in used && it.lastModified() < dayAgo }
            .forEach { it.delete() }
    }

    companion object {
        const val RETENTION_DAYS = 30L
    }
}
