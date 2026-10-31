package com.layers.app.ui.drawing

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.layers.app.AppContainer
import com.layers.app.data.model.DrawingContent
import com.layers.app.data.model.InkStroke
import com.layers.app.data.model.isLocked
import com.layers.app.drawing.InkRenderer
import com.layers.app.security.SecureContent
import com.layers.app.ui.components.layersViewModel
import com.layers.app.ui.editor.DrawingPaper
import com.layers.app.ui.lock.LockedMessage
import com.layers.app.ui.lock.rememberAuthenticator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.hypot

data class DrawingUi(
    val loading: Boolean = true,
    val strokes: List<InkStroke> = emptyList(),
    val aspect: Float = 1f,
    val needsUnlock: Boolean = false,
    val locked: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

class DrawingViewModel(private val c: AppContainer, private val noteId: String, private val blockId: String) : ViewModel() {
    private val _ui = MutableStateFlow(DrawingUi())
    val ui: StateFlow<DrawingUi> = _ui.asStateFlow()

    private val undoStack = ArrayDeque<List<InkStroke>>()
    private val redoStack = ArrayDeque<List<InkStroke>>()
    private var erasing = false
    private var dirty = false
    private var loaded = false
    private val saveSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            combine(c.notes.observeNote(noteId), c.lockSession.notesUnlocked) { n, u -> n to u }.collect { (note, unlocked) ->
                val locked = note?.isLocked == true
                val needsUnlock = locked && !unlocked
                _ui.value = _ui.value.copy(needsUnlock = needsUnlock, locked = locked)
                if (needsUnlock && loaded) {
                    save()
                    loaded = false
                    _ui.value = _ui.value.copy(strokes = emptyList())
                }
                if (!needsUnlock && !loaded && note != null) load()
            }
        }
        viewModelScope.launch { saveSignal.debounce(400).collect { save() } }
    }

    private suspend fun load() {
        val content = c.notes.loadBlock(blockId)?.content as? DrawingContent ?: DrawingContent()
        loaded = true
        _ui.value = _ui.value.copy(loading = false, strokes = content.strokes, aspect = content.aspect)
    }

    private fun changed(strokes: List<InkStroke>) {
        _ui.value = _ui.value.copy(strokes = strokes, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        dirty = true
        saveSignal.tryEmit(Unit)
    }

    fun addStroke(stroke: InkStroke) {
        undoStack.addLast(_ui.value.strokes)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        changed(_ui.value.strokes + stroke)
    }

    fun eraseAt(x: Float, y: Float, radius: Float) {
        val current = _ui.value.strokes
        val remaining = current.filterNot { InkRenderer.hits(it, x, y, radius) }
        if (remaining.size == current.size) return
        if (!erasing) {
            erasing = true
            undoStack.addLast(current)
            redoStack.clear()
        }
        changed(remaining)
    }

    fun endErase() {
        erasing = false
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_ui.value.strokes)
        changed(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_ui.value.strokes)
        changed(next)
    }

    /** Adds more room at the bottom of the page. */
    fun extendPage() {
        _ui.value = _ui.value.copy(aspect = (_ui.value.aspect + 0.5f).coerceAtMost(6f))
        dirty = true
        saveSignal.tryEmit(Unit)
    }

    private suspend fun save() {
        if (!dirty || !loaded) return
        dirty = false
        val strokes = _ui.value.strokes
        val aspect = _ui.value.aspect
        c.notes.updateBlock(noteId, blockId) { old ->
            (old as? DrawingContent)?.copy(strokes = strokes, aspect = aspect) ?: DrawingContent(strokes, aspect)
        }
    }

    fun flush() {
        viewModelScope.launch { save() }
    }

    override fun onCleared() {
        if (dirty && loaded) {
            dirty = false
            val strokes = _ui.value.strokes
            val aspect = _ui.value.aspect
            c.appScope.launch {
                c.notes.updateBlock(noteId, blockId) { old ->
                    (old as? DrawingContent)?.copy(strokes = strokes, aspect = aspect) ?: DrawingContent(strokes, aspect)
                }
            }
        }
        super.onCleared()
    }
}

/**
 * Full-screen drawing with the S Pen. With a pen, fingers pan and pinch-zoom the page (palm
 * rejection); without one, one finger draws and two fingers pan and zoom.
 */
@Composable
fun DrawingScreen(noteId: String, blockId: String, onBack: () -> Unit) {
    val vm = layersViewModel(key = "draw-$blockId") { DrawingViewModel(it, noteId, blockId) }
    val ui by vm.ui.collectAsState()
    val auth = rememberAuthenticator()
    val tools = remember { InkTools() }
    val density = LocalDensity.current.density
    val input = remember(vm) {
        InkInput(
            tools = tools,
            onStroke = vm::addStroke,
            onErase = vm::eraseAt,
            onEraseEnd = vm::endErase,
        )
    }

    SecureContent(enabled = ui.locked)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.flush() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Done") }
            Text("Drawing", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::undo, enabled = ui.canUndo) { Icon(Icons.Rounded.Undo, contentDescription = "Undo") }
            IconButton(onClick = vm::redo, enabled = ui.canRedo) { Icon(Icons.Rounded.Redo, contentDescription = "Redo") }
            IconButton(onClick = vm::extendPage) { Icon(Icons.Rounded.AddBox, contentDescription = "Add more space") }
        }

        if (ui.needsUnlock) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LockedMessage("Locked drawing", "Unlock to keep drawing.", onUnlock = { auth.unlock() })
            }
            return@Column
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
        ) {
            val marginPx = with(LocalDensity.current) { 12.dp.toPx() }
            val pageWidthPx = constraints.maxWidth - 2 * marginPx
            val pageHeightPx = pageWidthPx * ui.aspect
            var zoom by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset(marginPx, marginPx)) }
            val gesture = remember { FingerGesture() }
            val viewHeight = constraints.maxHeight.toFloat()
            val viewWidth = constraints.maxWidth.toFloat()

            fun clampOffset(o: Offset, z: Float): Offset {
                val w = pageWidthPx * z
                val h = pageHeightPx * z
                val minX = if (w + 2 * marginPx < viewWidth) (viewWidth - w) / 2 else viewWidth - w - marginPx
                val maxX = if (w + 2 * marginPx < viewWidth) (viewWidth - w) / 2 else marginPx
                val minY = (viewHeight - h - viewHeight * 0.4f).coerceAtMost(marginPx)
                val maxY = marginPx + viewHeight * 0.2f
                return Offset(o.x.coerceIn(minX, maxX), o.y.coerceIn(minY, maxY))
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Drawing canvas" }
                    .pointerInteropFilter { ev ->
                        val map: (Float, Float) -> Offset = { x, y ->
                            Offset((x - offset.x) / zoom / pageWidthPx, (y - offset.y) / zoom / pageWidthPx)
                        }
                        if (input.onEvent(ev, map, pageWidthPx * zoom, density, fingersDraw = true)) {
                            gesture.reset()
                            return@pointerInteropFilter true
                        }
                        gesture.handle(ev, zoom, offset) { z, o ->
                            zoom = z
                            offset = clampOffset(o, z)
                        }
                        true
                    }
            ) {
                Box(
                    Modifier
                        // Let the page be larger than the screen; it is moved and scaled by the layer below.
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = zoom
                            scaleY = zoom
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .size(
                            width = with(LocalDensity.current) { pageWidthPx.toDp() },
                            height = with(LocalDensity.current) { pageHeightPx.toDp() },
                        )
                        .shadow(6.dp, RoundedCornerShape(4.dp))
                        .background(DrawingPaper)
                ) {
                    InkLayers(strokes = ui.strokes, input = input, modifier = Modifier.fillMaxSize())
                }
            }

            if (zoom != 1f) {
                IconButton(
                    onClick = {
                        zoom = 1f
                        offset = Offset(marginPx, marginPx)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Reset zoom")
                }
            }

            ToolPalette(
                tools = tools,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
    }

}

/** One- and two-finger pan / pinch-zoom from raw MotionEvents (used when fingers aren't drawing). */
class FingerGesture {
    private var mode = 0 // 0 none, 1 pan, 2 zoom
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastFocus = Offset.Zero

    fun reset() {
        mode = 0
    }

    fun handle(ev: MotionEvent, zoom: Float, offset: Offset, apply: (Float, Offset) -> Unit) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 1
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount >= 2) startZoom(ev)
            MotionEvent.ACTION_MOVE -> when {
                mode == 2 && ev.pointerCount >= 2 -> {
                    val span = span(ev)
                    val focus = focus(ev)
                    if (lastSpan > 0f && span > 0f) {
                        val newZoom = (zoom * span / lastSpan).coerceIn(0.6f, 5f)
                        val factor = newZoom / zoom
                        val newOffset = Offset(
                            focus.x - (lastFocus.x - offset.x) * factor,
                            focus.y - (lastFocus.y - offset.y) * factor,
                        )
                        apply(newZoom, newOffset)
                    }
                    lastSpan = span
                    lastFocus = focus
                }
                mode == 1 -> {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    lastX = ev.x
                    lastY = ev.y
                    apply(zoom, Offset(offset.x + dx, offset.y + dy))
                }
                mode == 0 && ev.pointerCount >= 2 -> startZoom(ev)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount - 1 >= 2) {
                    lastSpan = span(ev)
                    lastFocus = focus(ev)
                } else {
                    // Continue panning with the finger that stays down.
                    val remaining = if (ev.actionIndex == 0) 1 else 0
                    mode = 1
                    lastX = ev.getX(remaining)
                    lastY = ev.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
    }

    private fun startZoom(ev: MotionEvent) {
        mode = 2
        lastSpan = span(ev)
        lastFocus = focus(ev)
    }

    private fun span(ev: MotionEvent): Float =
        if (ev.pointerCount < 2) 0f else hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private fun focus(ev: MotionEvent): Offset =
        if (ev.pointerCount < 2) Offset(ev.x, ev.y) else Offset((ev.getX(0) + ev.getX(1)) / 2f, (ev.getY(0) + ev.getY(1)) / 2f)
}
