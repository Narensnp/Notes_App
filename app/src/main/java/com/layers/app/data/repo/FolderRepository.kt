package com.layers.app.data.repo

import com.layers.app.data.db.FolderEntity
import com.layers.app.data.db.LayersDatabase
import com.layers.app.data.model.FolderNode
import com.layers.app.data.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class FolderContentCount(val folders: Int, val notes: Int, val layers: Int)

class FolderRepository(
    private val db: LayersDatabase,
    private val trashRepo: TrashRepository,
    private val lockManager: LockManager,
) {
    fun observeChildren(parentId: String?): Flow<List<FolderEntity>> = db.folders().observeChildren(parentId)

    fun observeFolder(id: String): Flow<FolderEntity?> = db.folders().observe(id)

    fun observeAll(): Flow<List<FolderEntity>> = db.folders().observeAllActive()

    /** All folders flattened depth-first, for the "Move to…" picker and filters. */
    fun observeTree(): Flow<List<FolderNode>> = observeAll().map { flatten(it) }

    fun flatten(all: List<FolderEntity>): List<FolderNode> {
        val byParent = all.groupBy { it.parentId }
        val ids = all.map { it.id }.toSet()
        val out = ArrayList<FolderNode>()
        val seen = HashSet<String>()
        fun visit(parentId: String?, depth: Int) {
            for (f in byParent[parentId].orEmpty().sortedBy { it.name.lowercase() }) {
                if (!seen.add(f.id)) continue
                out += FolderNode(f, depth)
                visit(f.id, depth + 1)
            }
        }
        visit(null, 0)
        // Folders whose parent is missing are shown at the top level.
        all.filter { it.parentId != null && it.parentId !in ids && it.id !in seen }.forEach {
            seen += it.id
            out += FolderNode(it, 0)
            visit(it.id, 1)
        }
        return out
    }

    suspend fun get(id: String): FolderEntity? = withContext(Dispatchers.IO) { db.folders().get(id) }

    suspend fun create(parentId: String?, name: String, color: Long, icon: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = newId()
        db.folders().upsert(
            FolderEntity(
                id = id, parentId = parentId, name = name.trim().ifBlank { "New folder" },
                color = color, icon = icon, createdAt = now, updatedAt = now,
            )
        )
        id
    }

    suspend fun update(id: String, name: String, color: Long, icon: String) = withContext(Dispatchers.IO) {
        val f = db.folders().get(id) ?: return@withContext
        db.folders().upsert(
            f.copy(name = name.trim().ifBlank { f.name }, color = color, icon = icon, updatedAt = System.currentTimeMillis())
        )
    }

    /** Returns false if the move would put a folder inside itself. */
    suspend fun move(id: String, newParentId: String?): Boolean = withContext(Dispatchers.IO) {
        if (newParentId == id) return@withContext false
        val descendants = descendantFolderIds(id)
        if (newParentId != null && newParentId in descendants) return@withContext false
        val f = db.folders().get(id) ?: return@withContext false
        db.folders().upsert(f.copy(parentId = newParentId, updatedAt = System.currentTimeMillis()))
        lockManager.reconcileFolderTree(id, descendants)
        true
    }

    suspend fun setLocked(id: String, locked: Boolean) = withContext(Dispatchers.IO) {
        val f = db.folders().get(id) ?: return@withContext
        db.folders().upsert(f.copy(locked = locked))
        lockManager.reconcileFolderTree(id, descendantFolderIds(id))
    }

    suspend fun trash(id: String) = withContext(Dispatchers.IO) {
        trashRepo.trashFolder(id, descendantFolderIds(id).toList())
    }

    suspend fun countContents(id: String): FolderContentCount = withContext(Dispatchers.IO) {
        val sub = descendantFolderIds(id)
        val notes = db.notes().inFolders((sub + id).toList())
        FolderContentCount(
            folders = sub.size,
            notes = notes.count { it.parentNoteId == null },
            layers = notes.count { it.parentNoteId != null },
        )
    }

    suspend fun descendantFolderIds(id: String): Set<String> = withContext(Dispatchers.IO) {
        val all = db.folders().getAllActive()
        val byParent = all.groupBy { it.parentId }
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque<String>().apply { add(id) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in byParent[current].orEmpty()) {
                if (result.add(child.id)) queue += child.id
            }
        }
        result
    }

    /** Home › Work › Project X, as folder entities (Home not included). */
    suspend fun path(folderId: String?): List<FolderEntity> = withContext(Dispatchers.IO) {
        val out = ArrayList<FolderEntity>()
        val seen = HashSet<String>()
        var f = folderId?.let { db.folders().get(it) }
        while (f != null && seen.add(f.id)) {
            out += f
            f = f.parentId?.let { db.folders().get(it) }
        }
        out.reversed()
    }
}
