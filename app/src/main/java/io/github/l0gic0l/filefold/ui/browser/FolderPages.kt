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
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * True while something drawn over the folders (the navigation drawer) owns the back gesture.
 * The folders' own back handling stands aside then, whatever order the handlers were set up in:
 * a pane rebuilt after split view closes would otherwise be the last one set up, and win.
 */
val LocalBackTakenOver = compositionLocalOf { false }

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
fun FolderPages(vm: BrowserViewModel, p: FolderPane, focused: Boolean, bottomPadding: Dp) {
    val page = p.page
    val transitionState = remember { SeekableTransitionState(page) }
    val scope = rememberCoroutineScope()

    // Taps, breadcrumbs and tab switches animate to the new page; after a back gesture this
    // finishes the animation from wherever the finger let go.
    LaunchedEffect(page) {
        if (transitionState.currentState != page || transitionState.targetState != page) transitionState.animateTo(page)
    }

    // Only the focused side of split view answers the back gesture.
    val parent = vm.parentOf(p.location)
    val takenOver = LocalBackTakenOver.current
    PredictiveBackHandler(enabled = !takenOver && focused && parent != null && !p.selectionMode && p.search == null) { progress ->
        val target = Page(page.tabId, parent ?: return@PredictiveBackHandler)
        try {
            progress.collect { event -> transitionState.seekTo(event.progress, target) }
            vm.navigateUp(p)
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
        FolderPage(vm, p, shown, isCurrent = shown == p.page, bottomPadding, Modifier.clip(RoundedCornerShape(corner)))
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
private fun FolderPage(vm: BrowserViewModel, p: FolderPane, page: Page, isCurrent: Boolean, bottomPadding: Dp, modifier: Modifier) {
    val location = page.location
    if (location is Location.Home) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
            HomeScreen(vm, p, enabled = isCurrent, bottomPadding = bottomPadding)
        }
        return
    }
    val inArchive = location is Location.InArchive
    val crumbs = remember(location, vm.places) { vm.breadcrumbs(location) }
    val items = if (isCurrent) p.items else remember(page) { vm.itemsFor(location, p) }

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

    // Search results and folder sizes arrive bit by bit and re-sort the list as they do. Left alone,
    // the list would follow whichever row was first and hide newer rows above it, so it is held at
    // the top until the reader scrolls it themselves.
    val query = if (isCurrent) p.search else null
    val usage = isCurrent && p.sizeView && location is Location.Disk && query?.isActive != true
    var holdAtTop by remember(page, query, usage) { mutableStateOf(true) }
    LaunchedEffect(page, query, usage) {
        snapshotFlow { listState.isScrollInProgress || gridState.isScrollInProgress }.first { it }
        holdAtTop = false
    }
    if (holdAtTop && (query?.isActive == true || usage)) {
        SideEffect {
            listState.requestScrollToItem(0)
            gridState.requestScrollToItem(0)
        }
    }

    // Opaque, so the folders do not show through each other while they move.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Anything done in a pane first makes it the focused one, so the top bar follows.
            Breadcrumbs(crumbs) { if (isCurrent) { vm.focus(p); vm.navigate(it, p) } }
            if (usage) StorageBanner(vm, p)
            if (isCurrent && location is Location.Trash && items.isNotEmpty()) TrashBanner()
            val archive = (location as? Location.InArchive)?.let { vm.archiveFor(it.archivePath) }
            if (archive != null && (!isCurrent || p.error == null)) {
                ArchiveBanner(archive) { if (isCurrent) { vm.focus(p); vm.promptExtract(archive.file.path) } }
            }
            var userRefresh by remember { mutableStateOf(false) }
            LaunchedEffect(p.loading) { if (!p.loading) userRefresh = false }
            PullToRefreshBox(
                isRefreshing = userRefresh && isCurrent,
                onRefresh = { if (isCurrent) { userRefresh = true; vm.focus(p); vm.refresh() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                // Room at the bottom so the last row can scroll clear of the FAB, toolbar and gesture bar.
                val contentPadding = PaddingValues(bottom = bottomPadding + 96.dp)
                val selection = if (isCurrent) p.selection else emptySet()
                val onClick: (io.github.l0gic0l.filefold.core.FileItem) -> Unit = { if (isCurrent) { vm.focus(p); vm.open(it) } }
                val onLongClick: (io.github.l0gic0l.filefold.core.FileItem) -> Unit = { if (isCurrent) { vm.focus(p); vm.toggleSelection(it) } }
                val error = if (isCurrent) p.error else null
                val searching = isCurrent && p.search?.isActive == true
                val thumbnails = !inArchive && vm.settings.showThumbnails
                val total = p.sizeViewTotal
                when {
                    error != null -> ErrorState(error) { vm.focus(p); vm.refresh() }
                    items.isEmpty() && (!isCurrent || p.loading || p.searching) -> DelayedSpinner()
                    items.isEmpty() -> EmptyState(
                        when {
                            searching -> "No matches"
                            location is Location.Trash -> "The trash is empty"
                            inArchive -> "This folder in the archive is empty"
                            else -> "This folder is empty"
                        },
                    )
                    vm.settings.gridView && !usage -> FileGrid(items, selection, inArchive, thumbnails, gridState, contentPadding, onClick, onLongClick)
                    else -> FileList(
                        items, selection, inArchive, thumbnails, listState, contentPadding, onClick, onLongClick,
                        hint = if (isCurrent && (searching || location is Location.Trash)) { item -> vm.hintFor(p, item) } else null,
                        share = if (usage && total > 0) { item -> item.size.toFloat() / total } else null,
                    )
                }
            }
        }
    }
}

/** A loading indicator that only appears if loading takes long enough to notice, so fast folders do not flicker. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DelayedSpinner() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250)
        visible = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (visible) LoadingIndicator()
    }
}
