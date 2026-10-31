package com.layers.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layers.app.AppContainer
import com.layers.app.backup.BackupManager
import com.layers.app.backup.WrongPasswordException
import com.layers.app.data.repo.AppSettings
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.layersViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BackupViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun export(target: Uri, password: String) = viewModelScope.launch {
        _busy.value = "Encrypting your backup…"
        try {
            c.backup.exportTo(target, password.toCharArray())
            c.settings.setLastBackup(System.currentTimeMillis(), null)
            _messages.tryEmit("Backup saved")
        } catch (e: Exception) {
            _messages.tryEmit("Backup failed: ${e.message ?: "unknown error"}")
        } finally {
            _busy.value = null
        }
    }

    fun restore(source: Uri, password: String) = viewModelScope.launch {
        _busy.value = "Restoring…"
        try {
            val summary = c.backup.restoreFrom(source, password.toCharArray())
            c.lockSession.relock()
            c.updateWidgets()
            _messages.tryEmit("Restored ${summary.notes} notes, ${summary.folders} folders and ${summary.attachments} attachments")
        } catch (e: WrongPasswordException) {
            _messages.tryEmit(e.message ?: "Wrong password")
        } catch (e: Exception) {
            _messages.tryEmit("Restore failed: ${e.message ?: "unknown error"}")
        } finally {
            _busy.value = null
        }
    }

    fun setTree(uri: Uri) = viewModelScope.launch {
        c.settings.setAutoBackupTree(uri.toString())
    }

    fun setAutoPassword(password: String) = viewModelScope.launch {
        c.backup.saveAutoBackupPassword(password)
        _messages.tryEmit("Backup password saved")
    }

    fun setAuto(on: Boolean) = viewModelScope.launch {
        c.settings.setAutoBackup(on)
        c.work.setAutoBackup(on)
        if (on) _messages.tryEmit("Weekly backups are on")
    }

    fun backupNow() {
        c.work.runAutoBackupNow()
        _messages.tryEmit("Backing up in the background…")
    }
}

private sealed interface BackupDialog {
    data object ExportPassword : BackupDialog
    data class RestoreWarning(val uri: Uri) : BackupDialog
    data class RestorePassword(val uri: Uri) : BackupDialog
    data object AutoPassword : BackupDialog
}

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val vm = layersViewModel { BackupViewModel(it) }
    val settings by vm.settings.collectAsState(initial = AppSettings())
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    var pendingPassword by remember { mutableStateOf<String?>(null) }
    val container = com.layers.app.ui.components.appContainer()

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = pendingPassword
        pendingPassword = null
        if (uri != null && pw != null) vm.export(uri, pw)
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) dialog = BackupDialog.RestoreWarning(uri)
    }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.setTree(uri)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Backup & restore", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
        ) {
            if (busy != null) {
                item {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Text(busy ?: "", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                PaperSheet(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = 2.dp) {
                    Column {
                        InfoRow(
                            Icons.Rounded.Security,
                            "A backup holds everything — folders, notes, every layer, attachments, locked notes and settings — " +
                                "in one file encrypted with a password you choose (AES-256, PBKDF2). Locked notes are re-encrypted with " +
                                "that password so you can restore them on a new phone. Keep the password safe: without it the backup can't be opened.",
                        )
                        ActionRow(Icons.Rounded.CloudUpload, "Back up now", "Save an encrypted file to Drive, Downloads or anywhere") {
                            dialog = BackupDialog.ExportPassword
                        }
                        ActionRow(Icons.Rounded.Restore, "Restore from a backup", "Replaces everything currently in Layers") {
                            container.lockSession.expectReturn()
                            openDoc.launch(arrayOf("*/*"))
                        }
                    }
                }
            }
            item {
                Text(
                    "Automatic backup",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            item {
                PaperSheet(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = 2.dp) {
                    Column {
                        ActionRow(
                            Icons.Rounded.FolderOpen,
                            "Backup folder",
                            settings.autoBackupTreeUri?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "Chosen" }
                                ?: "Choose a folder (for example in Google Drive)",
                        ) {
                            container.lockSession.expectReturn()
                            openTree.launch(null)
                        }
                        ActionRow(
                            Icons.Rounded.VpnKey,
                            "Backup password",
                            if (settings.hasBackupPassword) "Saved (stored encrypted with a hardware key)" else "Set the password automatic backups use",
                        ) { dialog = BackupDialog.AutoPassword }
                        val ready = settings.autoBackupTreeUri != null && settings.hasBackupPassword
                        SwitchRow(
                            icon = Icons.Rounded.Schedule,
                            title = "Weekly automatic backup",
                            subtitle = if (ready) "Keeps the 5 newest backups in that folder" else "Choose a folder and password first",
                            checked = settings.autoBackup,
                            enabled = ready,
                            onChange = { vm.setAuto(it) },
                        )
                        if (ready) {
                            ActionRow(Icons.Rounded.CloudUpload, "Run automatic backup now", lastBackupText(settings)) { vm.backupNow() }
                        }
                        settings.lastBackupError?.let {
                            InfoRow(Icons.Rounded.Warning, "Last automatic backup failed: $it")
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        BackupDialog.ExportPassword -> PasswordDialog(
            title = "Backup password",
            confirmTwice = true,
            onDismiss = { dialog = null },
            onConfirm = { pw ->
                dialog = null
                pendingPassword = pw
                container.lockSession.expectReturn()
                createDoc.launch(BackupManager.suggestedFileName())
            },
        )
        is BackupDialog.RestoreWarning -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Replace everything?") },
            text = {
                Text(
                    "Restoring deletes all folders, notes, layers and attachments currently in Layers on this phone " +
                        "and replaces them with the backup. This can't be undone. Consider backing up first."
                )
            },
            confirmButton = {
                TextButton(onClick = { dialog = BackupDialog.RestorePassword(d.uri) }) {
                    Text("Continue", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is BackupDialog.RestorePassword -> PasswordDialog(
            title = "Backup password",
            confirmTwice = false,
            confirmText = "Restore",
            onDismiss = { dialog = null },
            onConfirm = { pw ->
                dialog = null
                vm.restore(d.uri, pw)
            },
        )
        BackupDialog.AutoPassword -> PasswordDialog(
            title = "Password for automatic backups",
            confirmTwice = true,
            onDismiss = { dialog = null },
            onConfirm = { pw ->
                dialog = null
                vm.setAutoPassword(pw)
            },
        )
    }
}

private fun lastBackupText(s: AppSettings): String =
    if (s.lastBackupAt == 0L) "Not backed up yet"
    else "Last backup " + DateUtils.getRelativeTimeSpanString(s.lastBackupAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

@Composable
private fun PasswordDialog(
    title: String,
    confirmTwice: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    confirmText: String = "Continue",
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val tooShort = confirmTwice && pw.length < 8
    val mismatch = confirmTwice && pw2.isNotEmpty() && pw != pw2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { if (confirmTwice) Text("At least 8 characters") },
                    isError = confirmTwice && pw.isNotEmpty() && tooShort,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        value = pw2,
                        onValueChange = { pw2 = it },
                        label = { Text("Repeat password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = { if (mismatch) Text("Passwords don't match") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pw) },
                enabled = pw.isNotEmpty() && !tooShort && (!confirmTwice || pw == pw2),
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
