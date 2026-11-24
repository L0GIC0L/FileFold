package io.github.l0gic0l.filefold.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.l0gic0l.filefold.core.FileItem
import io.github.l0gic0l.filefold.core.SearchQuery
import kotlinx.coroutines.Job

/**
 * One folder view: its own tabs, the folder its active tab shows, and everything about that
 * listing (search, selection, storage usage). Split view shows two of these side by side.
 * Only [BrowserViewModel] changes it; the screen reads it.
 */
class FolderPane(tabs: List<BrowserTab>, activeTabId: Long = tabs.first().id) {

    /** Open tabs, in display order. */
    var tabs by mutableStateOf(tabs)
        internal set
    var activeTabId by mutableLongStateOf(activeTabId)
        internal set

    /** The active tab's folder. */
    var location by mutableStateOf(tabs.first { it.id == activeTabId }.location)
        internal set

    /** What the folder area is showing; transitions animate between pages. */
    val page: Page get() = Page(activeTabId, location)

    /** The folder's contents (or the search results), sorted and filtered. */
    var items by mutableStateOf<List<FileItem>>(emptyList())
        internal set
    internal var rawItems: List<FileItem> = emptyList()

    var loading by mutableStateOf(false)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    /** What is being searched for in this folder and everything below it; null when search is closed. */
    var search by mutableStateOf<SearchQuery?>(null)
        internal set

    /** True while a search is still walking the folders. */
    var searching by mutableStateOf(false)
        internal set

    /** True when a search stopped at its result limit. */
    var searchTruncated by mutableStateOf(false)
        internal set
    internal var searchResults: List<FileItem> = emptyList()
    internal var searchJob: Job? = null
    internal var searchGeneration = 0

    /** Storage usage view: folders show the size of everything inside them and the biggest come first. */
    var sizeView by mutableStateOf(false)
        internal set

    /** True while folder sizes are still being added up. */
    var sizing by mutableStateOf(false)
        internal set

    /** Bytes used by everything listed in the storage usage view. */
    var sizeViewTotal by mutableLongStateOf(0L)
        internal set
    internal var folderSizes: Map<String, Long> = emptyMap()
    internal var sizeJob: Job? = null

    var selection by mutableStateOf<Set<String>>(emptySet())
        internal set
    val selectionMode: Boolean get() = selection.isNotEmpty()

    internal var loadJob: Job? = null

    /** Stops everything running for this pane, when it is closed. */
    internal fun cancelJobs() {
        loadJob?.cancel()
        searchJob?.cancel()
        sizeJob?.cancel()
    }
}
