package com.layers.app.data.repo

import com.layers.app.data.db.LayersDatabase
import com.layers.app.data.db.NoteEntity
import com.layers.app.data.db.TaskEntity
import com.layers.app.data.model.Block
import com.layers.app.data.model.BlockContent
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.Repeat
import com.layers.app.data.model.isLocked
import com.layers.app.reminders.Recurrence
import com.layers.app.reminders.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** A change made to a block from outside the editor (widget, notification, calendar). */
data class ExternalBlockChange(val noteId: String, val blockId: String, val content: BlockContent)

object ChecklistLogic {
    /**
     * Checking a repeating task moves it to its next occurrence (and leaves it unchecked) instead of
     * completing it for good.
     */
    fun toggle(content: ChecklistContent, done: Boolean): ChecklistContent =
        if (done && content.repeat != Repeat.NONE && content.dueAt != null) {
            content.copy(done = false, dueAt = Recurrence.nextFuture(content.dueAt, content.repeat))
        } else {
            content.copy(done = done)
        }
}

const val ALL_DAY_REMINDER_HOUR = 9

class TaskRepository(
    private val db: LayersDatabase,
    private val codec: BlockCodec,
    private val scheduler: ReminderScheduler,
    private val onTasksChanged: suspend () -> Unit,
) {
    private val _external = MutableSharedFlow<ExternalBlockChange>(extraBufferCapacity = 32)
    val externalChanges: SharedFlow<ExternalBlockChange> = _external.asSharedFlow()

    fun observeBetween(start: Long, end: Long): Flow<List<TaskEntity>> = db.tasks().observeBetween(start, end)

    private fun todayRange(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    /** Today's tasks plus overdue open tasks, excluding locked notes. */
    suspend fun widgetTasks(): List<TaskEntity> = withContext(Dispatchers.IO) {
        val (start, end) = todayRange()
        db.tasks().forWidget(start, end)
    }

    fun observeWidgetTasks(): Flow<List<TaskEntity>> {
        val (start, end) = todayRange()
        return db.tasks().observeForWidget(start, end)
    }

    /** Rebuilds the task rows of one note from its checklist blocks and (re)schedules reminders. */
    suspend fun syncNote(note: NoteEntity, blocks: List<Block>) = withContext(Dispatchers.IO) {
        val existing = db.tasks().forNote(note.id).associateBy { it.blockId }
        val now = System.currentTimeMillis()
        val keep = HashSet<String>()
        val upserts = ArrayList<TaskEntity>()
        val locked = note.isLocked
        for (b in blocks) {
            val c = b.content as? ChecklistContent ?: continue
            val due = c.dueAt ?: continue
            keep += b.id
            // All-day tasks remind at 9:00 on the day (minus the chosen offset).
            val base = if (c.hasTime) due else due + ALL_DAY_REMINDER_HOUR * 3_600_000L
            var reminderAt = c.reminderOffsetMin?.let { base - it * 60_000L }
            val old = existing[b.id]
            // Keep a snoozed reminder (later than the planned one) as long as the due date is unchanged.
            if (old != null && !c.done && old.dueAt == due && reminderAt != null) {
                val oldReminder = old.reminderAt
                if (oldReminder != null && oldReminder > reminderAt) reminderAt = oldReminder
            }
            val task = TaskEntity(
                blockId = b.id,
                noteId = note.id,
                title = if (locked) "Locked task" else c.text.ifBlank { "Untitled task" },
                done = c.done,
                dueAt = due,
                hasTime = c.hasTime,
                reminderAt = reminderAt,
                repeat = c.repeat.name,
                locked = locked,
                updatedAt = now,
            )
            if (old == null || old.copy(updatedAt = 0) != task.copy(updatedAt = 0)) upserts += task
        }
        val removed = existing.keys - keep
        if (removed.isNotEmpty()) {
            db.tasks().deleteForBlocks(removed.toList())
            removed.forEach { scheduler.cancel(it) }
        }
        if (upserts.isNotEmpty()) {
            db.tasks().upsertAll(upserts)
            upserts.forEach { scheduler.schedule(it) }
        }
        if (removed.isNotEmpty() || upserts.isNotEmpty()) onTasksChanged()
    }

    /** Re-reads a note's blocks from the database (decrypting if needed) and re-syncs its tasks. */
    suspend fun resyncFromStorage(noteId: String) = withContext(Dispatchers.IO) {
        val note = db.notes().get(noteId) ?: return@withContext
        val blocks = db.blocks().forNote(noteId).mapNotNull { runCatching { codec.fromEntity(it) }.getOrNull() }
        syncNote(note, blocks)
    }

    /** Marks a task done (or not) from the widget, a notification or the calendar. */
    suspend fun setDone(blockId: String, done: Boolean) = withContext(Dispatchers.IO) {
        val entity = db.blocks().get(blockId) ?: return@withContext
        val note = db.notes().get(entity.noteId) ?: return@withContext
        val content = runCatching { codec.contentOf(entity) }.getOrNull() as? ChecklistContent ?: return@withContext
        val updated = ChecklistLogic.toggle(content, done)
        writeBlock(note, entity.position, blockId, updated)
    }

    /** Pushes the reminder one hour (or [minutes]) later. */
    suspend fun snooze(blockId: String, minutes: Int = 60) = withContext(Dispatchers.IO) {
        val task = db.tasks().get(blockId) ?: return@withContext
        val updated = task.copy(reminderAt = System.currentTimeMillis() + minutes * 60_000L, updatedAt = System.currentTimeMillis())
        db.tasks().upsert(updated)
        scheduler.schedule(updated)
    }

    suspend fun rescheduleAll() = withContext(Dispatchers.IO) {
        db.tasks().upcomingReminders(System.currentTimeMillis()).forEach { scheduler.schedule(it) }
    }

    suspend fun cancelForNotes(noteIds: List<String>) = withContext(Dispatchers.IO) {
        db.tasks().getAll().filter { it.noteId in noteIds }.forEach { scheduler.cancel(it.blockId) }
        onTasksChanged()
    }

    suspend fun rescheduleForNotes(noteIds: List<String>) = withContext(Dispatchers.IO) {
        db.tasks().getAll().filter { it.noteId in noteIds }.forEach { scheduler.schedule(it) }
        onTasksChanged()
    }

    suspend fun get(blockId: String): TaskEntity? = withContext(Dispatchers.IO) { db.tasks().get(blockId) }

    private suspend fun writeBlock(note: NoteEntity, position: Int, blockId: String, content: ChecklistContent) {
        val now = System.currentTimeMillis()
        db.blocks().upsert(codec.toEntity(note.id, position, Block(blockId, content), note.encrypted, now))
        val blocks = db.blocks().forNote(note.id).mapNotNull { runCatching { codec.fromEntity(it) }.getOrNull() }
        val refreshed = note.copy(
            updatedAt = now,
            preview = if (note.encrypted) "" else TextUtil.preview(blocks),
        )
        db.notes().upsert(refreshed)
        syncNote(refreshed, blocks)
        _external.tryEmit(ExternalBlockChange(note.id, blockId, content))
    }
}
