package com.layers.app.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.layers.app.LayersApplication

/** Weekly encrypted backup into the folder the user chose (Google Drive, Downloads…). */
class AutoBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LayersApplication).container
        val settings = container.settings.current()
        if (settings.autoBackupTreeUri == null || !settings.hasBackupPassword) return Result.success()
        return try {
            container.backup.runAutoBackup()
            Result.success()
        } catch (e: Exception) {
            container.settings.setBackupError(e.message ?: "Backup failed")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
