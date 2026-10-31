package com.layers.app.ui.lock

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.layers.app.security.BiometricAuth
import com.layers.app.security.LockSession
import com.layers.app.security.findFragmentActivity
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.appContainer

/** Wraps BiometricPrompt: fingerprint, face or the phone's PIN/pattern/password. */
class Authenticator(
    private val activity: FragmentActivity?,
    private val session: LockSession,
    private val onError: (String) -> Unit,
) {
    fun unlock(title: String = "Unlock locked notes", onSuccess: () -> Unit = {}) {
        val a = activity ?: return
        BiometricAuth.authenticate(
            activity = a,
            title = title,
            subtitle = "Use your fingerprint, face or screen lock",
            onSuccess = {
                session.unlockNotes()
                onSuccess()
            },
            onFailure = { if (it.isNotBlank()) onError(it) },
        )
    }

    /** Runs [action] right away if locked notes are already unlocked, otherwise asks first. */
    fun requireUnlocked(title: String = "Unlock locked notes", action: () -> Unit) {
        if (session.notesUnlocked.value) action() else unlock(title, action)
    }

    /** Always asks (used for turning locks off and for app-lock settings). */
    fun confirm(title: String, action: () -> Unit) = unlock(title, action)
}

@Composable
fun rememberAuthenticator(): Authenticator {
    val context = LocalContext.current
    val container = appContainer()
    return remember(context) {
        Authenticator(context.findFragmentActivity(), container.lockSession) { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

/** Full-screen gate shown while the whole app is locked (optional app lock in Settings). */
@Composable
fun AppLockGate() {
    val context = LocalContext.current
    val container = appContainer()
    val activity = context.findFragmentActivity()
    val unlock = {
        if (activity != null) {
            BiometricAuth.authenticate(
                activity = activity,
                title = "Unlock Layers",
                subtitle = "Use your fingerprint, face or screen lock",
                onSuccess = { container.lockSession.unlockNotes() },
                onFailure = { if (it.isNotBlank()) Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
            )
        }
    }
    val currentUnlock by rememberUpdatedState(unlock)
    LaunchedEffect(Unit) { currentUnlock() }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        LockedMessage(
            title = "Layers is locked",
            message = "Your notes stay private until you unlock.",
            onUnlock = unlock,
        )
    }
}

@Composable
fun LockedMessage(title: String, message: String, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PaperSheet(modifier = Modifier.size(104.dp), shape = RoundedCornerShape(28.dp), stackDepth = 2, elevation = 6.dp) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Unlock")
        }
    }
}
