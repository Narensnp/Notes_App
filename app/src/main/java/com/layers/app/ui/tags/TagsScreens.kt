package com.layers.app.ui.tags

import android.text.format.DateUtils
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.layers.app.data.db.TagEntity
import com.layers.app.data.model.NoteListItem
import com.layers.app.data.model.isLocked
import com.layers.app.data.repo.NoteRepository
import com.layers.app.ui.components.ConfirmDialog
import com.layers.app.ui.components.EmptyState
import com.layers.app.ui.components.LayerBadge
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.TextInputDialog
import com.layers.app.ui.components.appContainer
import com.layers.app.ui.components.toColor
import kotlinx.coroutines.launch

/** All tags with how many notes carry each. Tap one to filter. */
@Composable
fun TagsScreen(onBack: () -> Unit, onOpenTag: (String) -> Unit) {
    val c = appContainer()
    val tags by remember { c.tags.observeAllWithCounts() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<TagEntity?>(null) }
    var deleting by remember { mutableStateOf<TagEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, contentDescription = "New tag") }
        },
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Tags", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        val list = tags
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> EmptyState(
                icon = Icons.Rounded.LocalOffer,
                title = "No tags yet",
                message = "Add tags to a note from its menu. They help you find related notes across folders.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.tag.id }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    PaperSheet(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        elevation = 2.dp,
                        onClick = { onOpenTag(item.tag.id) },
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(item.tag.color.toColor(), CircleShape)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("#${item.tag.name}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${item.noteCount} note" + if (item.noteCount != 1) "s" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Options for ${item.tag.name}") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                        onClick = { menu = false; renaming = item.tag })
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; deleting = item.tag },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { tag ->
        TextInputDialog(
            title = "Rename tag",
            initial = tag.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> renaming = null; scope.launch { c.tags.rename(tag, name) } },
        )
    }
    deleting?.let { tag ->
        ConfirmDialog(
            title = "Delete #${tag.name}?",
            message = "The tag is removed from every note. The notes themselves stay.",
            confirmText = "Delete",
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; scope.launch { c.tags.delete(tag.id) } },
        )
    }
    if (creating) {
        TextInputDialog(
            title = "New tag",
            label = "Tag name",
            confirmText = "Create",
            onDismiss = { creating = false },
            onConfirm = { name -> creating = false; scope.launch { c.tags.getOrCreate(name) } },
        )
    }
}

/** Every note carrying one tag (top-level notes and sub-layers alike). */
@Composable
fun TagNotesScreen(tagId: String, onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val c = appContainer()
    val tag by remember(tagId) { c.tags.observeTag(tagId) }.collectAsState(initial = null)
    val notes by remember(tagId) { c.notes.observeNotesWithTag(tagId) }.collectAsState(initial = null)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text(tag?.let { "#${it.name}" } ?: "Tag", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        val list = notes
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> EmptyState(
                icon = Icons.Rounded.LocalOffer,
                title = "No notes with this tag",
                message = "Open a note and add this tag from its menu.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.note.id }) { item -> TaggedNoteRow(item) { onOpenNote(item.note.id) } }
            }
        }
    }
}

@Composable
private fun TaggedNoteRow(item: NoteListItem, onClick: () -> Unit) {
    val note = item.note
    PaperSheet(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = 2.dp,
        stackDepth = item.layerCount.coerceAtMost(2),
        onClick = onClick,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.isLocked) {
                    Icon(Icons.Rounded.Lock, contentDescription = "Locked", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    NoteRepository.displayTitle(note),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                LayerBadge(item.layerCount)
            }
            if (!note.isLocked && note.preview.isNotBlank()) {
                Text(
                    note.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                DateUtils.getRelativeTimeSpanString(note.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
