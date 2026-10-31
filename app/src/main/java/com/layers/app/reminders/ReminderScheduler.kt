package com.layers.app.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.layers.app.R
import com.layers.app.data.db.TaskEntity
import com.layers.app.data.model.Repeat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedules task reminders with AlarmManager. Uses exact alarms when the user allows them
 * (canScheduleExactAlarms) and falls back to inexact, battery-friendly alarms otherwise.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Opens the system screen where the user can allow exact alarms for Layers. */
    fun exactAlarmSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    fun schedule(task: TaskEntity) {
        val at = task.reminderAt
        if (at == null || task.done || at <= System.currentTimeMillis()) {
            cancel(task.blockId)
            return
        }
        val pi = pendingIntent(task.blockId)
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(blockId: String) {
        alarmManager.cancel(pendingIntent(blockId))
    }

    private fun pendingIntent(blockId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("layers://task/$blockId"))
            .putExtra(ReminderReceiver.EXTRA_BLOCK_ID, blockId)
        return PendingIntent.getBroadcast(
            context,
            blockId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "reminders"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_reminders),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = context.getString(R.string.channel_reminders_desc) }
                nm.createNotificationChannel(channel)
            }
        }
    }
}

object Recurrence {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun next(dueAt: Long, repeat: Repeat): Long {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(dueAt), zone)
        val next = when (repeat) {
            Repeat.NONE -> zdt
            Repeat.DAILY -> zdt.plusDays(1)
            Repeat.WEEKLY -> zdt.plusWeeks(1)
            Repeat.MONTHLY -> zdt.plusMonths(1)
        }
        return next.toInstant().toEpochMilli()
    }

    /** The first occurrence after both the current due date and "now" (skips missed occurrences). */
    fun nextFuture(dueAt: Long, repeat: Repeat, now: Long = System.currentTimeMillis()): Long {
        if (repeat == Repeat.NONE) return dueAt
        var t = next(dueAt, repeat)
        var guard = 0
        while (t <= now && guard < 5000) {
            t = next(t, repeat)
            guard++
        }
        return t
    }
}
