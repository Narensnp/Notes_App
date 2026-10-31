package com.layers.app.data.repo

import androidx.room.withTransaction
import com.layers.app.data.db.LayersDatabase
import com.layers.app.data.db.NoteEntity
import com.layers.app.data.files.AttachmentStore
import com.layers.app.data.model.ImageContent
import com.layers.app.data.model.PdfContent
import com.layers.app.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps every note's stored form in line with its lock:
 *  - a note is "effectively locked" if it, any parent note, or any folder above it is locked;
 *  - effectively locked notes are stored encrypted (block JSON + attachment files) and removed from
 *    the search index; unlocked notes are stored in plain form and indexed.
 */
class LockManager(
    private val db: LayersDatabase,
    private val codec: BlockCodec,
    private val attachments: AttachmentStore,
    private val indexer: SearchIndexer,
    private val tasks: TaskRepository,
    private val work: WorkScheduler,
) {
    suspend fun isEffectivelyLocked(note: NoteEntity): Boolean {
        val seen = HashSet<String>()
        var n: NoteEntity? = note
        while (n != null && seen.add(n.id)) {
            if (n.locked) return true
            n = n.parentNoteId?.let { db.notes().get(it) }
        }
        return isFolderLocked(note.folderId)
    }

    suspend fun isFolderLocked(folderId: String?): Boolean {
        val seen = HashSet<String>()
        var f = folderId?.let { db.folders().get(it) }
        while (f != null && seen.add(f.id)) {
            if (f.locked) return true
            f = f.parentId?.let { db.folders().get(it) }
        }
        return false
    }

    /** Re-checks the given notes and all their sub-layers, encrypting or decrypting as needed. */
    suspend fun reconcile(noteIds: Collection<String>) = withContext(Dispatchers.IO) {
        val all = LinkedHashSet<String>()
        for (id in noteIds) {
            all += id
            all += descendantIds(id)
        }
        for (id in all) {
            val note = db.notes().get(id) ?: continue
            val shouldLock = isEffectivelyLocked(note)
            if (shouldLock && !note.encrypted) encryptNote(note)
            else if (!shouldLock && note.encrypted) decryptNote(note)
        }
    }

    suspend fun reconcileFolderTree(folderId: String, folderIds: Collection<String>) {
        val ids = (folderIds + folderId).toList()
        val notes = db.notes().inFolders(ids).map { it.id }
        reconcile(notes)
    }

    private suspend fun descendantIds(noteId: String): List<String> {
        val result = ArrayList<String>()
        val queue = ArrayDeque<String>()
        queue += noteId
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            for (child in db.notes().children(id)) {
                result += child.id
                queue += child.id
            }
        }
        return result
    }

    private suspend fun encryptNote(note: NoteEntity) {
        val blocks = db.blocks().forNote(note.id)
        // Encrypt files first: if anything fails midway the note stays readable.
        blocks.mapNotNull { it.filePath }.forEach { attachments.encryptInPlace(it) }
        db.withTransaction {
            db.blocks().upsertAll(blocks.map { codec.encryptEntity(it) })
            db.notes().upsert(note.copy(encrypted = true, preview = "", thumbnailPath = null))
            db.extractedText().deleteForNotes(listOf(note.id))
            db.search().deleteForNote(note.id)
        }
        tasks.resyncFromStorage(note.id)
    }

    private suspend fun decryptNote(note: NoteEntity) {
        val entities = db.blocks().forNote(note.id)
        entities.mapNotNull { it.filePath }.forEach { attachments.decryptInPlace(it) }
        val plain = entities.map { codec.decryptEntity(it) }
        val blocks = plain.map { codec.fromEntity(it) }
        val thumb = blocks.firstNotNullOfOrNull { (it.content as? ImageContent)?.path }
        db.withTransaction {
            db.blocks().upsertAll(plain)
            db.notes().upsert(
                note.copy(
                    encrypted = false,
                    preview = TextUtil.preview(blocks),
                    thumbnailPath = thumb,
                )
            )
        }
        tasks.resyncFromStorage(note.id)
        indexer.reindex(note.id)
        // Text inside images and PDFs was dropped while locked; extract it again.
        for (b in blocks) {
            if (b.content is ImageContent || b.content is PdfContent) work.enqueueExtraction(note.id, b.id)
        }
    }
}
