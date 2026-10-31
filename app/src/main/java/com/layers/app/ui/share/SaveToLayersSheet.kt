package com.layers.app.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.layers.app.data.model.isLocked
import com.layers.app.data.repo.NoteRepository
import com.layers.app.data.repo.SettingsRepository
import com.layers.app.share.ShareImporter
import com.layers.app.share.SharedContent
import com.layers.app.ui.components.FolderIcons
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.appContainer
import com.layers.app.ui.components.toColor
import com.layers.app.ui.lock.rememberAuthenticator
import kotlinx.coroutines.launch

/** The "Save to Layers" sheet shown when another app shares text, links, images or files. */
@Composable
fun SaveToLayersSheet(
    content: SharedContent,
    onDismiss: () -> Unit,
    onSaved: (noteId: String, focusBlockId: String) -> Unit,
) {
    val c = appContainer()
    val importer = remember { ShareImporter(c) }
    val scope = rememberCoroutineScope()
    val auth = rememberAuthenticator()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tree by remember { c.folders.observeTree() }.collectAsState(initial = emptyList())
    val recentIds by c.settings.recentFolders.collectAsState(initial = emptyList())
    val recentNotes by remember { c.notes.observeRecentNotes() }.collectAsState(initial = emptyList())

    var newNote by remember { mutableStateOf(true) }
    var folderId by remember { mutableStateOf<String?>(null) }
    var folderChosen by remember { mutableStateOf(false) }
    var noteId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    // Recent folders first (Home included), then the rest of the tree.
    val byId = tree.associateBy { it.folder.id }
    val recent = recentIds.mapNotNull { id -> if (id == SettingsRepository.HOME) null to "Home" else byId[id]?.let { it.folder.id to it.folder.name } }
    LaunchedEffect(recentIds, tree.size) {
        if (!folderChosen && recent.isNotEmpty()) folderId = recent.first().first
    }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
        ) {
            Text("Save to Layers", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Preview(content)
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = newNote, onClick = { newNote = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text("New note")
                }
                SegmentedButton(selected = !newNote, onClick = { newNote = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text("Add to existing")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (newNote) {
                Text("Folder", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    if (recent.isNotEmpty()) {
                        item { SubHeader("Recent") }
                        items(recent, key = { "r-" + (it.first ?: "home") }) { (id, name) ->
                            PickRow(
                                icon = if (id == null) Icons.Rounded.Home else Icons.Rounded.History,
                                label = name,
                                depth = 0,
                                selected = folderId == id,
                            ) { folderId = id; folderChosen = true }
                        }
                        item { SubHeader("All folders") }
                    }
                    item {
                        PickRow(Icons.Rounded.Home, "Home", 0, folderId == null) { folderId = null; folderChosen = true }
                    }
                    items(tree, key = { "t-" + it.folder.id }) { node ->
                        PickRow(
                            icon = FolderIcons.icon(node.folder.icon),
                            label = node.folder.name,
                            depth = node.depth + 1,
                            selected = folderId == node.folder.id,
                            tintColor = node.folder.color,
                        ) { folderId = node.folder.id; folderChosen = true }
                    }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Find a note") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val filtered = recentNotes.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(filtered, key = { it.id }) { note ->
                        PickRow(
                            icon = if (note.isLocked) Icons.Rounded.Lock else Icons.Rounded.Description,
                            label = NoteRepository.displayTitle(note),
                            depth = 0,
                            selected = noteId == note.id,
                        ) {
                            if (note.isLocked) {
                                auth.requireUnlocked("Unlock to add to this note") { noteId = note.id }
                            } else {
                                noteId = note.id
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val result = runCatching {
                            if (newNote) importer.saveAsNewNote(content, folderId)
                            else noteId?.let { id -> id to importer.addToNote(content, id) }
                        }.getOrNull()
                        saving = false
                        if (result != null) onSaved(result.first, result.second) else onDismiss()
                    }
                },
                enabled = !saving && (newNote || noteId != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (newNote) "Save as new note" else "Add to note")
            }
        }
    }
}

@Composable
private fun Preview(content: SharedContent) {
    PaperSheet(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = 2.dp, stackDepth = if (content.uris.size > 1) 1 else 0) {
        Column(Modifier.padding(12.dp)) {
            val text = listOfNotNull(content.subject, content.text).joinToString("\n").trim()
            if (text.isNotEmpty()) {
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (content.uris.isNotEmpty()) {
                if (text.isNotEmpty()) Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    content.uris.take(8).forEach { uri ->
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            AsyncImage(model = uri, contentDescription = "Shared item", contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${content.uris.size} item" + if (content.uris.size != 1) "s" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun PickRow(
    icon: ImageVector,
    label: String,
    depth: Int,
    selected: Boolean,
    tintColor: Long? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = (8 + depth * 18).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tintColor?.toColor() ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}
