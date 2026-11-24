package io.github.l0gic0l.filefold.ui.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Where a dragged tab would land if it were let go now. */
sealed interface TabDrop {
    /** Into pane [pane]'s tab strip at [index] (0 = before the first tab). */
    data class Into(val pane: Int, val index: Int) : TabDrop

    /** Beside the only pane, opening split view. */
    data object Split : TabDrop

    /** Nowhere useful: the tab stays where it was. */
    data object Nowhere : TabDrop
}

/** The on-screen layout a tab is dragged over, in window coordinates. */
data class DropLayout(
    /** The whole folder area. */
    val area: Rect,
    /** Each pane's bounds, in pane order. */
    val panes: List<Rect>,
    /** Each pane's tab strip. */
    val strips: List<Rect>,
    /** Each pane's tabs, in strip order. */
    val tabs: List<List<Rect>>,
    /** True when split view puts the panes side by side; false when one is above the other. */
    val sideBySide: Boolean,
)

/**
 * Works out where a tab dragged out of pane [from] lands at [pointer]:
 *  - over a tab strip, it goes into that pane's tabs before the first tab whose middle is past the finger;
 *  - over the other pane, it joins that pane's tabs at the end;
 *  - with only one pane, over the half where a second pane would open (right, or bottom), it opens split view.
 */
fun dropTarget(pointer: Offset, from: Int, layout: DropLayout): TabDrop {
    layout.strips.forEachIndexed { pane, strip ->
        if (strip.contains(pointer)) {
            val index = layout.tabs.getOrNull(pane).orEmpty().count { it.center.x < pointer.x }
            return TabDrop.Into(pane, index)
        }
    }
    layout.panes.forEachIndexed { pane, bounds ->
        if (pane != from && bounds.contains(pointer)) return TabDrop.Into(pane, layout.tabs.getOrNull(pane).orEmpty().size)
    }
    if (layout.panes.size == 1 && splitZone(layout).contains(pointer)) return TabDrop.Split
    return TabDrop.Nowhere
}

/** With one pane, the half of the folder area where dropping a tab opens it in a second pane. */
fun splitZone(layout: DropLayout): Rect {
    val area = layout.area
    return if (layout.sideBySide) {
        Rect(area.center.x, area.top, area.right, area.bottom)
    } else {
        Rect(area.left, area.center.y, area.right, area.bottom)
    }
}
