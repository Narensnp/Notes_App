package com.layers.app.ui.pdf

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.layers.app.security.SecureContent
import com.layers.app.ui.components.EmptyState
import com.layers.app.ui.components.LayerBadge
import com.layers.app.ui.components.MoveToSheet
import com.layers.app.ui.components.TextInputDialog
import com.layers.app.ui.components.appContainer
import com.layers.app.ui.components.layerSharedBounds
import com.layers.app.ui.components.layersViewModel
import com.layers.app.ui.components.pinchToZoom
import com.layers.app.ui.components.rememberHaptics
import com.layers.app.ui.drawing.InkInput
import com.layers.app.ui.drawing.InkLayers
import com.layers.app.ui.drawing.InkTools
import com.layers.app.ui.drawing.ToolPalette
import com.layers.app.ui.lock.LockedMessage
import com.layers.app.ui.lock.rememberAuthenticator
import kotlinx.coroutines.launch

/** Holds zoom so the pinch handler (installed once) always sees current values. */
private class ZoomState {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)

    fun onTransform(pan: Offset, factor: Float) {
        zoom = (zoom * factor).coerceIn(1f, 4f)
        panX = if (zoom == 1f) 0f else panX + pan.x
    }

    fun reset() {
        zoom = 1f
        panX = 0f
    }
}

@Composable
fun PdfViewerScreen(
    noteId: String?,
    blockId: String?,
    externalUri: Uri?,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onSavedExternal: (String) -> Unit,
) {
    val c = appContainer()
    val key = blockId ?: externalUri?.toString() ?: "pdf"
    val vm = layersViewModel(key = "pdf-$key") { PdfViewerViewModel(it, noteId, blockId, externalUri) }
    val ui by vm.ui.collectAsState()
    val tree by remember { c.folders.observeTree() }.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val auth = rememberAuthenticator()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val density = LocalDensity.current.density
    val tools = remember { InkTools() }
    val inputs = remember { HashMap<Int, InkInput>() }
    val zoomState = remember { ZoomState() }

    var annotate by remember { mutableStateOf(false) }
    var thumbnails by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var saveSheet by remember { mutableStateOf(false) }

    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val inApp = noteId != null && blockId != null

    SecureContent(enabled = ui.locked)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.flush() }
    LaunchedEffect(annotate) { if (annotate) zoomState.reset() }

    fun inputFor(page: Int): InkInput = inputs.getOrPut(page) {
        InkInput(
            tools = tools,
            onStroke = { vm.addStroke(page, it) },
            onErase = { x, y, r -> vm.eraseAt(page, x, y, r) },
            onEraseEnd = vm::endErase,
        )
    }

    fun openPageLayer(page: Int) {
        if (!inApp) {
            scope.launch { snackbar.showSnackbar("Save this PDF to Layers first to add layers to its pages.") }
            return
        }
        haptics.longPress()
        scope.launch {
            vm.flush()
            vm.openPageLayer(page)?.let(onOpenNote)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---------- Top bar ----------
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(ui.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (ui.pageSizes.isNotEmpty()) {
                            Text(
                                "Page ${currentPage + 1} of ${ui.pageSizes.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (annotate) {
                        IconButton(onClick = vm::undo, enabled = ui.canUndo) { Icon(Icons.Rounded.Undo, contentDescription = "Undo") }
                        IconButton(onClick = vm::redo, enabled = ui.canRedo) { Icon(Icons.Rounded.Redo, contentDescription = "Redo") }
                    }
                    if (vm.searchSupported && !ui.needsUnlock) {
                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) vm.clearSearch()
                        }) { Icon(Icons.Rounded.Search, contentDescription = "Search in PDF") }
                    }
                    if (inApp && !ui.needsUnlock) {
                        IconButton(onClick = { annotate = !annotate }) {
                            Icon(
                                Icons.Rounded.Gesture,
                                contentDescription = if (annotate) "Stop annotating" else "Annotate with the S Pen",
                                tint = if (annotate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .then(
                                        if (annotate) Modifier
                                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                                            .padding(4.dp)
                                        else Modifier
                                    ),
                            )
                        }
                    }
                    if (ui.isExternal) {
                        IconButton(onClick = { saveSheet = true }, enabled = ui.pageSizes.isNotEmpty()) {
                            Icon(Icons.Rounded.SaveAlt, contentDescription = "Save to folder")
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Go to page…") }, onClick = { menu = false; jumpDialog = true })
                            DropdownMenuItem(
                                text = { Text(if (thumbnails) "Hide page thumbnails" else "Show page thumbnails") },
                                leadingIcon = { Icon(Icons.Rounded.ViewCarousel, null) },
                                onClick = { menu = false; thumbnails = !thumbnails },
                            )
                            if (inApp && ui.annotations.values.any { it.isNotEmpty() }) {
                                DropdownMenuItem(text = { Text("Export annotated PDF") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        snackbar.showSnackbar("Preparing PDF…", duration = androidx.compose.material3.SnackbarDuration.Short)
                                    }
                                    scope.launch {
                                        val intent = runCatching { vm.exportAnnotated() }.getOrNull()
                                        if (intent == null) snackbar.showSnackbar("Export failed") else {
                                            c.lockSession.expectReturn()
                                            context.startActivity(Intent.createChooser(intent, "Share annotated PDF"))
                                        }
                                    }
                                })
                            }
                            if (ui.isExternal) {
                                DropdownMenuItem(text = { Text("Save to folder…") }, onClick = { menu = false; saveSheet = true })
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = searchOpen) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                vm.search(it)
                            },
                            singleLine = true,
                            placeholder = { Text("Find in this PDF") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) IconButton(onClick = { query = ""; vm.clearSearch() }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        val results = ui.searchResults
                        if (results != null) {
                            if (results.isEmpty()) {
                                Text("No matches", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            } else {
                                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    results.forEach { page ->
                                        AssistChip(onClick = { scope.launch { listState.animateScrollToItem(page) } }, label = { Text("Page ${page + 1}") })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- Pages ----------
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                when {
                    ui.needsUnlock -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LockedMessage("Locked PDF", "Unlock to read this PDF.", onUnlock = { auth.unlock() })
                    }
                    ui.error != null -> EmptyState(Icons.Rounded.Layers, "Can't open this PDF", ui.error ?: "")
                    ui.loading -> Box(Modifier.fillMaxSize())
                    else -> {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(if (!annotate) Modifier.pinchToZoom { _, pan, zoom -> zoomState.onTransform(pan, zoom) } else Modifier)
                        ) {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(top = 8.dp, bottom = if (annotate) 180.dp else 96.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        transformOrigin = TransformOrigin(0.5f, 0f)
                                        scaleX = zoomState.zoom
                                        scaleY = zoomState.zoom
                                        translationX = zoomState.panX
                                    },
                            ) {
                                itemsIndexed(ui.pageSizes, key = { i, _ -> i }) { index, size ->
                                    PdfPage(
                                        index = index,
                                        size = size,
                                        vm = vm,
                                        zoom = zoomState.zoom,
                                        strokes = ui.annotations[index].orEmpty(),
                                        input = if (annotate) inputFor(index) else null,
                                        density = density,
                                        sharedKey = if (blockId != null) "anchor-$blockId-p$index" else null,
                                        hasLayer = ui.pageLayers.containsKey(index),
                                        onLongPress = { openPageLayer(index) },
                                        onOpenLayer = { ui.pageLayers[index]?.let(onOpenNote) },
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------- Bottom: tools or thumbnails ----------
                androidx.compose.animation.AnimatedVisibility(
                    visible = annotate,
                    enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                ) {
                    ToolPalette(tools)
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = thumbnails && !annotate && ui.pageSizes.isNotEmpty(),
                    enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                            .padding(vertical = 10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(ui.pageSizes) { index, size ->
                            Thumbnail(index, size, vm, selected = index == currentPage) {
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        }
    }

    if (jumpDialog) {
        TextInputDialog(
            title = "Go to page",
            label = "Page (1–${ui.pageSizes.size})",
            confirmText = "Go",
            onDismiss = { jumpDialog = false },
            onConfirm = { text ->
                jumpDialog = false
                text.toIntOrNull()?.let { n ->
                    scope.launch { listState.animateScrollToItem((n - 1).coerceIn(0, (ui.pageSizes.size - 1).coerceAtLeast(0))) }
                }
            },
        )
    }
    if (saveSheet) {
        MoveToSheet(
            title = "Save “${ui.name}” to…",
            tree = tree,
            currentFolderId = "__none__",
            onDismiss = { saveSheet = false },
            onPick = { folder ->
                saveSheet = false
                scope.launch {
                    val id = runCatching { vm.saveExternalTo(folder) }.getOrNull()
                    if (id == null) snackbar.showSnackbar("Couldn't save this PDF") else onSavedExternal(id)
                }
            },
        )
    }
}

@Composable
private fun PdfPage(
    index: Int,
    size: Pair<Int, Int>,
    vm: PdfViewerViewModel,
    zoom: Float,
    strokes: List<com.layers.app.data.model.InkStroke>,
    input: InkInput?,
    density: Float,
    sharedKey: String?,
    hasLayer: Boolean,
    onLongPress: () -> Unit,
    onOpenLayer: () -> Unit,
) {
    val (pw, ph) = size
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        val bucket = when {
            zoom < 1.5f -> 1.5f
            zoom < 2.5f -> 2.5f
            else -> 3.5f
        }
        val renderWidth = (widthPx * bucket).toInt().coerceAtMost(3000)
        val bitmap by produceState<ImageBitmap?>(null, index, renderWidth) { value = vm.bitmap(index, renderWidth) }
        val longPress by rememberUpdatedState(onLongPress)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(pw.toFloat() / ph.coerceAtLeast(1))
                .then(if (sharedKey != null) Modifier.layerSharedBounds(sharedKey) else Modifier)
                .shadow(3.dp, RoundedCornerShape(3.dp))
                .background(Color.White)
                .semantics { contentDescription = "Page ${index + 1}" }
                .then(
                    if (input == null) Modifier.pointerInput(index) { detectTapGestures(onLongPress = { longPress() }) }
                    else Modifier
                ),
        ) {
            bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            InkLayers(
                strokes = strokes,
                input = input,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (input != null) Modifier.pointerInteropFilter { ev ->
                            input.onEvent(ev, { x, y -> Offset(x / widthPx, y / widthPx) }, widthPx.toFloat(), density, fingersDraw = true)
                        } else Modifier
                    ),
            )
            if (hasLayer) {
                LayerBadge(
                    count = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onOpenLayer),
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(index: Int, size: Pair<Int, Int>, vm: PdfViewerViewModel, selected: Boolean, onClick: () -> Unit) {
    val (pw, ph) = size
    val bitmap by produceState<ImageBitmap?>(null, index) { value = vm.bitmap(index, 160) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(64.dp)
                .aspectRatio(pw.toFloat() / ph.coerceAtLeast(1))
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(if (selected) 2.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .semantics { contentDescription = "Go to page ${index + 1}" },
        ) {
            bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.height(4.dp))
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
