package io.github.l0gic0l.filefold.ui.browser

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.l0gic0l.filefold.archive.ArchiveFormat
import io.github.l0gic0l.filefold.archive.ArchiveIndex
import io.github.l0gic0l.filefold.archive.ArchivePasswordException
import io.github.l0gic0l.filefold.archive.Archives
import io.github.l0gic0l.filefold.core.AutoSortMode
import io.github.l0gic0l.filefold.core.AutoSorter
import io.github.l0gic0l.filefold.core.BatchRename
import io.github.l0gic0l.filefold.core.FileSearch
import io.github.l0gic0l.filefold.core.NaturalOrder
import io.github.l0gic0l.filefold.core.SearchQuery
import io.github.l0gic0l.filefold.core.Trash
import io.github.l0gic0l.filefold.core.FileItem
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.core.FileOps
import io.github.l0gic0l.filefold.core.FileTypes
import io.github.l0gic0l.filefold.core.Progress
import io.github.l0gic0l.filefold.core.SortOptions
import io.github.l0gic0l.filefold.core.TextDocument
import io.github.l0gic0l.filefold.data.AppSettings
import io.github.l0gic0l.filefold.data.SavedPane
import io.github.l0gic0l.filefold.data.SettingsRepository
import io.github.l0gic0l.filefold.ui.detail.DetailContent
import io.github.l0gic0l.filefold.ui.detail.DetailState
import io.github.l0gic0l.filefold.ui.detail.DetailTarget
import io.github.l0gic0l.filefold.ui.detail.EditorSession
import io.github.l0gic0l.filefold.ui.detail.FileInfo
import io.github.l0gic0l.filefold.ui.detail.InfoRow
import io.github.l0gic0l.filefold.util.Format
import io.github.l0gic0l.filefold.util.IncomingFiles
import io.github.l0gic0l.filefold.util.Launch
import io.github.l0gic0l.filefold.util.MediaInfo
import io.github.l0gic0l.filefold.util.Place
import io.github.l0gic0l.filefold.util.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * State and actions for browsing. One instance serves the whole window, so a foldable can open,
 * close or rotate without losing anything. The folder area holds one [FolderPane], or two in
 * split view; the top bar, menus and keyboard act on the focused one.
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val previewCache = File(app.cacheDir, "preview")
    private val appCache = app.cacheDir.path

    var settings by mutableStateOf(AppSettings())
        private set

    private var nextTabId = 1L

    /** One pane, or two side by side in split view. */
    var panes by mutableStateOf(listOf(FolderPane(listOf(BrowserTab(nextTabId++, Location.Home)))))
        private set

    /** Which pane the top bar, menus and keyboard act on. */
    var focusedIndex by mutableIntStateOf(0)
        private set

    val pane: FolderPane get() = panes[focusedIndex.coerceIn(0, panes.lastIndex)]
    val isSplit: Boolean get() = panes.size > 1

    // The focused pane, which the top bar and every action below work on.
    val location: Location get() = pane.location
    val tabs: List<BrowserTab> get() = pane.tabs
    val activeTabId: Long get() = pane.activeTabId
    val page: Page get() = pane.page
    val items: List<FileItem> get() = pane.items
    val loading: Boolean get() = pane.loading
    val error: String? get() = pane.error
    val search: SearchQuery? get() = pane.search
    val searching: Boolean get() = pane.searching
    val searchTruncated: Boolean get() = pane.searchTruncated
    val sizeView: Boolean get() = pane.sizeView
    val selection: Set<String> get() = pane.selection
    val selectionMode: Boolean get() = pane.selectionMode

    /** Storage volumes (internal, SD card, USB) followed by the standard folders. Refreshed on resume. */
    var places by mutableStateOf(Storage.volumes(app) + Storage.shortcuts())
        private set
    private val volumes: List<Place> get() = places.filter { it.isVolume }

    /** Files changed in the last week, newest first, for the home page; null until worked out. */
    var recentFiles by mutableStateOf<List<FileItem>?>(null)
        private set
    private var recentJob: Job? = null

    /** What is in the trash, by where each item sits now. */
    @Volatile
    private var trashEntries: Map<String, Trash.Entry> = emptyMap()

    /** Shared by both panes, so items copied on one side can be pasted on the other. */
    var clipboard by mutableStateOf<Clipboard?>(null)
        private set

    /** Open archives by path; their indexes are kept so moving between their folders is instant. */
    private val archives = mutableStateMapOf<String, ArchiveSession>()

    /** The archive the focused pane is in, if it is in one. */
    val archive: ArchiveSession? get() = (location as? Location.InArchive)?.let { archives[it.archivePath] }

    fun archiveFor(path: String): ArchiveSession? = archives[path]

    var dialog by mutableStateOf<BrowserDialog?>(null)

    var detail by mutableStateOf<DetailState?>(null)
        private set

    /** Set when the detail pane should be brought into view; the UI drives the navigator and clears it. */
    var detailRequest by mutableStateOf<String?>(null)
        private set

    private val _operation = MutableStateFlow<OperationState?>(null)
    val operation: StateFlow<OperationState?> = _operation

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** First visible item and offset for folders visited earlier, so going back restores the view. Keyed by [Page.scrollKey]. */
    val scrollMemory = HashMap<String, Pair<Int, Int>>()

    /**
     * The last listing seen for recently visited folders (unsorted). Revisiting a folder shows
     * it instantly while a fresh listing loads, and the back gesture can preview the parent.
     * Only touched on the main thread.
     */
    private val listingCache = object : LinkedHashMap<String, List<FileItem>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileItem>>?) = size > 48
    }

    private var operationJob: Job? = null
    private var detailJob: Job? = null

    /** False until the tabs from last time are back; something opened from another app waits for that. */
    private var ready = false
    private var pendingOpen: (() -> Unit)? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            previewCache.deleteRecursively()
            File(app.cacheDir, OPENED_DIR).deleteRecursively()
            // Items that have sat in the trash for a month are removed for good.
            try {
                Trash.purgeOlderThan(volumeRoots(), System.currentTimeMillis() - Trash.KEEP_DAYS * 86_400_000L)
            } catch (_: Exception) {
            }
        }
        viewModelScope.launch {
            restoreTabs()
            var first = true
            settingsRepo.settings.collect { new ->
                val old = settings
                settings = new
                when {
                    first -> {
                        loadAll()
                        ready = true
                        pendingOpen?.invoke()
                        pendingOpen = null
                    }
                    new.showHidden != old.showHidden -> {
                        listingCache.clear()
                        recentFiles = null
                        loadAll()
                    }
                    new.sort != old.sort -> panes.forEach(::applyFilterAndSort)
                }
                first = false
            }
        }
    }

    // ------------------------------------------------------------ panes

    /** Makes [target] the pane the top bar and menus act on. Leaving a pane clears its selection. */
    fun focus(target: FolderPane) {
        val index = panes.indexOf(target)
        if (index < 0 || index == focusedIndex) return
        pane.selection = emptySet()
        focusedIndex = index
    }

    /** Opens a second pane beside the first, showing [at]. With two panes open, focuses the other. */
    fun openSplit(at: Location = location) {
        if (isSplit) {
            focus(panes.first { it !== pane })
            return
        }
        addPane(FolderPane(listOf(BrowserTab(nextTabId++, at))))
    }

    /** Closes [side], adding its tabs to the pane that stays so nothing open is lost. */
    fun closeSide(side: FolderPane = pane) {
        if (!isSplit) return
        val keep = panes.first { it !== side }
        keep.tabs = keep.tabs + side.tabs
        dropPane(side)
    }

    private fun addPane(p: FolderPane) {
        panes = panes + p
        pane.selection = emptySet()
        focusedIndex = panes.lastIndex
        p.rawItems = listingCache[p.location.key].orEmpty()
        applyFilterAndSort(p)
        load(p)
        persistTabs()
    }

    private fun dropPane(p: FolderPane) {
        p.cancelJobs()
        panes = panes.filter { it !== p }
        focusedIndex = 0
        persistTabs()
    }

    /**
     * Finishes dragging a tab out of [from]. [to] is the pane it was dropped on, at [index] in its
     * strip, or null to open it in a second pane beside the first (split view).
     */
    fun moveTab(tabId: Long, from: FolderPane, to: FolderPane?, index: Int) {
        val tab = from.tabs.firstOrNull { it.id == tabId } ?: return
        when {
            to == null -> when {
                isSplit -> Unit
                // Its only tab: show the same folder beside it rather than leave this side empty.
                from.tabs.size == 1 -> openSplit(tab.location)
                else -> {
                    removeTab(from, tab)
                    addPane(FolderPane(listOf(tab)))
                }
            }
            to === from -> {
                val tabs = from.tabs.toMutableList()
                val old = tabs.indexOf(tab)
                tabs.removeAt(old)
                tabs.add((if (index > old) index - 1 else index).coerceIn(0, tabs.size), tab)
                from.tabs = tabs
                persistTabs()
            }
            else -> {
                to.tabs = to.tabs.toMutableList().apply { add(index.coerceIn(0, size), tab) }
                // A pane whose last tab is dragged away closes, and split view with it.
                if (from.tabs.size == 1) dropPane(from) else removeTab(from, tab)
                focus(to)
                to.activeTabId = tab.id
                show(to, tab.location)
            }
        }
    }

    // ------------------------------------------------------------ navigation

    fun navigate(to: Location, p: FolderPane = pane) {
        if (to == p.location) return
        p.tabs = p.tabs.map { if (it.id == p.activeTabId) it.copy(location = to) else it }
        show(p, to)
    }

    /** Switches [p] to [to]: the cached listing at once if there is one, then a fresh one. */
    private fun show(p: FolderPane, to: Location) {
        p.location = to
        p.selection = emptySet()
        resetSearch(p)
        p.error = null
        // Never the previous folder's files: either this folder's last listing or nothing.
        p.rawItems = listingCache[to.key].orEmpty()
        applyFilterAndSort(p)
        load(p)
        persistTabs()
    }

    // ------------------------------------------------------------ tabs

    /** Opens a new tab at [at] (the current folder by default), right after the active tab. */
    fun newTab(at: Location = location, p: FolderPane = pane) {
        val tab = BrowserTab(nextTabId++, at)
        val index = p.tabs.indexOfFirst { it.id == p.activeTabId }
        p.tabs = p.tabs.toMutableList().apply { add(index + 1, tab) }
        switchTab(tab.id, p)
    }

    /** Opens a folder or archive from the list in a new tab. */
    fun openInNewTab(item: FileItem) {
        val loc = location
        val target = when {
            loc is Location.InArchive && item.isDirectory -> Location.InArchive(loc.archivePath, item.path)
            item.isDirectory -> Location.Disk(item.path)
            else -> Location.InArchive(item.path, "")
        }
        clearSelection()
        newTab(target)
    }

    fun switchTab(id: Long, p: FolderPane = pane) {
        focus(p)
        if (id == p.activeTabId) return
        val tab = p.tabs.firstOrNull { it.id == id } ?: return
        p.activeTabId = id
        show(p, tab.location)
    }

    /** Closes a tab. Closing the last tab of one side of split view closes that side. */
    fun closeTab(id: Long, p: FolderPane = pane) {
        val tab = p.tabs.firstOrNull { it.id == id } ?: return
        if (p.tabs.size <= 1) {
            if (isSplit) dropPane(p)
            return
        }
        removeTab(p, tab)
        scrollMemory.keys.removeAll { it.startsWith("$id|") }
    }

    private fun removeTab(p: FolderPane, tab: BrowserTab) {
        val index = p.tabs.indexOf(tab)
        val remaining = p.tabs - tab
        p.tabs = remaining
        if (tab.id == p.activeTabId) {
            val next = remaining[index.coerceAtMost(remaining.lastIndex)]
            p.activeTabId = next.id
            show(p, next.location)
        } else {
            persistTabs()
        }
    }

    /** A short name for a tab: the folder's name, or the archive's for its root. */
    fun tabLabel(loc: Location): String = when (loc) {
        is Location.Disk -> volumes.firstOrNull { it.path == loc.path }?.label ?: File(loc.path).name.ifEmpty { "/" }
        is Location.InArchive -> if (loc.dir.isEmpty()) File(loc.archivePath).name else ArchiveIndex.nameOf(loc.dir)
        Location.Trash -> "Trash"
        Location.Home -> "Home"
    }

    private fun persistTabs() {
        val saved = panes.map { p -> SavedPane(p.tabs.map { it.location.encode() }, p.tabs.indexOfFirst { it.id == p.activeTabId }.coerceAtLeast(0)) }
        val focused = focusedIndex
        viewModelScope.launch { settingsRepo.setTabs(saved, focused) }
    }

    /** Reopens the tabs (and split view) from last time, skipping folders that have since gone. */
    private suspend fun restoreTabs() {
        val saved = settingsRepo.savedTabs.first()
        val restored = withContext(Dispatchers.IO) {
            saved.panes.map { pane ->
                pane to pane.tabs.mapNotNull(Location::decode).filter { it is Location.Trash || it is Location.Home || File(it.diskPath).exists() }
            }.filter { it.second.isNotEmpty() }
        }
        if (restored.isEmpty()) return
        panes = restored.map { (savedPane, locations) ->
            val tabs = locations.map { BrowserTab(nextTabId++, it) }
            FolderPane(tabs, tabs[savedPane.active.coerceIn(0, tabs.lastIndex)].id)
        }
        focusedIndex = saved.focused.coerceIn(0, panes.lastIndex)
    }

    fun openPlace(path: String) = navigate(Location.Disk(path))

    fun openHome() = navigate(Location.Home)

    fun openTrash() = navigate(Location.Trash)

    /** Everything changed in the last few days, anywhere on internal storage, newest first. */
    fun openRecent() = openCategory(SearchQuery(recent = true))

    /** Every file of a kind (or matching [query]) on internal storage, as a search from its top. */
    fun openCategory(query: SearchQuery) {
        val p = pane
        navigate(Location.Disk(Storage.primaryRoot.absolutePath), p)
        p.search = query
        runSearch(p)
    }

    // ------------------------------------------------------------ opened from other apps

    /**
     * Shows what another app asked FileFold to open: a folder, an archive to browse, or a file
     * to view. Files that only exist inside the other app are copied first and opened read-only.
     */
    fun openFromOutside(uri: Uri, type: String?) = whenReady {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val file = withContext(Dispatchers.IO) { IncomingFiles.resolve(app, uri) }
            when {
                file != null && file.isDirectory -> showInTab(Location.Disk(file.absolutePath))
                file != null -> openFileFromOutside(file, inPlace = !file.path.startsWith(app.cacheDir.path))
                type?.lowercase() in IncomingFiles.folderTypes -> message("FileFold cannot reach this folder")
                else -> try {
                    val copy = withContext(Dispatchers.IO) { IncomingFiles.copyToCache(app, uri, File(app.cacheDir, OPENED_DIR)) }
                    openFileFromOutside(copy, inPlace = false)
                } catch (e: Exception) {
                    message(e.message ?: "FileFold could not open this file")
                }
            }
        }
    }

    /** Shows a folder another app asked for, such as Downloads. */
    fun openFolderFromOutside(path: String) = whenReady { showInTab(Location.Disk(path)) }

    /** [inPlace] is false for a copy, which is shown on its own rather than in a folder. */
    private fun openFileFromOutside(file: File, inPlace: Boolean) {
        when {
            ArchiveFormat.fromName(file.name) != null -> showInTab(Location.InArchive(file.absolutePath, ""))
            inPlace -> {
                file.parent?.let { showInTab(Location.Disk(it)) }
                showDetail(DetailTarget.Disk(file.absolutePath))
            }
            else -> showDetail(DetailTarget.Disk(file.absolutePath))
        }
    }

    /** Shows [target] in the focused pane: in a tab that already has it, else in a new tab (or in place of a lone home tab). */
    private fun showInTab(target: Location) {
        val p = pane
        val existing = p.tabs.firstOrNull { it.location == target }
        when {
            existing != null -> switchTab(existing.id, p)
            p.tabs.size == 1 && p.location is Location.Home -> navigate(target, p)
            else -> newTab(target, p)
        }
    }

    /** Runs [action] now, or once the tabs from last time are back if that has not happened yet. */
    private fun whenReady(action: () -> Unit) {
        if (ready) action() else pendingOpen = action
    }

    // ------------------------------------------------------------ favourites

    fun isFavorite(path: String): Boolean = path in settings.favorites

    fun toggleFavorite(path: String) {
        val favorites = settings.favorites
        val pinned = path !in favorites
        viewModelScope.launch { settingsRepo.setFavorites(if (pinned) favorites + path else favorites - path) }
        message(if (pinned) "Added \"${File(path).name}\" to favourites" else "Removed \"${File(path).name}\" from favourites")
        clearSelection()
    }

    /** Called when the app returns to the foreground: storage may have been plugged in and files changed elsewhere. */
    fun onResume() {
        places = Storage.volumes(getApplication()) + Storage.shortcuts()
        recentFiles = recentFiles?.filter { File(it.path).exists() }
        if (operationJob?.isActive != true) loadAll()
    }

    /** Goes one level up. Returns false when there is nowhere further up. */
    fun navigateUp(p: FolderPane = pane): Boolean {
        val parent = parentOf(p.location) ?: return false
        navigate(parent, p)
        return true
    }

    /** Where "up" leads from [location]: the parent folder, the home page from the top of a volume, or null from home. */
    fun parentOf(location: Location): Location? = when (location) {
        is Location.InArchive ->
            if (location.dir.isEmpty()) {
                File(location.archivePath).parent?.takeUnless { it.startsWith(appCache) }?.let { Location.Disk(it) } ?: Location.Home
            }
            else Location.InArchive(location.archivePath, ArchiveIndex.parentOf(location.dir))
        is Location.Disk -> {
            val root = Storage.volumeRootOf(volumes, location.path)
            if (root == null || location.path == root) Location.Home else File(location.path).parent?.let { Location.Disk(it) }
        }
        Location.Trash -> Location.Home
        Location.Home -> null
    }

    private fun volumeRoots(): List<File> = volumes.map { File(it.path) }

    private fun volumeRootOf(file: File): File? = Storage.volumeRootOf(volumes, file.absolutePath)?.let(::File)

    val canNavigateUp: Boolean get() = parentOf(location) != null

    /** Where "up" leads from the focused pane's folder. */
    val parentLocation: Location? get() = parentOf(location)

    /** Handles the back gesture in the browser. Returns false when there is nothing left to undo. */
    fun back(): Boolean = when {
        selectionMode -> { clearSelection(); true }
        search != null -> { closeSearch(); true }
        else -> navigateUp()
    }

    fun breadcrumbs(loc: Location = location): List<Crumb> {
        val home = Crumb("Home", Location.Home)
        if (loc is Location.Home) return listOf(home)
        if (loc is Location.Trash) return listOf(home, Crumb("Trash", loc))
        val crumbs = arrayListOf(home)
        val diskPath = loc.diskPath
        val root = Storage.volumeRootOf(volumes, diskPath)
        if (root != null) {
            crumbs += Crumb(volumes.first { it.path == root }.label, Location.Disk(root))
            var path = root
            diskPath.removePrefix(root).split('/').filter { it.isNotEmpty() }.forEach { part ->
                path = "$path/$part"
                crumbs += Crumb(part, Location.Disk(path))
            }
        } else {
            crumbs += Crumb(File(diskPath).name.ifEmpty { "/" }, Location.Disk(diskPath))
        }
        if (loc is Location.InArchive) {
            // The archive itself stands for its root folder.
            crumbs[crumbs.lastIndex] = Crumb(File(loc.archivePath).name, Location.InArchive(loc.archivePath, ""))
            var dir = ""
            loc.dir.split('/').filter { it.isNotEmpty() }.forEach { part ->
                dir = if (dir.isEmpty()) part else "$dir/$part"
                crumbs += Crumb(part, Location.InArchive(loc.archivePath, dir))
            }
        }
        return crumbs
    }

    /** What happens when an item is tapped in the focused pane. */
    fun open(item: FileItem) {
        if (selectionMode) {
            toggleSelection(item)
            return
        }
        when (val loc = location) {
            is Location.Disk, Location.Home -> when {
                item.isDirectory -> navigate(Location.Disk(item.path))
                ArchiveFormat.fromName(item.name) != null -> navigate(Location.InArchive(item.path, ""))
                else -> showDetail(DetailTarget.Disk(item.path))
            }
            is Location.InArchive ->
                if (item.isDirectory) navigate(Location.InArchive(loc.archivePath, item.path))
                else showDetail(DetailTarget.Entry(loc.archivePath, item.path))
            // Trashed items can be looked at, but folders are only opened once they are restored.
            Location.Trash -> showDetail(DetailTarget.Disk(item.path))
        }
    }

    /** Leaves a search to show the folder that holds [item]. */
    fun showInFolder(item: FileItem) {
        when (val loc = location) {
            is Location.Disk, Location.Home -> File(item.path).parent?.let { navigate(Location.Disk(it)) }
            is Location.InArchive -> navigate(Location.InArchive(loc.archivePath, ArchiveIndex.parentOf(item.path)))
            Location.Trash -> Unit
        }
    }

    /** Opens a ZIP-style container that has another extension, such as an APK or DOCX. */
    fun browseAsArchive(path: String) = navigate(Location.InArchive(path, ""))

    // ------------------------------------------------------------ loading

    fun refresh() {
        pane.folderSizes = emptyMap()
        if (location is Location.Home) recentFiles = null
        load(pane)
    }

    private fun loadAll() = panes.forEach(::load)

    private fun load(p: FolderPane) {
        p.loadJob?.cancel()
        val target = p.location
        p.loadJob = viewModelScope.launch {
            p.loading = true
            try {
                var problem: String? = null
                p.rawItems = when (target) {
                    is Location.Disk -> withContext(Dispatchers.IO) { FileOps.list(File(target.path), settings.showHidden) }
                    is Location.InArchive -> {
                        val session = openArchive(target.archivePath)
                        when {
                            session == null -> {
                                problem = "Enter the password to open this archive"
                                emptyList()
                            }
                            !session.index.hasFolder(target.dir) -> throw IOException("Folder not found in archive")
                            else -> session.index.list(target.dir)
                        }
                    }
                    Location.Trash -> {
                        val entries = withContext(Dispatchers.IO) { Trash.list(volumeRoots()) }
                        trashEntries = entries.associateBy { it.file.absolutePath }
                        withContext(Dispatchers.IO) {
                            // Shown under the name each item had, dated by when it was deleted.
                            entries.map { FileOps.toItem(it.file).copy(name = it.originalName, modified = it.deletedAt) }
                        }
                    }
                    Location.Home -> {
                        loadRecent()
                        emptyList()
                    }
                }
                p.error = problem
                if (problem == null) listingCache[target.key] = p.rawItems
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                p.rawItems = emptyList()
                listingCache.remove(target.key)
                p.error = e.message ?: "Could not open this folder"
            } finally {
                p.loading = false
            }
            applyFilterAndSort(p)
            if (p.search?.isActive == true) runSearch(p)
            computeSizes(p)
            prefetchParent(target)
        }
    }

    /** Finds the files changed in the last week for the home page, unless that is already done. */
    private fun loadRecent() {
        if (recentFiles != null || recentJob?.isActive == true) return
        val showHidden = settings.showHidden
        recentJob = viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                val all = ArrayList<FileItem>()
                try {
                    FileSearch.search(Storage.primaryRoot, SearchQuery(recent = true), showHidden) { all += it }
                } catch (_: IOException) {
                }
                all.sortedByDescending { it.modified }.take(RECENT_ON_HOME)
            }
            recentFiles = found
        }
    }

    /** Lists the parent folder ahead of time, so a back gesture can show it as it starts. */
    private suspend fun prefetchParent(of: Location) {
        val parent = parentOf(of) ?: return
        if (listingCache.containsKey(parent.key)) return
        val listing = try {
            when (parent) {
                is Location.Disk -> withContext(Dispatchers.IO) { FileOps.list(File(parent.path), settings.showHidden) }
                is Location.InArchive -> archives[parent.archivePath]?.index?.list(parent.dir)
                Location.Trash, Location.Home -> null
            }
        } catch (e: IOException) {
            null
        } ?: return
        listingCache[parent.key] = listing
    }

    /** What to draw for [loc] while it animates in or out of [p]: live for its current folder, cached otherwise. */
    fun itemsFor(loc: Location, p: FolderPane): List<FileItem> =
        if (loc == p.location) p.items else listingCache[loc.key]?.sortedWith(settings.sort.comparator()).orEmpty()

    /**
     * Returns the cached session for an archive, reading its index when it is
     * new or has changed on disk. Asks for a password when the index itself is
     * encrypted; returns null if the user gives up.
     */
    private suspend fun openArchive(path: String, password: String? = archives[path]?.password): ArchiveSession? {
        val file = File(path)
        archives[path]?.let { if (it.lastModified == file.lastModified()) return it }
        val format = withContext(Dispatchers.IO) { ArchiveFormat.of(file) }
            ?: throw IOException("This is not an archive FileFold can read")
        return try {
            val entries = withContext(Dispatchers.IO) { Archives.list(file, format, password) }
            ArchiveSession(file, format, ArchiveIndex(format, entries), password, file.lastModified()).also(::keepArchive)
        } catch (e: ArchivePasswordException) {
            val showing = { p: FolderPane -> (p.location as? Location.InArchive)?.archivePath == path }
            requestPassword(file.name, wrong = password != null,
                onSubmit = { pw -> viewModelScope.launch { if (openArchive(path, pw) != null) panes.filter(showing).forEach(::load) } },
                onCancel = { panes.filter(showing).forEach { p -> navigate(parentOf(Location.InArchive(path, ""))!!, p) } },
            )
            null
        }
    }

    /** Remembers an archive's index, forgetting ones no pane is showing once there are a few. */
    private fun keepArchive(session: ArchiveSession) {
        archives[session.file.path] = session
        if (archives.size > 3) {
            val inUse = panes.mapNotNull { (it.location as? Location.InArchive)?.archivePath }.toSet() + session.file.path
            archives.keys.filter { it !in inUse }.forEach { archives.remove(it) }
        }
    }

    private fun updatePassword(path: String, password: String) {
        archives[path]?.let { archives[path] = it.copy(password = password) }
    }

    private fun requestPassword(name: String, wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        dialog = BrowserDialog.Password(name, wrong,
            onSubmit = { dialog = null; onSubmit(it) },
            onCancel = { dialog = null; onCancel() },
        )
    }

    private fun applyFilterAndSort(p: FolderPane) {
        val query = p.search?.takeIf { it.isActive }
        val usage = p.sizeView && query == null && p.location is Location.Disk
        val source = when {
            query != null -> p.searchResults
            usage -> p.rawItems.map { if (it.isDirectory) it.copy(size = p.folderSizes[it.path] ?: 0) else it }
            else -> p.rawItems
        }
        val byName = Comparator<FileItem> { a, b -> NaturalOrder.compare(a.name, b.name) }
        val order = when {
            query?.recent == true || p.location is Location.Trash -> compareByDescending<FileItem> { it.modified }.then(byName)
            query?.large == true || usage -> compareByDescending<FileItem> { it.size }.then(byName)
            else -> settings.sort.comparator()
        }
        p.items = source.sortedWith(order)
        p.sizeViewTotal = if (usage) source.sumOf { it.size } else 0L
        // Drop selected paths that are no longer listed, e.g. after a delete.
        val present = source.mapTo(HashSet()) { it.path }
        if (p.selection.any { it !in present }) p.selection = p.selection.filterTo(HashSet()) { it in present }
    }

    // ------------------------------------------------------------ search, sort, view

    /** Opens search in the focused pane; from the home page it searches all of internal storage. */
    fun openSearch() {
        if (location is Location.Home) navigate(Location.Disk(Storage.primaryRoot.absolutePath))
        pane.search = SearchQuery()
    }

    fun updateSearch(query: SearchQuery) {
        val typing = query.text != search?.text
        pane.search = query
        runSearch(pane, debounce = typing)
    }

    fun closeSearch() {
        resetSearch(pane)
        applyFilterAndSort(pane)
    }

    private fun resetSearch(p: FolderPane) {
        p.searchJob?.cancel()
        p.searchGeneration++
        p.search = null
        p.searching = false
        p.searchTruncated = false
        p.searchResults = emptyList()
    }

    /**
     * Searches [p]'s folder and everything below it. Matches are shown as they are
     * found. While typing, what is already listed is narrowed at once and the walk starts
     * after a short pause, so each keystroke does not start a search of its own.
     */
    private fun runSearch(p: FolderPane, debounce: Boolean = false) {
        p.searchJob?.cancel()
        val generation = ++p.searchGeneration
        val query = p.search?.takeIf { it.isActive }
        p.searchTruncated = false
        if (query == null) {
            p.searching = false
            p.searchResults = emptyList()
            applyFilterAndSort(p)
            return
        }
        val now = System.currentTimeMillis()
        fun matches(item: FileItem) = query.matches(item.name, item.isDirectory, item.size, item.modified, now)
        p.searchResults = p.searchResults.filter(::matches)
        applyFilterAndSort(p)
        p.searching = true
        val target = p.location
        p.searchJob = viewModelScope.launch {
            if (debounce) delay(250)
            // Only touched on the main thread; batches from the walk are posted here in order.
            val found = ArrayList<FileItem>()
            fun deliver(batch: List<FileItem>) {
                if (generation != p.searchGeneration) return
                found += batch
                p.searchResults = found.toList()
                applyFilterAndSort(p)
            }
            try {
                when (target) {
                    is Location.Disk -> {
                        val showHidden = settings.showHidden
                        val truncated = withContext(Dispatchers.IO) {
                            val job = coroutineContext.job
                            FileSearch.search(File(target.path), query, showHidden, now, Progress(isActive = { job.isActive })) { batch ->
                                viewModelScope.launch { deliver(batch) }
                            }
                        }
                        if (generation == p.searchGeneration) p.searchTruncated = truncated
                    }
                    is Location.InArchive -> archives[target.archivePath]?.let { session ->
                        deliver(session.index.search(target.dir, FileSearch.MAX_RESULTS, ::matches))
                    }
                    Location.Trash -> deliver(p.rawItems.filter(::matches))
                    Location.Home -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A folder that cannot be walked just ends the search with what was found.
            }
            if (generation == p.searchGeneration) {
                // Replaces the narrowed-down old results, also when nothing at all was found.
                p.searchResults = found.toList()
                p.searching = false
                applyFilterAndSort(p)
            }
        }
    }

    /** Extra text under a file's name in [p]: where a search result or a trashed item lives. */
    fun hintFor(p: FolderPane, item: FileItem): String? = when (val loc = p.location) {
        Location.Trash -> trashEntries[item.path]?.let { "from ${File(it.originalPath).parentFile?.name ?: "/"}" }
        is Location.Disk -> if (p.search?.isActive != true) null else {
            val parent = File(item.path).parent.orEmpty()
            if (parent == loc.path) null else "in ${parent.removePrefix(loc.path).trimStart('/')}"
        }
        is Location.InArchive -> if (p.search?.isActive != true) null else {
            val parent = ArchiveIndex.parentOf(item.path)
            if (parent == loc.dir) null else "in ${parent.removePrefix(loc.dir).trimStart('/')}"
        }
        Location.Home -> null
    }

    // ------------------------------------------------------------ storage usage

    /** Turns the storage usage view of the focused pane on or off. It stays on while moving between folders. */
    fun showSizes(on: Boolean) {
        val p = pane
        p.sizeView = on
        if (!on) {
            p.sizeJob?.cancel()
            p.sizing = false
            p.folderSizes = emptyMap()
        }
        applyFilterAndSort(p)
        computeSizes(p)
    }

    /** Opens a storage volume with the storage usage view on. */
    fun analyse(volume: Place) {
        openPlace(volume.path)
        showSizes(true)
    }

    /** Adds up the folders in [p]'s listing one by one, re-sorting as each size comes in. */
    private fun computeSizes(p: FolderPane) {
        p.sizeJob?.cancel()
        p.sizing = false
        if (!p.sizeView || p.location !is Location.Disk) return
        val pending = p.rawItems.filter { it.isDirectory && it.path !in p.folderSizes }
        if (pending.isEmpty()) return
        p.sizing = true
        p.sizeJob = viewModelScope.launch {
            for (dir in pending) {
                val size = withContext(Dispatchers.IO) {
                    val job = coroutineContext.job
                    FileSearch.folderSize(File(dir.path), Progress(isActive = { job.isActive }))
                }
                p.folderSizes = p.folderSizes + (dir.path to size)
                applyFilterAndSort(p)
            }
            p.sizing = false
        }
    }

    /** The storage volume [p]'s folder is on. */
    fun volumeOf(p: FolderPane): Place? =
        Storage.volumeRootOf(volumes, p.location.diskPath)?.let { root -> volumes.firstOrNull { it.path == root } }

    fun setSort(sort: SortOptions) = viewModelScope.launch { settingsRepo.setSort(sort) }
    fun setShowHidden(value: Boolean) = viewModelScope.launch { settingsRepo.setShowHidden(value) }
    fun setGridView(value: Boolean) = viewModelScope.launch { settingsRepo.setGridView(value) }
    fun setEditorWordWrap(value: Boolean) = viewModelScope.launch { settingsRepo.setEditorWordWrap(value) }
    fun setEditorLineNumbers(value: Boolean) = viewModelScope.launch { settingsRepo.setEditorLineNumbers(value) }
    fun setUseTrash(value: Boolean) = viewModelScope.launch { settingsRepo.setUseTrash(value) }
    fun setEditorFontSize(value: Float) = viewModelScope.launch { settingsRepo.setEditorFontSize(value.coerceIn(8f, 32f)) }

    // ------------------------------------------------------------ selection

    fun toggleSelection(item: FileItem) {
        pane.selection = if (item.path in selection) selection - item.path else selection + item.path
    }

    fun selectAll() {
        pane.selection = items.mapTo(HashSet()) { it.path }
    }

    fun clearSelection() {
        pane.selection = emptySet()
    }

    fun invertSelection() {
        pane.selection = items.mapNotNullTo(HashSet()) { item -> item.path.takeIf { it !in selection } }
    }

    /** Puts the full path of [item] on the system clipboard. */
    fun copyPath(item: FileItem) {
        val clipboardManager = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("Path", item.path))
        clearSelection()
        message("Path copied")
    }

    val selectedItems: List<FileItem> get() = items.filter { it.path in selection }

    // ------------------------------------------------------------ clipboard

    fun copySelection(cut: Boolean) {
        val selected = selectedItems
        if (selected.isEmpty()) return
        clipboard = when (val loc = location) {
            is Location.Disk -> Clipboard.Files(selected.map { File(it.path) }, cut)
            is Location.InArchive -> {
                val session = archive ?: return
                Clipboard.Entries(session.file, session.format, session.password, selected.map { it.path }, loc.dir)
            }
            Location.Trash, Location.Home -> return
        }
        clearSelection()
        val verb = when {
            location is Location.InArchive -> "ready to extract"
            cut -> "ready to move"
            else -> "ready to copy"
        }
        message("${selected.size} ${if (selected.size == 1) "item" else "items"} $verb. Open a folder and tap Paste.")
    }

    fun clearClipboard() {
        clipboard = null
    }

    val canPaste: Boolean get() = clipboard != null && location is Location.Disk

    fun paste() {
        val clip = clipboard ?: return
        val dest = (location as? Location.Disk)?.path?.let(::File) ?: return
        when (clip) {
            is Clipboard.Files -> runOperation(if (clip.cut) "Moving" else "Copying") { progress ->
                val done = if (clip.cut) FileOps.move(clip.files, dest, progress) else FileOps.copy(clip.files, dest, progress)
                if (clip.cut) withContext(Dispatchers.Main) { clipboard = null }
                "${if (clip.cut) "Moved" else "Copied"} ${plural(done.size, "item")}"
            }
            is Clipboard.Entries -> extract(clip.archive, clip.format, clip.password, dest, clip.entries, clip.baseDir) {
                clipboard = null
            }
        }
    }

    // ------------------------------------------------------------ file operations

    fun createFolder(name: String) = fileAction {
        val dir = currentDir() ?: return@fileAction
        FileOps.createFolder(dir, name)
    }

    fun createFile(name: String) = fileAction {
        val dir = currentDir() ?: return@fileAction
        val file = FileOps.createFile(dir, name)
        withContext(Dispatchers.Main) { showDetail(DetailTarget.Disk(file.path)) }
    }

    fun rename(item: FileItem, newName: String) = fileAction {
        val renamed = FileOps.rename(File(item.path), newName)
        withContext(Dispatchers.Main) {
            // Keep the detail pane pointing at the file under its new name.
            if ((detail?.target as? DetailTarget.Disk)?.path == item.path && detail?.isDirty != true) {
                showDetail(DetailTarget.Disk(renamed.path))
            }
            clearSelection()
        }
    }

    /** True when deleting from the current folder moves things to the trash rather than removing them. */
    val deletesToTrash: Boolean
        get() = settings.useTrash && (location as? Location.Disk)?.let { !Trash.isInTrash(it.path) } == true

    /** Deletes [items]: into the trash when it is in use and [permanently] is not asked for. */
    fun delete(items: List<FileItem>, permanently: Boolean = false) {
        val files = items.map { File(it.path) }
        clearSelection()
        when {
            location is Location.Trash -> {
                val entries = items.mapNotNull { trashEntries[it.path] }
                runOperation("Deleting") { progress ->
                    Trash.purge(entries, progress)
                    "Deleted ${plural(entries.size, "item")} for good"
                }
            }
            deletesToTrash && !permanently -> runOperation("Moving to trash") { progress ->
                val entries = Trash.moveToTrash(files, ::volumeRootOf, progress)
                withContext(Dispatchers.Main) {
                    message("Moved ${plural(entries.size, "item")} to the trash", "Undo") {
                        viewModelScope.launch {
                            try {
                                val restored = withContext(Dispatchers.IO) { Trash.restore(entries) }
                                message("Restored ${plural(restored.size, "item")}")
                            } catch (e: IOException) {
                                message(e.message ?: "Could not restore")
                            }
                            loadAll()
                        }
                    }
                }
                null
            }
            else -> runOperation("Deleting") { progress ->
                FileOps.delete(files, progress)
                "Deleted ${plural(files.size, "item")}"
            }
        }
    }

    fun restoreFromTrash(items: List<FileItem>) {
        val entries = items.mapNotNull { trashEntries[it.path] }
        clearSelection()
        runOperation("Restoring") { progress ->
            val restored = Trash.restore(entries, progress)
            withContext(Dispatchers.Main) {
                val folder = restored.singleOrNull()?.parentFile ?: restored.map { it.parentFile }.distinct().singleOrNull()
                if (folder != null) {
                    message("Restored ${plural(restored.size, "item")} to ${folder.name}", "Open") { navigate(Location.Disk(folder.path)) }
                } else {
                    message("Restored ${plural(restored.size, "item")}")
                }
            }
            null
        }
    }

    fun emptyTrash() {
        val entries = trashEntries.values.toList()
        clearSelection()
        runOperation("Emptying trash") { progress ->
            Trash.purge(entries, progress)
            "Trash emptied"
        }
    }

    /** Renames several items at once; the snackbar offers to undo it. */
    fun batchRename(steps: List<BatchRename.Step>) {
        clearSelection()
        viewModelScope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) { BatchRename.execute(steps) }
                message("Renamed ${plural(renamed.size, "item")}", "Undo") {
                    viewModelScope.launch {
                        try {
                            withContext(Dispatchers.IO) { BatchRename.undo(renamed) }
                            message("Old names restored")
                        } catch (e: IOException) {
                            message(e.message ?: "Could not undo the rename")
                        }
                        loadAll()
                    }
                }
            } catch (e: IOException) {
                message(e.message ?: "Could not rename")
            }
            loadAll()
        }
    }

    fun compress(files: List<File>, name: String, format: ArchiveFormat) {
        val dir = currentDir() ?: return
        clearSelection()
        runOperation("Compressing") { progress ->
            val target = FileOps.uniqueTarget(dir, "$name.${format.extension}")
            Archives.create(target, format, files, progress)
            "Created ${target.name} (${Format.size(target.length())})"
        }
    }

    /** Opens the extract dialog for an archive on disk, or for the entries selected inside one. */
    fun promptExtract(archivePath: String, entries: List<String>? = null, baseDir: String = "") {
        viewModelScope.launch {
            val file = File(archivePath)
            val session = try {
                archives[archivePath] ?: openArchive(archivePath)
            } catch (e: IOException) {
                message(e.message ?: "Could not read archive")
                null
            } ?: return@launch
            // A single top-level item can go straight next to the archive without cluttering it.
            val roots = (entries ?: session.index.list("").map { it.path })
                .map { (if (baseDir.isEmpty()) it else it.removePrefix("$baseDir/")).substringBefore('/') }
                .toSet()
            dialog = BrowserDialog.Extract(file, session.format, entries, baseDir, singleRoot = roots.size == 1)
        }
    }

    /** Extracts into [dest], creating it if needed. [onDone] runs on the main thread after success. */
    fun extract(
        archiveFile: File,
        format: ArchiveFormat,
        password: String?,
        dest: File,
        entries: List<String>?,
        baseDir: String,
        onDone: () -> Unit = {},
    ) {
        clearSelection()
        runOperation(
            "Extracting",
            onPassword = { pw ->
                updatePassword(archiveFile.path, pw)
                extract(archiveFile, format, pw, dest, entries, baseDir, onDone)
            },
            archiveName = archiveFile.name,
            hadPassword = password != null,
        ) { progress ->
            val count = Archives.extract(archiveFile, format, dest, password, entries, baseDir, progress)
            withContext(Dispatchers.Main) {
                onDone()
                message("Extracted ${plural(count, "file")} to ${dest.name}", "Open") { navigate(Location.Disk(dest.path)) }
            }
            null
        }
    }

    /** Extract dialog confirmed. [folderName] is null to extract straight into the archive's folder. */
    fun confirmExtract(dialog: BrowserDialog.Extract, folderName: String?) {
        val parent = dialog.archive.parentFile ?: return
        val dest = if (folderName == null) parent else FileOps.uniqueTarget(parent, folderName)
        val password = archives[dialog.archive.path]?.password
        extract(dialog.archive, dialog.format, password, dest, dialog.entries, dialog.baseDir)
    }

    /** Works out an auto-sort with the saved settings, using [mode] and [moveFolders] for this run. */
    fun planAutoSort(mode: AutoSortMode, moveFolders: Boolean) {
        val dir = currentDir() ?: return
        val config = settings.autoSort.copy(mode = mode, moveFolders = moveFolders)
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) { AutoSorter.plan(dir, config) }
            dialog = if (plan.moves.isEmpty()) {
                message("Nothing to sort here")
                null
            } else {
                BrowserDialog.AutoSortPreview(plan)
            }
        }
    }

    fun runAutoSort(plan: AutoSorter.Plan) {
        runOperation("Sorting") { progress ->
            val result = AutoSorter.execute(plan, progress)
            withContext(Dispatchers.Main) {
                message("Sorted ${plural(result.moved.size, "item")} into ${plural(plan.summary.size, "folder")}", "Undo") {
                    viewModelScope.launch {
                        val restored = withContext(Dispatchers.IO) { AutoSorter.undo(result) }
                        message("Restored ${plural(restored, "item")}")
                        loadAll()
                    }
                }
            }
            null
        }
    }

    fun share(items: List<FileItem>) {
        Launch.share(getApplication(), items.map { File(it.path) })
        clearSelection()
    }

    private fun currentDir(): File? = (location as? Location.Disk)?.path?.let(::File)

    /** Runs a quick file action off the main thread, reporting errors, then reloads. */
    private fun fileAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: IOException) {
                message(e.message ?: "Something went wrong")
            }
            loadAll()
        }
    }

    /**
     * Runs a long job with the progress dialog. [block] returns the message to
     * show when it finishes. When an archive turns out to need a password,
     * [onPassword] is offered the password the user types.
     */
    private fun runOperation(
        title: String,
        onPassword: ((String) -> Unit)? = null,
        archiveName: String = "",
        hadPassword: Boolean = false,
        block: suspend (Progress) -> String?,
    ) {
        if (operationJob?.isActive == true) {
            message("Please wait for the current task to finish")
            return
        }
        operationJob = viewModelScope.launch {
            _operation.value = OperationState(title, 0, 0, "")
            try {
                val result = withContext(Dispatchers.IO) {
                    val job = coroutineContext.job
                    val progress = Progress(isActive = { job.isActive }) { done, total, current ->
                        _operation.value = OperationState(title, done, total, current)
                    }
                    block(progress)
                }
                result?.let { message(it) }
            } catch (e: CancellationException) {
                message("$title cancelled")
            } catch (e: ArchivePasswordException) {
                if (onPassword != null) {
                    requestPassword(archiveName, wrong = hadPassword || e.wrongPassword, onSubmit = onPassword, onCancel = {})
                } else {
                    message(e.message ?: "Password required")
                }
            } catch (e: Exception) {
                message(e.message ?: "$title failed")
            } finally {
                _operation.value = null
                panes.forEach { it.folderSizes = emptyMap() }
                // The open file may just have been deleted, moved or trashed.
                val open = detail?.takeIf { !it.isDirty }?.target as? DetailTarget.Disk
                if (open != null && !File(open.path).exists()) closeDetail()
                loadAll()
            }
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    // ------------------------------------------------------------ detail pane

    /** Opens [target] in the detail pane, asking first if the current file has unsaved edits. */
    fun showDetail(target: DetailTarget) {
        val current = detail
        if (current != null && current.target != target && current.isDirty) {
            val editor = (current.content as DetailContent.Text).editor
            dialog = BrowserDialog.UnsavedChanges(
                current.target.name,
                onSave = {
                    dialog = null
                    viewModelScope.launch {
                        if (saveEditor(editor)) showDetail(target)
                    }
                },
                onDiscard = {
                    dialog = null
                    editor.revert()
                    showDetail(target)
                },
            )
            return
        }
        detailRequest = target.key
        if (current?.target == target && current.content !is DetailContent.Failed) return
        loadDetail(target)
    }

    fun consumeDetailRequest() {
        detailRequest = null
    }

    /** Called by the detail pane when the navigator shows a key we have no state for (e.g. after process death). */
    fun restoreDetail(key: String) {
        if (detail?.target?.key == key) return
        DetailTarget.fromKey(key)?.let(::loadDetail)
    }

    fun closeDetail() {
        detailJob?.cancel()
        detail = null
    }

    /** Re-opens the current file as text even though it did not look like text. */
    fun forceOpenAsText() {
        val target = detail?.target ?: return
        loadDetail(target, forceText = true)
    }

    /** Shows the raw bytes of the file that is open in the detail pane. */
    fun openAsHex(info: FileInfo) {
        val current = detail ?: return
        val file = info.localFile ?: return
        detail = DetailState(current.target, DetailContent.Hex(file, info))
    }

    /** Goes back from the hex view to the file's usual preview. */
    fun reloadDetail() {
        detail?.target?.let(::loadDetail)
    }

    /** The viewable images next to the one that is open, for stepping through a folder. */
    fun siblingImages(path: String): List<FileItem> = panes
        .filter { it.location is Location.Disk }
        .map { p -> p.items.filter { !it.isDirectory && FileTypes.isPreviewableImage(it.name) } }
        .firstOrNull { list -> list.any { it.path == path } }
        .orEmpty()

    suspend fun saveEditor(editor: EditorSession): Boolean = try {
        editor.save()
        message("Saved ${editor.file.name}")
        loadAll()
        true
    } catch (e: IOException) {
        message("Could not save: ${e.message}")
        false
    }

    private fun loadDetail(target: DetailTarget, forceText: Boolean = false) {
        detailJob?.cancel()
        detail = DetailState(target, DetailContent.Loading)
        detailJob = viewModelScope.launch {
            val content = try {
                when (target) {
                    is DetailTarget.Disk -> withContext(Dispatchers.IO) { diskContent(File(target.path), forceText) }
                    is DetailTarget.Entry -> entryContent(target, forceText)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ArchivePasswordException) {
                val archiveName = (target as? DetailTarget.Entry)?.archivePath?.let { File(it).name }.orEmpty()
                requestPassword(archiveName, e.wrongPassword,
                    onSubmit = { pw ->
                        (target as? DetailTarget.Entry)?.let { updatePassword(it.archivePath, pw) }
                        loadDetail(target, forceText)
                    },
                    onCancel = {},
                )
                DetailContent.Failed(e.message ?: "Password required")
            } catch (e: Exception) {
                DetailContent.Failed(e.message ?: "Could not open this file")
            }
            detail = DetailState(target, content)
        }
    }

    private fun diskContent(file: File, forceText: Boolean, readOnly: Boolean = false, entryRows: List<InfoRow>? = null): DetailContent {
        if (!file.exists()) throw IOException("\"${file.name}\" no longer exists")
        val kind = FileTypes.kindOf(file.name, file.isDirectory)
        val rows = entryRows ?: diskRows(file)
        val browsable = entryRows == null && file.isFile && ArchiveFormat.fromName(file.name) == null && ArchiveFormat.sniff(file) != null
        fun info(note: String? = null, canOpenAsText: Boolean = false) = DetailContent.Info(
            FileInfo(
                name = file.name,
                isDirectory = file.isDirectory,
                typeLabel = FileTypes.describe(file.name, file.isDirectory),
                rows = rows,
                localFile = file.takeIf { entryRows == null || it.isFile },
                browsableArchive = browsable,
                note = note,
                canOpenAsText = canOpenAsText,
            ),
        )
        if (file.isDirectory) return info()
        if (FileTypes.isPreviewableImage(file.name)) {
            return DetailContent.Image(file, info().info)
        }
        if (!forceText) {
            if (kind == FileKind.PDF) return DetailContent.Pdf(file, info().info)
            if (kind == FileKind.VIDEO || kind == FileKind.AUDIO) return DetailContent.Media(file, info().info, video = kind == FileKind.VIDEO)
        }
        val textCandidate = forceText || FileTypes.isText(file.name) || kind == FileKind.OTHER
        if (!textCandidate || browsable) return info()
        if (file.length() > TextDocument.EDIT_LIMIT) {
            val note = "This file is ${Format.size(file.length())}, too large for the built-in editor (limit ${Format.size(TextDocument.EDIT_LIMIT)})."
            return info(note = note.takeIf { FileTypes.isText(file.name) })
        }
        val bytes = file.readBytes()
        if (!forceText && TextDocument.looksBinary(bytes)) {
            return info(note = "This does not look like a text file.", canOpenAsText = true)
        }
        val writable = !readOnly && file.canWrite() && !Trash.isInTrash(file.path) && !file.path.startsWith(appCache)
        return DetailContent.Text(EditorSession(file, readOnly = !writable, TextDocument.decode(bytes)))
    }

    private fun diskRows(file: File): List<InfoRow> = buildList {
        add(InfoRow("Type", FileTypes.describe(file.name, file.isDirectory)))
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty()
            add(InfoRow("Contains", "${plural(children.count { it.isFile }, "file")}, ${plural(children.count { it.isDirectory }, "folder")}"))
            add(InfoRow("Total size", Format.size(FileOps.totalSize(listOf(file)))))
        } else {
            add(InfoRow("Size", "${Format.size(file.length())} (${"%,d".format(file.length())} bytes)"))
            add(InfoRow("MIME type", Launch.mimeType(file.name)))
            addAll(MediaInfo.rows(getApplication(), file))
        }
        trashEntries[file.absolutePath]?.let { entry ->
            add(InfoRow("Deleted from", File(entry.originalPath).parent ?: "/"))
            add(InfoRow("Deleted", Format.date(entry.deletedAt)))
        }
        add(InfoRow("Modified", Format.date(file.lastModified())))
        add(InfoRow("Location", file.parent ?: "/"))
        add(InfoRow("Permissions", buildString {
            append(if (file.canRead()) "Read" else "No read")
            append(if (file.canWrite()) " · Write" else " · Read only")
        }))
    }

    /** Pulls an entry out of its archive into the cache so it can be previewed. */
    private suspend fun entryContent(target: DetailTarget.Entry, forceText: Boolean): DetailContent {
        // openArchive has already asked for the password when it returns null.
        val session = openArchive(target.archivePath) ?: return DetailContent.Failed("This archive is password protected")
        val parentDir = ArchiveIndex.parentOf(target.entryPath)
        val item = session.index.list(parentDir).firstOrNull { it.path == target.entryPath }
            ?: throw IOException("\"${target.name}\" is not in this archive")
        val rows = buildList {
            add(InfoRow("Type", FileTypes.describe(item.name, false)))
            add(InfoRow("Size", Format.size(item.size)))
            if (item.compressedSize >= 0) {
                add(InfoRow("Compressed", Format.size(item.compressedSize) + (Format.ratio(item.compressedSize, item.size)?.let { " · $it" } ?: "")))
            }
            add(InfoRow("Modified", Format.date(item.modified)))
            add(InfoRow("In archive", "${session.file.name} › ${target.entryPath}"))
        }
        val previewable = FileTypes.isPreviewableImage(item.name) || FileTypes.isText(item.name) ||
            FileTypes.kindOf(item.name) in PREVIEWED_KINDS || forceText
        if (!previewable || item.size > PREVIEW_LIMIT) {
            return DetailContent.Info(
                FileInfo(item.name, false, FileTypes.describe(item.name, false), rows, null, false,
                    note = if (item.size > PREVIEW_LIMIT) "Too large to preview. Extract it to open." else null),
            )
        }
        val cached = File(previewCache, "${target.key.hashCode().toUInt()}/${item.name}")
        return withContext(Dispatchers.IO) {
            if (!cached.exists()) Archives.extractEntry(session.file, session.format, target.entryPath, cached, session.password)
            diskContent(cached, forceText, readOnly = true, entryRows = rows)
        }
    }

    // ------------------------------------------------------------ messages

    fun message(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _messages.trySend(UiMessage(text, actionLabel, action))
    }

    private fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"

    companion object {
        /** Largest archive entry pulled out automatically for a preview. */
        private const val PREVIEW_LIMIT = 64L * 1024 * 1024

        /** Kinds of archive entry worth pulling out for a preview, besides text and images. */
        /** Recent files listed on the home page. */
        private const val RECENT_ON_HOME = 8

        /** Under the cache folder: files opened from other apps that could not be read in place. */
        const val OPENED_DIR = "opened"

        private val PREVIEWED_KINDS = setOf(FileKind.OTHER, FileKind.PDF, FileKind.AUDIO, FileKind.VIDEO)
    }
}
