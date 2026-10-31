package com.layers.app.ui.outline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.layers.app.data.db.NoteEntity
import com.layers.app.data.model.OutlineNode
import com.layers.app.data.model.isLocked
import com.layers.app.data.repo.NoteRepository
import com.layers.app.ui.components.EmptyState
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.appContainer

private data class OutlineData(val root: NoteEntity?, val nodes: List<OutlineNode>)

/** The tree of every sub-layer under a note, at any depth. */
@Composable
fun OutlineScreen(noteId: String, onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val c = appContainer()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val data by produceState<OutlineData?>(null, noteId, refresh) {
        value = OutlineData(c.notes.getNote(noteId), c.notes.outline(noteId))
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Column {
                    Text("Layers outline", style = MaterialTheme.typography.titleLarge)
                    data?.root?.let {
                        Text(
                            NoteRepository.displayTitle(it),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    ) { padding ->
        val d = data
        when {
            d == null -> Box(Modifier.fillMaxSize().padding(padding))
            d.nodes.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Layers,
                title = "No layers yet",
                message = "Long-press a photo, drawing, PDF, recording or file in this note to add a layer underneath it.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 48.dp),
            ) {
                item {
                    d.root?.let { root ->
                        PaperSheet(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            shape = RoundedCornerShape(14.dp),
                            stackDepth = 2,
                            onClick = { onOpenNote(root.id) },
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(NoteRepository.displayTitle(root), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                items(d.nodes, key = { it.note.id }) { node ->
                    OutlineRow(node) { onOpenNote(node.note.id) }
                }
            }
        }
    }
}

@Composable
private fun OutlineRow(node: OutlineNode, onClick: () -> Unit) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val indent = 22.dp
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${NoteRepository.displayTitle(node.note)}, under ${node.anchorLabel}, level ${node.depth + 1}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tree guide lines, one per level.
        Box(
            Modifier
                .width(indent * (node.depth + 1))
                .height(60.dp)
        ) {
            Canvas(Modifier.fillMaxHeight().width(indent * (node.depth + 1))) {
                val step = indent.toPx()
                for (level in 0..node.depth) {
                    val x = step * level + step / 2
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                }
                val x = step * node.depth + step / 2
                drawLine(lineColor, Offset(x, size.height / 2), Offset(x + step / 2, size.height / 2), strokeWidth = 1.5f)
            }
        }
        Icon(
            iconFor(node.anchorLabel),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.note.isLocked) {
                    Icon(Icons.Rounded.Lock, contentDescription = "Locked", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    NoteRepository.displayTitle(node.note),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                node.anchorLabel + if (node.childCount > 0) " · ${node.childCount} below" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun iconFor(label: String): ImageVector = when {
    label.contains("Pin") -> Icons.Rounded.PushPin
    label.startsWith("Photo") -> Icons.Rounded.Image
    label.startsWith("Drawing") -> Icons.Rounded.Gesture
    label.startsWith("PDF") -> Icons.Rounded.PictureAsPdf
    label.startsWith("Recording") -> Icons.Rounded.Mic
    label.startsWith("File") -> Icons.Rounded.AttachFile
    else -> Icons.Rounded.Layers
}
