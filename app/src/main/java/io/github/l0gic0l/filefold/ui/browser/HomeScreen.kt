package io.github.l0gic0l.filefold.ui.browser

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.core.FileSearch
import io.github.l0gic0l.filefold.core.SearchQuery
import io.github.l0gic0l.filefold.core.SortGroup
import io.github.l0gic0l.filefold.ui.components.FileIcon
import io.github.l0gic0l.filefold.ui.components.iconFor
import io.github.l0gic0l.filefold.ui.theme.LocalFileColors
import io.github.l0gic0l.filefold.util.Format
import io.github.l0gic0l.filefold.util.Place
import io.github.l0gic0l.filefold.util.Storage
import java.io.File

/** A tile on the home page that lists every file of one kind on internal storage. */
private data class Category(val label: String, val kind: FileKind, val icon: ImageVector, val query: SearchQuery?)

private val categories = listOf(
    Category("Images", FileKind.IMAGE, iconFor(FileKind.IMAGE), SearchQuery(group = SortGroup.IMAGES)),
    Category("Videos", FileKind.VIDEO, iconFor(FileKind.VIDEO), SearchQuery(group = SortGroup.VIDEOS)),
    Category("Audio", FileKind.AUDIO, iconFor(FileKind.AUDIO), SearchQuery(group = SortGroup.AUDIO)),
    Category("Documents", FileKind.DOCUMENT, iconFor(FileKind.DOCUMENT), SearchQuery(group = SortGroup.DOCUMENTS)),
    Category("Archives", FileKind.ARCHIVE, iconFor(FileKind.ARCHIVE), SearchQuery(group = SortGroup.ARCHIVES)),
    Category("Apps", FileKind.APK, iconFor(FileKind.APK), SearchQuery(group = SortGroup.APPS)),
    // No query: opens the Download folder itself.
    Category("Downloads", FileKind.FOLDER, Icons.Default.Download, null),
    Category("Large files", FileKind.DISK_IMAGE, Icons.Default.DataUsage, SearchQuery(large = true)),
)

/**
 * The start page: how full each storage volume is, shortcuts to every image, video, document
 * and so on, the standard folders, favourites, and files changed in the last week.
 */
@Composable
fun HomeScreen(vm: BrowserViewModel, p: FolderPane, enabled: Boolean, bottomPadding: Dp) {
    // A page animating in or out is only a picture; taps act once it is the one shown.
    val act: (() -> Unit) -> Unit = { action -> if (enabled) { vm.focus(p); action() } }
    val (volumes, shortcuts) = vm.places.partition { it.isVolume }
    val favorites = vm.settings.favorites
    val recent = vm.recentFiles
    val wide = Modifier.widthIn(max = 720.dp).fillMaxWidth()
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(volumes, key = { "volume:" + it.path }) { volume ->
            StorageCard(volume, wide, onOpen = { act { vm.openPlace(volume.path) } }, onAnalyse = { act { vm.analyse(volume) } })
        }

        item(key = "categories") {
            Column(wide) {
                SectionTitle("Categories")
                CategoryGrid { category ->
                    act {
                        val query = category.query
                        if (query != null) {
                            vm.openCategory(query)
                        } else {
                            vm.openPlace(File(Storage.primaryRoot, Environment.DIRECTORY_DOWNLOADS).absolutePath)
                        }
                    }
                }
            }
        }

        item(key = "places") {
            Column(wide) {
                SectionTitle("Folders")
                PlaceChips(shortcuts, favorites, onPlace = { path -> act { vm.openPlace(path) } }, onTrash = { act { vm.openTrash() } })
            }
        }

        item(key = "recent-title") {
            Box(wide) {
                SectionTitle("Recent files", action = if (recent.isNullOrEmpty()) null else ("See all" to { act { vm.openRecent() } }))
            }
        }
        when {
            recent == null -> item(key = "recent-loading") {
                Box(wide.height(96.dp), contentAlignment = Alignment.Center) { HomeLoading() }
            }
            recent.isEmpty() -> item(key = "recent-none") {
                Text(
                    "Nothing has changed in the last ${FileSearch.RECENT_DAYS} days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = wide.padding(horizontal = 4.dp),
                )
            }
            else -> items(recent, key = { "recent:" + it.path }) { item ->
                ListItem(
                    headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                    supportingContent = {
                        val folder = File(item.path).parentFile?.name.orEmpty()
                        Text(
                            listOf(Format.size(item.size), Format.shortDate(item.modified), "in $folder").joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { FileIcon(item, 40.dp, showThumbnail = vm.settings.showThumbnails) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = wide.clip(RoundedCornerShape(16.dp)).clickable { act { vm.open(item) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeLoading() = LoadingIndicator()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StorageCard(volume: Place, modifier: Modifier, onOpen: () -> Unit, onAnalyse: () -> Unit) {
    val used = (volume.total - volume.free).coerceAtLeast(0)
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(volume.icon, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(volume.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onAnalyse) { Text("Storage usage") }
            }
            if (volume.total > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Format.size(used)} used of ${Format.size(volume.total)}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(12.dp))
                LinearWavyProgressIndicator(
                    progress = { used.toFloat() / volume.total },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("${Format.size(volume.free)} free", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: Pair<String, () -> Unit>? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp),
        )
        if (action != null) TextButton(onClick = action.second) { Text(action.first) }
    }
}

/** Category tiles in even columns: four on a phone, more where there is room. */
@Composable
private fun CategoryGrid(onClick: (Category) -> Unit) {
    val colors = LocalFileColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 88.dp).toInt().coerceIn(4, 8)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            categories.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { category ->
                        val (container, content) = colors[category.kind]
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { onClick(category) }
                                .padding(vertical = 8.dp),
                        ) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(container),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(category.icon, contentDescription = null, tint = content, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                category.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    // Keeps a short last row's tiles the same width as the rest.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceChips(shortcuts: List<Place>, favorites: List<String>, onPlace: (String) -> Unit, onTrash: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shortcuts.forEach { place ->
            AssistChip(onClick = { onPlace(place.path) }, label = { Text(place.label) }, leadingIcon = { Icon(place.icon, null, Modifier.size(18.dp)) })
        }
        favorites.forEach { path ->
            AssistChip(
                onClick = { onPlace(path) },
                label = { Text(File(path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) },
            )
        }
        AssistChip(onClick = onTrash, label = { Text("Trash") }, leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) })
    }
}
