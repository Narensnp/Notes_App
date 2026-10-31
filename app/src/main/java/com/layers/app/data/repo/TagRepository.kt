package com.layers.app.data.repo

import com.layers.app.data.db.LayersDatabase
import com.layers.app.data.db.NoteTagCrossRef
import com.layers.app.data.db.TagEntity
import com.layers.app.data.db.TagWithCount
import com.layers.app.data.model.FolderPalette
import com.layers.app.data.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TagRepository(private val db: LayersDatabase) {

    fun observeAllWithCounts(): Flow<List<TagWithCount>> = db.tags().observeAllWithCounts()

    fun observeAll(): Flow<List<TagEntity>> = db.tags().observeAll()

    fun observeForNote(noteId: String): Flow<List<TagEntity>> = db.tags().observeForNote(noteId)

    fun observeTag(id: String): Flow<TagEntity?> = db.tags().observe(id)

    /** Returns the existing tag with that name, or creates it. */
    suspend fun getOrCreate(name: String): TagEntity = withContext(Dispatchers.IO) {
        val clean = name.trim().removePrefix("#").trim()
        db.tags().byName(clean)?.let { return@withContext it }
        val color = FolderPalette.colors[(clean.hashCode() and 0x7fffffff) % FolderPalette.colors.size]
        val tag = TagEntity(newId(), clean, color)
        db.tags().upsert(tag)
        tag
    }

    suspend fun addToNote(noteId: String, name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        val tag = getOrCreate(name)
        db.tags().addRef(NoteTagCrossRef(noteId, tag.id))
    }

    suspend fun removeFromNote(noteId: String, tagId: String) = withContext(Dispatchers.IO) {
        db.tags().removeRef(noteId, tagId)
    }

    suspend fun rename(tag: TagEntity, newName: String) = withContext(Dispatchers.IO) {
        val clean = newName.trim().removePrefix("#").trim()
        if (clean.isNotEmpty()) db.tags().upsert(tag.copy(name = clean))
    }

    suspend fun delete(tagId: String) = withContext(Dispatchers.IO) {
        db.tags().removeRefsForTag(tagId)
        db.tags().delete(tagId)
    }
}
