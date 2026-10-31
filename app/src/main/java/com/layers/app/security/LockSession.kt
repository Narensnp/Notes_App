package com.layers.app.security

import android.os.SystemClock
import com.layers.app.data.files.AttachmentStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers whether the user has unlocked locked notes (and the app, if app lock is on) during this
 * visit. Everything re-locks as soon as the app goes to the background.
 *
 * Picking a photo or file briefly sends the app to the background too; [expectReturn] grants a short
 * grace period so the user is not asked to unlock again just because they attached something.
 */
class LockSession(private val attachments: AttachmentStore) {

    private val _notesUnlocked = MutableStateFlow(false)
    val notesUnlocked: StateFlow<Boolean> = _notesUnlocked.asStateFlow()

    private val _appUnlocked = MutableStateFlow(false)
    val appUnlocked: StateFlow<Boolean> = _appUnlocked.asStateFlow()

    @Volatile
    private var graceUntil = 0L

    fun unlockNotes() {
        _notesUnlocked.value = true
        _appUnlocked.value = true
    }

    fun unlockApp() {
        _appUnlocked.value = true
    }

    /** Call right before launching a picker, camera, share sheet or settings screen. */
    fun expectReturn() {
        graceUntil = SystemClock.elapsedRealtime() + GRACE_MS
    }

    /** Called when the whole app moves to the background. */
    fun onAppBackgrounded() {
        if (SystemClock.elapsedRealtime() < graceUntil) {
            // Excuse exactly one trip to a picker or share sheet, then lock normally again.
            graceUntil = 0L
            return
        }
        relock()
    }

    fun relock() {
        _notesUnlocked.value = false
        _appUnlocked.value = false
        attachments.clearDecryptedCache()
    }

    private companion object {
        const val GRACE_MS = 3 * 60 * 1000L
    }
}
