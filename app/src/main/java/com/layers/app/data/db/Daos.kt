package com.layers.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class NoteWithCount(
    @Embedded val note: NoteEntity,
    val layerCount: Int,
)

data class BlockLayerCount(
    val blockId: String,
    val count: Int,
)

data class TagWithCount(
    @Embedded val tag: TagEntity,
    val noteCount: Int,
)

data class SearchRow(
    val noteId: String,
    val blockId: String,
    val kind: String,
    val snippet: String,
)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE deletedAt IS NULL AND parentId IS :parentId ORDER BY name COLLATE NOCASE")
    fun observeChildren(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAllActive(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE deletedAt IS NULL")
    suspend fun getAllActive(): List<FolderEntity>

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :id")
    fun observe(id: String): Flow<FolderEntity?>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Upsert
    suspend fun upsertAll(folders: List<FolderEntity>)

    @Query("UPDATE folders SET deletedAt = :deletedAt, trashRootId = :rootId WHERE id IN (:ids)")
    suspend fun setDeleted(ids: List<String>, deletedAt: Long?, rootId: String?)

    @Query("SELECT * FROM folders WHERE deletedAt IS NOT NULL AND trashRootId = id ORDER BY deletedAt DESC")
    fun observeTrashRoots(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE trashRootId = :rootId")
    suspend fun inTrashGroup(rootId: String): List<FolderEntity>

    @Query("SELECT DISTINCT trashRootId FROM folders WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND trashRootId IS NOT NULL")
    suspend fun expiredTrashRoots(cutoff: Long): List<String>

    @Query("SELECT DISTINCT trashRootId FROM folders WHERE deletedAt IS NOT NULL AND trashRootId IS NOT NULL")
    suspend fun allTrashRoots(): List<String>

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    @Query("DELETE FROM folders")
    suspend fun deleteAll()
}

@Dao
interface NoteDao {
    @Query(
        """SELECT n.*, (SELECT COUNT(*) FROM notes c WHERE c.parentNoteId = n.id AND c.deletedAt IS NULL) AS layerCount
           FROM notes n
           WHERE n.deletedAt IS NULL AND n.parentNoteId IS NULL AND n.folderId IS :folderId"""
    )
    fun observeInFolder(folderId: String?): Flow<List<NoteWithCount>>

    @Query(
        """SELECT n.*, (SELECT COUNT(*) FROM notes c WHERE c.parentNoteId = n.id AND c.deletedAt IS NULL) AS layerCount
           FROM notes n JOIN note_tags t ON t.noteId = n.id
           WHERE t.tagId = :tagId AND n.deletedAt IS NULL
           ORDER BY n.updatedAt DESC"""
    )
    fun observeWithTag(tagId: String): Flow<List<NoteWithCount>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL AND parentNoteId IS NULL ORDER BY updatedAt DESC LIMIT 60")
    fun observeRecentTopLevel(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<NoteEntity>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL")
    suspend fun getAllActive(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE parentNoteId = :parentId AND deletedAt IS NULL ORDER BY createdAt")
    suspend fun children(parentId: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE parentNoteId = :parentId AND deletedAt IS NULL ORDER BY createdAt")
    fun observeChildren(parentId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE anchorBlockId = :blockId AND deletedAt IS NULL ORDER BY createdAt")
    suspend fun layersForBlock(blockId: String): List<NoteEntity>

    @Query(
        """SELECT * FROM notes WHERE anchorBlockId = :blockId AND anchorPinId IS NULL AND anchorPage IS NULL
           AND deletedAt IS NULL LIMIT 1"""
    )
    suspend fun blockLayer(blockId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE anchorBlockId = :blockId AND anchorPage = :page AND deletedAt IS NULL LIMIT 1")
    suspend fun pageLayer(blockId: String, page: Int): NoteEntity?

    @Query("SELECT * FROM notes WHERE anchorBlockId = :blockId AND anchorPage IS NOT NULL AND deletedAt IS NULL")
    fun observePageLayers(blockId: String): Flow<List<NoteEntity>>

    @Query(
        """SELECT anchorBlockId AS blockId, COUNT(*) AS count FROM notes
           WHERE parentNoteId = :noteId AND deletedAt IS NULL AND anchorBlockId IS NOT NULL
           GROUP BY anchorBlockId"""
    )
    fun observeLayerCounts(noteId: String): Flow<List<BlockLayerCount>>

    @Query("SELECT * FROM notes WHERE folderId IN (:folderIds) AND deletedAt IS NULL")
    suspend fun inFolders(folderIds: List<String>): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE folderId IS :folderId AND parentNoteId IS NULL AND deletedAt IS NULL")
    suspend fun topLevelInFolder(folderId: String?): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Upsert
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Query("UPDATE notes SET deletedAt = :deletedAt, trashRootId = :rootId WHERE id IN (:ids)")
    suspend fun setDeleted(ids: List<String>, deletedAt: Long?, rootId: String?)

    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL AND trashRootId = id ORDER BY deletedAt DESC")
    fun observeTrashRoots(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE trashRootId = :rootId")
    suspend fun inTrashGroup(rootId: String): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes WHERE trashRootId = :rootId")
    suspend fun countInTrashGroup(rootId: String): Int

    @Query("SELECT DISTINCT trashRootId FROM notes WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND trashRootId IS NOT NULL")
    suspend fun expiredTrashRoots(cutoff: Long): List<String>

    @Query("SELECT DISTINCT trashRootId FROM notes WHERE deletedAt IS NOT NULL AND trashRootId IS NOT NULL")
    suspend fun allTrashRoots(): List<String>

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}

@Dao
interface BlockDao {
    @Query("SELECT * FROM blocks WHERE noteId = :noteId ORDER BY position")
    suspend fun forNote(noteId: String): List<BlockEntity>

    @Query("SELECT * FROM blocks WHERE noteId IN (:noteIds) ORDER BY position")
    suspend fun forNotes(noteIds: List<String>): List<BlockEntity>

    @Query("SELECT * FROM blocks WHERE id = :id")
    suspend fun get(id: String): BlockEntity?

    @Query("SELECT * FROM blocks")
    suspend fun getAll(): List<BlockEntity>

    @Query("SELECT filePath FROM blocks WHERE filePath IS NOT NULL")
    suspend fun allFilePaths(): List<String>

    @Upsert
    suspend fun upsert(block: BlockEntity)

    @Upsert
    suspend fun upsertAll(blocks: List<BlockEntity>)

    @Query("DELETE FROM blocks WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    @Query("DELETE FROM blocks WHERE noteId IN (:noteIds)")
    suspend fun deleteForNotes(noteIds: List<String>)

    @Query("DELETE FROM blocks")
    suspend fun deleteAll()
}

@Dao
interface PinDao {
    @Query("SELECT * FROM pins WHERE noteId = :noteId ORDER BY number")
    fun observeForNote(noteId: String): Flow<List<PinEntity>>

    @Query("SELECT * FROM pins WHERE blockId = :blockId ORDER BY number")
    suspend fun forBlock(blockId: String): List<PinEntity>

    @Query("SELECT * FROM pins WHERE id = :id")
    suspend fun get(id: String): PinEntity?

    @Query("SELECT * FROM pins WHERE subNoteId = :noteId LIMIT 1")
    suspend fun forSubNote(noteId: String): PinEntity?

    @Query("SELECT COALESCE(MAX(number), 0) FROM pins WHERE blockId = :blockId")
    suspend fun maxNumber(blockId: String): Int

    @Query("SELECT * FROM pins")
    suspend fun getAll(): List<PinEntity>

    @Upsert
    suspend fun upsert(pin: PinEntity)

    @Upsert
    suspend fun upsertAll(pins: List<PinEntity>)

    @Query("DELETE FROM pins WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pins WHERE blockId IN (:blockIds)")
    suspend fun deleteForBlocks(blockIds: List<String>)

    @Query("DELETE FROM pins WHERE subNoteId IN (:noteIds)")
    suspend fun deleteForSubNotes(noteIds: List<String>)

    @Query("DELETE FROM pins WHERE noteId IN (:noteIds)")
    suspend fun deleteForNotes(noteIds: List<String>)

    @Query("DELETE FROM pins")
    suspend fun deleteAll()
}

@Dao
interface TaskDao {
    @Query(
        """SELECT t.* FROM tasks t JOIN notes n ON n.id = t.noteId
           WHERE n.deletedAt IS NULL AND t.dueAt >= :start AND t.dueAt < :end
           ORDER BY t.done, t.dueAt"""
    )
    fun observeBetween(start: Long, end: Long): Flow<List<TaskEntity>>

    @Query(
        """SELECT t.* FROM tasks t JOIN notes n ON n.id = t.noteId
           WHERE n.deletedAt IS NULL AND t.locked = 0
           AND ((t.dueAt >= :start AND t.dueAt < :end) OR (t.done = 0 AND t.dueAt < :start))
           ORDER BY t.done, t.dueAt"""
    )
    suspend fun forWidget(start: Long, end: Long): List<TaskEntity>

    @Query(
        """SELECT t.* FROM tasks t JOIN notes n ON n.id = t.noteId
           WHERE n.deletedAt IS NULL AND t.locked = 0
           AND ((t.dueAt >= :start AND t.dueAt < :end) OR (t.done = 0 AND t.dueAt < :start))
           ORDER BY t.done, t.dueAt"""
    )
    fun observeForWidget(start: Long, end: Long): Flow<List<TaskEntity>>

    @Query(
        """SELECT t.* FROM tasks t JOIN notes n ON n.id = t.noteId
           WHERE n.deletedAt IS NULL AND t.done = 0 AND t.reminderAt IS NOT NULL AND t.reminderAt > :now"""
    )
    suspend fun upcomingReminders(now: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE blockId = :blockId")
    suspend fun get(blockId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE noteId = :noteId")
    suspend fun forNote(noteId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE blockId IN (:blockIds)")
    suspend fun deleteForBlocks(blockIds: List<String>)

    @Query("DELETE FROM tasks WHERE noteId IN (:noteIds)")
    suspend fun deleteForNotes(noteIds: List<String>)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}

@Dao
interface TagDao {
    @Query(
        """SELECT t.*, (SELECT COUNT(*) FROM note_tags nt JOIN notes n ON n.id = nt.noteId
                        WHERE nt.tagId = t.id AND n.deletedAt IS NULL) AS noteCount
           FROM tags t ORDER BY t.name COLLATE NOCASE"""
    )
    fun observeAllWithCounts(): Flow<List<TagWithCount>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT t.* FROM tags t JOIN note_tags nt ON nt.tagId = t.id WHERE nt.noteId = :noteId ORDER BY t.name COLLATE NOCASE")
    fun observeForNote(noteId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    fun observe(id: String): Flow<TagEntity?>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): TagEntity?

    @Query("SELECT noteId FROM note_tags WHERE tagId = :tagId")
    suspend fun noteIdsWithTag(tagId: String): List<String>

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM note_tags")
    suspend fun getAllRefs(): List<NoteTagCrossRef>

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRef(ref: NoteTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRefs(refs: List<NoteTagCrossRef>)

    @Query("DELETE FROM note_tags WHERE noteId = :noteId AND tagId = :tagId")
    suspend fun removeRef(noteId: String, tagId: String)

    @Query("DELETE FROM note_tags WHERE noteId IN (:noteIds)")
    suspend fun removeRefsForNotes(noteIds: List<String>)

    @Query("DELETE FROM note_tags WHERE tagId = :tagId")
    suspend fun removeRefsForTag(tagId: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM note_tags")
    suspend fun deleteAllRefs()

    @Query("DELETE FROM tags")
    suspend fun deleteAll()
}

@Dao
interface ExtractedTextDao {
    @Query("SELECT * FROM extracted_text WHERE noteId = :noteId ORDER BY blockId, page")
    suspend fun forNote(noteId: String): List<ExtractedTextEntity>

    @Query("SELECT * FROM extracted_text WHERE blockId = :blockId ORDER BY page")
    suspend fun forBlock(blockId: String): List<ExtractedTextEntity>

    @Query("SELECT COUNT(*) FROM extracted_text WHERE blockId = :blockId")
    suspend fun countForBlock(blockId: String): Int

    @Query("SELECT * FROM extracted_text")
    suspend fun getAll(): List<ExtractedTextEntity>

    @Upsert
    suspend fun upsertAll(rows: List<ExtractedTextEntity>)

    @Query("DELETE FROM extracted_text WHERE blockId = :blockId")
    suspend fun deleteForBlock(blockId: String)

    @Query("DELETE FROM extracted_text WHERE noteId IN (:noteIds)")
    suspend fun deleteForNotes(noteIds: List<String>)

    @Query("DELETE FROM extracted_text")
    suspend fun deleteAll()
}

@Dao
interface SearchDao {
    @Query("INSERT INTO search_fts(noteId, blockId, kind, title, body) VALUES (:noteId, :blockId, :kind, :title, :body)")
    suspend fun insert(noteId: String, blockId: String, kind: String, title: String, body: String)

    @Query("DELETE FROM search_fts WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String)

    @Query("DELETE FROM search_fts WHERE noteId IN (:noteIds)")
    suspend fun deleteForNotes(noteIds: List<String>)

    @Query(
        """SELECT noteId, blockId, kind, snippet(search_fts, '[[', ']]', '…', 4, 14) AS snippet
           FROM search_fts WHERE search_fts MATCH :match LIMIT 400"""
    )
    suspend fun search(match: String): List<SearchRow>

    @Query("DELETE FROM search_fts")
    suspend fun deleteAll()
}
