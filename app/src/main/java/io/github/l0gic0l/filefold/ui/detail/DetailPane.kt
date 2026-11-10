package io.github.l0gic0l.filefold.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.filefold.ui.browser.BrowserViewModel

/**
 * The right-hand pane on an unfolded device, or a full screen on a folded one.
 * Shows the editor, an image, or file details depending on what was opened.
 *
 * @param showBack true when this pane is on its own (folded), so it needs a way back to the list.
 */
@Composable
fun DetailPane(vm: BrowserViewModel, contentKey: String?, showBack: Boolean, onBack: () -> Unit) {
    if (contentKey == null) {
        EmptyDetail()
        return
    }
    LaunchedEffect(contentKey) { vm.restoreDetail(contentKey) }
    val detail = vm.detail?.takeIf { it.target.key == contentKey }
    val name = detail?.target?.name ?: DetailTarget.fromKey(contentKey)?.name.orEmpty()
    when (val content = detail?.content) {
        null, DetailContent.Loading -> SimpleDetail(name, showBack, onBack) {
            CircularProgressIndicator()
        }
        is DetailContent.Failed -> SimpleDetail(name, showBack, onBack) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Error, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                Text(content.message, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
        is DetailContent.Text -> TextEditorScreen(vm, content.editor, showBack, onBack)
        is DetailContent.Image -> ImageScreen(vm, detail.target, content, showBack, onBack)
        is DetailContent.Info -> InfoScreen(vm, detail.target, content.info, showBack, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    subtitle: String? = null,
    insideTabletopControls: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        navigationIcon = {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to files") }
        },
        actions = actions,
        // On the lower half of a half-folded phone the bar is nowhere near the status bar.
        windowInsets = if (insideTabletopControls) WindowInsets(0) else TopAppBarDefaults.windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun SimpleDetail(title: String, showBack: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = { DetailTopBar(title, showBack, onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { content() }
    }
}

/** Shown in the second pane on an unfolded device before anything is opened. */
@Composable
private fun EmptyDetail() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Inventory2, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Text("Nothing open", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap a file to preview, edit or see its details here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
