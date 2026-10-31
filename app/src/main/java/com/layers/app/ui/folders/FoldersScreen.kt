package com.layers.app.ui.folders

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.layers.app.data.db.FolderEntity
import com.layers.app.data.db.NoteEntity
import com.layers.app.data.model.Crumb
import com.layers.app.data.model.CrumbKind
import com.layers.app.data.model.NoteListItem
import com.layers.app.data.model.isLocked
import com.layers.app.data.repo.NoteRepository
import com.layers.app.data.repo.SortMode
import com.layers.app.data.repo.ViewMode
import com.layers.app.ui.components.Breadcrumbs
import com.layers.app.ui.components.ColorPicker
import com.layers.app.ui.components.ConfirmDialog
import com.layers.app.ui.components.EmptyState
import com.layers.app.ui.components.FolderEditDialog
import com.layers.app.ui.components.FolderIcons
import com.layers.app.ui.components.LayerBadge
import com.layers.app.ui.components.MoveToSheet
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.layersViewModel
import com.layers.app.ui.components.rememberHaptics
import com.layers.app.ui.components.toColor
import com.layers.app.ui.lock.rememberAuthenticator
import kotlinx.coroutines.launch
import java.io.File

private sealed interface FolderDialog {
    data object NewFolder : FolderDialog
    data class EditFolder(val folder: FolderEntity) : FolderDialog
    data class MoveFolder(val folder: FolderEntity, val disabled: Set<String>) : FolderDialog
    data class DeleteFolder(val folder: FolderEntity, val message: String) : FolderDialog
    data class MoveNote(val note: NoteEntity) : FolderDialog
    data class DeleteNote(val note: NoteEntity, val layers: Int) : FolderDialog
    data class NoteColor(val note: NoteEntity) : FolderDialog
}

@Composable
fun FoldersScreen(
    onOpenNote: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTags: () -> Unit,
) {
    val vm = layersViewModel { FoldersViewModel(it) }
    val state by vm.state.collectAsState()
    val tree by vm.tree.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val auth = rememberAuthenticator()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = state.path.isNotEmpty()) { vm.goUp() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { onOpenNote(vm.createNote()) } },
                icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                text = { Text("New note") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.path.isNotEmpty()) {
                        IconButton(onClick = { vm.goUp() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Up one folder")
                        }
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = state.current?.name ?: "Layers",
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { dialog = FolderDialog.NewFolder }) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = "New folder")
                    }
                    IconButton(onClick = {
                        vm.setViewMode(if (state.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID)
                    }) {
                        if (state.viewMode == ViewMode.GRID) {
                            Icon(Icons.Rounded.ViewAgenda, contentDescription = "Show as list")
                        } else {
                            Icon(Icons.Rounded.GridView, contentDescription = "Show as grid")
                        }
                    }
                    Box {
                        IconButton(onClick = { sortOpen = true }) {
                            Icon(Icons.Rounded.SwapVert, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            listOf(
                                SortMode.MODIFIED to "Date modified",
                                SortMode.CREATED to "Date created",
                                SortMode.TITLE to "Title",
                            ).forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { vm.setSortMode(mode); sortOpen = false },
                                    trailingIcon = {
                                        if (state.sortMode == mode) Icon(Icons.Rounded.Check, contentDescription = "Selected")
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Tags") },
                                leadingIcon = { Icon(Icons.Rounded.LocalOffer, contentDescription = null) },
                                onClick = { menuOpen = false; onOpenTags() },
                            )
                            DropdownMenuItem(
                                text = { Text("Trash") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; onOpenTrash() },
                            )
                        }
                    }
                }
                if (state.path.isNotEmpty()) {
                    val crumbs = listOf(Crumb("Home", CrumbKind.HOME, null)) +
                        state.path.map { Crumb(it.name, CrumbKind.FOLDER, it.id) }
                    Breadcrumbs(
                        crumbs = crumbs,
                        onClick = { crumb ->
                            val index = state.path.indexOfFirst { it.id == crumb.targetId }
                            vm.goTo(index)
                        },
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = state,
            contentKey = { it.current?.id to it.loading },
            transitionSpec = {
                val deeper = targetState.path.size > initialState.path.size
                val spec = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
                if (deeper) {
                    (slideInHorizontally(spec) { it / 5 } + fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                        scaleIn(spring(stiffness = Spring.StiffnessMediumLow), initialScale = 0.96f)) togetherWith
                        (fadeOut(spring(stiffness = Spring.StiffnessMedium)) + scaleOut(spring(stiffness = Spring.StiffnessMediumLow), targetScale = 1.03f))
                } else {
                    (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + scaleIn(spring(stiffness = Spring.StiffnessMediumLow), initialScale = 1.03f)) togetherWith
                        (slideOutHorizontally(spec) { it / 5 } + fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                }
            },
            label = "folder",
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) { s ->
            if (s.loading) {
                Box(Modifier.fillMaxSize())
            } else if (s.isEmpty) {
                EmptyState(
                    icon = Icons.Rounded.Layers,
                    title = if (s.current == null) "A blank stack" else "This folder is empty",
                    message = if (s.current == null) {
                        "Start a note, then long-press any photo or PDF in it to add layers underneath."
                    } else {
                        "Add a note or a sub-folder to start stacking ideas here."
                    },
                    action = {
                        TextButton(onClick = { dialog = FolderDialog.NewFolder }) { Text("New folder") }
                    },
                )
            } else {
                val folderActions = FolderActions(
                    onOpen = { vm.open(it.id) },
                    onEdit = { dialog = FolderDialog.EditFolder(it) },
                    onMove = { f -> scope.launch { dialog = FolderDialog.MoveFolder(f, vm.descendantFolderIds(f.id) + f.id) } },
                    onDelete = { f ->
                        scope.launch {
                            val count = vm.countFolder(f.id)
                            val parts = buildList {
                                if (count.folders > 0) add("${count.folders} sub-folder" + if (count.folders != 1) "s" else "")
                                if (count.notes > 0) add("${count.notes} note" + if (count.notes != 1) "s" else "")
                                if (count.layers > 0) add("${count.layers} sub-layer" + if (count.layers != 1) "s" else "")
                            }
                            val msg = if (parts.isEmpty()) "The empty folder moves to the trash for 30 days."
                            else "Everything inside — ${parts.joinToString(", ")} — moves to the trash for 30 days."
                            dialog = FolderDialog.DeleteFolder(f, msg)
                        }
                    },
                    onToggleLock = { f ->
                        if (f.locked) auth.confirm("Remove folder lock") { vm.setFolderLocked(f.id, false) }
                        else vm.setFolderLocked(f.id, true)
                    },
                    onLongPress = { haptics.longPress() },
                )
                val noteActions = NoteActions(
                    onOpen = { onOpenNote(it.id) },
                    onTogglePin = { vm.setPinned(it.id, !it.pinned) },
                    onColor = { dialog = FolderDialog.NoteColor(it) },
                    onMove = { dialog = FolderDialog.MoveNote(it) },
                    onToggleLock = { n ->
                        if (n.locked) auth.confirm("Remove note lock") { vm.setNoteLocked(n.id, false) }
                        else vm.setNoteLocked(n.id, true)
                    },
                    onDelete = { n -> scope.launch { dialog = FolderDialog.DeleteNote(n, vm.countLayers(n.id)) } },
                    onLongPress = { haptics.longPress() },
                )
                if (s.viewMode == ViewMode.GRID) {
                    FolderGrid(s, folderActions, noteActions)
                } else {
                    FolderList(s, folderActions, noteActions)
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        FolderDialog.NewFolder -> FolderEditDialog(
            title = "New folder",
            initial = null,
            onDismiss = { dialog = null },
            onConfirm = { name, color, icon -> vm.createFolder(name, color, icon); dialog = null },
        )
        is FolderDialog.EditFolder -> FolderEditDialog(
            title = "Edit folder",
            initial = d.folder,
            onDismiss = { dialog = null },
            onConfirm = { name, color, icon -> vm.updateFolder(d.folder.id, name, color, icon); dialog = null },
        )
        is FolderDialog.MoveFolder -> MoveToSheet(
            title = "Move “${d.folder.name}” to…",
            tree = tree,
            currentFolderId = d.folder.parentId,
            disabledIds = d.disabled,
            onDismiss = { dialog = null },
            onPick = { target ->
                dialog = null
                vm.moveFolder(d.folder.id, target) { ok ->
                    if (!ok) scope.launch { snackbar.showSnackbar("A folder can't move into itself.") }
                }
            },
        )
        is FolderDialog.DeleteFolder -> ConfirmDialog(
            title = "Delete “${d.folder.name}”?",
            message = d.message,
            confirmText = "Move to trash",
            onDismiss = { dialog = null },
            onConfirm = { vm.trashFolder(d.folder.id); dialog = null },
        )
        is FolderDialog.MoveNote -> MoveToSheet(
            title = "Move note to…",
            tree = tree,
            currentFolderId = d.note.folderId,
            onDismiss = { dialog = null },
            onPick = { target -> vm.moveNote(d.note.id, target); dialog = null },
        )
        is FolderDialog.DeleteNote -> ConfirmDialog(
            title = "Delete “${NoteRepository.displayTitle(d.note)}”?",
            message = if (d.layers > 0) {
                "This note and its ${d.layers} sub-layer${if (d.layers != 1) "s" else ""} move to the trash for 30 days."
            } else {
                "The note moves to the trash for 30 days."
            },
            confirmText = "Move to trash",
            onDismiss = { dialog = null },
            onConfirm = { vm.trashNote(d.note.id); dialog = null },
        )
        is FolderDialog.NoteColor -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Note color") },
            text = {
                ColorPicker(
                    selected = d.note.color,
                    allowNone = true,
                    onSelect = { vm.setColor(d.note.id, it); dialog = null },
                )
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
        )
    }
}

private class FolderActions(
    val onOpen: (FolderEntity) -> Unit,
    val onEdit: (FolderEntity) -> Unit,
    val onMove: (FolderEntity) -> Unit,
    val onDelete: (FolderEntity) -> Unit,
    val onToggleLock: (FolderEntity) -> Unit,
    val onLongPress: () -> Unit,
)

private class NoteActions(
    val onOpen: (NoteEntity) -> Unit,
    val onTogglePin: (NoteEntity) -> Unit,
    val onColor: (NoteEntity) -> Unit,
    val onMove: (NoteEntity) -> Unit,
    val onToggleLock: (NoteEntity) -> Unit,
    val onDelete: (NoteEntity) -> Unit,
    val onLongPress: () -> Unit,
)

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun FolderGrid(s: FoldersUiState, folderActions: FolderActions, noteActions: NoteActions) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(168.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalItemSpacing = 14.dp,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (s.folders.isNotEmpty()) {
            item(key = "h-folders", span = StaggeredGridItemSpan.FullLine) { SectionLabel("Folders") }
            items(s.folders, key = { "f-" + it.id }) { folder ->
                FolderCard(folder, folderActions, Modifier.animateItem())
            }
        }
        if (s.notes.isNotEmpty()) {
            item(key = "h-notes", span = StaggeredGridItemSpan.FullLine) { SectionLabel("Notes") }
            items(s.notes, key = { "n-" + it.note.id }) { item ->
                NoteCard(item, noteActions, grid = true, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun FolderList(s: FoldersUiState, folderActions: FolderActions, noteActions: NoteActions) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (s.folders.isNotEmpty()) {
            item(key = "h-folders") { SectionLabel("Folders") }
            items(s.folders, key = { "f-" + it.id }) { folder ->
                FolderCard(folder, folderActions, Modifier.animateItem())
            }
        }
        if (s.notes.isNotEmpty()) {
            item(key = "h-notes") { SectionLabel("Notes") }
            items(s.notes, key = { "n-" + it.note.id }) { item ->
                NoteCard(item, noteActions, grid = false, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun FolderCard(folder: FolderEntity, actions: FolderActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val accent = folder.color.toColor()
    Box(modifier) {
        PaperSheet(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = 2.dp,
            onClick = { actions.onOpen(folder) },
            onLongClick = { actions.onLongPress(); menu = true },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FolderIcons.icon(folder.icon), contentDescription = null, tint = accent)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (folder.locked) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = "Locked folder",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Edit name, color & icon") }, leadingIcon = { Icon(Icons.Rounded.Palette, null) },
                onClick = { menu = false; actions.onEdit(folder) })
            DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                onClick = { menu = false; actions.onMove(folder) })
            DropdownMenuItem(
                text = { Text(if (folder.locked) "Remove lock" else "Lock folder") },
                leadingIcon = { Icon(if (folder.locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) },
                onClick = { menu = false; actions.onToggleLock(folder) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; actions.onDelete(folder) },
            )
        }
    }
}

@Composable
private fun NoteCard(item: NoteListItem, actions: NoteActions, grid: Boolean, modifier: Modifier = Modifier) {
    val note = item.note
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val locked = note.isLocked
    val accent = note.color?.toColor()
    val relative = remember(note.updatedAt) {
        DateUtils.getRelativeTimeSpanString(note.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    Box(modifier) {
        PaperSheet(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = buildString {
                        append(NoteRepository.displayTitle(note))
                        if (locked) append(", locked")
                        if (note.pinned) append(", pinned")
                        if (item.layerCount > 0) append(", ${item.layerCount} sub-layers")
                    }
                },
            stackDepth = item.layerCount.coerceAtMost(2),
            onClick = { actions.onOpen(note) },
            onLongClick = { actions.onLongPress(); menu = true },
        ) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                if (accent != null && !grid) {
                    Box(
                        Modifier
                            .width(5.dp)
                            .fillMaxHeight()
                            .background(accent)
                    )
                }
                Column(Modifier.fillMaxWidth()) {
                    if (accent != null && grid) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(accent)
                        )
                    }
                    val thumb = note.thumbnailPath
                    if (!locked && thumb != null && grid) {
                        AsyncImage(
                            model = File(context.filesDir, thumb),
                            contentDescription = "Note image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(124.dp),
                        )
                    }
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (note.pinned) {
                                Icon(
                                    Icons.Rounded.PushPin,
                                    contentDescription = "Pinned",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                NoteRepository.displayTitle(note),
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (note.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (locked) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Locked", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        } else if (note.preview.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                note.preview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (grid) 7 else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                relative,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            if (!locked) {
                                if (note.hasTasks) MiniIcon(Icons.Rounded.CheckBox, "Has tasks")
                                if (note.hasImages && note.thumbnailPath == null || note.hasImages && !grid) MiniIcon(Icons.Rounded.Image, "Has images")
                                if (note.hasPdfs) MiniIcon(Icons.Rounded.PictureAsPdf, "Has PDFs")
                            }
                            if (item.layerCount > 0) {
                                Spacer(Modifier.width(4.dp))
                                LayerBadge(item.layerCount)
                            }
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (note.pinned) "Unpin" else "Pin to top") },
                leadingIcon = { Icon(if (note.pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin, null) },
                onClick = { menu = false; actions.onTogglePin(note) },
            )
            DropdownMenuItem(text = { Text("Color") }, leadingIcon = { Icon(Icons.Rounded.Palette, null) },
                onClick = { menu = false; actions.onColor(note) })
            DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                onClick = { menu = false; actions.onMove(note) })
            DropdownMenuItem(
                text = { Text(if (note.locked) "Remove lock" else "Lock note") },
                leadingIcon = { Icon(if (note.locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) },
                onClick = { menu = false; actions.onToggleLock(note) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; actions.onDelete(note) },
            )
        }
    }
}

@Composable
private fun MiniIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Icon(
        icon,
        contentDescription = description,
        modifier = Modifier
            .padding(start = 4.dp)
            .size(15.dp),
        tint = MaterialTheme.colorScheme.outline,
    )
}
