package io.github.l0gic0l.filefold.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.MutableThreePaneScaffoldState
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import io.github.l0gic0l.filefold.ui.browser.BrowserDialogs
import io.github.l0gic0l.filefold.ui.browser.BrowserPane
import io.github.l0gic0l.filefold.ui.browser.BrowserViewModel
import io.github.l0gic0l.filefold.ui.browser.Location
import io.github.l0gic0l.filefold.ui.browser.OperationDialog
import io.github.l0gic0l.filefold.ui.browser.openSettings
import io.github.l0gic0l.filefold.ui.detail.DetailPane
import io.github.l0gic0l.filefold.util.Format
import io.github.l0gic0l.filefold.util.Place
import java.io.File
import kotlinx.coroutines.launch

/**
 * The whole app. The layout follows the window, not the device:
 *  - folded / phone: one pane at a time, storage locations in a drawer;
 *  - unfolded / tablet: files and the open file side by side, placed either
 *    side of the hinge, with a draggable divider and a navigation rail.
 * Everything is driven by one ViewModel, so folding or unfolding mid-task
 * keeps the current folder, selection and any unsaved edits.
 */
@Composable
fun FileFoldApp(vm: BrowserViewModel = viewModel()) {
    // Size the layout from the space we are actually given. The window size Compose reports
    // can lag a fold or unfold, which would leave the app stuck in the old layout.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val posture = currentWindowAdaptiveInfoV2().windowPosture
        val sizeClass = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(maxWidth.value, maxHeight.value)
        AdaptiveApp(vm, WindowAdaptiveInfo(sizeClass, posture))
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun AdaptiveApp(vm: BrowserViewModel, adaptiveInfo: WindowAdaptiveInfo) {
    val wide = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    // Two panes from medium width up, which covers the inner screen of book-style foldables.
    // The default hinge policy keeps both panes clear of a separating hinge.
    // In split view the two folders take the whole width, and an opened file shows over them.
    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(adaptiveInfo).let {
        if (vm.isSplit) it.copy(maxHorizontalPartitions = 1) else it
    }
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = directive)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // When the device folds or unfolds, the navigator works out the new pane layout but
    // only moves its displayed state on the next navigation. Move it straight away.
    LaunchedEffect(directive) {
        val state = navigator.scaffoldState as? MutableThreePaneScaffoldState ?: return@LaunchedEffect
        if (state.targetState != navigator.scaffoldValue) state.snapTo(navigator.scaffoldValue)
    }

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    // The ViewModel asks for the detail pane; the navigator decides how to show it on this screen.
    LaunchedEffect(vm.detailRequest) {
        val key = vm.detailRequest ?: return@LaunchedEffect
        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
        vm.consumeDetailRequest()
    }
    // When the open file goes away (e.g. it was deleted), leave the detail pane.
    LaunchedEffect(vm.detail == null) {
        if (vm.detail == null && navigator.currentDestination?.contentKey != null) {
            navigator.navigateBack(BackNavigationBehavior.PopUntilContentChange)
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message.text,
                actionLabel = message.actionLabel,
                withDismissAction = message.actionLabel != null,
                duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) message.action?.invoke()
        }
    }

    val panes: @Composable (showMenuButton: Boolean) -> Unit = { showMenuButton ->
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            listPane = {
                AnimatedPane {
                    BrowserPane(vm, showMenuButton = showMenuButton, onMenu = { scope.launch { drawerState.open() } })
                }
            },
            detailPane = {
                AnimatedPane {
                    val listHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
                    DetailPane(
                        vm,
                        contentKey = navigator.currentDestination?.contentKey,
                        showBack = listHidden,
                        onBack = { scope.launch { navigator.navigateBack() } },
                    )
                }
            },
            // A handle between the panes, so the split can be dragged to suit what is open.
            paneExpansionDragHandle = { state ->
                val interaction = remember { MutableInteractionSource() }
                VerticalDragHandle(
                    modifier = Modifier.paneExpansionDraggable(state, LocalMinimumInteractiveComponentSize.current, interaction),
                    interactionSource = interaction,
                )
            },
        )
    }

    // The panes must stay at one place in the composition whatever the width: they share the
    // navigator's state, and rebuilding them elsewhere (e.g. on rotating to landscape, which
    // brings in the rail) would attach that state to two scaffolds at once and crash.
    LaunchedEffect(wide) { if (wide) drawerState.snapTo(DrawerValue.Closed) }
    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Only swipe-to-close; opening is from the menu button so edge swipes stay with system back.
            gesturesEnabled = !wide && drawerState.isOpen,
            drawerContent = {
                if (!wide) {
                    ModalDrawerSheet {
                        PlacesDrawer(vm) { scope.launch { drawerState.close() } }
                    }
                }
            },
        ) {
            Row(Modifier.fillMaxSize()) {
                if (wide) PlacesRail(vm)
                Box(Modifier.weight(1f)) { panes(!wide) }
            }
        }

        // Composed after the folders, so it wins over their back gesture: back closes an open drawer first.
        BackHandler(enabled = !wide && drawerState.isOpen) { scope.launch { drawerState.close() } }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(bottom = 80.dp)
                .widthIn(max = 600.dp),
        )
    }

    BrowserDialogs(vm)
    val operation by vm.operation.collectAsStateWithLifecycle()
    operation?.let { OperationDialog(it, vm::cancelOperation) }
}

/** The storage location or favourite that best matches where the browser is, for highlighting. */
private fun currentPlace(vm: BrowserViewModel): String? {
    val path = vm.location.diskPath
    if (path.isEmpty()) return null
    return (vm.places.map { it.path } + vm.settings.favorites).filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }
}

/** The drawer on a folded phone. [onDone] closes it after a choice. */
@Composable
private fun PlacesDrawer(vm: BrowserViewModel, onDone: () -> Unit) {
    val current = currentPlace(vm)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(
            "FileFold",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        val open: (String) -> Unit = { vm.openPlace(it); onDone() }
        NavigationDrawerItem(
            label = { Text("Home") },
            icon = { Icon(Icons.Default.Home, null) },
            selected = vm.location is Location.Home,
            onClick = { vm.openHome(); onDone() },
        )
        val (volumes, shortcuts) = vm.places.partition { it.isVolume }
        volumes.forEach { place ->
            NavigationDrawerItem(
                label = {
                    Column {
                        Text(place.label)
                        if (place.total > 0) {
                            Text(
                                "${Format.size(place.free)} free of ${Format.size(place.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                icon = { Icon(place.icon, null) },
                selected = place.path == current,
                onClick = { open(place.path) },
            )
        }
        if (shortcuts.isNotEmpty()) {
            DrawerDivider()
            shortcuts.forEach { place ->
                NavigationDrawerItem(
                    label = { Text(place.label) },
                    icon = { Icon(place.icon, null) },
                    selected = place.path == current,
                    onClick = { open(place.path) },
                )
            }
        }
        val favorites = vm.settings.favorites
        if (favorites.isNotEmpty()) {
            DrawerDivider()
            Text(
                "Favourites",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            favorites.forEach { path ->
                NavigationDrawerItem(
                    label = { Text(File(path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.Default.Star, null) },
                    badge = {
                        IconButton(onClick = { vm.toggleFavorite(path) }) { Icon(Icons.Default.Close, "Remove from favourites") }
                    },
                    selected = path == current,
                    onClick = { open(path) },
                )
            }
        }
        DrawerDivider()
        NavigationDrawerItem(
            label = { Text("Recent files") },
            icon = { Icon(Icons.Default.History, null) },
            selected = false,
            onClick = { vm.openRecent(); onDone() },
        )
        NavigationDrawerItem(
            label = { Text("Trash") },
            icon = { Icon(Icons.Default.Delete, null) },
            selected = vm.location is Location.Trash,
            onClick = { vm.openTrash(); onDone() },
        )
        DrawerDivider()
        val context = LocalContext.current
        NavigationDrawerItem(
            label = { Text("Settings") },
            icon = { Icon(Icons.Default.Settings, null) },
            selected = false,
            onClick = {
                openSettings(context)
                onDone()
            },
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DrawerDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

/** The rail on an unfolded device or in landscape: the same destinations as the drawer. */
@Composable
private fun PlacesRail(vm: BrowserViewModel) {
    val current = currentPlace(vm)
    NavigationRail {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            NavigationRailItem(
                selected = vm.location is Location.Home,
                onClick = { vm.openHome() },
                icon = { Icon(Icons.Default.Home, null) },
                label = { Text("Home") },
            )
            vm.places.forEach { place ->
                NavigationRailItem(
                    selected = place.path == current,
                    onClick = { vm.openPlace(place.path) },
                    icon = { Icon(place.icon, null) },
                    label = {
                        Text(
                            if (place.isVolume && place.label == "Internal storage") "Internal" else place.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
            vm.settings.favorites.forEach { path ->
                NavigationRailItem(
                    selected = path == current,
                    onClick = { vm.openPlace(path) },
                    icon = { Icon(Icons.Default.Star, null) },
                    label = { Text(File(path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            NavigationRailItem(
                selected = false,
                onClick = { vm.openRecent() },
                icon = { Icon(Icons.Default.History, null) },
                label = { Text("Recent") },
            )
            NavigationRailItem(
                selected = vm.location is Location.Trash,
                onClick = { vm.openTrash() },
                icon = { Icon(Icons.Default.Delete, null) },
                label = { Text("Trash") },
            )
            Spacer(Modifier.height(8.dp))
            val context = LocalContext.current
            NavigationRailItem(
                selected = false,
                onClick = { openSettings(context) },
                icon = { Icon(Icons.Default.Settings, null) },
                label = { Text("Settings") },
            )
        }
    }
}
