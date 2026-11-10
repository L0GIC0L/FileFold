package io.github.l0gic0l.filefold.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.filefold.archive.ArchiveFormat
import io.github.l0gic0l.filefold.core.FileItem
import io.github.l0gic0l.filefold.core.SortBy
import io.github.l0gic0l.filefold.ui.detail.DetailTarget
import kotlinx.coroutines.delay
import java.io.File

/** The file list: top bar, breadcrumbs, contents and the paste bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPane(vm: BrowserViewModel, showMenuButton: Boolean, onMenu: () -> Unit) {
    val location = vm.location
    val inArchive = location is Location.InArchive
    val crumbs = remember(location, vm.places) { vm.breadcrumbs() }

    BackHandler(enabled = vm.selectionMode || vm.search != null || vm.canNavigateUp) { vm.back() }

    // Each folder keeps its own scroll position, restored when you come back to it.
    val listState = remember(location.key) { LazyListState() }
    val gridState = remember(location.key) { LazyGridState() }
    DisposableEffect(location.key) {
        val key = location.key
        onDispose {
            vm.scrollMemory[key] = if (vm.settings.gridView) {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            } else {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
        }
    }
    LaunchedEffect(location.key, vm.items, vm.loading) {
        if (vm.loading || vm.items.isEmpty()) return@LaunchedEffect
        val (index, offset) = vm.scrollMemory.remove(location.key) ?: return@LaunchedEffect
        if (vm.settings.gridView) gridState.scrollToItem(index, offset) else listState.scrollToItem(index, offset)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            when {
                vm.selectionMode -> SelectionBar(vm, inArchive)
                vm.search != null -> SearchBar(vm)
                else -> TopAppBar(
                    title = { Text(crumbs.lastOrNull()?.label.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        when {
                            showMenuButton -> IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Storage locations") }
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
        floatingActionButton = {
            if (!inArchive && !vm.selectionMode && vm.clipboard == null) NewItemButton(vm)
        },
        bottomBar = {
            AnimatedVisibility(
                visible = vm.clipboard != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) { vm.clipboard?.let { PasteBar(vm, it) } }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Breadcrumbs(crumbs) { vm.navigate(it) }
            val archive = vm.archive
            if (location is Location.InArchive && archive != null && archive.file.path == location.archivePath && vm.error == null) {
                ArchiveBanner(archive) { vm.promptExtract(archive.file.path) }
            }
            var userRefresh by remember { mutableStateOf(false) }
            LaunchedEffect(vm.loading) { if (!vm.loading) userRefresh = false }
            PullToRefreshBox(
                isRefreshing = userRefresh,
                onRefresh = { userRefresh = true; vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                // Room at the bottom so the last row can scroll clear of the FAB and gesture bar.
                val contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 96.dp)
                val error = vm.error
                when {
                    error != null -> ErrorState(error) { vm.refresh() }
                    vm.items.isEmpty() && vm.loading -> DelayedSpinner()
                    vm.items.isEmpty() -> EmptyState(searching = !vm.search.isNullOrBlank(), inArchive = inArchive)
                    vm.settings.gridView -> FileGrid(
                        vm.items, vm.selection, inArchive, gridState, contentPadding,
                        onClick = vm::open, onLongClick = vm::toggleSelection,
                    )
                    else -> FileList(
                        vm.items, vm.selection, inArchive, listState, contentPadding,
                        onClick = vm::open, onLongClick = vm::toggleSelection,
                    )
                }
            }
        }
    }
}

/** A spinner that only appears if loading takes long enough to notice, so fast folders do not flicker. */
@Composable
private fun DelayedSpinner() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250)
        visible = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (visible) CircularProgressIndicator()
    }
}

@Composable
private fun BrowserActions(vm: BrowserViewModel, inArchive: Boolean) {
    IconButton(onClick = vm::openSearch) { Icon(Icons.Default.Search, "Search this folder") }
    SortMenu(vm)
    IconButton(onClick = { vm.setGridView(!vm.settings.gridView) }) {
        Icon(
            if (vm.settings.gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
            if (vm.settings.gridView) "Show as list" else "Show as grid",
        )
    }
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
                }
            }
            item("Select all", Icons.Default.SelectAll) { vm.selectAll() }
            item("Refresh", Icons.Default.Refresh) { vm.refresh() }
            if (!inArchive) {
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
private fun SearchBar(vm: BrowserViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = vm::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
        title = {
            TextField(
                value = vm.search.orEmpty(),
                onValueChange = vm::updateSearch,
                placeholder = { Text("Search this folder") },
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
            if (!vm.search.isNullOrEmpty()) IconButton(onClick = { vm.updateSearch("") }) { Icon(Icons.Default.Close, "Clear") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(vm: BrowserViewModel, inArchive: Boolean) {
    val selected = vm.selectedItems
    val single = selected.singleOrNull()
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Clear selection") } },
        title = { Text("${selected.size} selected") },
        actions = {
            if (inArchive) {
                val location = vm.location as Location.InArchive
                IconButton(onClick = { vm.promptExtract(location.archivePath, selected.map { it.path }, location.dir) }) {
                    Icon(Icons.Default.Unarchive, "Extract selected")
                }
                IconButton(onClick = { vm.copySelection(cut = false) }) { Icon(Icons.Default.ContentCopy, "Copy out of archive") }
                IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, "Select all") }
                return@TopAppBar
            }
            IconButton(onClick = { vm.copySelection(cut = false) }) { Icon(Icons.Default.ContentCopy, "Copy") }
            IconButton(onClick = { vm.copySelection(cut = true) }) { Icon(Icons.Default.ContentCut, "Move") }
            IconButton(onClick = { vm.dialog = BrowserDialog.Delete(selected) }) { Icon(Icons.Default.Delete, "Delete") }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More actions") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    @Composable
                    fun action(text: String, icon: ImageVector, run: () -> Unit) =
                        DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = { open = false; run() })

                    if (single != null) action("Rename", Icons.Default.DriveFileRenameOutline) { vm.dialog = BrowserDialog.Rename(single) }
                    action("Compress…", Icons.Default.Compress) {
                        vm.dialog = BrowserDialog.Compress(selected.map { File(it.path) }, suggestedArchiveName(vm, selected))
                    }
                    if (single != null && !single.isDirectory && ArchiveFormat.fromName(single.name) != null) {
                        action("Extract…", Icons.Default.Unarchive) { vm.clearSelection(); vm.promptExtract(single.path) }
                    }
                    action("Share", Icons.Default.Share) { vm.share(selected) }
                    if (single != null) {
                        action("Details", Icons.Default.Info) { vm.clearSelection(); vm.showDetail(DetailTarget.Disk(single.path)) }
                    }
                    action("Select all", Icons.Default.SelectAll) { vm.selectAll() }
                }
            }
        },
    )
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
private fun NewItemButton(vm: BrowserViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
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
