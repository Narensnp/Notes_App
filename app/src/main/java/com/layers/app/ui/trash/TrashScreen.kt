package com.layers.app.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Restore
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layers.app.AppContainer
import com.layers.app.data.repo.NoteRepository
import com.layers.app.data.repo.TrashItem
import com.layers.app.data.repo.TrashRepository
import com.layers.app.ui.components.ConfirmDialog
import com.layers.app.ui.components.EmptyState
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.layersViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TrashViewModel(private val c: AppContainer) : ViewModel() {
    val items: StateFlow<List<TrashItem>?> = combine(c.trash.observeNoteRoots(), c.trash.observeFolderRoots()) { notes, folders ->
        val list = ArrayList<TrashItem>()
        notes.forEach { list += TrashItem.Note(it, c.trash.countInGroup(it.id)) }
        folders.forEach { list += TrashItem.Folder(it, c.trash.countInGroup(it.id)) }
        list.sortedByDescending { it.deletedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(id: String) = viewModelScope.launch { c.trash.restore(id) }
    fun deleteForever(id: String) = viewModelScope.launch { c.trash.deleteForever(id) }
    fun emptyTrash() = viewModelScope.launch { c.trash.emptyTrash() }
}

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val vm = layersViewModel { TrashViewModel(it) }
    val items by vm.items.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TrashItem?>(null) }

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
                Text("Trash", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!items.isNullOrEmpty()) {
                    TextButton(onClick = { confirmEmpty = true }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Empty trash")
                    }
                }
            }
        },
    ) { padding ->
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Layers,
                title = "Trash is empty",
                message = "Deleted notes and folders stay here for 30 days, then disappear for good.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Items are deleted forever 30 days after they were moved here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.id }) { item ->
                    TrashRow(
                        item = item,
                        onRestore = {
                            vm.restore(item.id)
                            scope.launch { snackbar.showSnackbar("Restored") }
                        },
                        onDelete = { confirmDelete = item },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty trash?",
            message = "Everything in the trash, including all sub-layers and attachments, is deleted permanently.",
            confirmText = "Delete forever",
            onDismiss = { confirmEmpty = false },
            onConfirm = { confirmEmpty = false; vm.emptyTrash() },
        )
    }
    confirmDelete?.let { item ->
        ConfirmDialog(
            title = "Delete forever?",
            message = "This can't be undone." + if (item.itemCountOf() > 1) " ${item.itemCountOf()} items are included." else "",
            confirmText = "Delete forever",
            onDismiss = { confirmDelete = null },
            onConfirm = { confirmDelete = null; vm.deleteForever(item.id) },
        )
    }
}

private fun TrashItem.itemCountOf(): Int = when (this) {
    is TrashItem.Note -> itemCount
    is TrashItem.Folder -> itemCount
}

@Composable
private fun TrashRow(item: TrashItem, onRestore: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val daysLeft = (TrashRepository.RETENTION_DAYS - TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - item.deletedAt)).coerceAtLeast(0)
    val (icon, title, detail) = when (item) {
        is TrashItem.Note -> Triple(
            Icons.Rounded.Description,
            NoteRepository.displayTitle(item.note),
            if (item.itemCount > 1) "Note with ${item.itemCount - 1} sub-layer${if (item.itemCount - 1 != 1) "s" else ""}" else "Note",
        )
        is TrashItem.Folder -> Triple(
            Icons.Rounded.Folder,
            item.folder.name,
            "Folder · ${item.itemCount} item${if (item.itemCount != 1) "s" else ""}",
        )
    }
    PaperSheet(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = 2.dp,
        stackDepth = if (item.itemCountOf() > 1) 1 else 0,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$detail · $daysLeft day${if (daysLeft != 1L) "s" else ""} left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRestore) { Icon(Icons.Rounded.Restore, contentDescription = "Restore $title") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteForever, contentDescription = "Delete $title forever", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            }
        }
    }
}
