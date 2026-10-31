package com.layers.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layers.app.data.db.FolderEntity
import com.layers.app.data.model.FolderNode
import com.layers.app.data.model.FolderPalette

/** Create or edit a folder: name, accent color and icon. */
@Composable
fun FolderEditDialog(
    title: String,
    initial: FolderEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Long, icon: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableLongStateOf(initial?.color ?: FolderPalette.default) }
    var icon by remember { mutableStateOf(initial?.icon ?: "folder") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                ColorPicker(selected = color, onSelect = { it?.let { c -> color = c } })
                Spacer(Modifier.height(16.dp))
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FolderIcons.all.forEach { (key, vector) ->
                        val isSelected = key == icon
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) color.toColor().copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .clickable(role = Role.RadioButton) { icon = key }
                                .semantics {
                                    contentDescription = "Icon $key"
                                    selected = isSelected
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                vector,
                                contentDescription = null,
                                tint = if (isSelected) color.toColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), color, icon) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "Move to…": the whole folder tree, indented by depth. [disabledIds] can't be chosen (a folder
 * can't move into itself or its own sub-folders).
 */
@Composable
fun MoveToSheet(
    title: String,
    tree: List<FolderNode>,
    currentFolderId: String?,
    disabledIds: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                item {
                    FolderRow(
                        name = "Home",
                        depth = 0,
                        icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                        current = currentFolderId == null,
                        enabled = true,
                        locked = false,
                        onClick = { onPick(null) },
                    )
                }
                items(tree, key = { it.folder.id }) { node ->
                    FolderRow(
                        name = node.folder.name,
                        depth = node.depth + 1,
                        icon = {
                            Icon(
                                FolderIcons.icon(node.folder.icon),
                                contentDescription = null,
                                tint = node.folder.color.toColor(),
                            )
                        },
                        current = currentFolderId == node.folder.id,
                        enabled = node.folder.id !in disabledIds,
                        locked = node.folder.locked,
                        onClick = { onPick(node.folder.id) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    depth: Int,
    icon: @Composable () -> Unit,
    current: Boolean,
    enabled: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !current, role = Role.Button, onClick = onClick)
            .padding(start = (24 + depth * 20).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(16.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            Icon(Icons.Rounded.Lock, contentDescription = "Locked", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        if (current) {
            Icon(Icons.Rounded.Check, contentDescription = "Current folder", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
