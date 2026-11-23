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
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.VerticalSplit
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
            if (sideBySide) {
                Row(Modifier.fillMaxSize()) {
                    PaneColumn(vm, panes[0], panes[0] === focused, true, true, drag, bottomPadding, Modifier.weight(ratio).fillMaxHeight())
                    SplitDivider(sideBySide = true, onDrag = resize)
                    PaneColumn(vm, panes[1], panes[1] === focused, true, true, drag, bottomPadding, Modifier.weight(1f - ratio).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Only the lower pane reaches the gesture bar.
                    PaneColumn(vm, panes[0], panes[0] === focused, true, false, drag, 0.dp, Modifier.weight(ratio).fillMaxWidth())
                    SplitDivider(sideBySide = false, onDrag = resize)
                    PaneColumn(vm, panes[1], panes[1] === focused, true, false, drag, bottomPadding, Modifier.weight(1f - ratio).fillMaxWidth())
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
    val shape = RoundedCornerShape(20.dp)
    val frame = if (split) {
        Modifier
            .padding(4.dp)
            .clip(shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
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
        FolderPages(vm, p, focused, bottomPadding)
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
    LaunchedEffect(p.activeTabId, p.tabs.size) {
        val index = p.tabs.indexOfFirst { it.id == p.activeTabId }
        if (index >= 0) state.animateScrollToItem(index)
    }
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onGloballyPositioned { drag.stripBounds[p] = it.boundsInWindow() },
    ) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
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
                            modifier = Modifier.widthIn(max = 160.dp).padding(vertical = 10.dp),
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
                Icon(if (sideBySide) Icons.Default.VerticalSplit else Icons.Default.HorizontalSplit, "Split view")
            }
        }
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
                label = "Drop to open side by side",
                icon = if (layout.sideBySide) Icons.Default.VerticalSplit else Icons.Default.HorizontalSplit,
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

@Composable
private fun DropZone(bounds: Rect, label: String, icon: ImageVector, active: Boolean) {
    if (bounds.isEmpty) return
    val density = LocalDensity.current
    val shape = RoundedCornerShape(24.dp)
    val color = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .padding(8.dp)
            .background(color.copy(alpha = if (active) 0.24f else 0.08f), shape)
            .border(2.dp, color.copy(alpha = if (active) 1f else 0.4f), shape),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, color = color, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}
