package com.layers.app.ui.editor

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.layers.app.data.files.ScreenshotFinder
import com.layers.app.data.model.AudioContent
import com.layers.app.data.model.Block
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.Crumb
import com.layers.app.data.model.CrumbKind
import com.layers.app.data.model.DrawingContent
import com.layers.app.data.model.FileContent
import com.layers.app.data.model.ImageContent
import com.layers.app.data.model.PdfContent
import com.layers.app.data.model.TextContent
import com.layers.app.data.model.supportsLayers
import com.layers.app.data.repo.NoteRepository
import com.layers.app.drawing.InkRenderer
import com.layers.app.pdf.PdfSource
import com.layers.app.security.SecureContent
import com.layers.app.ui.components.Breadcrumbs
import com.layers.app.ui.components.ColorPicker
import com.layers.app.ui.components.ConfirmDialog
import com.layers.app.ui.components.EmptyState
import com.layers.app.ui.components.MoveToSheet
import com.layers.app.ui.components.appContainer
import com.layers.app.ui.components.layerSharedBounds
import com.layers.app.ui.components.layersViewModel
import com.layers.app.ui.components.rememberHaptics
import com.layers.app.ui.lock.LockedMessage
import com.layers.app.ui.lock.rememberAuthenticator
import com.layers.app.ui.theme.Paper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class BlockMenu(val blockId: String, val x: Float = 0.5f, val y: Float = 0.5f)

private sealed interface EditorDialog {
    data class DeleteBlock(val blockId: String, val layers: Int) : EditorDialog
    data class DeleteNote(val layers: Int) : EditorDialog
    data class Task(val blockId: String) : EditorDialog
    data class OcrResult(val text: String) : EditorDialog
    data object Record : EditorDialog
    data class ReRecord(val blockId: String) : EditorDialog
    data object Color : EditorDialog
    data object Tags : EditorDialog
    data object Move : EditorDialog
    data object Insert : EditorDialog
    data class Rationale(val title: String, val message: String, val onContinue: () -> Unit) : EditorDialog
    data object ExactAlarm : EditorDialog
}

@Composable
fun EditorScreen(
    noteId: String,
    scrollToBlock: String?,
    focus: String?,
    onBack: () -> Unit,
    onOpenNote: (noteId: String, blockId: String?) -> Unit,
    onOpenParent: (parentNoteId: String, blockId: String?) -> Unit,
    onOpenDrawing: (noteId: String, blockId: String) -> Unit,
    onOpenPdf: (noteId: String, blockId: String) -> Unit,
    onOpenOutline: (noteId: String) -> Unit,
    onOpenFolder: (String?) -> Unit,
) {
    val c = appContainer()
    val vm = layersViewModel(key = "editor-$noteId") { EditorViewModel(it, noteId) }
    val ui by vm.ui.collectAsState()
    val title by vm.title.collectAsState()
    val focusRequest by vm.focusRequest.collectAsState()
    val focusedBlock by vm.focusedBlock.collectAsState()
    val tree by remember { c.folders.observeTree() }.collectAsState(initial = emptyList())
    val allTags by remember { c.tags.observeAll() }.collectAsState(initial = emptyList())

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val auth = rememberAuthenticator()
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val titleFocus = remember { FocusRequester() }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requesterFor(id: String) = requesters.getOrPut(id) { FocusRequester() }

    var menu by remember { mutableStateOf<BlockMenu?>(null) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var topMenu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var highlightBlock by remember { mutableStateOf<String?>(null) }
    var cameraTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var replaceTarget by rememberSaveable { mutableStateOf<String?>(null) }

    SecureContent(enabled = ui.locked)

    // Save when leaving the app; reload drawings/PDFs edited on their own screens.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.flush() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ui.blocks.filter { it.content is DrawingContent || it.content is PdfContent }.forEach { vm.reloadBlock(it.id) }
    }

    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    // Opening a locked note asks for the fingerprint right away.
    var promptedUnlock by remember { mutableStateOf(false) }
    LaunchedEffect(ui.needsUnlock) {
        if (ui.needsUnlock && !promptedUnlock) {
            promptedUnlock = true
            auth.unlock()
        }
    }

    // ---------- Pickers & permissions ----------

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.addImages(uris)
    }
    val replacePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = replaceTarget
        if (uri != null && target != null) vm.replaceImage(target, uri)
        replaceTarget = null
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val rel = cameraTarget
        if (ok && rel != null) vm.addCameraPhoto(rel) else rel?.let { c.attachments.delete(it) }
        cameraTarget = null
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addDocument(uri, replaceTarget)
        replaceTarget = null
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val target = replaceTarget
            dialog = if (target != null) EditorDialog.ReRecord(target) else EditorDialog.Record
        } else {
            replaceTarget = null
            scope.launch { snackbar.showSnackbar("Microphone access is needed to record voice notes.") }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!c.scheduler.canScheduleExact()) dialog = EditorDialog.ExactAlarm
    }

    fun launchPhotoPicker() {
        c.lockSession.expectReturn()
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val screenshotPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        scope.launch {
            val uri = if (c.screenshots.access() != ScreenshotFinder.Access.NONE) c.screenshots.latestScreenshot() else null
            if (uri != null) vm.addImages(listOf(uri)) else {
                snackbar.showSnackbar("Pick the screenshot instead")
                launchPhotoPicker()
            }
        }
    }

    fun insertLatestScreenshot() {
        if (c.screenshots.access() == ScreenshotFinder.Access.NONE) {
            dialog = EditorDialog.Rationale(
                "Find your latest screenshot?",
                "Layers needs access to your photos to grab the newest screenshot. You can also allow only selected photos — or skip this and pick it yourself.",
            ) {
                c.lockSession.expectReturn()
                screenshotPermission.launch(c.screenshots.permissionsToRequest())
            }
            return
        }
        scope.launch {
            val uri = c.screenshots.latestScreenshot()
            if (uri != null) vm.addImages(listOf(uri)) else {
                snackbar.showSnackbar("No screenshot found — pick one instead")
                launchPhotoPicker()
            }
        }
    }

    fun launchCamera() {
        val (rel, file) = c.attachments.createEmpty()
        cameraTarget = rel
        val uri = FileProvider.getUriForFile(context, c.attachments.authority, file)
        c.lockSession.expectReturn()
        camera.launch(uri)
    }

    fun startRecording(replaceId: String? = null) {
        replaceTarget = replaceId
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            dialog = if (replaceId != null) EditorDialog.ReRecord(replaceId) else EditorDialog.Record
        } else {
            dialog = EditorDialog.Rationale(
                "Record a voice note?",
                "Layers needs the microphone to record audio into this note. Recordings stay on your phone.",
            ) { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }

    fun openDrawing(blockId: String) {
        scope.launch {
            vm.flushSave()
            onOpenDrawing(noteId, blockId)
        }
    }

    fun openPdf(blockId: String) {
        scope.launch {
            vm.flushSave()
            onOpenPdf(noteId, blockId)
        }
    }

    fun openLayer(blockId: String) {
        haptics.confirm()
        scope.launch { onOpenNote(vm.openLayer(blockId), null) }
    }

    fun shareIntent(build: suspend () -> Intent?) {
        scope.launch {
            val intent = runCatching { build() }.getOrNull()
            if (intent == null) {
                snackbar.showSnackbar("Couldn't prepare that for sharing")
                return@launch
            }
            c.lockSession.expectReturn()
            context.startActivity(Intent.createChooser(intent, "Share"))
        }
    }

    fun openExternally(path: String, name: String, mime: String) {
        scope.launch {
            val uri = runCatching { c.attachments.shareUri(path, ui.encrypted, name) }.getOrNull() ?: return@launch
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            c.lockSession.expectReturn()
            runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
                .onFailure { snackbar.showSnackbar("No app can open this file") }
        }
    }

    fun requestDelete(blockId: String) {
        scope.launch {
            val layers = vm.countLayers(blockId)
            if (layers > 0) {
                dialog = EditorDialog.DeleteBlock(blockId, layers)
            } else {
                vm.delete(blockId)
                val result = snackbar.showSnackbar("Block deleted", actionLabel = "Undo", withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed) vm.undo()
            }
        }
    }

    fun onInsert(kind: InsertKind) {
        dialog = null
        when (kind) {
            InsertKind.TEXT -> vm.insert(TextContent(), focus = true)
            InsertKind.CHECKLIST -> vm.addChecklistAfter(focusedBlock)
            InsertKind.PHOTO -> launchPhotoPicker()
            InsertKind.CAMERA -> launchCamera()
            InsertKind.SCREENSHOT -> insertLatestScreenshot()
            InsertKind.DRAWING -> openDrawing(vm.addDrawing())
            InsertKind.VOICE -> startRecording()
            InsertKind.PDF -> {
                replaceTarget = null
                c.lockSession.expectReturn()
                documentPicker.launch(arrayOf("application/pdf"))
            }
            InsertKind.FILE -> {
                replaceTarget = null
                c.lockSession.expectReturn()
                documentPicker.launch(arrayOf("*/*"))
            }
        }
    }

    fun onCrumb(crumb: Crumb) {
        when (crumb.kind) {
            CrumbKind.HOME -> onOpenFolder(null)
            CrumbKind.FOLDER -> onOpenFolder(crumb.targetId)
            CrumbKind.NOTE -> crumb.targetId?.let { onOpenParent(it, null) }
            CrumbKind.ANCHOR -> crumb.targetId?.let { onOpenParent(it, crumb.blockId) }
        }
    }

    // ---------- Focus & scrolling ----------

    val headerCount = 3 // anchor, title, tags
    LaunchedEffect(focusRequest, ui.blocks.size) {
        val id = focusRequest ?: return@LaunchedEffect
        if (id == "title") {
            delay(120)
            runCatching { titleFocus.requestFocus() }
            vm.consumeFocusRequest()
            return@LaunchedEffect
        }
        val index = ui.blocks.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.key == id }
        if (!visible) listState.animateScrollToItem(index + headerCount)
        delay(80)
        runCatching { requesterFor(id).requestFocus() }
        vm.consumeFocusRequest()
    }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ui.loading, ui.needsUnlock) {
        if (ui.loading || ui.needsUnlock || initialFocusDone) return@LaunchedEffect
        initialFocusDone = true
        when {
            scrollToBlock != null -> {
                val index = ui.blocks.indexOfFirst { it.id == scrollToBlock }
                if (index >= 0) {
                    delay(250)
                    listState.animateScrollToItem(index + headerCount)
                    highlightBlock = scrollToBlock
                    delay(1600)
                    highlightBlock = null
                }
            }
            focus == "title" -> vm.requestFocus("title")
            focus == "end" -> ui.blocks.lastOrNull { it.content is TextContent || it.content is ChecklistContent }?.let { vm.requestFocus(it.id) }
            focus != null -> vm.requestFocus(focus)
        }
    }

    // ---------- Reorder ----------

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        vm.move(from.key, to.key)
        haptics.tick()
    }

    val focusedRich = focusedBlock?.let { id ->
        if (ui.blocks.any { it.id == id && it.content is TextContent }) vm.richState(id) else null
    }
    val imeVisible = WindowInsets.isImeVisible
    val showToolbar = imeVisible && !ui.needsUnlock
    val depth = ui.depth
    val background = lerp(
        MaterialTheme.colorScheme.background,
        Paper.colors.sheetDeep,
        (depth * 0.22f).coerceAtMost(0.66f),
    )

    Scaffold(
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar, Modifier.imePadding()) },
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(background)
                    .statusBarsPadding()
                    .padding(top = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Breadcrumbs(
                        crumbs = ui.crumbs,
                        onClick = ::onCrumb,
                        modifier = Modifier.weight(1f),
                    )
                    if (!imeVisible && !ui.needsUnlock) {
                        IconButton(onClick = vm::undo, enabled = ui.canUndo) {
                            Icon(Icons.Rounded.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = vm::redo, enabled = ui.canRedo) {
                            Icon(Icons.Rounded.Redo, contentDescription = "Redo")
                        }
                    }
                    if (ui.hasLayers && !ui.needsUnlock) {
                        IconButton(onClick = { onOpenOutline(noteId) }) {
                            Icon(Icons.Rounded.AccountTree, contentDescription = "Layers outline")
                        }
                    }
                    Box {
                        IconButton(onClick = { shareMenu = true }, enabled = !ui.needsUnlock) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share note")
                        }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            DropdownMenuItem(text = { Text("Share as text") }, leadingIcon = { Icon(Icons.Rounded.Notes, null) }, onClick = {
                                shareMenu = false
                                scope.launch { vm.flushSave() }
                                shareIntent { c.exporter.textIntent(noteId) }
                            })
                            DropdownMenuItem(text = { Text("Share as PDF") }, leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) }, onClick = {
                                shareMenu = false
                                shareIntent { vm.flushSave(); c.exporter.pdfIntent(noteId) }
                            })
                            DropdownMenuItem(text = { Text("Share as images") }, leadingIcon = { Icon(Icons.Rounded.Image, null) }, onClick = {
                                shareMenu = false
                                shareIntent { vm.flushSave(); c.exporter.imagesIntent(noteId) }
                            })
                        }
                    }
                    Box {
                        IconButton(onClick = { topMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                            val note = ui.note
                            val isTopLevel = note?.parentNoteId == null
                            if (isTopLevel && note != null) {
                                DropdownMenuItem(
                                    text = { Text(if (note.pinned) "Unpin" else "Pin to top") },
                                    leadingIcon = { Icon(if (note.pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin, null) },
                                    onClick = { topMenu = false; vm.setPinned(!note.pinned) },
                                )
                            }
                            DropdownMenuItem(text = { Text("Color") }, leadingIcon = { Icon(Icons.Rounded.Palette, null) },
                                onClick = { topMenu = false; dialog = EditorDialog.Color })
                            DropdownMenuItem(text = { Text("Tags") }, leadingIcon = { Icon(Icons.Rounded.LocalOffer, null) },
                                onClick = { topMenu = false; dialog = EditorDialog.Tags })
                            if (note != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when {
                                                note.locked -> "Remove lock"
                                                note.encrypted -> "Locked by parent"
                                                else -> "Lock note"
                                            }
                                        )
                                    },
                                    enabled = !(note.encrypted && !note.locked),
                                    leadingIcon = { Icon(if (note.locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) },
                                    onClick = {
                                        topMenu = false
                                        if (note.locked) auth.confirm("Remove note lock") { vm.setLocked(false) }
                                        else vm.setLocked(true)
                                    },
                                )
                            }
                            if (isTopLevel) {
                                DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                                    onClick = { topMenu = false; dialog = EditorDialog.Move })
                            }
                            DropdownMenuItem(text = { Text("Layers outline") }, leadingIcon = { Icon(Icons.Rounded.AccountTree, null) },
                                onClick = { topMenu = false; onOpenOutline(noteId) })
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete note", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    topMenu = false
                                    scope.launch { dialog = EditorDialog.DeleteNote(vm.countLayersUnderNote()) }
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            when {
                ui.missing -> EmptyState(
                    icon = Icons.Rounded.Layers,
                    title = "This note is gone",
                    message = "It may have been moved to the trash.",
                    action = { TextButton(onClick = onBack) { Text("Go back") } },
                )
                ui.needsUnlock -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LockedMessage(
                        title = ui.note?.let { NoteRepository.displayTitle(it) } ?: "Locked note",
                        message = "This note is encrypted. Unlock with your fingerprint, face or screen lock.",
                        onUnlock = { auth.unlock() },
                    )
                }
                ui.loading -> Box(Modifier.fillMaxSize())
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding(),
                        contentPadding = PaddingValues(start = 4.dp, end = 16.dp, bottom = 160.dp),
                    ) {
                        item(key = "anchor") {
                            val anchor = ui.anchor
                            if (anchor != null) {
                                AnchorHeader(
                                    anchor = anchor,
                                    onClick = { onOpenParent(anchor.parentNoteId, anchor.blockId) },
                                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
                                )
                            }
                        }
                        item(key = "title") {
                            BasicTextField(
                                value = title,
                                onValueChange = vm::setTitle,
                                textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = {
                                    ui.blocks.firstOrNull { it.content is TextContent || it.content is ChecklistContent }?.let { vm.requestFocus(it.id) }
                                }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 28.dp, top = 8.dp, bottom = 4.dp)
                                    .focusRequester(titleFocus)
                                    .onFocusChanged { if (it.isFocused) vm.setFocused(null) }
                                    .semantics { contentDescription = "Title" },
                                decorationBox = { inner ->
                                    Box {
                                        if (title.isEmpty()) {
                                            Text(
                                                if (ui.anchor != null) "Layer title" else "Title",
                                                style = MaterialTheme.typography.headlineMedium,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                        item(key = "tags") {
                            if (ui.tags.isNotEmpty()) {
                                FlowRow(
                                    modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ui.tags.forEach { tag ->
                                        SuggestionChip(onClick = { dialog = EditorDialog.Tags }, label = { Text("#${tag.name}") })
                                    }
                                }
                            }
                        }
                        items(ui.blocks, key = { it.id }) { block ->
                            ReorderableItem(reorderState, key = block.id) { isDragging ->
                                val highlight by animateColorAsState(
                                    targetValue = when {
                                        highlightBlock == block.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                        isDragging -> Paper.colors.sheet
                                        else -> Color.Transparent
                                    },
                                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                                    label = "blockHighlight",
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (isDragging) Modifier.shadow(8.dp, RoundedCornerShape(14.dp)) else Modifier)
                                        .background(highlight, RoundedCornerShape(14.dp))
                                        .padding(vertical = if (block.content is TextContent) 0.dp else 6.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Box(
                                        Modifier
                                            .padding(top = 6.dp)
                                            .size(width = 24.dp, height = 36.dp)
                                            .draggableHandle(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Rounded.DragIndicator,
                                            contentDescription = "Drag to reorder",
                                            tint = MaterialTheme.colorScheme.outline.copy(alpha = if (focusedBlock == block.id || isDragging) 0.8f else 0.28f),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Box(Modifier.weight(1f)) {
                                        BlockView(
                                            block = block,
                                            ui = ui,
                                            vm = vm,
                                            requester = requesterFor(block.id),
                                            isFirstText = block.id == ui.blocks.firstOrNull()?.id,
                                            onLongPress = { x, y ->
                                                haptics.longPress()
                                                menu = BlockMenu(block.id, x, y)
                                            },
                                            onOpenDrawing = { openDrawing(block.id) },
                                            onOpenPdf = { openPdf(block.id) },
                                            onOpenFile = { f -> openExternally(f.path, f.name, f.mime) },
                                            onOpenLayer = { openLayer(block.id) },
                                            onPinTap = { pin ->
                                                haptics.confirm()
                                                onOpenNote(pin.subNoteId, null)
                                            },
                                            onTaskSettings = { dialog = EditorDialog.Task(block.id) },
                                        )
                                        val m = menu
                                        if (m != null && m.blockId == block.id) {
                                            BlockMenuDropdown(
                                                block = block,
                                                hasLayer = (ui.layerCounts[block.id] ?: 0) > 0,
                                                pinCount = ui.pins.count { it.blockId == block.id },
                                                onDismiss = { menu = null },
                                                onOpenLayer = { menu = null; openLayer(block.id) },
                                                onAddPin = {
                                                    menu = null
                                                    haptics.drop()
                                                    scope.launch {
                                                        val sub = vm.addPin(block.id, m.x, m.y)
                                                        val result = snackbar.showSnackbar("Pin added", actionLabel = "Open", duration = androidx.compose.material3.SnackbarDuration.Short)
                                                        if (result == SnackbarResult.ActionPerformed) onOpenNote(sub, null)
                                                    }
                                                },
                                                onTogglePins = { menu = null; vm.togglePins(block.id) },
                                                onCopyText = {
                                                    menu = null
                                                    scope.launch {
                                                        snackbar.showSnackbar("Reading text…", duration = androidx.compose.material3.SnackbarDuration.Short)
                                                    }
                                                    scope.launch {
                                                        val text = vm.recognizeText(block.id)
                                                        if (text.isBlank()) snackbar.showSnackbar("No text found in this image")
                                                        else {
                                                            val cm = context.getSystemService(ClipboardManager::class.java)
                                                            cm.setPrimaryClip(ClipData.newPlainText("Text from image", text))
                                                            dialog = EditorDialog.OcrResult(text)
                                                        }
                                                    }
                                                },
                                                onShare = {
                                                    menu = null
                                                    shareIntent { c.exporter.attachmentIntent(noteId, block) }
                                                },
                                                onReplace = {
                                                    menu = null
                                                    when (block.content) {
                                                        is ImageContent -> {
                                                            replaceTarget = block.id
                                                            c.lockSession.expectReturn()
                                                            replacePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                        }
                                                        is DrawingContent -> {
                                                            vm.clearDrawing(block.id)
                                                            openDrawing(block.id)
                                                        }
                                                        is AudioContent -> startRecording(block.id)
                                                        is PdfContent -> {
                                                            replaceTarget = block.id
                                                            c.lockSession.expectReturn()
                                                            documentPicker.launch(arrayOf("application/pdf"))
                                                        }
                                                        is FileContent -> {
                                                            replaceTarget = block.id
                                                            c.lockSession.expectReturn()
                                                            documentPicker.launch(arrayOf("*/*"))
                                                        }
                                                        else -> Unit
                                                    }
                                                },
                                                onTask = { menu = null; dialog = EditorDialog.Task(block.id) },
                                                onCalendar = {
                                                    menu = null
                                                    (block.content as? ChecklistContent)?.let { addToPhoneCalendar(context, it) }
                                                },
                                                onDelete = { menu = null; requestDelete(block.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "tail") {
                            // Tapping the empty space below the last block continues writing there.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        val last = ui.blocks.lastOrNull()
                                        if (last != null && last.content is TextContent) vm.requestFocus(last.id)
                                        else vm.insert(TextContent(), afterId = last?.id, focus = true)
                                    }
                                    .semantics { contentDescription = "Continue writing" }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showToolbar,
                        enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it } + fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .imePadding(),
                    ) {
                        FormattingToolbar(
                            state = focusedRich,
                            canUndo = ui.canUndo,
                            canRedo = ui.canRedo,
                            onUndo = vm::undo,
                            onRedo = vm::redo,
                            onInsert = { dialog = EditorDialog.Insert },
                            onChecklist = { vm.addChecklistAfter(focusedBlock) },
                            onHideKeyboard = { focusManager.clearFocus() },
                        )
                    }
                    AnimatedVisibility(
                        visible = !imeVisible,
                        enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(20.dp),
                    ) {
                        FloatingActionButton(
                            onClick = { dialog = EditorDialog.Insert },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Insert")
                        }
                    }
                }
            }
        }
    }

    // ---------- Dialogs ----------

    when (val d = dialog) {
        null -> Unit
        EditorDialog.Insert -> InsertSheet(
            icons = mapOf(
                InsertKind.TEXT to Icons.Rounded.Notes,
                InsertKind.CHECKLIST to Icons.Rounded.CheckBox,
                InsertKind.PHOTO to Icons.Rounded.Image,
                InsertKind.CAMERA to Icons.Rounded.PhotoCamera,
                InsertKind.SCREENSHOT to Icons.Rounded.Screenshot,
                InsertKind.DRAWING to Icons.Rounded.Gesture,
                InsertKind.VOICE to Icons.Rounded.Mic,
                InsertKind.PDF to Icons.Rounded.PictureAsPdf,
                InsertKind.FILE to Icons.Rounded.AttachFile,
            ),
            onDismiss = { dialog = null },
            onPick = ::onInsert,
        )
        is EditorDialog.DeleteBlock -> ConfirmDialog(
            title = "Delete this block?",
            message = "It has ${d.layers} sub-layer${if (d.layers != 1) "s" else ""} underneath. They'll move to the trash with it, for 30 days.",
            confirmText = "Delete",
            onDismiss = { dialog = null },
            onConfirm = { vm.delete(d.blockId); dialog = null },
        )
        is EditorDialog.DeleteNote -> ConfirmDialog(
            title = "Delete this note?",
            message = if (d.layers > 0) {
                "The note and its ${d.layers} sub-layer${if (d.layers != 1) "s" else ""} move to the trash for 30 days."
            } else "The note moves to the trash for 30 days.",
            confirmText = "Move to trash",
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                scope.launch {
                    vm.trash()
                    onBack()
                }
            },
        )
        is EditorDialog.Task -> {
            val block = ui.blocks.firstOrNull { it.id == d.blockId }
            val content = block?.content as? ChecklistContent
            if (content == null) {
                LaunchedEffect(d) { dialog = null }
            } else {
                TaskDialog(
                    content = content,
                    onDismiss = { dialog = null },
                    onSave = { updated ->
                        vm.setTask(d.blockId, updated)
                        dialog = null
                        if (updated.reminderOffsetMin != null && updated.dueAt != null) {
                            val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            if (needsNotif) {
                                dialog = EditorDialog.Rationale(
                                    "Allow reminders?",
                                    "Layers needs permission to show notifications so it can remind you when this task is due.",
                                ) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            } else if (!c.scheduler.canScheduleExact()) {
                                dialog = EditorDialog.ExactAlarm
                            }
                        }
                    },
                )
            }
        }
        is EditorDialog.OcrResult -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Text from image") },
            text = {
                Column {
                    Text(
                        "Copied to the clipboard.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(d.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.insert(TextContent(com.layers.app.data.repo.TextUtil.plainToHtml(d.text)))
                    dialog = null
                }) { Text("Insert as text") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Done")
                }
            },
        )
        EditorDialog.Record -> RecordAudioDialog(
            onDismiss = { dialog = null },
            onSaved = { rel, duration, wave ->
                vm.addAudio(rel, duration, wave)
                dialog = null
            },
        )
        is EditorDialog.ReRecord -> RecordAudioDialog(
            onDismiss = { dialog = null; replaceTarget = null },
            onSaved = { rel, duration, wave ->
                vm.addAudio(rel, duration, wave, replaceBlockId = d.blockId)
                dialog = null
                replaceTarget = null
            },
        )
        EditorDialog.Color -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Note color") },
            text = {
                ColorPicker(selected = ui.note?.color, allowNone = true, onSelect = { vm.setColor(it); dialog = null })
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
        )
        EditorDialog.Tags -> TagsDialog(
            current = ui.tags,
            all = allTags,
            onAdd = { vm.addTag(it) },
            onRemove = { vm.removeTag(it.id) },
            onDismiss = { dialog = null },
        )
        EditorDialog.Move -> MoveToSheet(
            title = "Move note to…",
            tree = tree,
            currentFolderId = ui.note?.folderId,
            onDismiss = { dialog = null },
            onPick = { vm.moveNote(it); dialog = null },
        )
        is EditorDialog.Rationale -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.title) },
            text = { Text(d.message) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    d.onContinue()
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialog = null
                    if (d.title.contains("screenshot")) launchPhotoPicker()
                }) { Text(if (d.title.contains("screenshot")) "Pick it myself" else "Not now") }
            },
        )
        EditorDialog.ExactAlarm -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("On-time reminders") },
            text = {
                Text("To ring at the exact minute, Layers needs the “Alarms & reminders” permission. Without it, reminders still arrive, but Android may delay them by a few minutes.")
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    c.lockSession.expectReturn()
                    runCatching { context.startActivity(c.scheduler.exactAlarmSettingsIntent()) }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Not now") } },
        )
    }
}

// ---------- One block ----------

@Composable
private fun BlockView(
    block: Block,
    ui: EditorUi,
    vm: EditorViewModel,
    requester: FocusRequester,
    isFirstText: Boolean,
    onLongPress: (Float, Float) -> Unit,
    onOpenDrawing: () -> Unit,
    onOpenPdf: () -> Unit,
    onOpenFile: (FileContent) -> Unit,
    onOpenLayer: () -> Unit,
    onPinTap: (com.layers.app.data.db.PinEntity) -> Unit,
    onTaskSettings: () -> Unit,
) {
    val layerCount = ui.layerCounts[block.id] ?: 0
    val sharedKey = "anchor-${block.id}"
    when (val content = block.content) {
        is TextContent -> TextBlockView(
            state = vm.richState(block.id),
            placeholder = if (isFirstText && ui.blocks.size == 1) "Start writing…" else null,
            focusRequester = requester,
            onFocusChange = { if (it) vm.setFocused(block.id) },
            onEdited = vm::onTextEdited,
            modifier = Modifier.padding(start = 4.dp),
        )
        is ChecklistContent -> Box(
            Modifier.combinedLongPress { onLongPress(0.5f, 0.5f) }
        ) {
            ChecklistBlockView(
                blockId = block.id,
                content = content,
                focusRequester = requester,
                onFocusChange = { if (it) vm.setFocused(block.id) },
                onToggle = { vm.toggleChecklist(block.id, it) },
                onText = { vm.setChecklistText(block.id, it) },
                onEnter = { before, after -> vm.checklistEnter(block.id, before, after) },
                onDeleteEmpty = { vm.deleteEmptyChecklist(block.id) },
                onTaskSettings = onTaskSettings,
            )
        }
        is ImageContent -> ImageBlockView(
            content = content,
            encrypted = ui.encrypted,
            pins = ui.pins.filter { it.blockId == block.id },
            layerCount = layerCount,
            sharedKey = sharedKey,
            onTap = { if (layerCount > 0) onOpenLayer() else onLongPress(0.5f, 0.5f) },
            onLongPress = onLongPress,
            onPinTap = onPinTap,
        )
        is DrawingContent -> DrawingBlockView(
            content = content,
            layerCount = layerCount,
            sharedKey = sharedKey,
            onTap = onOpenDrawing,
            onLongPress = { onLongPress(0.5f, 0.5f) },
        )
        is AudioContent -> AudioBlockView(
            content = content,
            encrypted = ui.encrypted,
            layerCount = layerCount,
            sharedKey = sharedKey,
            onLongPress = { onLongPress(0.5f, 0.5f) },
        )
        is PdfContent -> PdfBlockView(
            content = content,
            encrypted = ui.encrypted,
            layerCount = layerCount,
            sharedKey = sharedKey,
            onOpen = onOpenPdf,
            onLongPress = { onLongPress(0.5f, 0.5f) },
        )
        is FileContent -> FileBlockView(
            content = content,
            layerCount = layerCount,
            sharedKey = sharedKey,
            onOpen = { onOpenFile(content) },
            onLongPress = { onLongPress(0.5f, 0.5f) },
        )
    }
}

/** Long-press without stealing taps (used around checklist rows). */
private fun Modifier.combinedLongPress(onLongPress: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInputLongPress(onLongPress)
    )

private fun Modifier.pointerInputLongPress(onLongPress: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(onLongPress) {
            detectTapGestures(onLongPress = { onLongPress() })
        }
    )

@Composable
private fun BlockMenuDropdown(
    block: Block,
    hasLayer: Boolean,
    pinCount: Int,
    onDismiss: () -> Unit,
    onOpenLayer: () -> Unit,
    onAddPin: () -> Unit,
    onTogglePins: () -> Unit,
    onCopyText: () -> Unit,
    onShare: () -> Unit,
    onReplace: () -> Unit,
    onTask: () -> Unit,
    onCalendar: () -> Unit,
    onDelete: () -> Unit,
) {
    val content = block.content
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (content.supportsLayers) {
            MenuItem(if (hasLayer) "Open layer" else "Add layer", Icons.Rounded.Layers, onOpenLayer)
        }
        if (content is ImageContent) {
            MenuItem("Add pin here", Icons.Rounded.PushPin, onAddPin)
            if (pinCount > 0) {
                MenuItem(
                    if (content.showPins) "Hide pins" else "Show pins",
                    if (content.showPins) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    onTogglePins,
                )
            }
            MenuItem("Copy text from image", Icons.Rounded.ContentCopy, onCopyText)
        }
        if (content is ChecklistContent) {
            MenuItem("Due date & reminder…", Icons.Rounded.Event, onTask)
            if (content.dueAt != null) MenuItem("Add to phone calendar", Icons.Rounded.Event, onCalendar)
        }
        if (content is ImageContent || content is AudioContent || content is PdfContent || content is FileContent) {
            MenuItem("Share", Icons.Rounded.Share, onShare)
        }
        if (content.supportsLayers) {
            MenuItem("Replace", Icons.Rounded.SwapHoriz, onReplace)
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = onDelete,
        )
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

// ---------- The header of a sub-layer: the parent's block it hangs under ----------

@Composable
private fun AnchorHeader(anchor: AnchorInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val paper = Paper.colors
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .layerSharedBounds(anchor.sharedKey)
                .shadow(8.dp, RoundedCornerShape(18.dp), ambientColor = paper.shadow.copy(alpha = 0.18f), spotColor = paper.shadow.copy(alpha = 0.24f))
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "Back up to ${anchor.label} in ${anchor.parentTitle}" },
        ) {
            when (val content = anchor.content) {
                is ImageContent -> {
                    val file = rememberReadableFile(content.path, anchor.encrypted)
                    if (file != null) {
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = anchor.pin?.let { BiasAlignmentFor(it.x, it.y) } ?: Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    val pin = anchor.pin
                    if (pin != null) {
                        Box(Modifier.fillMaxSize()) {
                            // With Crop the pin's position shifts; show the marker centered as a cue.
                            PinMarker(
                                number = pin.number,
                                onClick = onClick,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(y = (-4).dp),
                            )
                        }
                    }
                }
                is DrawingContent -> Box(Modifier.fillMaxSize().background(DrawingPaper)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { InkRenderer.drawStrokes(it.nativeCanvas, content.strokes, size.width) }
                    }
                }
                is PdfContent -> {
                    val file = rememberReadableFile(content.path, anchor.encrypted)
                    val page = anchor.page ?: 0
                    val bmp by produceState<ImageBitmap?>(null, file, page) {
                        value = file?.let { PdfSource.renderPage(it, page, 900)?.asImageBitmap() }
                    }
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        bmp?.let {
                            Image(it, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        when (content) {
                            is AudioContent -> Icons.Rounded.Mic
                            is FileContent -> Icons.Rounded.AttachFile
                            else -> Icons.Rounded.Layers
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            // A soft fade at the bottom, like the edge of the sheet above.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f))
                        )
                    )
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Icon(Icons.Rounded.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Under ${anchor.label} in “${anchor.parentTitle}”",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun BiasAlignmentFor(x: Float, y: Float): Alignment =
    androidx.compose.ui.BiasAlignment(horizontalBias = (x * 2f - 1f).coerceIn(-1f, 1f), verticalBias = (y * 2f - 1f).coerceIn(-1f, 1f))
