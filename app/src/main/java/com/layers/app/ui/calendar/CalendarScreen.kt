package com.layers.app.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layers.app.AppContainer
import com.layers.app.data.db.TaskEntity
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.Repeat
import com.layers.app.ui.components.EmptyState
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.layersViewModel
import com.layers.app.ui.components.rememberHaptics
import com.layers.app.ui.editor.addToPhoneCalendar
import com.layers.app.ui.theme.Paper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

data class CalendarUi(
    val month: YearMonth,
    val selected: LocalDate,
    val byDay: Map<LocalDate, List<TaskEntity>>,
    val noteTitles: Map<String, String>,
)

class CalendarViewModel(private val c: AppContainer) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val month = MutableStateFlow(YearMonth.now(zone))
    private val selected = MutableStateFlow(LocalDate.now(zone))
    val today: LocalDate get() = LocalDate.now(zone)

    val ui: StateFlow<CalendarUi> = run {
        val tasks = month.flatMapLatest { m ->
            val start = m.atDay(1).minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = m.atEndOfMonth().plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
            c.tasks.observeBetween(start, end)
        }
        val titles = c.notes.observeRecentNotes().map { _ -> Unit }
        combine(month, selected, tasks, titles) { m, s, list, _ ->
            val byDay = list.groupBy { t -> Instant.ofEpochMilli(t.dueAt ?: 0L).atZone(zone).toLocalDate() }
            val ids = list.map { it.noteId }.distinct()
            val notes = c.db.notes().getMany(ids)
            CalendarUi(m, s, byDay, notes.associate { it.id to com.layers.app.data.repo.NoteRepository.displayTitle(it) })
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CalendarUi(YearMonth.now(zone), LocalDate.now(zone), emptyMap(), emptyMap()),
        )
    }

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
    }

    fun select(date: LocalDate) {
        selected.value = date
        if (YearMonth.from(date) != month.value) month.value = YearMonth.from(date)
    }

    fun goToday() = select(today)

    fun setDone(task: TaskEntity, done: Boolean) = viewModelScope.launch { c.tasks.setDone(task.blockId, done) }
}

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayTitle = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun CalendarScreen(onOpenTask: (noteId: String, blockId: String) -> Unit) {
    val vm = layersViewModel { CalendarViewModel(it) }
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = vm.today

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ui.month.format(monthTitle), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::goToday) { Icon(Icons.Rounded.Today, contentDescription = "Today") }
                IconButton(onClick = vm::previousMonth) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Previous month") }
                IconButton(onClick = vm::nextMonth) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Next month") }
            }
        },
    ) { padding ->
        val dayTasks = ui.byDay[ui.selected].orEmpty()
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "month") {
                PaperSheet(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    AnimatedContent(
                        targetState = ui.month,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                                (slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (forward) -it / 3 else it / 3 } + fadeOut())
                        },
                        label = "month",
                    ) { month ->
                        MonthGrid(
                            month = month,
                            selected = ui.selected,
                            today = today,
                            byDay = ui.byDay,
                            now = now,
                            onSelect = { haptics.tick(); vm.select(it) },
                            onSwipe = { forward -> if (forward) vm.nextMonth() else vm.previousMonth() },
                        )
                    }
                }
            }
            item(key = "dayTitle") {
                Text(
                    if (ui.selected == today) "Today · " + ui.selected.format(dayTitle) else ui.selected.format(dayTitle),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            if (dayTasks.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.EventAvailable,
                        title = if (ui.selected == today) "No tasks today" else "Nothing planned",
                        message = "Give a checklist item a due date and it shows up here.",
                    )
                }
            } else {
                items(dayTasks, key = { it.blockId }) { task ->
                    TaskRow(
                        task = task,
                        noteTitle = ui.noteTitles[task.noteId],
                        overdue = isOverdue(task, now, zone),
                        onToggle = { done -> haptics.tick(); vm.setDone(task, done) },
                        onOpen = { onOpenTask(task.noteId, task.blockId) },
                        onCalendar = {
                            addToPhoneCalendar(
                                context,
                                ChecklistContent(text = task.title, dueAt = task.dueAt, hasTime = task.hasTime),
                            )
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

private fun isOverdue(task: TaskEntity, now: Long, zone: ZoneId): Boolean {
    val due = task.dueAt ?: return false
    if (task.done) return false
    return if (task.hasTime) due < now else Instant.ofEpochMilli(due).atZone(zone).toLocalDate().isBefore(LocalDate.now(zone))
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    byDay: Map<LocalDate, List<TaskEntity>>,
    now: Long,
    onSelect: (LocalDate) -> Unit,
    onSwipe: (forward: Boolean) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val firstDay: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val first = month.atDay(1)
    val lead = ((first.dayOfWeek.value - firstDay.value) + 7) % 7
    val start = first.minusDays(lead.toLong())
    Column(
        Modifier
            .padding(12.dp)
            .pointerInput(month) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag < -120f) onSwipe(true) else if (drag > 120f) onSwipe(false)
                    },
                    onHorizontalDrag = { _, amount -> drag += amount },
                )
            }
    ) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val dow = firstDay.plus(i.toLong())
                Text(
                    dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        for (week in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0 until 7) {
                    val date = start.plusDays((week * 7 + d).toLong())
                    val inMonth = YearMonth.from(date) == month
                    val tasks = byDay[date].orEmpty()
                    val hasOverdue = tasks.any { isOverdue(it, now, zone) }
                    val open = tasks.count { !it.done }
                    DayCell(
                        date = date,
                        inMonth = inMonth,
                        isToday = date == today,
                        isSelected = date == selected,
                        taskCount = tasks.size,
                        openCount = open,
                        hasOverdue = hasOverdue,
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    taskCount: Int,
    openCount: Int,
    hasOverdue: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val description = buildString {
        append(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")))
        if (isToday) append(", today")
        if (taskCount > 0) append(", $taskCount task" + if (taskCount != 1) "s" else "")
        if (hasOverdue) append(", overdue")
    }
    Column(
        modifier = modifier
            .aspectRatio(0.9f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (isSelected) primary else Color.Transparent)
                .then(if (isToday && !isSelected) Modifier.border(1.5.dp, primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.dayOfMonth}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    !inMonth -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(6.dp)) {
            if (taskCount > 0) {
                val dots = taskCount.coerceAtMost(3)
                for (i in 0 until dots) {
                    val color = when {
                        hasOverdue && i == 0 -> Paper.colors.overdue
                        i < openCount -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Box(
                        Modifier
                            .size(if (hasOverdue && i == 0) 6.dp else 5.dp)
                            .clip(if (hasOverdue && i == 0) RoundedCornerShape(1.dp) else CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    noteTitle: String?,
    overdue: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    PaperSheet(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = 2.dp,
        onClick = onOpen,
    ) {
        Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.done, onCheckedChange = onToggle, enabled = !task.locked)
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.locked) {
                        Icon(Icons.Rounded.Lock, contentDescription = "Locked", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (overdue) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = Paper.colors.overdue, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Overdue", style = MaterialTheme.typography.labelMedium, color = Paper.colors.overdue, fontWeight = FontWeight.SemiBold)
                        Text(" · ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val due = task.dueAt
                    Text(
                        (if (due != null && task.hasTime) Instant.ofEpochMilli(due).atZone(zone).format(timeFormat) else "All day") +
                            (noteTitle?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (task.repeat != Repeat.NONE.name) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.Repeat, contentDescription = "Repeats", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onCalendar) {
                Icon(Icons.Rounded.CalendarToday, contentDescription = "Add to phone calendar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
