package io.github.l0gic0l.filefold.ui.browser

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import io.github.l0gic0l.filefold.data.SettingsRepository
import io.github.l0gic0l.filefold.ui.detail.DetailContent
import io.github.l0gic0l.filefold.ui.detail.DetailState
import io.github.l0gic0l.filefold.ui.detail.DetailTarget
import io.github.l0gic0l.filefold.ui.detail.EditorSession
import io.github.l0gic0l.filefold.ui.detail.FileInfo
import io.github.l0gic0l.filefold.ui.detail.InfoRow
import io.github.l0gic0l.filefold.util.Format
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
 * State and actions for browsing. One instance serves both panes, so a
 * foldable can open, close or rotate without losing anything.
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val previewCache = File(app.cacheDir, "preview")

    var settings by mutableStateOf(AppSettings())
        private set

    /** The active tab's folder. */
    var location by mutableStateOf<Location>(Location.Disk(Storage.primaryRoot.absolutePath))
        private set

    private var nextTabId = 1L

    /** Open tabs, in display order. */
    var tabs by mutableStateOf(listOf(BrowserTab(nextTabId++, location)))
        private set
    var activeTabId by mutableLongStateOf(tabs.first().id)
        private set

    /** What the folder area is showing; transitions animate between pages. */
    val page: Page get() = Page(activeTabId, location)

    /** Storage volumes (internal, SD card, USB) followed by the standard folders. Refreshed on resume. */
    var places by mutableStateOf(Storage.volumes(app) + Storage.shortcuts())
        private set
    private val volumes: List<Place> get() = places.filter { it.isVolume }

    /** The current folder's contents, sorted and filtered. */
    var items by mutableStateOf<List<FileItem>>(emptyList())
        private set
    private var rawItems: List<FileItem> = emptyList()

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** What is being searched for in this folder and everything below it; null when search is closed. */
    var search by mutableStateOf<SearchQuery?>(null)
        private set

    /** True while a search is still walking the folders. */
    var searching by mutableStateOf(false)
        private set

    /** True when a search stopped at [FileSearch.MAX_RESULTS]. */
    var searchTruncated by mutableStateOf(false)
        private set
    private var searchResults: List<FileItem> = emptyList()
    private var searchJob: Job? = null
    private var searchGeneration = 0

    /** Storage usage view: folders show the size of everything inside them and the biggest come first. */
    var sizeView by mutableStateOf(false)
        private set

    /** True while folder sizes are still being added up. */
    var sizing by mutableStateOf(false)
        private set

    /** Bytes used by everything listed in the storage usage view. */
    var sizeViewTotal by mutableLongStateOf(0L)
        private set
    private var folderSizes: Map<String, Long> = emptyMap()
    private var sizeJob: Job? = null

    /** What is in the trash, by where each item sits now. */
    @Volatile
    private var trashEntries: Map<String, Trash.Entry> = emptyMap()

    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    val selectionMode: Boolean get() = selection.isNotEmpty()

    var clipboard by mutableStateOf<Clipboard?>(null)
        private set

    var archive by mutableStateOf<ArchiveSession?>(null)
        private set

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

    private var loadJob: Job? = null
    private var operationJob: Job? = null
    private var detailJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            previewCache.deleteRecursively()
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
                    first -> load()
                    new.showHidden != old.showHidden -> {
                        listingCache.clear()
                        load()
                    }
                    new.sort != old.sort -> applyFilterAndSort()
                }
                first = false
            }
        }
    }

    // ------------------------------------------------------------ navigation

    fun navigate(to: Location) {
        if (to == location) return
        tabs = tabs.map { if (it.id == activeTabId) it.copy(location = to) else it }
        show(to)
    }

    /** Switches the folder area to [to]: the cached listing at once if there is one, then a fresh one. */
    private fun show(to: Location) {
        location = to
        selection = emptySet()
        resetSearch()
        error = null
        // Never the previous folder's files: either this folder's last listing or nothing.
        rawItems = listingCache[to.key].orEmpty()
        applyFilterAndSort()
        load()
        persistTabs()
    }

    // ------------------------------------------------------------ tabs

    /** Opens a new tab at [at] (the current folder by default), right after the active tab. */
    fun newTab(at: Location = location) {
        val tab = BrowserTab(nextTabId++, at)
        val index = tabs.indexOfFirst { it.id == activeTabId }
        tabs = tabs.toMutableList().apply { add(index + 1, tab) }
        switchTab(tab.id)
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

    fun switchTab(id: Long) {
        if (id == activeTabId) return
        val tab = tabs.firstOrNull { it.id == id } ?: return
        activeTabId = id
        show(tab.location)
    }

    fun closeTab(id: Long) {
        if (tabs.size <= 1) return
        val index = tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val remaining = tabs.filterNot { it.id == id }
        tabs = remaining
        scrollMemory.keys.removeAll { it.startsWith("$id|") }
        if (id == activeTabId) {
            val next = remaining[index.coerceAtMost(remaining.lastIndex)]
            activeTabId = next.id
            show(next.location)
        } else {
            persistTabs()
        }
    }

    /** A short name for a tab: the folder's name, or the archive's for its root. */
    fun tabLabel(loc: Location): String = when (loc) {
        is Location.Disk -> volumes.firstOrNull { it.path == loc.path }?.label ?: File(loc.path).name.ifEmpty { "/" }
        is Location.InArchive -> if (loc.dir.isEmpty()) File(loc.archivePath).name else ArchiveIndex.nameOf(loc.dir)
        Location.Trash -> "Trash"
    }

    private fun persistTabs() {
        val saved = tabs.map { it.location.encode() }
        val active = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
        viewModelScope.launch { settingsRepo.setTabs(saved, active) }
    }

    /** Reopens the tabs from last time, skipping folders that have since gone. */
    private suspend fun restoreTabs() {
        val saved = settingsRepo.savedTabs.first()
        val restored = withContext(Dispatchers.IO) {
            saved.tabs.mapNotNull(Location::decode).filter { it is Location.Trash || File(it.diskPath).exists() }
        }
        if (restored.isEmpty()) return
        tabs = restored.map { BrowserTab(nextTabId++, it) }
        val active = tabs[saved.active.coerceIn(0, tabs.lastIndex)]
        activeTabId = active.id
        location = active.location
    }

    fun openPlace(path: String) = navigate(Location.Disk(path))

    fun openTrash() = navigate(Location.Trash)

    /** Everything changed in the last few days, anywhere on internal storage, newest first. */
    fun openRecent() {
        navigate(Location.Disk(Storage.primaryRoot.absolutePath))
        search = SearchQuery(recent = true)
        runSearch()
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
        if (operationJob?.isActive != true) load()
    }

    /** Goes one level up. Returns false at the top of a storage volume. */
    fun navigateUp(): Boolean {
        val parent = parentOf(location) ?: return false
        navigate(parent)
        return true
    }

    private fun parentOf(location: Location): Location? = when (location) {
        is Location.InArchive ->
            if (location.dir.isEmpty()) Location.Disk(File(location.archivePath).parent ?: return null)
            else Location.InArchive(location.archivePath, ArchiveIndex.parentOf(location.dir))
        is Location.Disk -> {
            val root = Storage.volumeRootOf(volumes, location.path)
            if (root == null || location.path == root) null else File(location.path).parent?.let { Location.Disk(it) }
        }
        // Leaving the trash goes back to the top of internal storage.
        Location.Trash -> Location.Disk(Storage.primaryRoot.absolutePath)
    }

    private fun volumeRoots(): List<File> = volumes.map { File(it.path) }

    private fun volumeRootOf(file: File): File? = Storage.volumeRootOf(volumes, file.absolutePath)?.let(::File)

    val canNavigateUp: Boolean get() = parentOf(location) != null

    /** Where "up" leads from the current folder, or null at the top of a volume. */
    val parentLocation: Location? get() = parentOf(location)

    /** Handles the back gesture in the browser. Returns false when there is nothing left to undo. */
    fun back(): Boolean = when {
        selectionMode -> { clearSelection(); true }
        search != null -> { closeSearch(); true }
        else -> navigateUp()
    }

    fun breadcrumbs(loc: Location = location): List<Crumb> {
        if (loc is Location.Trash) return listOf(Crumb("Trash", loc))
        val crumbs = ArrayList<Crumb>()
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

    /** What happens when an item is tapped. */
    fun open(item: FileItem) {
        if (selectionMode) {
            toggleSelection(item)
            return
        }
        when (val loc = location) {
            is Location.Disk -> when {
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
            is Location.Disk -> File(item.path).parent?.let { navigate(Location.Disk(it)) }
            is Location.InArchive -> navigate(Location.InArchive(loc.archivePath, ArchiveIndex.parentOf(item.path)))
            Location.Trash -> Unit
        }
    }

    /** Opens a ZIP-style container that has another extension, such as an APK or DOCX. */
    fun browseAsArchive(path: String) = navigate(Location.InArchive(path, ""))

    // ------------------------------------------------------------ loading

    fun refresh() {
        folderSizes = emptyMap()
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val target = location
        loadJob = viewModelScope.launch {
            loading = true
            try {
                var problem: String? = null
                rawItems = when (target) {
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
                }
                error = problem
                if (problem == null) listingCache[target.key] = rawItems
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rawItems = emptyList()
                listingCache.remove(target.key)
                error = e.message ?: "Could not open this folder"
            } finally {
                loading = false
            }
            applyFilterAndSort()
            if (search?.isActive == true) runSearch()
            computeSizes()
            prefetchParent(target)
        }
    }

    /** Lists the parent folder ahead of time, so a back gesture can show it as it starts. */
    private suspend fun prefetchParent(of: Location) {
        val parent = parentOf(of) ?: return
        if (listingCache.containsKey(parent.key)) return
        val listing = try {
            when (parent) {
                is Location.Disk -> withContext(Dispatchers.IO) { FileOps.list(File(parent.path), settings.showHidden) }
                is Location.InArchive -> archive?.takeIf { it.file.path == parent.archivePath }?.index?.list(parent.dir)
                Location.Trash -> null
            }
        } catch (e: IOException) {
            null
        } ?: return
        listingCache[parent.key] = listing
    }

    /** What to draw for [loc] while it animates in or out: live for the current folder, cached otherwise. */
    fun itemsFor(loc: Location): List<FileItem> =
        if (loc == location) items else listingCache[loc.key]?.sortedWith(settings.sort.comparator()).orEmpty()

    /**
     * Returns the cached session for an archive, reading its index when it is
     * new or has changed on disk. Asks for a password when the index itself is
     * encrypted; returns null if the user gives up.
     */
    private suspend fun openArchive(path: String, password: String? = archive?.takeIf { it.file.path == path }?.password): ArchiveSession? {
        val file = File(path)
        archive?.let { if (it.file.path == path && it.lastModified == file.lastModified()) return it }
        val format = withContext(Dispatchers.IO) { ArchiveFormat.of(file) }
            ?: throw IOException("This is not an archive FileFold can read")
        return try {
            val entries = withContext(Dispatchers.IO) { Archives.list(file, format, password) }
            ArchiveSession(file, format, ArchiveIndex(format, entries), password, file.lastModified()).also { archive = it }
        } catch (e: ArchivePasswordException) {
            requestPassword(file.name, wrong = password != null,
                onSubmit = { pw -> viewModelScope.launch { if (openArchive(path, pw) != null) load() } },
                onCancel = { if (location is Location.InArchive) navigate(parentOf(Location.InArchive(path, ""))!!) },
            )
            null
        }
    }

    private fun requestPassword(name: String, wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        dialog = BrowserDialog.Password(name, wrong,
            onSubmit = { dialog = null; onSubmit(it) },
            onCancel = { dialog = null; onCancel() },
        )
    }

    private fun applyFilterAndSort() {
        val query = search?.takeIf { it.isActive }
        val usage = sizeView && query == null && location is Location.Disk
        val source = when {
            query != null -> searchResults
            usage -> rawItems.map { if (it.isDirectory) it.copy(size = folderSizes[it.path] ?: 0) else it }
            else -> rawItems
        }
        val byName = Comparator<FileItem> { a, b -> NaturalOrder.compare(a.name, b.name) }
        val order = when {
            query?.recent == true || location is Location.Trash -> compareByDescending<FileItem> { it.modified }.then(byName)
            query?.large == true || usage -> compareByDescending<FileItem> { it.size }.then(byName)
            else -> settings.sort.comparator()
        }
        items = source.sortedWith(order)
        sizeViewTotal = if (usage) source.sumOf { it.size } else 0L
        // Drop selected paths that are no longer listed, e.g. after a delete.
        val present = source.mapTo(HashSet()) { it.path }
        if (selection.any { it !in present }) selection = selection.filterTo(HashSet()) { it in present }
    }

    // ------------------------------------------------------------ search, sort, view

    fun openSearch() {
        search = SearchQuery()
    }

    fun updateSearch(query: SearchQuery) {
        val typing = query.text != search?.text
        search = query
        runSearch(debounce = typing)
    }

    fun closeSearch() {
        resetSearch()
        applyFilterAndSort()
    }

    private fun resetSearch() {
        searchJob?.cancel()
        searchGeneration++
        search = null
        searching = false
        searchTruncated = false
        searchResults = emptyList()
    }

    /**
     * Searches the current folder and everything below it. Matches are shown as they are
     * found. While typing, what is already listed is narrowed at once and the walk starts
     * after a short pause, so each keystroke does not start a search of its own.
     */
    private fun runSearch(debounce: Boolean = false) {
        searchJob?.cancel()
        val generation = ++searchGeneration
        val query = search?.takeIf { it.isActive }
        searchTruncated = false
        if (query == null) {
            searching = false
            searchResults = emptyList()
            applyFilterAndSort()
            return
        }
        val now = System.currentTimeMillis()
        fun matches(item: FileItem) = query.matches(item.name, item.isDirectory, item.size, item.modified, now)
        searchResults = searchResults.filter(::matches)
        applyFilterAndSort()
        searching = true
        val target = location
        searchJob = viewModelScope.launch {
            if (debounce) delay(250)
            // Only touched on the main thread; batches from the walk are posted here in order.
            val found = ArrayList<FileItem>()
            fun deliver(batch: List<FileItem>) {
                if (generation != searchGeneration) return
                found += batch
                searchResults = found.toList()
                applyFilterAndSort()
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
                        if (generation == searchGeneration) searchTruncated = truncated
                    }
                    is Location.InArchive -> archive?.takeIf { it.file.path == target.archivePath }?.let { session ->
                        deliver(session.index.search(target.dir, FileSearch.MAX_RESULTS, ::matches))
                    }
                    Location.Trash -> deliver(rawItems.filter(::matches))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A folder that cannot be walked just ends the search with what was found.
            }
            if (generation == searchGeneration) {
                // Replaces the narrowed-down old results, also when nothing at all was found.
                searchResults = found.toList()
                searching = false
                applyFilterAndSort()
            }
        }
    }

    /** Extra text under a file's name: where a search result or a trashed item lives. */
    fun hintFor(item: FileItem): String? = when (val loc = location) {
        Location.Trash -> trashEntries[item.path]?.let { "from ${File(it.originalPath).parentFile?.name ?: "/"}" }
        is Location.Disk -> if (search?.isActive != true) null else {
            val parent = File(item.path).parent.orEmpty()
            if (parent == loc.path) null else "in ${parent.removePrefix(loc.path).trimStart('/')}"
        }
        is Location.InArchive -> if (search?.isActive != true) null else {
            val parent = ArchiveIndex.parentOf(item.path)
            if (parent == loc.dir) null else "in ${parent.removePrefix(loc.dir).trimStart('/')}"
        }
    }

    // ------------------------------------------------------------ storage usage

    /** Turns the storage usage view on or off. It stays on while moving between folders. */
    fun showSizes(on: Boolean) {
        sizeView = on
        if (!on) {
            sizeJob?.cancel()
            sizing = false
            folderSizes = emptyMap()
        }
        applyFilterAndSort()
        computeSizes()
    }

    /** Adds up the folders in the current listing one by one, re-sorting as each size comes in. */
    private fun computeSizes() {
        sizeJob?.cancel()
        sizing = false
        if (!sizeView || location !is Location.Disk) return
        val pending = rawItems.filter { it.isDirectory && it.path !in folderSizes }
        if (pending.isEmpty()) return
        sizing = true
        sizeJob = viewModelScope.launch {
            for (dir in pending) {
                val size = withContext(Dispatchers.IO) {
                    val job = coroutineContext.job
                    FileSearch.folderSize(File(dir.path), Progress(isActive = { job.isActive }))
                }
                folderSizes = folderSizes + (dir.path to size)
                applyFilterAndSort()
            }
            sizing = false
        }
    }

    /** The storage volume the current folder is on. */
    val currentVolume: Place? get() = Storage.volumeRootOf(volumes, location.diskPath)?.let { root -> volumes.firstOrNull { it.path == root } }

    fun setSort(sort: SortOptions) = viewModelScope.launch { settingsRepo.setSort(sort) }
    fun setShowHidden(value: Boolean) = viewModelScope.launch { settingsRepo.setShowHidden(value) }
    fun setGridView(value: Boolean) = viewModelScope.launch { settingsRepo.setGridView(value) }
    fun setEditorWordWrap(value: Boolean) = viewModelScope.launch { settingsRepo.setEditorWordWrap(value) }
    fun setEditorLineNumbers(value: Boolean) = viewModelScope.launch { settingsRepo.setEditorLineNumbers(value) }
    fun setUseTrash(value: Boolean) = viewModelScope.launch { settingsRepo.setUseTrash(value) }
    fun setEditorFontSize(value: Float) = viewModelScope.launch { settingsRepo.setEditorFontSize(value.coerceIn(8f, 32f)) }

    // ------------------------------------------------------------ selection

    fun toggleSelection(item: FileItem) {
        selection = if (item.path in selection) selection - item.path else selection + item.path
    }

    fun selectAll() {
        selection = items.mapTo(HashSet()) { it.path }
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun invertSelection() {
        selection = items.mapNotNullTo(HashSet()) { item -> item.path.takeIf { it !in selection } }
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
            Location.Trash -> return
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
                            load()
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
                        load()
                    }
                }
            } catch (e: IOException) {
                message(e.message ?: "Could not rename")
            }
            load()
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
                if (archive?.file?.path == archivePath) archive else openArchive(archivePath)
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
                if (archive?.file == archiveFile) archive = archive?.copy(password = pw)
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
        val password = archive?.takeIf { it.file == dialog.archive }?.password
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
                        load()
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
            load()
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
                folderSizes = emptyMap()
                // The open file may just have been deleted, moved or trashed.
                val open = detail?.takeIf { !it.isDirty }?.target as? DetailTarget.Disk
                if (open != null && !File(open.path).exists()) closeDetail()
                load()
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
    fun siblingImages(path: String): List<FileItem> =
        if (location !is Location.Disk) emptyList()
        else items.filter { !it.isDirectory && FileTypes.isPreviewableImage(it.name) }.takeIf { list -> list.any { it.path == path } }.orEmpty()

    suspend fun saveEditor(editor: EditorSession): Boolean = try {
        editor.save()
        message("Saved ${editor.file.name}")
        load()
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
                        archive = archive?.copy(password = pw)
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
        val writable = !readOnly && file.canWrite() && !Trash.isInTrash(file.path)
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
        private val PREVIEWED_KINDS = setOf(FileKind.OTHER, FileKind.PDF, FileKind.AUDIO, FileKind.VIDEO)
    }
}
