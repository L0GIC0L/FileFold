package io.github.l0gic0l.filefold.ui.browser

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Material's emphasised easing: a quick start that settles gently. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val SLIDE_MS = 400

/**
 * The folder area. Moving between folders animates: deeper slides in from the
 * right, up slides back. The back gesture is predictive: as the finger moves,
 * the current folder shrinks and slides away and its parent shows through
 * behind it, so you can see where you are going and let go to cancel.
 */
@Composable
fun FolderPages(vm: BrowserViewModel, bottomPadding: Dp) {
    val page = vm.page
    val transitionState = remember { SeekableTransitionState(page) }
    val scope = rememberCoroutineScope()

    // Taps, breadcrumbs and tab switches animate to the new page; after a back gesture this
    // finishes the animation from wherever the finger let go.
    LaunchedEffect(page) {
        if (transitionState.currentState != page || transitionState.targetState != page) transitionState.animateTo(page)
    }

    val parent = vm.parentLocation
    PredictiveBackHandler(enabled = parent != null && !vm.selectionMode && vm.search == null) { progress ->
        val target = Page(page.tabId, parent ?: return@PredictiveBackHandler)
        try {
            progress.collect { event -> transitionState.seekTo(event.progress, target) }
            vm.navigateUp()
        } catch (e: CancellationException) {
            // Gesture abandoned: spring back to the folder we are in.
            scope.launch { transitionState.animateTo(transitionState.currentState) }
            throw e
        }
    }

    val goingBack = transitionState.currentState.let { from ->
        val to = transitionState.targetState
        from.tabId == to.tabId && from.location.isInside(to.location)
    }
    rememberTransition(transitionState, label = "folders").AnimatedContent(
        transitionSpec = { folderTransform() },
        contentKey = { it.scrollKey },
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        // The departing folder gets rounded corners as it shrinks away, like a card being put down.
        val corner by transition.animateDp(label = "corner") { state ->
            if (state == EnterExitState.PostExit && goingBack) 28.dp else 0.dp
        }
        FolderPage(vm, shown, isCurrent = shown == vm.page, bottomPadding, Modifier.clip(RoundedCornerShape(corner)))
    }
}

private fun AnimatedContentTransitionScope<Page>.folderTransform(): ContentTransform {
    val from = initialState
    val to = targetState
    val sameTab = from.tabId == to.tabId
    val slide = tween<androidx.compose.ui.unit.IntOffset>(SLIDE_MS, easing = Emphasized)
    return when {
        // Into a sub-folder: the new one slides in over the old.
        sameTab && to.location.isInside(from.location) ->
            (slideInHorizontally(slide) { it / 6 } + fadeIn(tween(200, delayMillis = 50))) togetherWith
                (slideOutHorizontally(slide) { -it / 12 } + fadeOut(tween(150)))

        // Up a level: the current folder shrinks and slides off, revealing its parent underneath.
        sameTab && from.location.isInside(to.location) ->
            ((slideInHorizontally(slide) { -it / 12 } + fadeIn(tween(250))) togetherWith
                (slideOutHorizontally(slide) { it / 5 } + scaleOut(tween(SLIDE_MS, easing = Emphasized), targetScale = 0.9f) +
                    fadeOut(tween(SLIDE_MS)))).apply { targetContentZIndex = -1f }

        // Another tab, or a jump elsewhere: a quick fade-through.
        else -> fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90))
    }.using(SizeTransform(clip = false))
}

/** One folder: breadcrumbs, archive banner and contents. Pages that are animating out or being previewed are inert. */
@Composable
private fun FolderPage(vm: BrowserViewModel, page: Page, isCurrent: Boolean, bottomPadding: Dp, modifier: Modifier) {
    val location = page.location
    val inArchive = location is Location.InArchive
    val crumbs = remember(location, vm.places) { vm.breadcrumbs(location) }
    val items = if (isCurrent) vm.items else remember(page) { vm.itemsFor(location) }

    // Each folder in each tab keeps its own scroll position.
    val saved = remember(page) { vm.scrollMemory[page.scrollKey] }
    val listState = remember(page) { LazyListState(saved?.first ?: 0, saved?.second ?: 0) }
    val gridState = remember(page) { LazyGridState(saved?.first ?: 0, saved?.second ?: 0) }
    DisposableEffect(page) {
        onDispose {
            vm.scrollMemory[page.scrollKey] = if (vm.settings.gridView) {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            } else {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
        }
    }
    // If the folder was not cached, its items arrive after the list was first laid out at the top.
    LaunchedEffect(page, items.isNotEmpty()) {
        if (items.isEmpty() || saved == null || saved.first == 0) return@LaunchedEffect
        if (vm.settings.gridView) {
            if (gridState.firstVisibleItemIndex == 0) gridState.scrollToItem(saved.first, saved.second)
        } else if (listState.firstVisibleItemIndex == 0) {
            listState.scrollToItem(saved.first, saved.second)
        }
    }

    // Opaque, so the folders do not show through each other while they move.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Breadcrumbs(crumbs) { if (isCurrent) vm.navigate(it) }
            val archive = vm.archive
            if (location is Location.InArchive && archive != null && archive.file.path == location.archivePath &&
                (!isCurrent || vm.error == null)
            ) {
                ArchiveBanner(archive) { if (isCurrent) vm.promptExtract(archive.file.path) }
            }
            var userRefresh by remember { mutableStateOf(false) }
            LaunchedEffect(vm.loading) { if (!vm.loading) userRefresh = false }
            PullToRefreshBox(
                isRefreshing = userRefresh && isCurrent,
                onRefresh = { if (isCurrent) { userRefresh = true; vm.refresh() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                // Room at the bottom so the last row can scroll clear of the FAB and gesture bar.
                val contentPadding = PaddingValues(bottom = bottomPadding + 96.dp)
                val selection = if (isCurrent) vm.selection else emptySet()
                val onClick: (io.github.l0gic0l.filefold.core.FileItem) -> Unit = { if (isCurrent) vm.open(it) }
                val onLongClick: (io.github.l0gic0l.filefold.core.FileItem) -> Unit = { if (isCurrent) vm.toggleSelection(it) }
                val error = if (isCurrent) vm.error else null
                when {
                    error != null -> ErrorState(error) { vm.refresh() }
                    items.isEmpty() && (!isCurrent || vm.loading) -> DelayedSpinner()
                    items.isEmpty() -> EmptyState(searching = !vm.search.isNullOrBlank(), inArchive = inArchive)
                    vm.settings.gridView -> FileGrid(items, selection, inArchive, gridState, contentPadding, onClick, onLongClick)
                    else -> FileList(items, selection, inArchive, listState, contentPadding, onClick, onLongClick)
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

/** The open tabs, shown once there is more than one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabStrip(vm: BrowserViewModel) {
    val state = rememberLazyListState()
    LaunchedEffect(vm.activeTabId, vm.tabs.size) {
        val index = vm.tabs.indexOfFirst { it.id == vm.activeTabId }
        if (index >= 0) state.animateScrollToItem(index)
    }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        items(vm.tabs, key = { it.id }) { tab ->
            val active = tab.id == vm.activeTabId
            Surface(
                onClick = { vm.switchTab(tab.id) },
                shape = RoundedCornerShape(14.dp),
                color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.animateItem(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Icon(
                        if (tab.location is Location.InArchive) Icons.Default.FolderZip else Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        vm.tabLabel(tab.location),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    IconButton(onClick = { vm.closeTab(tab.id) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Close tab", Modifier.size(16.dp))
                    }
                }
            }
        }
        item(key = "new") {
            IconButton(onClick = { vm.newTab() }) { Icon(Icons.Default.Add, "New tab") }
        }
    }
}
