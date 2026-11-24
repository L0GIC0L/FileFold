package io.github.l0gic0l.filefold.ui.browser

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.filefold.archive.ArchiveFormat
import io.github.l0gic0l.filefold.core.FileItem
import io.github.l0gic0l.filefold.core.FileSearch
import io.github.l0gic0l.filefold.core.SearchQuery
import io.github.l0gic0l.filefold.core.SortBy
import io.github.l0gic0l.filefold.core.SortGroup
import io.github.l0gic0l.filefold.util.Format
import io.github.l0gic0l.filefold.ui.detail.DetailTarget
import io.github.l0gic0l.filefold.ui.settings.SettingsActivity
import java.io.File

/** The file list: top bar, tabs, breadcrumbs, contents and the paste bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPane(vm: BrowserViewModel, showMenuButton: Boolean, onMenu: () -> Unit) {
    val location = vm.location
    val inArchive = location is Location.InArchive
    val title = remember(location, vm.places) { vm.tabLabel(location) }

    // Selection and search close on back; going up a folder is the predictive gesture in FolderPages.
    BackHandler(enabled = !LocalBackTakenOver.current && (vm.selectionMode || vm.search != null)) { vm.back() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            when {
                vm.selectionMode -> SelectionBar(vm)
                vm.search != null -> SearchBar(vm, title)
                else -> TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        when {
                            showMenuButton -> IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Places") }
                            vm.canNavigateUp -> IconButton(onClick = { vm.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up one level")
                            }
                        }
                    },
                    actions = { BrowserActions(vm, inArchive) },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = vm.clipboard != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) { vm.clipboard?.let { PasteBar(vm, it) } }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            PaneArea(vm, bottomPadding = padding.calculateBottomPadding())
            // Actions for the selection sit at the bottom, in reach of the thumb.
            AnimatedVisibility(
                visible = vm.selectionMode,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = padding.calculateBottomPadding() + if (vm.isSplit) 24.dp else 16.dp),
            ) { SelectionToolbar(vm) }
        }
    }
}

/** Opens the settings screen. */
fun openSettings(context: Context) = context.startActivity(Intent(context, SettingsActivity::class.java))

@Composable
private fun BrowserActions(vm: BrowserViewModel, inArchive: Boolean) {
    val context = LocalContext.current
    val listed = vm.location is Location.Disk || vm.location is Location.InArchive
    IconButton(onClick = vm::openSearch) { Icon(Icons.Default.Search, "Search") }
    if (listed) SortMenu(vm)
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(text: String, icon: ImageVector, action: () -> Unit) =
                DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = { open = false; action() })

            when (val location = vm.location) {
                is Location.InArchive -> item("Extract all…", Icons.Default.Unarchive) { vm.promptExtract(location.archivePath) }
                is Location.Disk -> {
                    item("New folder", Icons.Default.CreateNewFolder) { vm.dialog = BrowserDialog.NewFolder }
                    item("New text file", Icons.AutoMirrored.Filled.NoteAdd) { vm.dialog = BrowserDialog.NewFile }
                    item("Auto-sort into folders…", Icons.Default.AutoAwesome) { vm.dialog = BrowserDialog.AutoSort(File(location.path)) }
                    if (vm.sizeView) {
                        item("Hide storage usage", Icons.Default.DataUsage) { vm.showSizes(false) }
                    } else {
                        item("Storage usage", Icons.Default.DataUsage) { vm.showSizes(true) }
                    }
                    if (vm.isFavorite(location.path)) {
                        item("Remove from favourites", Icons.Default.Star) { vm.toggleFavorite(location.path) }
                    } else {
                        item("Add to favourites", Icons.Default.StarBorder) { vm.toggleFavorite(location.path) }
                    }
                }
                Location.Trash -> item("Empty trash…", Icons.Default.DeleteForever) {
                    if (vm.items.isEmpty()) vm.message("The trash is already empty") else vm.dialog = BrowserDialog.EmptyTrash
                }
                Location.Home -> Unit
            }
            if (listed) {
                if (vm.settings.gridView) {
                    item("Show as list", Icons.AutoMirrored.Filled.ViewList) { vm.setGridView(false) }
                } else {
                    item("Show as grid", Icons.Default.GridView) { vm.setGridView(true) }
                }
            }
            item("New tab", Icons.Default.Tab) { vm.newTab() }
            if (vm.isSplit) {
                item("Close split view", Icons.Default.CloseFullscreen) { vm.closeSide(vm.panes.first { it !== vm.pane }) }
            } else {
                item("Split view", Icons.Default.Splitscreen) { vm.openSplit() }
            }
            if (vm.location !is Location.Home) item("Select all", Icons.Default.SelectAll) { vm.selectAll() }
            item("Refresh", Icons.Default.Refresh) { vm.refresh() }
            item("Settings", Icons.Default.Settings) { openSettings(context) }
            if (vm.location is Location.Disk) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Show hidden files") },
                    leadingIcon = { Icon(Icons.Default.Visibility, null) },
                    trailingIcon = { Checkbox(checked = vm.settings.showHidden, onCheckedChange = null) },
                    onClick = { vm.setShowHidden(!vm.settings.showHidden) },
                )
            }
        }
    }
}

/** Sort order, saved and applied automatically to every folder you open. */
@Composable
private fun SortMenu(vm: BrowserViewModel) {
    var open by remember { mutableStateOf(false) }
    val sort = vm.settings.sort
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SortBy.entries.forEach { by ->
                DropdownMenuItem(
                    text = { Text(by.label) },
                    leadingIcon = { RadioButton(selected = sort.by == by, onClick = null) },
                    onClick = { vm.setSort(sort.copy(by = by)) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (sort.ascending) "Ascending" else "Descending") },
                leadingIcon = { Icon(if (sort.ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null) },
                onClick = { vm.setSort(sort.copy(ascending = !sort.ascending)) },
            )
            DropdownMenuItem(
                text = { Text("Folders first") },
                trailingIcon = { Checkbox(checked = sort.foldersFirst, onCheckedChange = null) },
                onClick = { vm.setSort(sort.copy(foldersFirst = !sort.foldersFirst)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(vm: BrowserViewModel, folderName: String) {
    val focus = remember { FocusRequester() }
    val query = vm.search ?: return
    // Opened from "Recent files" there is already something to show, so the keyboard stays down.
    LaunchedEffect(Unit) { if (!query.isActive) focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = vm::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
        title = {
            TextField(
                value = query.text,
                onValueChange = { vm.updateSearch(query.copy(text = it)) },
                placeholder = { Text("Search in $folderName", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.text.isNotEmpty()) {
                IconButton(onClick = { vm.updateSearch(query.copy(text = "")) }) { Icon(Icons.Default.Close, "Clear") }
            }
        },
    )
}

/** Under the search bar: narrow the search to a kind of file, to recent changes or to big files. */
@Composable
internal fun SearchFilters(vm: BrowserViewModel, query: SearchQuery) {
    Column {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(SortGroup.entries.filter { it != SortGroup.OTHER }, key = { it.name }) { group ->
                FilterChip(
                    selected = query.group == group,
                    onClick = { vm.updateSearch(query.copy(group = if (query.group == group) null else group)) },
                    label = { Text(group.defaultFolder) },
                )
            }
            item(key = "recent") {
                FilterChip(
                    selected = query.recent,
                    onClick = { vm.updateSearch(query.copy(recent = !query.recent)) },
                    label = { Text("Last ${FileSearch.RECENT_DAYS} days") },
                )
            }
            item(key = "large") {
                FilterChip(
                    selected = query.large,
                    onClick = { vm.updateSearch(query.copy(large = !query.large)) },
                    label = { Text("Over ${Format.size(FileSearch.LARGE_BYTES)}") },
                )
            }
        }
        // Kept in the layout while idle, so the list does not jump as searches start and stop.
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(4.dp)) {
            if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (vm.searchTruncated) {
            Text(
                "Showing the first ${FileSearch.MAX_RESULTS} matches. Add more words to narrow it down.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** The top bar while items are selected: how many, select all, and the less common actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(vm: BrowserViewModel) {
    val selected = vm.selectedItems
    val single = selected.singleOrNull()
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Clear selection") } },
        title = { Text("${selected.size} selected") },
        actions = {
            IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, "Select all") }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More actions") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    @Composable
                    fun action(text: String, icon: ImageVector, run: () -> Unit) =
                        DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = { open = false; run() })

                    val location = vm.location
                    if (location is Location.Disk) {
                        if (single != null && (single.isDirectory || ArchiveFormat.fromName(single.name) != null)) {
                            action("Open in new tab", Icons.Default.Tab) { vm.openInNewTab(single) }
                        }
                        if (single != null && vm.search?.isActive == true) {
                            action("Show in its folder", Icons.Default.FolderOpen) { vm.showInFolder(single) }
                        }
                        action("Compress…", Icons.Default.Compress) {
                            vm.dialog = BrowserDialog.Compress(selected.map { File(it.path) }, suggestedArchiveName(vm, selected))
                        }
                        if (single != null && !single.isDirectory && ArchiveFormat.fromName(single.name) != null) {
                            action("Extract…", Icons.Default.Unarchive) { vm.clearSelection(); vm.promptExtract(single.path) }
                        }
                        if (single != null && !single.isDirectory) {
                            action("Checksums…", Icons.Default.Fingerprint) { vm.clearSelection(); vm.dialog = BrowserDialog.Checksums(File(single.path)) }
                        }
                        // Folders can be pinned to the side panel, one or several at a time.
                        if (selected.isNotEmpty() && selected.all { it.isDirectory }) {
                            val paths = selected.map { it.path }
                            if (paths.all(vm::isFavorite)) {
                                action("Remove from favourites", Icons.Default.Star) { vm.setFavorites(paths, pinned = false) }
                            } else {
                                action("Add to favourites", Icons.Default.StarBorder) { vm.setFavorites(paths, pinned = true) }
                            }
                        }
                        if (single != null) {
                            action("Copy path", Icons.Default.Link) { vm.copyPath(single) }
                            action("Details", Icons.Default.Info) { vm.clearSelection(); vm.showDetail(DetailTarget.Disk(single.path)) }
                        }
                    }
                    if (location is Location.InArchive && single != null && single.isDirectory) {
                        action("Open in new tab", Icons.Default.Tab) { vm.openInNewTab(single) }
                    }
                    action("Invert selection", Icons.Default.FlipToBack) { vm.invertSelection() }
                }
            }
        },
    )
}

/** The main actions for the selection, in a floating toolbar at the bottom of the screen. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectionToolbar(vm: BrowserViewModel) {
    val selected = vm.selectedItems
    val single = selected.singleOrNull()
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        @Composable
        fun action(icon: ImageVector, label: String, run: () -> Unit) =
            IconButton(onClick = run) { Icon(icon, label) }

        when (val location = vm.location) {
            is Location.InArchive -> {
                action(Icons.Default.Unarchive, "Extract selected") { vm.promptExtract(location.archivePath, selected.map { it.path }, location.dir) }
                action(Icons.Default.ContentCopy, "Copy out of archive") { vm.copySelection(cut = false) }
            }
            Location.Trash -> {
                action(Icons.Default.RestoreFromTrash, "Restore") { vm.restoreFromTrash(selected) }
                action(Icons.Default.DeleteForever, "Delete for good") { vm.dialog = BrowserDialog.Delete(selected) }
            }
            else -> {
                action(Icons.Default.ContentCopy, "Copy") { vm.copySelection(cut = false) }
                action(Icons.Default.ContentCut, "Move") { vm.copySelection(cut = true) }
                action(Icons.Default.Share, "Share") { vm.share(selected) }
                if (single != null) {
                    action(Icons.Default.DriveFileRenameOutline, "Rename") { vm.dialog = BrowserDialog.Rename(single) }
                } else {
                    action(Icons.Default.DriveFileRenameOutline, "Rename all") { vm.dialog = BrowserDialog.BatchRename(selected) }
                }
                action(Icons.Default.Delete, "Delete") { vm.dialog = BrowserDialog.Delete(selected) }
            }
        }
    }
}

private fun suggestedArchiveName(vm: BrowserViewModel, selected: List<FileItem>): String {
    val single = selected.singleOrNull()
    return when {
        single == null -> (vm.location as? Location.Disk)?.path?.let { File(it).name }?.ifEmpty { null } ?: "Archive"
        single.isDirectory -> single.name
        else -> ArchiveFormat.stripExtension(single.name)
    }
}

@Composable
internal fun NewItemButton(vm: BrowserViewModel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FloatingActionButton(onClick = { open = true }) { Icon(Icons.Default.Add, "Create new") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("New folder") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                onClick = { open = false; vm.dialog = BrowserDialog.NewFolder },
            )
            DropdownMenuItem(
                text = { Text("New text file") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) },
                onClick = { open = false; vm.dialog = BrowserDialog.NewFile },
            )
        }
    }
}

/** Shown while something is on the clipboard: where it came from and a Paste button. */
@Composable
private fun PasteBar(vm: BrowserViewModel, clip: Clipboard) {
    val verb = when (clip) {
        is Clipboard.Entries -> "to extract"
        is Clipboard.Files -> if (clip.cut) "to move" else "to copy"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("${clip.count} ${if (clip.count == 1) "item" else "items"} $verb", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (vm.canPaste) "Paste into this folder" else "Open a folder to paste",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = vm::clearClipboard) { Text("Cancel") }
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(onClick = vm::paste, enabled = vm.canPaste) {
                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste")
            }
        }
    }
}
