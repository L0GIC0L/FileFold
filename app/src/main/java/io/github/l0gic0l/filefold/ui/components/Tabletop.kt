package io.github.l0gic0l.filefold.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset

/**
 * Lays out [top] and [bottom] one above the other. When a foldable is half
 * open like a laptop (tabletop posture) and its hinge crosses this area, the
 * split lands exactly on the hinge: content on the upright half, controls on
 * the half resting on the table. Otherwise [top] fills the space and [bottom]
 * sits under it at its natural height.
 *
 * [isTabletop] is passed to both slots so they can adapt, e.g. show more controls.
 */
@Composable
fun TabletopLayout(
    modifier: Modifier = Modifier,
    top: @Composable (isTabletop: Boolean) -> Unit,
    bottom: @Composable (isTabletop: Boolean) -> Unit,
) {
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val hinge = if (posture.isTabletop) {
        posture.hingeList.firstOrNull { !it.isVertical && !it.isFlat }?.bounds
    } else {
        null
    }
    val box = bounds
    // Only split when the hinge actually runs through this pane.
    val split = if (hinge != null && box != null && hinge.top > box.top && hinge.bottom < box.bottom) hinge else null

    Box(modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInWindow() }) {
        if (split != null && box != null) {
            val density = LocalDensity.current
            val topHeight = with(density) { (split.top - box.top).toDp() }
            val bottomStart = (split.bottom - box.top).toInt()
            val bottomHeight = with(density) { (box.bottom - split.bottom).toDp() }
            Box(Modifier.fillMaxWidth().height(topHeight)) { top(true) }
            Box(Modifier.fillMaxWidth().height(bottomHeight).offset { IntOffset(0, bottomStart) }) { bottom(true) }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) { top(false) }
                bottom(false)
            }
        }
    }
}
