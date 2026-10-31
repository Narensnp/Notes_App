package com.layers.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.layers.app.data.repo.AppSettings
import com.layers.app.data.repo.ThemeMode
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.appContainer
import com.layers.app.ui.lock.rememberAuthenticator
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onOpenTrash: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val c = appContainer()
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = rememberAuthenticator()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val notificationsAllowed = remember(refresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val exactAllowed = remember(refresh) { c.scheduler.canScheduleExact() }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
        ) {
            item { SectionTitle("Appearance") }
            item {
                Card {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                    SingleChoiceSegmentedButtonRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    ) {
                        val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                        options.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { scope.launch { c.settings.setThemeMode(mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            ) { Text(label) }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
                            icon = Icons.Rounded.ColorLens,
                            title = "Material You colors",
                            subtitle = "Use colors from your wallpaper instead of paper & ink",
                            checked = settings.dynamicColor,
                            onChange = { on -> scope.launch { c.settings.setDynamicColor(on) } },
                        )
                    }
                }
            }

            item { SectionTitle("Privacy & security") }
            item {
                Card {
                    SwitchRow(
                        icon = Icons.Rounded.Lock,
                        title = "App lock",
                        subtitle = "Ask for your fingerprint, face or screen lock every time Layers opens",
                        checked = settings.appLock,
                        onChange = { on ->
                            auth.confirm(if (on) "Turn on app lock" else "Turn off app lock") {
                                scope.launch { c.settings.setAppLock(on) }
                            }
                        },
                    )
                    InfoRow(
                        icon = Icons.Rounded.Security,
                        text = "Locked notes are encrypted with AES-256 using a key kept in Android's secure hardware. " +
                            "They re-lock as soon as you leave the app, never appear in search or widgets, and can't be screenshotted.",
                    )
                }
            }

            item { SectionTitle("Reminders") }
            item {
                Card {
                    ActionRow(
                        icon = Icons.Rounded.Notifications,
                        title = "Notifications",
                        subtitle = if (notificationsAllowed) "Allowed" else "Not allowed — reminders can't appear",
                        onClick = {
                            if (!notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                c.lockSession.expectReturn()
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            }
                        },
                    )
                    ActionRow(
                        icon = Icons.Rounded.Alarm,
                        title = "On-time reminders",
                        subtitle = if (exactAllowed) "Exact alarms allowed" else "Off — reminders may arrive a few minutes late. Tap to allow.",
                        onClick = {
                            c.lockSession.expectReturn()
                            runCatching { context.startActivity(c.scheduler.exactAlarmSettingsIntent()) }
                        },
                    )
                }
            }

            item { SectionTitle("Organize") }
            item {
                Card {
                    ActionRow(Icons.Rounded.LocalOffer, "Tags", "Browse and rename tags", onOpenTags)
                    ActionRow(Icons.Rounded.Delete, "Trash", "Deleted items stay here for 30 days", onOpenTrash)
                    ActionRow(Icons.Rounded.Backup, "Backup & restore", backupSubtitle(settings), onOpenBackup)
                }
            }

            item { SectionTitle("About") }
            item {
                Card {
                    InfoRow(
                        icon = Icons.Rounded.Info,
                        text = "Layers ${versionName(context)} · No analytics, no accounts, no network access. " +
                            "Your notes only leave this phone through backups and shares you start yourself.",
                    )
                    ActionRow(
                        icon = Icons.Rounded.Info,
                        title = "App info & permissions",
                        subtitle = "Open Android's settings for Layers",
                        onClick = {
                            c.lockSession.expectReturn()
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun backupSubtitle(s: AppSettings): String = when {
    s.lastBackupError != null -> "Last automatic backup failed"
    s.autoBackup -> "Weekly automatic backup is on"
    else -> "Export everything into one encrypted file"
}

private fun versionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    PaperSheet(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = 2.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
    }
}

@Composable
fun SwitchRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun InfoRow(icon: ImageVector, text: String) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
