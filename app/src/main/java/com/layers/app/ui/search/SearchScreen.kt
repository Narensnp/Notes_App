package com.layers.app.ui.search

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layers.app.AppContainer
import com.layers.app.data.db.TagEntity
import com.layers.app.data.model.FolderNode
import com.layers.app.data.model.HitKind
import com.layers.app.data.model.SearchFilters
import com.layers.app.data.model.SearchHit
import com.layers.app.ui.components.EmptyState
import com.layers.app.ui.components.PaperSheet
import com.layers.app.ui.components.layersViewModel
import com.layers.app.ui.theme.Paper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class DateRange(val label: String) { ANY("Any time"), TODAY("Today"), WEEK("Past 7 days"), MONTH("Past 30 days"), YEAR("Past year") }

data class SearchUi(
    val results: List<SearchHit> = emptyList(),
    val searched: Boolean = false,
)

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val filters = MutableStateFlow(SearchFilters())
    val dateRange = MutableStateFlow(DateRange.ANY)

    val recent: StateFlow<List<String>> = c.settings.recentSearches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folders: StateFlow<List<FolderNode>> = c.folders.observeTree().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags: StateFlow<List<TagEntity>> = c.tags.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query.debounce(180), filters, dateRange) { q, f, d -> Triple(q, f, d) }
                .mapLatest { (q, f, d) ->
                    val withDates = f.copy(fromMillis = fromFor(d))
                    val active = q.isNotBlank() || withDates.isActive
                    SearchUi(results = if (active) c.search.search(q, withDates) else emptyList(), searched = active)
                }
                .collect { _ui.value = it }
        }
    }

    private fun fromFor(range: DateRange): Long? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val day = when (range) {
            DateRange.ANY -> return null
            DateRange.TODAY -> today
            DateRange.WEEK -> today.minusDays(7)
            DateRange.MONTH -> today.minusDays(30)
            DateRange.YEAR -> today.minusYears(1)
        }
        return day.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun terms(): List<String> = c.search.terms(query.value)

    fun saveQuery() = viewModelScope.launch { c.settings.addRecentSearch(query.value) }
    fun clearRecent() = viewModelScope.launch { c.settings.clearRecentSearches() }
}

@Composable
fun SearchScreen(onOpenNote: (noteId: String, blockId: String?) -> Unit, autoFocus: Boolean = false) {
    val vm = layersViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsState()
    val filters by vm.filters.collectAsState()
    val dateRange by vm.dateRange.collectAsState()
    val ui by vm.ui.collectAsState()
    val recent by vm.recent.collectAsState()
    val folders by vm.folders.collectAsState()
    val tags by vm.tags.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    placeholder = { Text("Search notes, tasks, photos, PDFs…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.saveQuery() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .focusRequester(focus),
                )
                FilterRow(
                    filters = filters,
                    dateRange = dateRange,
                    folders = folders,
                    tags = tags,
                    onFilters = { vm.filters.value = it },
                    onDate = { vm.dateRange.value = it },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            when {
                !ui.searched && recent.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.clearRecent() }) { Text("Clear") }
                        }
                    }
                    items(recent) { r ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.query.value = r }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(14.dp))
                            Text(r, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                !ui.searched -> EmptyState(
                    icon = Icons.Rounded.Search,
                    title = "Search every layer",
                    message = "Titles, text, checklists, words inside photos and PDFs, and every sub-layer. Locked notes stay out of search.",
                )
                ui.results.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = "Nothing found",
                    message = "Try fewer words, or loosen the filters.",
                )
                else -> {
                    val terms = vm.terms()
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Text(
                                "${ui.results.size} result" + if (ui.results.size != 1) "s" else "",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(ui.results, key = { it.noteId }) { hit ->
                            ResultCard(hit, terms) {
                                vm.saveQuery()
                                onOpenNote(hit.noteId, hit.blockId)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    filters: SearchFilters,
    dateRange: DateRange,
    folders: List<FolderNode>,
    tags: List<TagEntity>,
    onFilters: (SearchFilters) -> Unit,
    onDate: (DateRange) -> Unit,
) {
    var folderMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            val folderName = folders.firstOrNull { it.folder.id == filters.folderId }?.folder?.name
            FilterChip(
                selected = filters.folderId != null,
                onClick = { folderMenu = true },
                label = { Text(folderName ?: "Folder") },
                leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                DropdownMenuItem(text = { Text("All folders") }, onClick = { folderMenu = false; onFilters(filters.copy(folderId = null)) })
                folders.forEach { node ->
                    DropdownMenuItem(
                        text = { Text("   ".repeat(node.depth) + node.folder.name) },
                        onClick = { folderMenu = false; onFilters(filters.copy(folderId = node.folder.id)) },
                    )
                }
            }
        }
        Box {
            val tagName = tags.firstOrNull { it.id == filters.tagId }?.name
            FilterChip(
                selected = filters.tagId != null,
                onClick = { tagMenu = true },
                label = { Text(tagName?.let { "#$it" } ?: "Tag") },
                leadingIcon = { Icon(Icons.Rounded.LocalOffer, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                DropdownMenuItem(text = { Text("Any tag") }, onClick = { tagMenu = false; onFilters(filters.copy(tagId = null)) })
                tags.forEach { tag ->
                    DropdownMenuItem(text = { Text("#${tag.name}") }, onClick = { tagMenu = false; onFilters(filters.copy(tagId = tag.id)) })
                }
            }
        }
        FilterChip(
            selected = filters.hasTasks,
            onClick = { onFilters(filters.copy(hasTasks = !filters.hasTasks)) },
            label = { Text("Has tasks") },
            leadingIcon = { Icon(Icons.Rounded.CheckBox, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = filters.hasImages,
            onClick = { onFilters(filters.copy(hasImages = !filters.hasImages)) },
            label = { Text("Has images") },
            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = filters.hasPdfs,
            onClick = { onFilters(filters.copy(hasPdfs = !filters.hasPdfs)) },
            label = { Text("Has PDFs") },
            leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        Box {
            FilterChip(
                selected = dateRange != DateRange.ANY,
                onClick = { dateMenu = true },
                label = { Text(dateRange.label) },
                leadingIcon = { Icon(Icons.Rounded.DateRange, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                DateRange.entries.forEach { r ->
                    DropdownMenuItem(text = { Text(r.label) }, onClick = { dateMenu = false; onDate(r) })
                }
            }
        }
    }
}

@Composable
private fun ResultCard(hit: SearchHit, terms: List<String>, onClick: () -> Unit) {
    val highlight = SpanStyle(background = Paper.colors.highlight, fontWeight = FontWeight.SemiBold)
    PaperSheet(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = 2.dp,
        stackDepth = if (hit.layerDepth > 0) 1 else 0,
        onClick = onClick,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    highlightTerms(hit.title, terms, highlight),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                foundLabel(hit.kind)?.let { (icon, label) ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (hit.snippet.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    snippetText(hit.snippet, terms, highlight),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hit.layerDepth > 0) {
                    Icon(Icons.Rounded.Layers, contentDescription = "Inside a sub-layer", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    hit.breadcrumb,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(hit.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private fun foundLabel(kind: HitKind): Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? = when (kind) {
    HitKind.IMAGE -> Icons.Rounded.Image to "Found in image"
    HitKind.PDF -> Icons.Rounded.PictureAsPdf to "Found in PDF"
    HitKind.TASK -> Icons.Rounded.CheckBox to "In a task"
    else -> null
}

/** Turns the FTS snippet markers [[ ]] into highlighted text (also highlights the query words). */
private fun snippetText(snippet: String, terms: List<String>, style: SpanStyle): AnnotatedString {
    if (!snippet.contains("[[")) return highlightTerms(snippet, terms, style)
    return buildAnnotatedString {
        var rest = snippet
        while (true) {
            val start = rest.indexOf("[[")
            if (start < 0) {
                append(rest)
                break
            }
            val end = rest.indexOf("]]", start + 2)
            if (end < 0) {
                append(rest.replace("[[", ""))
                break
            }
            append(rest.substring(0, start))
            withStyle(style) { append(rest.substring(start + 2, end)) }
            rest = rest.substring(end + 2)
        }
    }
}

private fun highlightTerms(text: String, terms: List<String>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    if (terms.isEmpty()) return@buildAnnotatedString
    val lower = text.lowercase()
    for (t in terms) {
        var i = lower.indexOf(t)
        while (i >= 0 && i + t.length <= text.length) {
            addStyle(style, i, i + t.length)
            i = lower.indexOf(t, i + t.length)
        }
    }
}
