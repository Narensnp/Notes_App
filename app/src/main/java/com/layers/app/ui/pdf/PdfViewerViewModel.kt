package com.layers.app.ui.pdf

import android.content.Intent
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layers.app.AppContainer
import com.layers.app.data.model.Block
import com.layers.app.data.model.InkStroke
import com.layers.app.data.model.PdfContent
import com.layers.app.data.model.TextContent
import com.layers.app.data.model.isLocked
import com.layers.app.data.model.newId
import com.layers.app.drawing.InkRenderer
import com.layers.app.pdf.PdfSource
import com.layers.app.pdf.PdfText
import com.layers.app.share.NoteExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PdfUi(
    val loading: Boolean = true,
    val error: String? = null,
    val name: String = "PDF",
    val pageSizes: List<Pair<Int, Int>> = emptyList(),
    val annotations: Map<Int, List<InkStroke>> = emptyMap(),
    val pageLayers: Map<Int, String> = emptyMap(),
    val needsUnlock: Boolean = false,
    val locked: Boolean = false,
    val isExternal: Boolean = false,
    val searchResults: List<Int>? = null,
    val searching: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/**
 * The PDF viewer: renders pages with PdfRenderer, keeps per-page ink annotations (saved inside the
 * block, separate from the original file), page sub-layers, text search (Android 15+) and export.
 * [noteId]/[blockId] identify an in-app PDF; [externalUri] a PDF opened from another app.
 */
class PdfViewerViewModel(
    private val c: AppContainer,
    private val noteId: String?,
    private val blockId: String?,
    private val externalUri: Uri?,
) : ViewModel() {

    private val _ui = MutableStateFlow(PdfUi(isExternal = externalUri != null))
    val ui: StateFlow<PdfUi> = _ui.asStateFlow()

    private var source: PdfSource? = null
    private var file: File? = null
    private val cache = LruCache<String, ImageBitmap>(18)
    private val undoStack = ArrayDeque<Map<Int, List<InkStroke>>>()
    private val redoStack = ArrayDeque<Map<Int, List<InkStroke>>>()
    private var erasing = false
    private var dirty = false
    private val saveSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var searchJob: Job? = null

    val searchSupported: Boolean get() = PdfText.isSupported

    init {
        if (externalUri != null) {
            viewModelScope.launch { openExternal(externalUri) }
        } else if (noteId != null && blockId != null) {
            viewModelScope.launch {
                combine(c.notes.observeNote(noteId), c.lockSession.notesUnlocked) { n, u -> n to u }.collect { (note, unlocked) ->
                    val locked = note?.isLocked == true
                    val needsUnlock = locked && !unlocked
                    _ui.update { it.copy(needsUnlock = needsUnlock, locked = locked) }
                    if (needsUnlock) closeSource()
                    else if (source == null && note != null) openBlock(note.encrypted)
                }
            }
            viewModelScope.launch {
                c.notes.observePageLayers(blockId).collect { layers ->
                    _ui.update { s -> s.copy(pageLayers = layers.mapNotNull { n -> n.anchorPage?.let { it to n.id } }.toMap()) }
                }
            }
            viewModelScope.launch { saveSignal.debounce(500).collect { saveAnnotations() } }
        }
    }

    private suspend fun openBlock(encrypted: Boolean) {
        val id = blockId ?: return
        val content = c.notes.loadBlock(id)?.content as? PdfContent
        if (content == null) {
            _ui.update { it.copy(loading = false, error = "This PDF is no longer here.") }
            return
        }
        try {
            val f = c.attachments.readableAsync(content.path, encrypted)
            file = f
            val s = PdfSource.open(f)
            source = s
            _ui.update {
                it.copy(loading = false, name = content.name, pageSizes = s.pageSizes, annotations = content.annotations)
            }
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = "This PDF can't be opened. It may be damaged or password-protected.") }
        }
    }

    private suspend fun openExternal(uri: Uri) {
        try {
            val resolver = appContext().contentResolver
            var name = "Document.pdf"
            withContext(Dispatchers.IO) {
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                        if (cur.moveToFirst() && !cur.isNull(0)) name = cur.getString(0)
                    }
                }
            }
            val dir = File(appContext().cacheDir, "external").apply { mkdirs() }
            val target = File(dir, UUID.randomUUID().toString() + ".pdf")
            withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { inp -> target.outputStream().use { inp.copyTo(it) } }
                    ?: throw IllegalStateException("Can't read")
            }
            file = target
            val s = PdfSource.open(target)
            source = s
            _ui.update { it.copy(loading = false, name = name, pageSizes = s.pageSizes) }
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = "This PDF can't be opened.") }
        }
    }

    private fun appContext() = c.appContext

    private fun closeSource() {
        val s = source ?: return
        source = null
        cache.evictAll()
        c.appScope.launch { s.closeSafely() }
    }

    /** A page bitmap at roughly [widthPx] wide (cached). */
    suspend fun bitmap(page: Int, widthPx: Int): ImageBitmap? {
        val key = "$page@$widthPx"
        cache.get(key)?.let { return it }
        val s = source ?: return null
        val bmp = s.render(page, widthPx)?.asImageBitmap() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    // ---------- Annotations ----------

    private fun setAnnotations(map: Map<Int, List<InkStroke>>) {
        _ui.update { it.copy(annotations = map, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
        dirty = true
        saveSignal.tryEmit(Unit)
    }

    fun addStroke(page: Int, stroke: InkStroke) {
        val current = _ui.value.annotations
        undoStack.addLast(current)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        setAnnotations(current + (page to (current[page].orEmpty() + stroke)))
    }

    fun eraseAt(page: Int, x: Float, y: Float, r: Float) {
        val current = _ui.value.annotations
        val strokes = current[page].orEmpty()
        val remaining = strokes.filterNot { InkRenderer.hits(it, x, y, r) }
        if (remaining.size == strokes.size) return
        if (!erasing) {
            erasing = true
            undoStack.addLast(current)
            redoStack.clear()
        }
        setAnnotations(current + (page to remaining))
    }

    fun endErase() {
        erasing = false
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_ui.value.annotations)
        setAnnotations(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_ui.value.annotations)
        setAnnotations(next)
    }

    private suspend fun saveAnnotations() {
        if (!dirty || noteId == null || blockId == null) return
        dirty = false
        val map = _ui.value.annotations.filterValues { it.isNotEmpty() }
        c.notes.updateBlock(noteId, blockId) { old -> (old as? PdfContent)?.copy(annotations = map) ?: old }
    }

    fun flush() {
        viewModelScope.launch { saveAnnotations() }
    }

    // ---------- Layers ----------

    suspend fun openPageLayer(page: Int): String? {
        val n = noteId ?: return null
        val b = blockId ?: return null
        return c.notes.openOrCreatePageLayer(n, b, page)
    }

    // ---------- Search ----------

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _ui.update { it.copy(searchResults = null, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _ui.update { it.copy(searching = true) }
            val s = source ?: return@launch
            val hits = ArrayList<Int>()
            for (i in 0 until s.pageCount) {
                if (s.pageText(i).contains(q, ignoreCase = true)) hits += i
            }
            _ui.update { it.copy(searchResults = hits, searching = false) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searchResults = null, searching = false) }
    }

    // ---------- Export & save ----------

    /** Flattens pages and ink into a new PDF and returns a share intent. */
    suspend fun exportAnnotated(): Intent? = withContext(Dispatchers.IO) {
        val s = source ?: return@withContext null
        val annotations = _ui.value.annotations
        val doc = PdfDocument()
        val out = c.attachments.sharedFile(NoteExporter.safeName(_ui.value.name.removeSuffix(".pdf")) + " (annotated).pdf")
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (i in 0 until s.pageCount) {
                val (pw, ph) = s.pageSizes[i]
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val bmp = s.render(i, (pw * 2).coerceAtMost(3000))
                if (bmp != null) {
                    page.canvas.drawBitmap(bmp, null, Rect(0, 0, pw, ph), paint)
                    bmp.recycle()
                }
                annotations[i]?.let { InkRenderer.drawStrokes(page.canvas, it, pw.toFloat()) }
                doc.finishPage(page)
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, c.attachments.uriForShared(out))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Copies an externally opened PDF into Layers as a new note in [folderId]. */
    suspend fun saveExternalTo(folderId: String?): String? {
        val f = file ?: return null
        val name = _ui.value.name
        val imported = c.attachments.importFile(f, name, "application/pdf")
        val title = name.removeSuffix(".pdf").removeSuffix(".PDF")
        c.settings.touchRecentFolder(folderId)
        return c.notes.createNote(
            folderId = folderId,
            title = title,
            blocks = listOf(
                Block(newId(), PdfContent(imported.relativePath, name, _ui.value.pageSizes.size)),
                Block(newId(), TextContent()),
            ),
        )
    }

    override fun onCleared() {
        if (dirty && noteId != null && blockId != null) {
            dirty = false
            val map = _ui.value.annotations.filterValues { it.isNotEmpty() }
            c.appScope.launch {
                c.notes.updateBlock(noteId, blockId) { old -> (old as? PdfContent)?.copy(annotations = map) ?: old }
            }
        }
        val s = source
        source = null
        if (s != null) c.appScope.launch { s.closeSafely() }
        if (externalUri != null) file?.let { f -> c.appScope.launch { withContext(Dispatchers.IO) { f.delete() } } }
        super.onCleared()
    }
}
