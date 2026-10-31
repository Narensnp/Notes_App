package com.layers.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.layers.app.backup.AutoBackupWorker
import java.util.concurrent.TimeUnit

/** One place that knows about every background job. */
class WorkScheduler(private val context: Context) {

    private val wm: WorkManager get() = WorkManager.getInstance(context)

    /** OCR for an image block, or text extraction for a PDF block (Android 15+). */
    fun enqueueExtraction(noteId: String, blockId: String) {
        val request = OneTimeWorkRequestBuilder<TextExtractionWorker>()
            .setInputData(
                workDataOf(
                    TextExtractionWorker.KEY_NOTE_ID to noteId,
                    TextExtractionWorker.KEY_BLOCK_ID to blockId,
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork("extract-$blockId", ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleTrashPurge() {
        val request = PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork("trash-purge", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun setAutoBackup(enabled: Boolean) {
        if (!enabled) {
            wm.cancelUniqueWork(AUTO_BACKUP)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(AUTO_BACKUP, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runAutoBackupNow() {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>().build()
        wm.enqueueUniqueWork("$AUTO_BACKUP-now", ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val AUTO_BACKUP = "auto-backup"
    }
}
