package com.layers.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.layers.app.navigation.IncomingIntent
import com.layers.app.navigation.PendingAction
import com.layers.app.ui.LayersApp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single activity. It is a FragmentActivity because BiometricPrompt needs one.
 * Intents from shortcuts, tiles, the widget, notifications, "Share" and "Open with" are turned into
 * [PendingAction]s that the Compose UI handles.
 */
class MainActivity : FragmentActivity() {

    private val pending = MutableStateFlow<PendingAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pending.value = IncomingIntent.parse(intent)
        setContent {
            LayersApp(pending = pending, onConsumed = { pending.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingIntent.parse(intent)?.let { pending.value = it }
    }
}
