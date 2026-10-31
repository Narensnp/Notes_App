package com.layers.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.layers.app.backup.BackupManager
import com.layers.app.data.db.LayersDatabase
import com.layers.app.data.files.AttachmentStore
import com.layers.app.data.files.ScreenshotFinder
import com.layers.app.data.repo.BlockCodec
import com.layers.app.data.repo.FolderRepository
import com.layers.app.data.repo.LockManager
import com.layers.app.data.repo.NoteRepository
import com.layers.app.data.repo.SearchIndexer
import com.layers.app.data.repo.SearchRepository
import com.layers.app.data.repo.SettingsRepository
import com.layers.app.data.repo.TagRepository
import com.layers.app.data.repo.TaskRepository
import com.layers.app.data.repo.TrashRepository
import com.layers.app.navigation.NavRequests
import com.layers.app.reminders.ReminderScheduler
import com.layers.app.security.KeystoreCrypto
import com.layers.app.security.LockSession
import com.layers.app.share.NoteExporter
import com.layers.app.widget.TasksWidget
import com.layers.app.work.WorkScheduler

/** Manual dependency injection: every long-lived object is created once, here. */
class AppContainer(private val context: Context) {
    val appContext: Context get() = context

    val db: LayersDatabase = LayersDatabase.build(context)
    val crypto = KeystoreCrypto()
    val attachments = AttachmentStore(context, crypto)
    val codec = BlockCodec(crypto)
    val settings = SettingsRepository(context)
    val scheduler = ReminderScheduler(context)
    val work = WorkScheduler(context)
    val lockSession = LockSession(attachments)
    val indexer = SearchIndexer(db, codec)
    val tasks = TaskRepository(db, codec, scheduler) { updateWidgets() }
    val trash = TrashRepository(db, attachments, indexer, tasks)
    val lockManager = LockManager(db, codec, attachments, indexer, tasks, work)
    val notes = NoteRepository(db, codec, attachments, tasks, indexer, trash, lockManager, work)
    val folders = FolderRepository(db, trash, lockManager)
    val tags = TagRepository(db)
    val search = SearchRepository(db, notes, folders)
    val backup = BackupManager(context, db, codec, attachments, settings, indexer, tasks, lockManager, scheduler, crypto)
    val screenshots = ScreenshotFinder(context)
    val exporter = NoteExporter(context, notes, attachments)
    val navRequests = NavRequests()

    /** Outlives screens: used to finish saving when an editor closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun updateWidgets() {
        runCatching { TasksWidget().updateAll(context) }
    }
}
