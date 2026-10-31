package com.layers.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.layers.app.data.files.ScreenshotFinder
import com.layers.app.data.model.Block
import com.layers.app.data.model.ChecklistContent
import com.layers.app.data.model.ImageContent
import com.layers.app.data.model.TextContent
import com.layers.app.data.model.newId
import com.layers.app.navigation.PendingAction
import com.layers.app.security.SecureContent
import com.layers.app.share.SharedContent
import com.layers.app.ui.calendar.CalendarScreen
import com.layers.app.ui.components.LocalNavAnimatedVisibilityScope
import com.layers.app.ui.components.LocalSharedTransitionScope
import com.layers.app.ui.components.appContainer
import com.layers.app.ui.drawing.DrawingScreen
import com.layers.app.ui.editor.EditorScreen
import com.layers.app.ui.folders.FoldersScreen
import com.layers.app.ui.lock.AppLockGate
import com.layers.app.ui.outline.OutlineScreen
import com.layers.app.ui.pdf.PdfViewerScreen
import com.layers.app.ui.search.SearchScreen
import com.layers.app.ui.settings.BackupScreen
import com.layers.app.ui.settings.SettingsScreen
import com.layers.app.ui.share.SaveToLayersSheet
import com.layers.app.ui.tags.TagNotesScreen
import com.layers.app.ui.tags.TagsScreen
import com.layers.app.ui.trash.TrashScreen
import com.layers.app.ui.theme.LayersTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

object Routes {
    const val NOTES = "notes"
    const val CALENDAR = "calendar"
    const val SEARCH = "search?focus={focus}"
    const val SEARCH_TAB = "search"
    const val SETTINGS = "settings"
    const val NOTE = "note/{noteId}?block={block}&focus={focus}"
    const val DRAW = "draw/{noteId}/{blockId}"
    const val PDF = "pdf/{noteId}/{blockId}"
    const val PDF_EXTERNAL = "pdfExternal"
    const val OUTLINE = "outline/{noteId}"
    const val TRASH = "trash"
    const val TAGS = "tags"
    const val TAG = "tag/{tagId}"
    const val BACKUP = "backup"

    fun note(id: String, block: String? = null, focus: String? = null): String = buildString {
        append("note/").append(Uri.encode(id))
        val params = buildList {
            if (block != null) add("block=" + Uri.encode(block))
            if (focus != null) add("focus=" + Uri.encode(focus))
        }
        if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
    }

    fun draw(noteId: String, blockId: String) = "draw/${Uri.encode(noteId)}/${Uri.encode(blockId)}"
    fun pdf(noteId: String, blockId: String) = "pdf/${Uri.encode(noteId)}/${Uri.encode(blockId)}"
    fun outline(noteId: String) = "outline/${Uri.encode(noteId)}"
    fun tag(tagId: String) = "tag/${Uri.encode(tagId)}"
}

private data class Tab(val route: String, val pattern: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.NOTES, Routes.NOTES, "Notes", Icons.Rounded.Layers),
    Tab(Routes.CALENDAR, Routes.CALENDAR, "Calendar", Icons.Rounded.CalendarToday),
    Tab(Routes.SEARCH_TAB, Routes.SEARCH, "Search", Icons.Rounded.Search),
    Tab(Routes.SETTINGS, Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

private fun NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// ---------- Transitions: notes dive into their layers and zoom back out ----------

private val springFloat = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

private fun isNote(entry: NavBackStackEntry) = entry.destination.route == Routes.NOTE

private val diveEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isNote(initialState) && isNote(targetState)) {
        fadeIn(springFloat) + scaleIn(springFloat, initialScale = 0.9f)
    } else {
        fadeIn(springFloat) + scaleIn(springFloat, initialScale = 0.96f)
    }
}
private val diveExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isNote(initialState) && isNote(targetState)) {
        fadeOut(spring(stiffness = Spring.StiffnessMedium)) + scaleOut(springFloat, targetScale = 1.08f)
    } else {
        fadeOut(spring(stiffness = Spring.StiffnessMedium))
    }
}
private val divePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isNote(initialState) && isNote(targetState)) {
        fadeIn(springFloat) + scaleIn(springFloat, initialScale = 1.08f)
    } else {
        fadeIn(springFloat)
    }
}
private val divePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isNote(initialState) && isNote(targetState)) {
        fadeOut(spring(stiffness = Spring.StiffnessMedium)) + scaleOut(springFloat, targetScale = 0.9f)
    } else {
        fadeOut(spring(stiffness = Spring.StiffnessMedium)) + scaleOut(springFloat, targetScale = 0.96f)
    }
}

/** The root of the UI: theme, optional app lock, navigation and incoming actions. */
@Composable
fun LayersApp(pending: StateFlow<PendingAction?>, onConsumed: () -> Unit) {
    val c = appContainer()
    val settings by c.settings.settings.collectAsState(initial = null)
    val s = settings
    if (s == null) {
        // Settings not read yet: show plain paper (never flash content before app lock applies).
        LayersTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) }
        return
    }
    LayersTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
        val appUnlocked by c.lockSession.appUnlocked.collectAsState()
        SecureContent(enabled = s.appLock)
        if (s.appLock && !appUnlocked) {
            AppLockGate()
        } else {
            MainContent(pending, onConsumed)
        }
    }
}

@Composable
private fun MainContent(pending: StateFlow<PendingAction?>, onConsumed: () -> Unit) {
    val c = appContainer()
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val showBar = tabs.any { tab -> current?.hierarchy?.any { it.route == tab.pattern } == true }
    var share by remember { mutableStateOf<SharedContent?>(null) }
    var screenshotRationale by remember { mutableStateOf(false) }

    fun openNote(id: String, block: String? = null, focus: String? = null) = nav.navigate(Routes.note(id, block, focus))

    suspend fun createImageNote(uri: Uri) {
        val imported = runCatching { c.attachments.importUri(uri, "Screenshot") }.getOrNull() ?: return
        val (w, h) = c.attachments.imageSize(imported.relativePath, false)
        val id = c.notes.createNote(
            folderId = null,
            blocks = listOf(Block(newId(), ImageContent(imported.relativePath, w, h)), Block(newId(), TextContent())),
        )
        openNote(id, focus = "end")
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { createImageNote(uri) }
    }
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        scope.launch {
            val uri = if (c.screenshots.access() != ScreenshotFinder.Access.NONE) c.screenshots.latestScreenshot() else null
            if (uri != null) createImageNote(uri)
            else photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    fun noteFromScreenshot() {
        if (c.screenshots.access() == ScreenshotFinder.Access.NONE) {
            screenshotRationale = true
            return
        }
        scope.launch {
            val uri = c.screenshots.latestScreenshot()
            if (uri != null) createImageNote(uri)
            else photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    // Shortcuts, tiles, the widget, notifications and other apps. Collected (not keyed on the value)
    // so marking an action as consumed never cancels the work it started.
    suspend fun handleAction(a: PendingAction) {
        when (a) {
            PendingAction.NewNote -> openNote(c.notes.createNote(null), focus = "title")
            PendingAction.NewTask -> {
                val zone = ZoneId.systemDefault()
                val block = Block(newId(), ChecklistContent(dueAt = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()))
                openNote(c.notes.createNote(null, "", listOf(block)), focus = block.id)
            }
            PendingAction.Search -> nav.navigate("search?focus=true") {
                popUpTo(nav.graph.findStartDestination().id)
                launchSingleTop = true
            }
            PendingAction.ScreenshotNote -> noteFromScreenshot()
            is PendingAction.OpenNote -> openNote(a.noteId, block = a.blockId)
            is PendingAction.Share -> {
                share = SharedContent(a.text, a.subject, a.uris)
            }
            is PendingAction.ViewPdf -> {
                c.navRequests.externalPdf = a.uri
                nav.navigate(Routes.PDF_EXTERNAL)
            }
        }
    }
    LaunchedEffect(pending) {
        // Wait until the navigation graph is in place before acting on anything.
        nav.currentBackStackEntryFlow.first()
        pending.filterNotNull().collect { a ->
            onConsumed()
            handleAction(a)
        }
    }

    fun goToFolder(folderId: String?) {
        c.navRequests.showFolder(folderId)
        if (!nav.popBackStack(Routes.NOTES, inclusive = false)) nav.navigateTab(Routes.NOTES)
    }

    fun openParent(parentId: String, blockId: String?) {
        val previous = nav.previousBackStackEntry
        if (previous != null && isNote(previous) && previous.arguments?.getString("noteId") == parentId) {
            nav.popBackStack()
        } else {
            openNote(parentId, block = blockId)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it } + fadeOut(),
            ) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { tab ->
                        val selected = current?.hierarchy?.any { it.route == tab.pattern } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val bottom = padding.calculateBottomPadding()
        SharedTransitionLayout(
            Modifier
                .fillMaxSize()
                .padding(bottom = bottom)
                .consumeWindowInsets(PaddingValues(bottom = bottom))
        ) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.NOTES,
                    enterTransition = diveEnter,
                    exitTransition = diveExit,
                    popEnterTransition = divePopEnter,
                    popExitTransition = divePopExit,
                ) {
                    composable(Routes.NOTES) {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            FoldersScreen(
                                onOpenNote = { openNote(it) },
                                onOpenTrash = { nav.navigate(Routes.TRASH) },
                                onOpenTags = { nav.navigate(Routes.TAGS) },
                            )
                        }
                    }
                    composable(Routes.CALENDAR) {
                        CalendarScreen(onOpenTask = { noteId, blockId -> openNote(noteId, block = blockId) })
                    }
                    composable(
                        Routes.SEARCH,
                        arguments = listOf(navArgument("focus") { type = NavType.BoolType; defaultValue = false }),
                    ) { entry ->
                        SearchScreen(
                            onOpenNote = { noteId, blockId -> openNote(noteId, block = blockId) },
                            autoFocus = entry.arguments?.getBoolean("focus") == true,
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            onOpenTrash = { nav.navigate(Routes.TRASH) },
                            onOpenTags = { nav.navigate(Routes.TAGS) },
                            onOpenBackup = { nav.navigate(Routes.BACKUP) },
                        )
                    }
                    composable(
                        Routes.NOTE,
                        arguments = listOf(
                            navArgument("noteId") { type = NavType.StringType },
                            navArgument("block") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entry ->
                        val noteId = entry.arguments?.getString("noteId") ?: return@composable
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            EditorScreen(
                                noteId = noteId,
                                scrollToBlock = entry.arguments?.getString("block"),
                                focus = entry.arguments?.getString("focus"),
                                onBack = { nav.popBackStack() },
                                onOpenNote = { id, block -> openNote(id, block) },
                                onOpenParent = ::openParent,
                                onOpenDrawing = { n, b -> nav.navigate(Routes.draw(n, b)) },
                                onOpenPdf = { n, b -> nav.navigate(Routes.pdf(n, b)) },
                                onOpenOutline = { nav.navigate(Routes.outline(it)) },
                                onOpenFolder = ::goToFolder,
                            )
                        }
                    }
                    composable(
                        Routes.DRAW,
                        arguments = listOf(
                            navArgument("noteId") { type = NavType.StringType },
                            navArgument("blockId") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        DrawingScreen(
                            noteId = entry.arguments?.getString("noteId") ?: return@composable,
                            blockId = entry.arguments?.getString("blockId") ?: return@composable,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(
                        Routes.PDF,
                        arguments = listOf(
                            navArgument("noteId") { type = NavType.StringType },
                            navArgument("blockId") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            PdfViewerScreen(
                                noteId = entry.arguments?.getString("noteId"),
                                blockId = entry.arguments?.getString("blockId"),
                                externalUri = null,
                                onBack = { nav.popBackStack() },
                                onOpenNote = { openNote(it) },
                                onSavedExternal = { openNote(it) },
                            )
                        }
                    }
                    composable(Routes.PDF_EXTERNAL) {
                        val uri = remember { c.navRequests.externalPdf }
                        if (uri == null) {
                            LaunchedEffect(Unit) { nav.popBackStack() }
                        } else {
                            PdfViewerScreen(
                                noteId = null,
                                blockId = null,
                                externalUri = uri,
                                onBack = { nav.popBackStack() },
                                onOpenNote = { openNote(it) },
                                onSavedExternal = { id ->
                                    nav.popBackStack()
                                    openNote(id)
                                },
                            )
                        }
                    }
                    composable(
                        Routes.OUTLINE,
                        arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
                    ) { entry ->
                        OutlineScreen(
                            noteId = entry.arguments?.getString("noteId") ?: return@composable,
                            onBack = { nav.popBackStack() },
                            onOpenNote = { openNote(it) },
                        )
                    }
                    composable(Routes.TRASH) { TrashScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.TAGS) {
                        TagsScreen(onBack = { nav.popBackStack() }, onOpenTag = { nav.navigate(Routes.tag(it)) })
                    }
                    composable(
                        Routes.TAG,
                        arguments = listOf(navArgument("tagId") { type = NavType.StringType }),
                    ) { entry ->
                        TagNotesScreen(
                            tagId = entry.arguments?.getString("tagId") ?: return@composable,
                            onBack = { nav.popBackStack() },
                            onOpenNote = { openNote(it) },
                        )
                    }
                    composable(Routes.BACKUP) { BackupScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    share?.let { content ->
        SaveToLayersSheet(
            content = content,
            onDismiss = { share = null },
            onSaved = { noteId, focusBlock ->
                share = null
                openNote(noteId, focus = focusBlock)
            },
        )
    }

    if (screenshotRationale) {
        AlertDialog(
            onDismissRequest = { screenshotRationale = false },
            title = { Text("Use your latest screenshot?") },
            text = {
                Text("Layers needs access to your photos to find the newest screenshot. You can allow only selected photos, or pick the screenshot yourself.")
            },
            confirmButton = {
                TextButton(onClick = {
                    screenshotRationale = false
                    c.lockSession.expectReturn()
                    mediaPermission.launch(c.screenshots.permissionsToRequest())
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    screenshotRationale = false
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Pick it myself") }
            },
        )
    }
}
