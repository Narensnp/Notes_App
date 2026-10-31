package com.layers.app.ui.drawing

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.layers.app.data.model.InkStroke
import com.layers.app.data.model.InkTool
import com.layers.app.drawing.InkRenderer
import com.layers.app.ui.theme.Paper

enum class Tool { PEN, HIGHLIGHTER, ERASER }

object InkPalette {
    val pens: List<Long> = listOf(
        0xFF1E1D1B, 0xFF5E6268, 0xFF2F5D9E, 0xFF2E7D74, 0xFF4E8A3E, 0xFFC28B3C, 0xFFC0392B, 0xFF7B4FA8,
    )
    val highlighters: List<Long> = listOf(0xFFFFD54F, 0xFFA5D6A7, 0xFFF48FB1, 0xFF90CAF9, 0xFFFFAB91)
}

/** The chosen tool, color and thickness, plus whether an S Pen has been seen (palm rejection). */
@Stable
class InkTools {
    var tool by mutableStateOf(Tool.PEN)
    var penColor by mutableLongStateOf(InkPalette.pens.first())
    var highlighterColor by mutableLongStateOf(InkPalette.highlighters.first())
    var penWidthDp by mutableFloatStateOf(3f)
    var highlighterWidthDp by mutableFloatStateOf(18f)
    var stylusSeen by mutableStateOf(false)

    val color: Long get() = if (tool == Tool.HIGHLIGHTER) highlighterColor else penColor
    val widthDp: Float get() = if (tool == Tool.HIGHLIGHTER) highlighterWidthDp else penWidthDp
}

/**
 * Turns MotionEvents into ink strokes.
 *  - Palm rejection: once an S Pen (TOOL_TYPE_STYLUS) has been seen, only the pen draws; fingers are
 *    left to the caller for scrolling and zooming.
 *  - Holding the S Pen side button (BUTTON_STYLUS_PRIMARY), or using the pen's eraser end, erases.
 *  - Pressure is recorded for every sample, including batched historical samples.
 */
@Stable
class InkInput(
    private val tools: InkTools,
    private val onStroke: (InkStroke) -> Unit,
    private val onErase: (x: Float, y: Float, radius: Float) -> Unit,
    private val onEraseEnd: () -> Unit,
) {
    /** Points of the stroke being drawn (normalized x, y, pressure). Read [liveVersion] to observe changes. */
    val live = ArrayList<Float>()
    var liveVersion by mutableIntStateOf(0)
        private set
    var liveTool: InkTool = InkTool.PEN
        private set
    var liveColor: Long = 0xFF000000
        private set
    var liveWidth: Float = 0.005f
        private set

    /** Eraser cursor in normalized coordinates, or null. */
    var eraserAt by mutableStateOf<Offset?>(null)
        private set

    private var pointerId = -1
    private var erasing = false

    val isActive: Boolean get() = pointerId != -1

    /**
     * @param map converts view coordinates into normalized page coordinates (divide by page width).
     * @param pageWidthPx the page width on screen, used to turn the chosen thickness into a stroke width.
     * @param fingersDraw whether a finger may draw while no S Pen has been seen.
     * @return true if the event was used for drawing or erasing.
     */
    fun onEvent(ev: MotionEvent, map: (Float, Float) -> Offset, pageWidthPx: Float, density: Float, fingersDraw: Boolean): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = ev.actionIndex
                val type = ev.getToolType(index)
                val isPen = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
                if (isPen) tools.stylusSeen = true
                if (pointerId != -1) {
                    // A second finger joins a finger stroke: that's a pinch, not drawing.
                    if (!isPen && liveFromFinger) {
                        cancel()
                        return false
                    }
                    return pointerId != -1
                }
                if (!isPen && (tools.stylusSeen || !fingersDraw)) return false
                if (!isPen && ev.pointerCount > 1) return false
                pointerId = ev.getPointerId(index)
                liveFromFinger = !isPen
                erasing = tools.tool == Tool.ERASER || type == MotionEvent.TOOL_TYPE_ERASER ||
                    (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                live.clear()
                liveTool = if (tools.tool == Tool.HIGHLIGHTER) InkTool.HIGHLIGHTER else InkTool.PEN
                liveColor = tools.color
                liveWidth = (tools.widthDp * density) / pageWidthPx.coerceAtLeast(1f)
                addPoint(ev, index, map, isPen, historical = -1)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerId == -1) return false
                val index = ev.findPointerIndex(pointerId)
                if (index < 0) return true
                // The side button can be pressed or released mid-stroke.
                val sideButton = (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                if (sideButton && !erasing) {
                    erasing = true
                    live.clear()
                }
                val isPen = ev.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS || ev.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
                for (h in 0 until ev.historySize) addPoint(ev, index, map, isPen, historical = h)
                addPoint(ev, index, map, isPen, historical = -1)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerId == -1) return false
                if (ev.getPointerId(ev.actionIndex) == pointerId) finish()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pointerId == -1) return false
                finish()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pointerId == -1) return false
                cancel()
                return true
            }
        }
        return pointerId != -1
    }

    private var liveFromFinger = false

    private fun addPoint(ev: MotionEvent, index: Int, map: (Float, Float) -> Offset, isPen: Boolean, historical: Int) {
        val x = if (historical >= 0) ev.getHistoricalX(index, historical) else ev.getX(index)
        val y = if (historical >= 0) ev.getHistoricalY(index, historical) else ev.getY(index)
        val p = when {
            !isPen -> 0.5f
            historical >= 0 -> ev.getHistoricalPressure(index, historical)
            else -> ev.getPressure(index)
        }
        val pos = map(x, y)
        if (erasing) {
            eraserAt = pos
            onErase(pos.x, pos.y, ERASER_RADIUS)
            return
        }
        val n = live.size
        if (n >= 3) {
            val dx = pos.x - live[n - 3]
            val dy = pos.y - live[n - 2]
            if (dx * dx + dy * dy < MIN_STEP * MIN_STEP) return
        }
        live += pos.x
        live += pos.y
        live += p.coerceIn(0f, 1f)
        liveVersion++
    }

    private fun finish() {
        if (erasing) {
            onEraseEnd()
        } else if (live.size >= 3) {
            onStroke(InkStroke(liveTool, liveColor, liveWidth, ArrayList(live)))
        }
        reset()
    }

    fun cancel() {
        if (erasing) onEraseEnd()
        reset()
    }

    private fun reset() {
        pointerId = -1
        erasing = false
        liveFromFinger = false
        eraserAt = null
        live.clear()
        liveVersion++
    }

    companion object {
        const val ERASER_RADIUS = 0.018f
        private const val MIN_STEP = 0.0008f
    }
}

/** Draws committed strokes and the stroke in progress, each on its own layer so zooming stays smooth. */
@Composable
fun InkLayers(
    strokes: List<InkStroke>,
    input: InkInput?,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
        ) {
            drawIntoCanvas { InkRenderer.drawStrokes(it.nativeCanvas, strokes, size.width) }
        }
        if (input != null) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer()
            ) {
                @Suppress("UNUSED_VARIABLE")
                val version = input.liveVersion
                if (input.live.size >= 3) {
                    val stroke = InkStroke(input.liveTool, input.liveColor, input.liveWidth, input.live)
                    drawIntoCanvas { InkRenderer.drawStroke(it.nativeCanvas, stroke, size.width) }
                }
                input.eraserAt?.let { at ->
                    drawCircle(
                        color = Color(0x55888888),
                        radius = InkInput.ERASER_RADIUS * size.width,
                        center = Offset(at.x * size.width, at.y * size.width),
                    )
                    drawCircle(
                        color = Color(0xAA555555),
                        radius = InkInput.ERASER_RADIUS * size.width,
                        center = Offset(at.x * size.width, at.y * size.width),
                        style = Stroke(width = 1.5f),
                    )
                }
            }
        }
    }
}

/** Pen / highlighter / eraser, colors and thickness. */
@Composable
fun ToolPalette(tools: InkTools, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(12.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Paper.colors.sheet,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolChip(Icons.Rounded.Create, "Pen", tools.tool == Tool.PEN) { tools.tool = Tool.PEN }
                ToolChip(Icons.Rounded.Highlight, "Highlighter", tools.tool == Tool.HIGHLIGHTER) { tools.tool = Tool.HIGHLIGHTER }
                ToolChip(Icons.Rounded.AutoFixNormal, "Eraser", tools.tool == Tool.ERASER) { tools.tool = Tool.ERASER }
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val colors = if (tools.tool == Tool.HIGHLIGHTER) InkPalette.highlighters else InkPalette.pens
                    colors.forEachIndexed { i, c ->
                        val selected = tools.color == c && tools.tool != Tool.ERASER
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(c.toInt()))
                                .border(if (selected) 2.5.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.onSurface else Paper.colors.hairline, CircleShape)
                                .clickable(role = Role.RadioButton) {
                                    if (tools.tool == Tool.HIGHLIGHTER) {
                                        tools.highlighterColor = c
                                    } else {
                                        tools.penColor = c
                                        if (tools.tool == Tool.ERASER) tools.tool = Tool.PEN
                                    }
                                }
                                .semantics {
                                    contentDescription = "Ink color ${i + 1}"
                                    this.selected = selected
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (tools.tool != Tool.ERASER) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    if (tools.tool == Tool.HIGHLIGHTER) {
                        Slider(
                            value = tools.highlighterWidthDp,
                            onValueChange = { tools.highlighterWidthDp = it },
                            valueRange = 8f..40f,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Highlighter size" },
                        )
                    } else {
                        Slider(
                            value = tools.penWidthDp,
                            onValueChange = { tools.penWidthDp = it },
                            valueRange = 1f..16f,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Pen size" },
                        )
                    }
                }
            } else {
                Text(
                    "Tip: hold the S Pen button to erase while writing.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
