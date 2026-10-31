package com.layers.app.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.layers.app.LayersApplication
import com.layers.app.MainActivity
import com.layers.app.R
import com.layers.app.navigation.IntentActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** Fires when a task's reminder time arrives and shows the notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID) ?: return
        runAsync {
            val container = (context.applicationContext as LayersApplication).container
            val task = container.db.tasks().get(blockId) ?: return@runAsync
            val note = container.db.notes().get(task.noteId) ?: return@runAsync
            if (task.done || note.deletedAt != null) return@runAsync
            ReminderNotifications.show(context, task.blockId, task.noteId, task.title, task.locked, task.dueAt)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.layers.app.reminders.FIRE"
        const val EXTRA_BLOCK_ID = "blockId"
    }
}

/** Handles the "Mark done" and "Snooze 1 hour" notification buttons. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val blockId = intent.getStringExtra(ReminderReceiver.EXTRA_BLOCK_ID) ?: return
        NotificationManagerCompat.from(context).cancel(blockId.hashCode())
        runAsync {
            val container = (context.applicationContext as LayersApplication).container
            when (intent.action) {
                ACTION_DONE -> container.tasks.setDone(blockId, true)
                ACTION_SNOOZE -> container.tasks.snooze(blockId, 60)
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.layers.app.reminders.DONE"
        const val ACTION_SNOOZE = "com.layers.app.reminders.SNOOZE"
    }
}

/** Puts all reminders back after a reboot, an app update or a change of the exact-alarm permission. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> runAsync {
                val container = (context.applicationContext as LayersApplication).container
                container.tasks.rescheduleAll()
                container.updateWidgets()
            }
        }
    }
}

object ReminderNotifications {

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun show(context: Context, blockId: String, noteId: String, title: String, locked: Boolean, dueAt: Long?) {
        if (!canPost(context)) return
        ReminderScheduler.ensureChannel(context)
        val id = blockId.hashCode()

        val open = Intent(context, MainActivity::class.java)
            .setAction(IntentActions.OPEN_NOTE)
            .setData(Uri.parse("layers://note/$noteId/$blockId"))
            .putExtra(IntentActions.EXTRA_NOTE_ID, noteId)
            .putExtra(IntentActions.EXTRA_BLOCK_ID, blockId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPi = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun action(action: String, requestOffset: Int): PendingIntent {
            val i = Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("layers://task/$blockId/$action"))
                .putExtra(ReminderReceiver.EXTRA_BLOCK_ID, blockId)
            return PendingIntent.getBroadcast(
                context, id + requestOffset, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val text = if (locked) "A task in a locked note is due. Open Layers to see it." else title
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (locked) "Task reminder" else "Task due")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(if (locked) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .apply { if (dueAt != null) setWhen(dueAt).setShowWhen(true) }
            .addAction(0, "Mark done", action(ReminderActionReceiver.ACTION_DONE, 1))
            .addAction(0, "Snooze 1 hour", action(ReminderActionReceiver.ACTION_SNOOZE, 2))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Notification permission was revoked in the meantime.
        }
    }
}
