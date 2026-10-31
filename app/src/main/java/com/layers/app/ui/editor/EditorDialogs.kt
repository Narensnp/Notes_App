package com.layers.app.ui.editor

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.layers.app.audio.AudioRecorder
import com.layers.app.data.db.TagEntity
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.Repeat
import com.layers.app.data.repo.ALL_DAY_REMINDER_HOUR
import com.layers.app.share.NoteExporter
import com.layers.app.ui.components.appContainer
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ---------- Task: due date, time, reminder, repeat ----------

private val longDate = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")

@Composable
fun TaskDialog(
    content: ChecklistContent,
    onDismiss: () -> Unit,
    onSave: (ChecklistContent) -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val existing = content.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var date by remember { mutableStateOf(existing?.toLocalDate() ?: LocalDate.now(zone)) }
    var time by remember { mutableStateOf(if (content.hasTime) existing?.toLocalTime() else null) }
    var reminder by remember { mutableStateOf(content.reminderOffsetMin) }
    var repeat by remember { mutableStateOf(content.repeat) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    fun build(): ChecklistContent {
        val t = time
        val dueAt = if (t != null) date.atTime(t).atZone(zone).toInstant().toEpochMilli()
        else date.atStartOfDay(zone).toInstant().toEpochMilli()
        return content.copy(dueAt = dueAt, hasTime = t != null, reminderOffsetMin = reminder, repeat = repeat)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Task") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Due", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickDate = true }) {
                        Icon(Icons.Rounded.Event, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(date.format(longDate))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickTime = true }) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(time?.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")) ?: "All day")
                    }
                    if (time != null) {
                        TextButton(onClick = { time = null }) { Text("Make all-day") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Reminder", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val options: List<Pair<Int?, String>> = listOf(
                        null to "None",
                        0 to if (time == null) "At $ALL_DAY_REMINDER_HOUR:00" else "At time",
                        10 to "10 min before",
                        60 to "1 hour before",
                        1440 to "1 day before",
                    )
                    options.forEach { (value, label) ->
                        FilterChip(selected = reminder == value, onClick = { reminder = value }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Repeat", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Repeat.NONE to "Never", Repeat.DAILY to "Daily", Repeat.WEEKLY to "Weekly", Repeat.MONTHLY to "Monthly")
                        .forEach { (value, label) ->
                            FilterChip(selected = repeat == value, onClick = { repeat = value }, label = { Text(label) })
                        }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { addToPhoneCalendar(context, build()) }) {
                    Icon(Icons.Rounded.Event, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add to phone calendar")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(build()) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (content.dueAt != null) {
                    TextButton(onClick = {
                        onSave(content.copy(dueAt = null, hasTime = false, reminderOffsetMin = null, repeat = Repeat.NONE))
                    }) { Text("Remove date") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
    if (pickTime) {
        val initial = time ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(state.hour, state.minute)
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}

/** One-way export to the phone's calendar app. Needs no calendar permission. */
fun addToPhoneCalendar(context: Context, content: ChecklistContent) {
    val due = content.dueAt ?: System.currentTimeMillis()
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, content.text.ifBlank { "Task" })
        .putExtra(CalendarContract.Events.DESCRIPTION, "From Layers")
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, !content.hasTime)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, due)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, if (content.hasTime) due + 30 * 60_000L else due + 24 * 3_600_000L)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
    }
}

// ---------- Voice recording ----------

@Composable
fun RecordAudioDialog(
    onDismiss: () -> Unit,
    onSaved: (relativePath: String, durationMs: Long, waveform: List<Float>) -> Unit,
) {
    val context = LocalContext.current
    val container = appContainer()
    val recorder = remember { AudioRecorder(context) }
    val levels = remember { mutableStateListOf<Float>() }
    var elapsed by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val (rel, file) = container.attachments.createEmpty()
        target = rel
        try {
            recorder.start(file)
        } catch (e: Exception) {
            error = "The microphone couldn't start."
            return@LaunchedEffect
        }
        while (recorder.isRecording) {
            levels += recorder.level()
            if (levels.size > 400) levels.removeAt(0)
            elapsed = recorder.elapsedMs()
            delay(80)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (recorder.isRecording) recorder.cancel() }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Voice recording") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FiberManualRecord, contentDescription = "Recording", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(NoteExporter.formatDuration(elapsed), style = MaterialTheme.typography.headlineSmall)
                    }
                    Spacer(Modifier.height(16.dp))
                    Waveform(
                        samples = levels.takeLast(60),
                        progress = 1f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = error == null,
                onClick = {
                    val duration = recorder.stop()
                    val rel = target
                    if (rel != null && duration > 300) {
                        onSaved(rel, duration, downsample(levels.toList(), 64))
                    } else {
                        rel?.let { container.attachments.delete(it) }
                        onDismiss()
                    }
                },
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Stop & save")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                recorder.cancel()
                onDismiss()
            }) { Text("Discard") }
        },
    )
}

private fun downsample(values: List<Float>, buckets: Int): List<Float> {
    if (values.isEmpty()) return emptyList()
    if (values.size <= buckets) return normalize(values)
    val out = ArrayList<Float>(buckets)
    val step = values.size.toFloat() / buckets
    for (i in 0 until buckets) {
        val from = (i * step).toInt()
        val to = ((i + 1) * step).toInt().coerceAtMost(values.size).coerceAtLeast(from + 1)
        out += values.subList(from, to).max()
    }
    return normalize(out)
}

private fun normalize(values: List<Float>): List<Float> {
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: return values
    return values.map { (it / max).coerceIn(0f, 1f) }
}

// ---------- Insert sheet ----------

enum class InsertKind(val label: String) {
    TEXT("Text"), CHECKLIST("Checklist"), PHOTO("Photo"), CAMERA("Camera"), SCREENSHOT("Last screenshot"),
    DRAWING("Drawing"), VOICE("Voice"), PDF("PDF"), FILE("File"),
}

@Composable
fun InsertSheet(
    icons: Map<InsertKind, ImageVector>,
    onDismiss: () -> Unit,
    onPick: (InsertKind) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Insert",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InsertKind.entries.chunked(3).forEach { row ->
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                   row.forEach { kind ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(role = Role.Button) { onPick(kind) }
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(icons[kind] ?: Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(kind.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                   }
                  }
                }
            }
        }
    }
}

// ---------- Tags ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsDialog(
    current: List<TagEntity>,
    all: List<TagEntity>,
    onAdd: (String) -> Unit,
    onRemove: (TagEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column {
                if (current.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        current.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { onRemove(tag) },
                                label = { Text("#${tag.name}") },
                                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove tag ${tag.name}", modifier = Modifier.size(16.dp)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Add a tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val suggestions = all.filter { t ->
                    current.none { it.id == t.id } && (text.isBlank() || t.name.contains(text.trim().removePrefix("#"), ignoreCase = true))
                }.take(12)
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { tag ->
                            FilterChip(selected = false, onClick = { onAdd(tag.name) }, label = { Text("#${tag.name}") })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) onAdd(text)
                text = ""
                onDismiss()
            }) { Text(if (text.isNotBlank()) "Add" else "Done") }
        },
    )
}
