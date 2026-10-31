package com.layers.app.navigation

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Something another app, a shortcut, a tile, the widget or a notification asked Layers to do. */
sealed interface PendingAction {
    data object NewNote : PendingAction
    data object NewTask : PendingAction
    data object Search : PendingAction
    data object ScreenshotNote : PendingAction
    data class OpenNote(val noteId: String, val blockId: String?) : PendingAction
    data class Share(val text: String?, val subject: String?, val uris: List<Uri>, val mime: String?) : PendingAction
    data class ViewPdf(val uri: Uri) : PendingAction
}

object IncomingIntent {

    fun parse(intent: Intent?): PendingAction? {
        if (intent == null) return null
        return when (intent.action) {
            IntentActions.NEW_NOTE -> PendingAction.NewNote
            IntentActions.NEW_TASK -> PendingAction.NewTask
            IntentActions.SEARCH -> PendingAction.Search
            IntentActions.SCREENSHOT_NOTE -> PendingAction.ScreenshotNote
            IntentActions.OPEN_NOTE -> intent.getStringExtra(IntentActions.EXTRA_NOTE_ID)?.let {
                PendingAction.OpenNote(it, intent.getStringExtra(IntentActions.EXTRA_BLOCK_ID))
            }
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val uris = buildList {
                    if (stream != null) add(stream)
                    else intent.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) }
                    }
                }
                if (text.isNullOrBlank() && uris.isEmpty()) null
                else PendingAction.Share(text, subject, uris, intent.type)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val uris = list?.filterNotNull() ?: buildList {
                    intent.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) }
                    }
                }
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (uris.isEmpty() && text.isNullOrBlank()) null
                else PendingAction.Share(text, intent.getStringExtra(Intent.EXTRA_SUBJECT), uris, intent.type)
            }
            Intent.ACTION_VIEW -> intent.data?.let { PendingAction.ViewPdf(it) }
            else -> null
        }
    }
}
