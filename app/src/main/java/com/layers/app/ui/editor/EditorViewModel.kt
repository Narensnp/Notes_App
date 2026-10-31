package com.layers.app.ui.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layers.app.AppContainer
import com.layers.app.data.db.NoteEntity
import com.layers.app.data.db.PinEntity
import com.layers.app.data.db.TagEntity
import com.layers.app.data.model.AudioContent
import com.layers.app.data.model.Block
import com.layers.app.data.model.BlockContent
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.Crumb
import com.layers.app.data.model.CrumbKind
import com.layers.app.data.model.DrawingContent
import com.layers.app.data.model.FileContent
import com.layers.app.data.model.ImageContent
import com.layers.app.data.model.PdfContent
import com.layers.app.data.model.TextContent
import com.layers.app.data.model.isLocked
import com.layers.app.data.model.newId
import com.layers.app.data.repo.ChecklistLogic
import com.layers.app.data.repo.TextUtil
import com.layers.app.ocr.Ocr
import com.layers.app.pdf.PdfSource
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What sits above a sub-layer: the parent's block it hangs under. */
data class AnchorInfo(
    val parentNoteId: String,
    val parentTitle: String,
    val blockId: String,
    val content: BlockContent?,
    val pin: PinEntity?,
    val page: Int?,
    val label: String,
    val encrypted: Boolean,
) {
    val sharedKey: String get() = if (page != null) "anchor-$blockId-p$page" else "anchor-$blockId"
}

data class EditorUi(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val note: NoteEntity? = null,
    val blocks: List<Block> = emptyList(),
    val needsUnlock: Boolean = false,
    val crumbs: List<Crumb> = emptyList(),
    val layerCounts: Map<String, Int> = emptyMap(),
    val pins: List<PinEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val anchor: AnchorInfo? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val hasLayers: Boolean = false,
) {
    val locked: Boolean get() = note?.isLocked == true
    val encrypted: Boolean get() = note?.encrypted == true
    val depth: Int get() = crumbs.count { it.kind == CrumbKind.ANCHOR }
}

class EditorViewModel(
    private val c: AppContainer,
    val noteId: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /** One rich text state per text block, kept here so it survives scrolling and recomposition. */
    private val richStates = HashMap<String, RichTextState>()

    private val _focusRequest = MutableStateFlow<String?>(null)
    val focusRequest: StateFlow<String?> = _focusRequest.asStateFlow()

    private val _focusedBlock = MutableStateFlow<String?>(null)
    val focusedBlock: StateFlow<String?> = _focusedBlock.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val saveSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val titleSignal = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private var loaded = false
    private var titleInitialized = false
    private var dirty = false

    // Undo history: snapshots of the whole block list (rich text captured as HTML).
    private val history = ArrayList<List<Block>>()
    private var historyIndex = -1

    init {
        viewModelScope.launch {
            combine(c.notes.observeNote(noteId), c.lockSession.notesUnlocked) { n, u -> n to u }
                .collect { (note, unlocked) -> onNoteChanged(note, unlocked) }
        }
        viewModelScope.launch {
            c.notes.observeLayerCounts(noteId).collect { counts ->
                _ui.update { it.copy(layerCounts = counts, hasLayers = counts.isNotEmpty()) }
            }
        }
        viewModelScope.launch {
            c.notes.observePins(noteId).collect { pins -> _ui.update { it.copy(pins = pins) } }
        }
        viewModelScope.launch {
            c.tags.observeForNote(noteId).collect { tags -> _ui.update { it.copy(tags = tags) } }
        }
        viewModelScope.launch {
            saveSignal.debounce(600).collect { saveNow() }
        }
        viewModelScope.launch {
            titleSignal.debounce(500).collect { t ->
                c.notes.updateTitle(noteId, t)
                refreshCrumbs()
            }
        }
        // Tasks checked off from the widget or a notification while this note is open.
        viewModelScope.launch {
            c.tasks.externalChanges.collect { change ->
                if (change.noteId == noteId && loaded) {
                    _ui.update { s ->
                        s.copy(blocks = s.blocks.map { if (it.id == change.blockId) it.copy(content = change.content) else it })
                    }
                }
            }
        }
    }

    private var lastFolderId: String? = null
    private var lastParentId: String? = null

    private suspend fun onNoteChanged(note: NoteEntity?, unlocked: Boolean) {
        if (note == null || note.deletedAt != null) {
            _ui.update { it.copy(loading = false, missing = true) }
            return
        }
        if (!titleInitialized) {
            _title.value = note.title
            titleInitialized = true
        }
        val needsUnlock = note.isLocked && !unlocked
        _ui.update { it.copy(note = note, needsUnlock = needsUnlock) }
        if (_ui.value.crumbs.isEmpty() || note.folderId != lastFolderId || note.parentNoteId != lastParentId) {
            lastFolderId = note.folderId
            lastParentId = note.parentNoteId
            refreshCrumbs()
        }
        if (needsUnlock && loaded) {
            // The app was re-locked: save, then drop decrypted content from memory.
            flushSave()
            loaded = false
            richStates.clear()
            history.clear()
            historyIndex = -1
            _ui.update { it.copy(blocks = emptyList(), anchor = null, canUndo = false, canRedo = false) }
        }
        if (!needsUnlock && !loaded) load(note)
        if (needsUnlock) _ui.update { it.copy(loading = false) }
    }

    private suspend fun refreshCrumbs() {
        val crumbs = c.notes.breadcrumb(noteId)
        _ui.update { it.copy(crumbs = crumbs) }
    }

    private suspend fun load(note: NoteEntity) {
        loaded = true
        val blocks = c.notes.loadBlocks(noteId).ifEmpty { listOf(Block(newId(), TextContent())) }
        blocks.forEach { b -> (b.content as? TextContent)?.let { stateFor(b.id, it.html) } }
        history.clear()
        history += blocks
        historyIndex = 0
        _ui.update {
            it.copy(loading = false, blocks = blocks, anchor = loadAnchor(note), canUndo = false, canRedo = false)
        }
    }

    private suspend fun loadAnchor(note: NoteEntity): AnchorInfo? {
        val parentId = note.parentNoteId ?: return null
        val blockId = note.anchorBlockId ?: return null
        val parent = c.notes.getNote(parentId) ?: return null
        val content = runCatching {
            c.db.blocks().get(blockId)?.let { c.codec.contentOf(it) }
        }.getOrNull()
        val pin = note.anchorPinId?.let { c.db.pins().get(it) }
        return AnchorInfo(
            parentNoteId = parentId,
            parentTitle = com.layers.app.data.repo.NoteRepository.displayTitle(parent),
            blockId = blockId,
            content = content,
            pin = pin,
            page = note.anchorPage,
            label = c.notes.anchorLabel(note),
            encrypted = parent.encrypted,
        )
    }

    // ---------- Rich text ----------

    fun richState(blockId: String): RichTextState = richStates.getOrPut(blockId) { RichTextState() }

    private fun stateFor(blockId: String, html: String): RichTextState {
        val state = RichTextState()
        if (html.isNotBlank()) runCatching { state.setHtml(html) }
        richStates[blockId] = state
        return state
    }

    fun onTextEdited() = markDirty()

    fun setFocused(blockId: String?) {
        _focusedBlock.value = blockId
    }

    fun requestFocus(blockId: String?) {
        _focusRequest.value = blockId
    }

    fun consumeFocusRequest() {
        _focusRequest.value = null
    }

    fun focusedRichState(): RichTextState? {
        val id = _focusedBlock.value ?: return null
        val block = _ui.value.blocks.firstOrNull { it.id == id } ?: return null
        return if (block.content is TextContent) richStates[id] else null
    }

    // ---------- Title ----------

    fun setTitle(value: String) {
        _title.value = value
        titleSignal.tryEmit(value)
    }

    // ---------- Saving & undo ----------

    private fun markDirty() {
        dirty = true
        saveSignal.tryEmit(Unit)
    }

    /** The current blocks, with rich text read back from the editors as HTML. */
    private fun currentBlocks(): List<Block> = _ui.value.blocks.map { b ->
        if (b.content is TextContent) {
            val html = richStates[b.id]?.let { runCatching { it.toHtml() }.getOrNull() } ?: (b.content as TextContent).html
            b.copy(content = TextContent(cleanHtml(html)))
        } else b
    }

    private fun cleanHtml(html: String): String =
        if (TextUtil.htmlToPlain(html).isBlank() && !html.contains("<img")) "" else html

    private suspend fun saveNow() {
        if (!loaded || !dirty) return
        dirty = false
        val blocks = currentBlocks()
        pushHistory(blocks)
        c.notes.saveBlocks(noteId, blocks)
    }

    suspend fun flushSave() {
        if (!loaded) return
        if (dirty) saveNow()
    }

    fun flush() {
        viewModelScope.launch { flushSave() }
    }

    private fun pushHistory(blocks: List<Block>) {
        if (historyIndex >= 0 && history.getOrNull(historyIndex) == blocks) return
        while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
        history += blocks
        if (history.size > MAX_HISTORY) history.removeAt(0)
        historyIndex = history.lastIndex
        _ui.update { it.copy(canUndo = historyIndex > 0, canRedo = false) }
    }

    fun undo() {
        viewModelScope.launch {
            // Capture any typing that hasn't been saved yet as its own step first.
            if (dirty) {
                dirty = false
                pushHistory(currentBlocks())
            }
            if (historyIndex <= 0) return@launch
            historyIndex--
            restore(history[historyIndex])
        }
    }

    fun redo() {
        viewModelScope.launch {
            if (historyIndex >= history.lastIndex) return@launch
            historyIndex++
            restore(history[historyIndex])
        }
    }

    private suspend fun restore(blocks: List<Block>) {
        blocks.forEach { b ->
            val content = b.content
            if (content is TextContent) {
                val existing = richStates[b.id]
                val currentHtml = existing?.let { runCatching { it.toHtml() }.getOrNull() }
                if (existing == null || cleanHtml(currentHtml ?: "") != content.html) stateFor(b.id, content.html)
            }
        }
        _ui.update { it.copy(blocks = blocks, canUndo = historyIndex > 0, canRedo = historyIndex < history.lastIndex) }
        c.notes.saveBlocks(noteId, blocks)
    }

    // ---------- Block operations ----------

    private fun mutate(change: (MutableList<Block>) -> Unit) {
        val list = _ui.value.blocks.toMutableList()
        change(list)
        _ui.update { it.copy(blocks = list) }
        markDirty()
    }

    /** Inserts after the focused block (or at the end) and returns the new block's id. */
    fun insert(content: BlockContent, afterId: String? = _focusedBlock.value, focus: Boolean = false): String {
        val block = Block(newId(), content)
        if (content is TextContent) stateFor(block.id, content.html)
        mutate { list ->
            val index = afterId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
            if (index >= 0) list.add(index + 1, block) else list.add(block)
            // Media at the very end gets an empty paragraph below it, so writing can continue.
            if (content !is TextContent && content !is ChecklistContent && list.last().id == block.id) {
                val tail = Block(newId(), TextContent())
                stateFor(tail.id, "")
                list.add(tail)
            }
        }
        if (focus) requestFocus(block.id)
        return block.id
    }

    fun updateContent(blockId: String, content: BlockContent) = mutate { list ->
        val i = list.indexOfFirst { it.id == blockId }
        if (i >= 0) list[i] = list[i].copy(content = content)
    }

    fun delete(blockId: String) {
        mutate { list ->
            list.removeAll { it.id == blockId }
            if (list.isEmpty()) {
                val b = Block(newId(), TextContent())
                stateFor(b.id, "")
                list.add(b)
            }
        }
        viewModelScope.launch { saveNow() }
    }

    fun move(fromKey: Any, toKey: Any) {
        val list = _ui.value.blocks
        val from = list.indexOfFirst { it.id == fromKey }
        val to = list.indexOfFirst { it.id == toKey }
        if (from < 0 || to < 0 || from == to) return
        mutate { l -> l.add(to, l.removeAt(from)) }
    }

    suspend fun countLayers(blockId: String): Int = c.notes.countLayersForBlock(blockId)

    // ---------- Checklists & tasks ----------

    fun addChecklistAfter(afterId: String?, text: String = ""): String =
        insert(ChecklistContent(text = text), afterId = afterId, focus = true)

    fun setChecklistText(blockId: String, text: String) {
        val block = _ui.value.blocks.firstOrNull { it.id == blockId } ?: return
        val content = block.content as? ChecklistContent ?: return
        updateContent(blockId, content.copy(text = text))
    }

    /** Enter pressed in a checklist item: split it, or leave the list if the item is empty. */
    fun checklistEnter(blockId: String, before: String, after: String) {
        val block = _ui.value.blocks.firstOrNull { it.id == blockId } ?: return
        val content = block.content as? ChecklistContent ?: return
        if (before.isBlank() && after.isBlank()) {
            val textId = newId()
            stateFor(textId, "")
            mutate { list ->
                val i = list.indexOfFirst { it.id == blockId }
                if (i >= 0) list[i] = Block(textId, TextContent())
            }
            requestFocus(textId)
            return
        }
        updateContent(blockId, content.copy(text = before))
        addChecklistAfter(blockId, after)
    }

    /** Backspace in an empty checklist item: remove it and continue in the block above. */
    fun deleteEmptyChecklist(blockId: String) {
        val list = _ui.value.blocks
        val index = list.indexOfFirst { it.id == blockId }
        if (index < 0) return
        val previous = list.subList(0, index).lastOrNull { it.content is TextContent || it.content is ChecklistContent }
        mutate { l -> l.removeAll { it.id == blockId } }
        if (_ui.value.blocks.isEmpty()) {
            insert(TextContent(), afterId = null, focus = true)
        } else {
            requestFocus(previous?.id)
        }
    }

    /** Returns a message when a repeating task moved to its next date. */
    fun toggleChecklist(blockId: String, done: Boolean) {
        val block = _ui.value.blocks.firstOrNull { it.id == blockId } ?: return
        val content = block.content as? ChecklistContent ?: return
        val updated = ChecklistLogic.toggle(content, done)
        updateContent(blockId, updated)
        if (done && !updated.done && updated.dueAt != null) {
            _messages.tryEmit("Repeating task moved to its next date")
        }
        viewModelScope.launch { saveNow() }
    }

    fun setTask(blockId: String, updated: ChecklistContent) {
        updateContent(blockId, updated)
        viewModelScope.launch { saveNow() }
    }

    // ---------- Attachments ----------

    fun addImages(uris: List<Uri>) {
        viewModelScope.launch {
            var lastId = _focusedBlock.value
            for (uri in uris) {
                val imported = runCatching { c.attachments.importUri(uri, "Image") }.getOrNull()
                if (imported == null) {
                    _messages.tryEmit("Couldn't read that image")
                    continue
                }
                val (w, h) = c.attachments.imageSize(imported.relativePath, false)
                lastId = insert(ImageContent(imported.relativePath, w, h), afterId = lastId)
            }
            saveNow()
        }
    }

    fun addCameraPhoto(relativePath: String) {
        viewModelScope.launch {
            val (w, h) = c.attachments.imageSize(relativePath, false)
            insert(ImageContent(relativePath, w, h))
            saveNow()
        }
    }

    fun replaceImage(blockId: String, uri: Uri) {
        viewModelScope.launch {
            val block = _ui.value.blocks.firstOrNull { it.id == blockId } ?: return@launch
            val old = block.content as? ImageContent ?: return@launch
            val imported = runCatching { c.attachments.importUri(uri, "Image") }.getOrNull() ?: return@launch
            val (w, h) = c.attachments.imageSize(imported.relativePath, false)
            updateContent(blockId, old.copy(path = imported.relativePath, width = w, height = h))
            saveNow()
        }
    }

    fun addDocument(uri: Uri, replaceBlockId: String? = null) {
        viewModelScope.launch {
            val imported = runCatching { c.attachments.importUri(uri, "File") }.getOrNull()
            if (imported == null) {
                _messages.tryEmit("Couldn't read that file")
                return@launch
            }
            val content: BlockContent = when {
                imported.mime == "application/pdf" || imported.displayName.endsWith(".pdf", ignoreCase = true) -> {
                    val pages = PdfSource.pageCount(c.attachments.file(imported.relativePath))
                    PdfContent(imported.relativePath, imported.displayName, pages)
                }
                imported.mime.startsWith("image/") -> {
                    val (w, h) = c.attachments.imageSize(imported.relativePath, false)
                    ImageContent(imported.relativePath, w, h)
                }
                else -> FileContent(imported.relativePath, imported.displayName, imported.mime, imported.size)
            }
            if (replaceBlockId != null) updateContent(replaceBlockId, content) else insert(content)
            saveNow()
        }
    }

    fun addAudio(relativePath: String, durationMs: Long, waveform: List<Float>, replaceBlockId: String? = null) {
        val content = AudioContent(relativePath, durationMs, waveform)
        if (replaceBlockId != null) updateContent(replaceBlockId, content) else insert(content)
        viewModelScope.launch { saveNow() }
    }

    fun addDrawing(): String {
        val id = insert(DrawingContent(aspect = 1.0f))
        viewModelScope.launch { saveNow() }
        return id
    }

    fun clearDrawing(blockId: String) {
        val block = _ui.value.blocks.firstOrNull { it.id == blockId } ?: return
        val content = block.content as? DrawingContent ?: return
        updateContent(blockId, content.copy(strokes = emptyList()))
        viewModelScope.launch { saveNow() }
    }

    fun togglePins(blockId: String) {
        val block = _ui.value.blocks.firstOrNull { it.id == blockId } ?: return
        val content = block.content as? ImageContent ?: return
        updateContent(blockId, content.copy(showPins = !content.showPins))
    }

    /** Reloads one block from the database (after the drawing or PDF screen changed it). */
    fun reloadBlock(blockId: String) {
        viewModelScope.launch {
            val entity = c.db.blocks().get(blockId) ?: return@launch
            val content = runCatching { c.codec.contentOf(entity) }.getOrNull() ?: return@launch
            _ui.update { s -> s.copy(blocks = s.blocks.map { if (it.id == blockId) it.copy(content = content) else it }) }
        }
    }

    // ---------- Layers & pins ----------

    suspend fun openLayer(blockId: String): String {
        flushSave()
        return c.notes.openOrCreateBlockLayer(noteId, blockId)
    }

    suspend fun existingLayer(blockId: String): String? = c.notes.existingBlockLayer(blockId)

    suspend fun addPin(blockId: String, x: Float, y: Float): String {
        flushSave()
        return c.notes.addPin(noteId, blockId, x, y)
    }

    fun deletePin(pinId: String) = viewModelScope.launch { c.notes.deletePin(pinId) }

    suspend fun recognizeText(blockId: String): String {
        val block = _ui.value.blocks.firstOrNull { it.id == blockId } ?: return ""
        val content = block.content as? ImageContent ?: return ""
        val file = c.attachments.readableAsync(content.path, _ui.value.encrypted)
        return runCatching { Ocr.recognize(file) }.getOrDefault("")
    }

    // ---------- Note ----------

    fun addTag(name: String) = viewModelScope.launch { c.tags.addToNote(noteId, name) }
    fun removeTag(tagId: String) = viewModelScope.launch { c.tags.removeFromNote(noteId, tagId) }
    fun setColor(color: Long?) = viewModelScope.launch { c.notes.setColor(noteId, color) }
    fun setPinned(pinned: Boolean) = viewModelScope.launch { c.notes.setPinned(noteId, pinned) }

    fun setLocked(locked: Boolean) = viewModelScope.launch {
        flushSave()
        c.notes.setLocked(noteId, locked)
    }

    fun moveNote(folderId: String?) = viewModelScope.launch {
        flushSave()
        c.notes.moveNote(noteId, folderId)
    }

    suspend fun countLayersUnderNote(): Int = c.notes.countLayersUnder(noteId)

    suspend fun trash() {
        flushSave()
        c.notes.trashNote(noteId)
    }

    override fun onCleared() {
        // Finish the last save even though this screen is gone.
        if (loaded && dirty) {
            dirty = false
            val blocks = currentBlocks()
            c.appScope.launch { c.notes.saveBlocks(noteId, blocks) }
        }
        val pendingTitle = _title.value
        val note = _ui.value.note
        if (note != null && titleInitialized && note.title != pendingTitle) {
            c.appScope.launch { c.notes.updateTitle(noteId, pendingTitle) }
        }
        super.onCleared()
    }

    companion object {
        private const val MAX_HISTORY = 80
    }
}
