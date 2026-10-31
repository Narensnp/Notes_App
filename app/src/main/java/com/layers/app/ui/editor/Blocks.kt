package com.layers.app.ui.editor

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.layers.app.data.db.PinEntity
import com.layers.app.data.model.AudioContent
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.DrawingContent
import com.layers.app.data.model.FileContent
import com.layers.app.data.model.ImageContent
import com.layers.app.data.model.PdfContent
import com.layers.app.data.model.Repeat
import com.layers.app.drawing.InkRenderer
import com.layers.app.pdf.PdfSource
import com.layers.app.share.NoteExporter
import com.layers.app.ui.components.LayerBadge
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.appContainer
import com.layers.app.ui.components.layerSharedBounds
import com.layers.app.ui.components.rememberHaptics
import com.layers.app.ui.theme.Paper
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The readable form of an attachment (a decrypted cache copy for locked notes), loaded off the main thread. */
@Composable
fun rememberReadableFile(path: String, encrypted: Boolean): File? {
    val c = appContainer()
    val state = produceState<File?>(initialValue = if (!encrypted) c.attachments.file(path) else null, path, encrypted) {
        value = runCatching { c.attachments.readableAsync(path, encrypted) }.getOrNull()
    }
    return state.value
}

// ---------- Text ----------

@Composable
fun TextBlockView(
    state: RichTextState,
    placeholder: String?,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onEdited: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val edited by rememberUpdatedState(onEdited)
    LaunchedEffect(state) {
        snapshotFlow { state.annotatedString }.drop(1).collect { edited() }
    }
    Box(modifier.fillMaxWidth()) {
        BasicRichTextEditor(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .padding(vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
        if (placeholder != null && state.annotatedString.text.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

// ---------- Checklist / task ----------

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

fun formatDueLabel(dueAt: Long, hasTime: Boolean): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(dueAt).atZone(zone)
    val today = LocalDate.now(zone)
    val day = when (dt.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> dt.format(dayFormat)
    }
    return if (hasTime) "$day, ${dt.format(timeFormat)}" else day
}

fun isOverdue(c: ChecklistContent, now: Long = System.currentTimeMillis()): Boolean {
    val due = c.dueAt ?: return false
    if (c.done) return false
    return if (c.hasTime) due < now else {
        val zone = ZoneId.systemDefault()
        Instant.ofEpochMilli(due).atZone(zone).toLocalDate().isBefore(LocalDate.now(zone))
    }
}

@Composable
fun ChecklistBlockView(
    blockId: String,
    content: ChecklistContent,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onText: (String) -> Unit,
    onEnter: (before: String, after: String) -> Unit,
    onDeleteEmpty: () -> Unit,
    onTaskSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    var value by remember(blockId) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(content.text))
    }
    LaunchedEffect(content.text) {
        if (value.text != content.text) value = value.copy(text = content.text)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = content.done,
            onCheckedChange = {
                haptics.tick()
                onToggle(it)
            },
            modifier = Modifier.semantics { contentDescription = if (content.done) "Done" else "Not done" },
        )
        Column(
            Modifier
                .weight(1f)
                .padding(top = 12.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { v ->
                    val nl = v.text.indexOf('\n')
                    if (nl >= 0) {
                        onEnter(v.text.substring(0, nl), v.text.substring(nl + 1).replace("\n", ""))
                    } else {
                        value = v
                        onText(v.text)
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (content.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (content.done) TextDecoration.LineThrough else null,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChange(it.isFocused) }
                    .onPreviewKeyEvent { event ->
                        // Backspace in an empty item removes it (keyboards that send key events).
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.text.isEmpty()) {
                            onDeleteEmpty()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text("To-do", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                        }
                        inner()
                    }
                },
            )
            val due = content.dueAt
            if (due != null) {
                val overdue = isOverdue(content)
                val color = if (overdue) Paper.colors.overdue else MaterialTheme.colorScheme.primary
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                ) {
                    Icon(
                        if (overdue) Icons.Rounded.Warning else Icons.Rounded.Event,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        (if (overdue) "Overdue · " else "") + formatDueLabel(due, content.hasTime),
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    if (content.reminderOffsetMin != null) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.Alarm, contentDescription = "Reminder set", tint = color, modifier = Modifier.size(14.dp))
                    }
                    if (content.repeat != Repeat.NONE) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.Repeat, contentDescription = "Repeats ${content.repeat.name.lowercase()}", tint = color, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        IconButton(onClick = onTaskSettings) {
            Icon(
                Icons.Rounded.Alarm,
                contentDescription = "Due date and reminder",
                tint = if (content.dueAt != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            )
        }
    }
}

// ---------- Image with pins ----------

@Composable
fun ImageBlockView(
    content: ImageContent,
    encrypted: Boolean,
    pins: List<PinEntity>,
    layerCount: Int,
    sharedKey: String,
    onTap: () -> Unit,
    onLongPress: (x: Float, y: Float) -> Unit,
    onPinTap: (PinEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val file = rememberReadableFile(content.path, encrypted)
    val ratio = (content.width.toFloat() / content.height.coerceAtLeast(1)).coerceIn(0.05f, 20f)
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .layerSharedBounds(sharedKey)
                .shadow(3.dp, RoundedCornerShape(14.dp), ambientColor = Paper.colors.shadow.copy(alpha = 0.12f), spotColor = Paper.colors.shadow.copy(alpha = 0.18f))
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (file != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(file).crossfade(true).build(),
                    contentDescription = "Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { tap() },
                            onLongPress = { o ->
                                longPress(
                                    (o.x / size.width).coerceIn(0f, 1f),
                                    (o.y / size.height).coerceIn(0f, 1f),
                                )
                            },
                        )
                    }
            )
            if (content.showPins && pins.isNotEmpty()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    pins.forEach { pin ->
                        PinMarker(
                            number = pin.number,
                            onClick = { onPinTap(pin) },
                            modifier = Modifier.offset(
                                x = maxWidth * pin.x - PIN_SIZE / 2,
                                y = maxHeight * pin.y - PIN_SIZE / 2,
                            ),
                        )
                    }
                }
            }
        }
        LayerBadge(
            count = layerCount,
            pins = pins.size,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )
    }
}

private val PIN_SIZE = 30.dp

@Composable
fun PinMarker(number: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Paper.colors.pin,
        contentColor = Paper.colors.onPin,
        shadowElevation = 4.dp,
        modifier = modifier
            .size(PIN_SIZE)
            .semantics { contentDescription = "Pin $number. Opens its sub-note." },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Drawing ----------

/** Drawings always sit on a warm white sheet, like paper, in both light and dark themes. */
val DrawingPaper = Color(0xFFFFFCF5)

@Composable
fun DrawingBlockView(
    content: DrawingContent,
    layerCount: Int,
    sharedKey: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        PaperSheet(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f / content.aspect.coerceIn(0.2f, 5f))
                .layerSharedBounds(sharedKey)
                .semantics { contentDescription = "Drawing. Tap to edit." },
            color = DrawingPaper,
            shape = RoundedCornerShape(14.dp),
            elevation = 2.dp,
            onClick = onTap,
            onLongClick = onLongPress,
        ) {
            if (content.strokes.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Gesture, contentDescription = null, tint = Color(0xFF8E8981))
                    Spacer(Modifier.height(6.dp))
                    Text("Tap to draw", style = MaterialTheme.typography.labelLarge, color = Color(0xFF8E8981))
                }
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { InkRenderer.drawStrokes(it.nativeCanvas, content.strokes, size.width) }
                }
            }
        }
        LayerBadge(count = layerCount, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

// ---------- Audio ----------

@Composable
fun AudioBlockView(
    content: AudioContent,
    encrypted: Boolean,
    layerCount: Int,
    sharedKey: String,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = rememberReadableFile(content.path, encrypted)
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = content.durationMs.coerceAtLeast(1L)

    DisposableEffect(content.path) {
        onDispose {
            player?.release()
            player = null
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { p -> runCatching { position = p.currentPosition.toLong() } }
            delay(50)
        }
    }

    fun togglePlay() {
        val f = file ?: return
        val p = player
        if (p == null) {
            val mp = MediaPlayer()
            runCatching {
                mp.setDataSource(f.absolutePath)
                mp.setOnPreparedListener {
                    if (position > 0) it.seekTo(position.toInt())
                    it.start()
                    playing = true
                }
                mp.setOnCompletionListener {
                    playing = false
                    position = 0L
                }
                mp.prepareAsync()
                player = mp
            }.onFailure { mp.release() }
        } else if (playing) {
            p.pause()
            playing = false
        } else {
            p.start()
            playing = true
        }
    }

    Box(modifier.fillMaxWidth()) {
        PaperSheet(
            modifier = Modifier
                .fillMaxWidth()
                .layerSharedBounds(sharedKey),
            shape = RoundedCornerShape(16.dp),
            elevation = 2.dp,
            onClick = { togglePlay() },
            onLongClick = onLongPress,
        ) {
            Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { togglePlay() }) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause recording" else "Play recording",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    val progress = if (dragging) dragValue else position.toFloat() / duration
                    Waveform(content.waveform, progress, Modifier.fillMaxWidth().height(34.dp))
                    Slider(
                        value = progress.coerceIn(0f, 1f),
                        onValueChange = {
                            dragging = true
                            dragValue = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            position = (dragValue * duration).toLong()
                            player?.seekTo(position.toInt())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .semantics { contentDescription = "Seek" },
                    )
                    Row {
                        Text(NoteExporter.formatDuration(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(NoteExporter.formatDuration(content.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        LayerBadge(count = layerCount, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
fun Waveform(samples: List<Float>, progress: Float, modifier: Modifier = Modifier) {
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val bars = if (samples.isEmpty()) List(48) { 0.15f } else samples
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
        bars.forEachIndexed { i, v ->
            val h = (size.height * (0.12f + 0.88f * v.coerceIn(0f, 1f)))
            val x = i * (barWidth + gap)
            val color = if ((i + 0.5f) / bars.size <= progress) played else rest
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

// ---------- PDF ----------

@Composable
fun PdfBlockView(
    content: PdfContent,
    encrypted: Boolean,
    layerCount: Int,
    sharedKey: String,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = rememberReadableFile(content.path, encrypted)
    val thumb by produceState<ImageBitmap?>(initialValue = null, file) {
        value = file?.let { PdfSource.renderPage(it, 0, 360)?.asImageBitmap() }
    }
    Box(modifier.fillMaxWidth()) {
        PaperSheet(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = 2.dp,
            onClick = onOpen,
            onLongClick = onLongPress,
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(84.dp)
                        .aspectRatio(0.72f)
                        .layerSharedBounds(sharedKey)
                        .shadow(2.dp, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val t = thumb
                    if (t != null) {
                        Image(t, contentDescription = "First page", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = Color(0xFFB3261E))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(content.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "PDF · ${content.pageCount} page${if (content.pageCount != 1) "s" else ""}" +
                            if (content.annotations.isNotEmpty()) " · annotated" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Tap to read and annotate", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        LayerBadge(count = layerCount, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

// ---------- Other files ----------

@Composable
fun FileBlockView(
    content: FileContent,
    layerCount: Int,
    sharedKey: String,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        PaperSheet(
            modifier = Modifier
                .fillMaxWidth()
                .layerSharedBounds(sharedKey),
            shape = RoundedCornerShape(16.dp),
            elevation = 2.dp,
            onClick = onOpen,
            onLongClick = onLongPress,
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(content.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        NoteExporter.formatSize(content.size) + " · " + content.mime.substringAfter('/').uppercase().take(12),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LayerBadge(count = layerCount, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}
