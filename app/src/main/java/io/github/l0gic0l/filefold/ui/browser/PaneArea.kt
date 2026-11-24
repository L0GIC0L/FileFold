package io.github.l0gic0l.filefold.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A tab being dragged: which one, where the finger is, and where it would land. */
class TabDragState {
    var dragged by mutableStateOf<DraggedTab?>(null)
        private set

    /** The finger, in window coordinates. */
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var target by mutableStateOf<TabDrop>(TabDrop.Nowhere)
        private set

    // Where things are on screen, in window coordinates, as last laid out. Read only while dragging.
    internal var area: Rect = Rect.Zero
    internal var sideBySide = true
    internal val paneBounds = HashMap<FolderPane, Rect>()
    internal val stripBounds = HashMap<FolderPane, Rect>()
    internal val tabBounds = HashMap<Long, Rect>()

    fun layout(panes: List<FolderPane>) = DropLayout(
        area = area,
        panes = panes.map { paneBounds[it] ?: Rect.Zero },
        strips = panes.map { stripBounds[it] ?: Rect.Zero },
        tabs = panes.map { pane -> pane.tabs.mapNotNull { tabBounds[it.id] } },
        sideBySide = sideBySide,
    )

    internal fun start(tab: BrowserTab, from: FolderPane, label: String, grab: Offset, panes: List<FolderPane>) {
        val chip = tabBounds[tab.id] ?: return
        dragged = DraggedTab(tab, from, label, grab)
        pointer = chip.topLeft + grab
        target = dropTarget(pointer, panes.indexOf(from), layout(panes))
    }

    internal fun moveBy(delta: Offset, panes: List<FolderPane>) {
        val drag = dragged ?: return
        pointer += delta
        target = dropTarget(pointer, panes.indexOf(drag.from), layout(panes))
    }

    internal fun finish(vm: BrowserViewModel) {
        val drag = dragged ?: return
        val drop = target
        cancel()
        when (drop) {
            is TabDrop.Into -> vm.panes.getOrNull(drop.pane)?.let { vm.moveTab(drag.tab.id, drag.from, it, drop.index) }
            TabDrop.Split -> vm.moveTab(drag.tab.id, drag.from, null, 0)
            TabDrop.Nowhere -> Unit
        }
    }

    internal fun cancel() {
        dragged = null
        target = TabDrop.Nowhere
    }
}

// Split view's spacing. Each side's outline keeps clear of the screen edges and the gesture bar,
// and what is inside keeps clear of the outline's rounded corners.
private val SplitGutter = 8.dp
private val SplitCorner = 20.dp
private val SplitInset = 8.dp

/** [grab] is where on the tab the finger took hold, so the tab stays under it as it moves. */
data class DraggedTab(val tab: BrowserTab, val from: FolderPane, val label: String, val grab: Offset)

/**
 * The folder area: one pane, or two in split view, side by side where there is room (an
 * unfolded screen, or landscape) and one above the other otherwise. The divider between them
 * can be dragged. Tabs can be dragged between the panes, or out to open split view.
 */
@Composable
fun PaneArea(vm: BrowserViewModel, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val drag = remember { TabDragState() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                drag.area = it.boundsInWindow()
                origin = drag.area.topLeft
            },
    ) {
        val sideBySide = maxWidth >= 600.dp || maxWidth > maxHeight
        drag.sideBySide = sideBySide
        val panes = vm.panes
        val focused = vm.pane
        if (panes.size == 1) {
            PaneColumn(vm, panes[0], focused = true, split = false, sideBySide, drag, bottomPadding, Modifier.fillMaxSize())
        } else {
            var ratio by rememberSaveable { mutableFloatStateOf(0.5f) }
            val total = (if (sideBySide) constraints.maxWidth else constraints.maxHeight).toFloat().coerceAtLeast(1f)
            val resize: (Float) -> Unit = { delta -> ratio = (ratio + delta / total).coerceIn(0.25f, 0.75f) }
            // The outlines stop above the gesture bar (or the paste bar), rather than running under it.
            val gutters = Modifier
                .fillMaxSize()
                .padding(start = SplitGutter, end = SplitGutter, top = SplitGutter / 2, bottom = bottomPadding + SplitGutter)
            if (sideBySide) {
                Row(gutters) {
                    PaneColumn(vm, panes[0], panes[0] === focused, true, true, drag, 0.dp, Modifier.weight(ratio).fillMaxHeight())
                    SplitDivider(sideBySide = true, onDrag = resize)
                    PaneColumn(vm, panes[1], panes[1] === focused, true, true, drag, 0.dp, Modifier.weight(1f - ratio).fillMaxHeight())
                }
            } else {
                Column(gutters) {
                    PaneColumn(vm, panes[0], panes[0] === focused, true, false, drag, 0.dp, Modifier.weight(ratio).fillMaxWidth())
                    SplitDivider(sideBySide = false, onDrag = resize)
                    PaneColumn(vm, panes[1], panes[1] === focused, true, false, drag, 0.dp, Modifier.weight(1f - ratio).fillMaxWidth())
                }
            }
        }
        TabDragOverlay(vm, drag, origin)
    }
}

/** One pane: its tabs, the search filters while searching, and its folders. Touching it focuses it. */
@Composable
private fun PaneColumn(
    vm: BrowserViewModel,
    p: FolderPane,
    focused: Boolean,
    split: Boolean,
    sideBySide: Boolean,
    drag: TabDragState,
    bottomPadding: Dp,
    modifier: Modifier,
) {
    DisposableEffect(p) {
        onDispose {
            drag.paneBounds.remove(p)
            drag.stripBounds.remove(p)
        }
    }
    val shape = RoundedCornerShape(SplitCorner)
    val frame = if (split) {
        Modifier
            .clip(shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            // Room above the tabs, so the first one clears the rounded corner.
            .padding(top = SplitInset)
    } else {
        Modifier
    }
    Column(
        modifier
            .then(frame)
            .onGloballyPositioned { drag.paneBounds[p] = it.boundsInWindow() }
            // Watches every touch without taking it, so the list still scrolls and taps still open things.
            .pointerInput(p) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    vm.focus(p)
                }
            },
    ) {
        TabStrip(vm, p, split, sideBySide, drag)
        if (focused) p.search?.let { SearchFilters(vm, it) }
        Box(Modifier.weight(1f)) {
            FolderPages(vm, p, focused, bottomPadding)
            // The create button belongs to the side it creates in: the focused one.
            val canCreate = p.location is Location.Disk && !p.selectionMode && p.search == null && vm.clipboard == null
            if (focused && canCreate) {
                NewItemButton(vm, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomPadding + 16.dp))
            }
        }
    }
}

/** The handle between the two panes; drag it to give one side more room. */
@Composable
private fun SplitDivider(sideBySide: Boolean, onDrag: (Float) -> Unit) {
    val state = rememberDraggableState(onDrag)
    Box(
        modifier = (if (sideBySide) Modifier.fillMaxHeight().width(12.dp) else Modifier.fillMaxWidth().height(12.dp))
            .draggable(state, if (sideBySide) Orientation.Horizontal else Orientation.Vertical),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (sideBySide) 4.dp else 40.dp, if (sideBySide) 40.dp else 4.dp)
                .background(MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

private fun tabIcon(location: Location): ImageVector = when (location) {
    is Location.InArchive -> Icons.Default.FolderZip
    Location.Home -> Icons.Default.Home
    Location.Trash -> Icons.Default.RestoreFromTrash
    is Location.Disk -> Icons.Default.Folder
}

/**
 * A pane's tabs. Touch and hold a tab, then drag it: along the strip to reorder, onto the other
 * pane to move it there, or to the far side of a single pane to open it in split view.
 */
@Composable
private fun TabStrip(vm: BrowserViewModel, p: FolderPane, split: Boolean, sideBySide: Boolean, drag: TabDragState) {
    val state = rememberLazyListState()
    val density = LocalDensity.current
    // Brings the active tab fully into view, with a little room either side, only when it is not already.
    LaunchedEffect(p.activeTabId, p.tabs.size) {
        val index = p.tabs.indexOfFirst { it.id == p.activeTabId }.takeIf { it >= 0 } ?: return@LaunchedEffect
        if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
        val margin = with(density) { 24.dp.toPx() }
        val start = info.viewportStartOffset + margin
        val end = info.viewportEndOffset - margin
        val delta = when {
            item.offset < start -> item.offset - start
            item.offset + item.size > end -> minOf(item.offset + item.size - end, item.offset - start)
            else -> 0f
        }
        if (delta != 0f) state.animateScrollBy(delta)
    }
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // A little room below the top bar, so the tabs do not sit against its edge.
            .padding(top = if (split) 0.dp else 4.dp, end = if (split) 4.dp else 0.dp)
            .height(48.dp)
            .onGloballyPositioned { drag.stripBounds[p] = it.boundsInWindow() },
    ) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Tabs that run past either end fade out instead of being cut off.
            modifier = Modifier.weight(1f).fadeEdges(state, 20.dp),
        ) {
            items(p.tabs, key = { it.id }) { tab ->
                DisposableEffect(tab.id) { onDispose { drag.tabBounds.remove(tab.id) } }
                val active = tab.id == p.activeTabId
                val lifted = drag.dragged?.tab?.id == tab.id
                Surface(
                    onClick = { vm.switchTab(tab.id, p) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .animateItem()
                        // The tab stays in place, faded, while its copy follows the finger.
                        .alpha(if (lifted) 0.35f else 1f)
                        .onGloballyPositioned { drag.tabBounds[tab.id] = it.boundsInWindow() }
                        .pointerInput(tab.id, p) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { grab ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    drag.start(tab, p, vm.tabLabel(tab.location), grab, vm.panes)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag.moveBy(amount, vm.panes)
                                },
                                onDragEnd = { drag.finish(vm) },
                                onDragCancel = { drag.cancel() },
                            )
                        },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = if (p.tabs.size > 1 || split) 0.dp else 12.dp)) {
                        Icon(tabIcon(tab.location), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            vm.tabLabel(tab.location),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp).padding(vertical = 8.dp),
                        )
                        // The only tab of a single pane cannot be closed.
                        if (p.tabs.size > 1 || split) {
                            IconButton(onClick = { vm.closeTab(tab.id, p) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, "Close tab", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
        IconButton(onClick = { vm.newTab(at = p.location, p = p) }) { Icon(Icons.Default.Add, "New tab") }
        if (split) {
            IconButton(onClick = { vm.closeSide(p) }) { Icon(Icons.Default.CloseFullscreen, "Close this side") }
        } else {
            IconButton(onClick = { vm.openSplit() }) {
                // Two stacked windows; turned on its side when the panes would sit side by side.
                Icon(Icons.Default.Splitscreen, "Split view", Modifier.rotate(if (sideBySide) 90f else 0f))
            }
        }
    }
}

/** Fades the first and last [width] of a horizontal list where there is more to scroll to. */
private fun Modifier.fadeEdges(state: LazyListState, width: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width / 2)
        if (state.canScrollBackward) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** While a tab is dragged: a copy of it under the finger, and where it would land. */
@Composable
private fun TabDragOverlay(vm: BrowserViewModel, drag: TabDragState, origin: Offset) {
    val dragged = drag.dragged ?: return
    val panes = vm.panes
    val layout = drag.layout(panes)
    val target = drag.target
    Box(Modifier.fillMaxSize()) {
        if (panes.size == 1) {
            // The half where a second pane would open, shown as soon as the drag starts.
            DropZone(
                splitZone(layout).translate(-origin),
                label = if (layout.sideBySide) "Drop to open alongside" else "Drop to open below",
                icon = Icons.Default.Splitscreen,
                iconRotation = if (layout.sideBySide) 90f else 0f,
                active = target == TabDrop.Split,
            )
        } else {
            panes.forEachIndexed { index, pane ->
                if (pane !== dragged.from) {
                    DropZone(
                        layout.panes[index].translate(-origin),
                        label = "Move tab here",
                        icon = Icons.Default.Folder,
                        active = (target as? TabDrop.Into)?.pane == index,
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 8.dp,
            modifier = Modifier.offset {
                val at = drag.pointer - origin - dragged.grab
                IntOffset(at.x.roundToInt(), at.y.roundToInt())
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Icon(tabIcon(dragged.tab.location), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(dragged.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** Where a dragged tab can go: faint until the finger is over it, then solid, so the label always reads clearly. */
@Composable
private fun DropZone(bounds: Rect, label: String, icon: ImageVector, active: Boolean, iconRotation: Float = 0f) {
    if (bounds.isEmpty) return
    val density = LocalDensity.current
    val shape = RoundedCornerShape(24.dp)
    val scheme = MaterialTheme.colorScheme
    val fill = if (active) scheme.primaryContainer else scheme.surfaceContainerHigh.copy(alpha = 0.92f)
    val ink = if (active) scheme.onPrimaryContainer else scheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .padding(8.dp)
            .background(fill, shape)
            .border(2.dp, scheme.primary.copy(alpha = if (active) 1f else 0.5f), shape),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(40.dp).rotate(iconRotation))
            Spacer(Modifier.height(8.dp))
            Text(label, color = ink, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}
