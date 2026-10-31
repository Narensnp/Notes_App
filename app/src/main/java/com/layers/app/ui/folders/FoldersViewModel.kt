package com.layers.app.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layers.app.AppContainer
import com.layers.app.data.db.FolderEntity
import com.layers.app.data.model.FolderNode
import com.layers.app.data.model.NoteListItem
import com.layers.app.data.repo.FolderContentCount
import com.layers.app.data.repo.SortMode
import com.layers.app.data.repo.ViewMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FoldersUiState(
    val loading: Boolean = true,
    val path: List<FolderEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val notes: List<NoteListItem> = emptyList(),
    val viewMode: ViewMode = ViewMode.GRID,
    val sortMode: SortMode = SortMode.MODIFIED,
) {
    val current: FolderEntity? get() = path.lastOrNull()
    val isEmpty: Boolean get() = !loading && folders.isEmpty() && notes.isEmpty()
}

class FoldersViewModel(private val c: AppContainer) : ViewModel() {

    /** The open folder path from Home, as ids. Empty = Home. */
    private val stack = MutableStateFlow<List<String>>(emptyList())

    val tree: StateFlow<List<FolderNode>> =
        c.folders.observeTree().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allFolders = c.folders.observeAll()

    val state: StateFlow<FoldersUiState> = run {
        val currentId = stack.map { it.lastOrNull() }
        val folders = currentId.flatMapLatest { c.folders.observeChildren(it) }
        val notes = currentId.flatMapLatest { c.notes.observeNotesInFolder(it) }
        val path = combine(stack, allFolders) { ids, all ->
            val byId = all.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }
        combine(path, folders, notes, c.settings.settings) { p, f, n, s ->
            FoldersUiState(
                loading = false,
                path = p,
                folders = f,
                notes = sortNotes(n, s.sortMode),
                viewMode = s.viewMode,
                sortMode = s.sortMode,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersUiState())
    }

    init {
        // Folders that disappear (trashed elsewhere) are dropped from the open path.
        viewModelScope.launch {
            allFolders.collect { all ->
                val ids = all.map { it.id }.toSet()
                val trimmed = stack.value.takeWhile { it in ids }
                if (trimmed.size != stack.value.size) stack.value = trimmed
            }
        }
        // Breadcrumb taps in the editor ask the Notes tab to show a folder.
        viewModelScope.launch {
            c.navRequests.folder.collect { request ->
                if (request != null) {
                    jumpTo(request.folderId)
                    c.navRequests.consumeFolder()
                }
            }
        }
    }

    private fun sortNotes(list: List<NoteListItem>, mode: SortMode): List<NoteListItem> {
        val byMode = when (mode) {
            SortMode.MODIFIED -> list.sortedByDescending { it.note.updatedAt }
            SortMode.CREATED -> list.sortedByDescending { it.note.createdAt }
            SortMode.TITLE -> list.sortedBy { it.note.title.ifBlank { "￿" }.lowercase() }
        }
        return byMode.sortedByDescending { it.note.pinned }
    }

    val depth: Int get() = stack.value.size

    fun open(folderId: String) {
        stack.value = stack.value + folderId
        viewModelScope.launch { c.settings.touchRecentFolder(folderId) }
    }

    /** Returns false when already at Home. */
    fun goUp(): Boolean {
        if (stack.value.isEmpty()) return false
        stack.value = stack.value.dropLast(1)
        return true
    }

    /** Breadcrumb tap: index -1 = Home. */
    fun goTo(index: Int) {
        stack.value = if (index < 0) emptyList() else stack.value.take(index + 1)
    }

    fun jumpTo(folderId: String?) {
        viewModelScope.launch {
            stack.value = if (folderId == null) emptyList() else c.folders.path(folderId).map { it.id }
        }
    }

    fun setViewMode(mode: ViewMode) = viewModelScope.launch { c.settings.setViewMode(mode) }
    fun setSortMode(mode: SortMode) = viewModelScope.launch { c.settings.setSortMode(mode) }

    fun createFolder(name: String, color: Long, icon: String) = viewModelScope.launch {
        c.folders.create(stack.value.lastOrNull(), name, color, icon)
    }

    fun updateFolder(id: String, name: String, color: Long, icon: String) = viewModelScope.launch {
        c.folders.update(id, name, color, icon)
    }

    fun moveFolder(id: String, parentId: String?, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(c.folders.move(id, parentId))
    }

    fun trashFolder(id: String) = viewModelScope.launch { c.folders.trash(id) }

    fun setFolderLocked(id: String, locked: Boolean) = viewModelScope.launch { c.folders.setLocked(id, locked) }

    suspend fun countFolder(id: String): FolderContentCount = c.folders.countContents(id)

    suspend fun descendantFolderIds(id: String): Set<String> = c.folders.descendantFolderIds(id)

    suspend fun createNote(): String {
        val folderId = stack.value.lastOrNull()
        c.settings.touchRecentFolder(folderId)
        return c.notes.createNote(folderId)
    }

    fun moveNote(id: String, folderId: String?) = viewModelScope.launch { c.notes.moveNote(id, folderId) }
    fun trashNote(id: String) = viewModelScope.launch { c.notes.trashNote(id) }
    fun setPinned(id: String, pinned: Boolean) = viewModelScope.launch { c.notes.setPinned(id, pinned) }
    fun setColor(id: String, color: Long?) = viewModelScope.launch { c.notes.setColor(id, color) }
    fun setNoteLocked(id: String, locked: Boolean) = viewModelScope.launch { c.notes.setLocked(id, locked) }
    suspend fun countLayers(id: String): Int = c.notes.countLayersUnder(id)
}
