package io.github.l0gic0l.filefold.ui.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class TabDropTest {

    // A 1000 × 2000 folder area. Tab strips are 100 high; tabs are 200 wide with 10 between them.
    private fun tabs(top: Float, count: Int, left: Float = 0f) =
        (0 until count).map { Rect(left + 10f + it * 210f, top + 10f, left + 210f + it * 210f, top + 90f) }

    private val single = DropLayout(
        area = Rect(0f, 0f, 1000f, 2000f),
        panes = listOf(Rect(0f, 0f, 1000f, 2000f)),
        strips = listOf(Rect(0f, 0f, 1000f, 100f)),
        tabs = listOf(tabs(0f, 3)),
        sideBySide = false,
    )

    // Split one above the other: the second pane starts at 1000.
    private val stacked = DropLayout(
        area = Rect(0f, 0f, 1000f, 2000f),
        panes = listOf(Rect(0f, 0f, 1000f, 1000f), Rect(0f, 1000f, 1000f, 2000f)),
        strips = listOf(Rect(0f, 0f, 1000f, 100f), Rect(0f, 1000f, 1000f, 1100f)),
        tabs = listOf(tabs(0f, 2), tabs(1000f, 1)),
        sideBySide = false,
    )

    @Test
    fun `dropping on a strip places the tab before the first tab past the finger`() {
        assertEquals(TabDrop.Into(0, 0), dropTarget(Offset(50f, 50f), 0, single))
        assertEquals(TabDrop.Into(0, 1), dropTarget(Offset(200f, 50f), 0, single))
        assertEquals(TabDrop.Into(0, 2), dropTarget(Offset(450f, 50f), 0, single))
        // Past the last tab, in the empty end of the strip.
        assertEquals(TabDrop.Into(0, 3), dropTarget(Offset(900f, 50f), 0, single))
    }

    @Test
    fun `with one pane, the lower half opens split view and the upper half does nothing`() {
        assertEquals(TabDrop.Split, dropTarget(Offset(500f, 1500f), 0, single))
        assertEquals(TabDrop.Nowhere, dropTarget(Offset(500f, 600f), 0, single))
        assertEquals(TabDrop.Nowhere, dropTarget(Offset(500f, 2500f), 0, single))
    }

    @Test
    fun `side by side, the right half opens split view`() {
        val wide = single.copy(sideBySide = true)
        assertEquals(TabDrop.Split, dropTarget(Offset(800f, 600f), 0, wide))
        assertEquals(TabDrop.Nowhere, dropTarget(Offset(200f, 600f), 0, wide))
        assertEquals(Rect(500f, 0f, 1000f, 2000f), splitZone(wide))
        assertEquals(Rect(0f, 1000f, 1000f, 2000f), splitZone(single))
    }

    @Test
    fun `in split view a tab joins the other pane, or its strip at a place`() {
        // Anywhere in the other pane: added after its tabs.
        assertEquals(TabDrop.Into(1, 1), dropTarget(Offset(500f, 1600f), 0, stacked))
        // On the other pane's strip, before its only tab.
        assertEquals(TabDrop.Into(1, 0), dropTarget(Offset(50f, 1050f), 0, stacked))
        // Back over its own pane's list: nothing happens, and there is no third pane to open.
        assertEquals(TabDrop.Nowhere, dropTarget(Offset(500f, 600f), 0, stacked))
        // From the lower pane up into the first.
        assertEquals(TabDrop.Into(0, 2), dropTarget(Offset(500f, 600f), 1, stacked))
    }
}
