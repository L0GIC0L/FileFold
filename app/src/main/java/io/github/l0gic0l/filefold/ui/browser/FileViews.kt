package io.github.l0gic0l.filefold.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.filefold.core.FileItem
import io.github.l0gic0l.filefold.ui.components.FileIcon
import io.github.l0gic0l.filefold.util.Format

/** "12 KB · 3 Nov 2026", or "5 items · 3 Nov 2026" for folders, then [hint] such as "in Work/2026". */
private fun subtitle(item: FileItem, inArchive: Boolean, hint: String?): String {
    val first = when {
        item.isDirectory && inArchive -> Format.items(item.itemCount) + " · " + Format.size(item.size)
        item.isDirectory -> Format.items(item.itemCount)
        else -> Format.size(item.size)
    }
    val ratio = if (inArchive && !item.isDirectory) Format.ratio(item.compressedSize, item.size) else null
    val date = Format.shortDate(item.modified)
    return listOfNotNull(first, ratio, date.ifEmpty { null }, hint).joinToString(" · ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileList(
    items: List<FileItem>,
    selection: Set<String>,
    inArchive: Boolean,
    thumbnails: Boolean,
    state: LazyListState,
    contentPadding: PaddingValues,
    onClick: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
    /** Extra text for the second line, e.g. the folder a search result is in. */
    hint: ((FileItem) -> String?)? = null,
    /** Storage usage view: each item's share of the folder, from 0 to 1, drawn as a bar. */
    share: ((FileItem) -> Float)? = null,
) {
    val selecting = selection.isNotEmpty()
    LazyColumn(state = state, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        // The content type lets a scrolled-off row be reused for one shaped the same way.
        items(items, key = { it.path }, contentType = { it.kind }) { item ->
            val selected = item.path in selection
            ListItem(
                headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                supportingContent = {
                    if (share == null) {
                        Text(subtitle(item, inArchive, hint?.invoke(item)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        val part = share(item).coerceIn(0f, 1f)
                        Column {
                            Text("${Format.size(item.size)} · ${Format.percent(part)}", maxLines = 1)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(progress = { part }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                        }
                    }
                },
                leadingContent = { FileIcon(item, 40.dp, showThumbnail = thumbnails) },
                trailingContent = if (selecting) {
                    { Checkbox(checked = selected, onCheckedChange = { onClick(item) }) }
                } else {
                    null
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) }),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGrid(
    items: List<FileItem>,
    selection: Set<String>,
    inArchive: Boolean,
    thumbnails: Boolean,
    state: LazyGridState,
    contentPadding: PaddingValues,
    onClick: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        state = state,
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.path }, contentType = { it.kind }) { item ->
            val selected = item.path in selection
            val shape = RoundedCornerShape(20.dp)
            Surface(
                shape = shape,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .animateItem()
                    .clip(shape)
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) }),
            ) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        FileIcon(item, size = 96.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1f), showThumbnail = thumbnails)
                        if (selected) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.MiddleEllipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (item.isDirectory) Format.items(item.itemCount) else Format.size(item.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The path as tappable chips; scrolls so the current folder is always visible. */
@Composable
fun Breadcrumbs(crumbs: List<Crumb>, onClick: (Location) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(crumbs) { if (crumbs.isNotEmpty()) state.animateScrollToItem(crumbs.lastIndex) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        itemsIndexed(crumbs, key = { _, crumb -> crumb.location.key }) { index, crumb ->
            val last = index == crumbs.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onClick(crumb.location) }, enabled = !last) {
                    Text(
                        crumb.label,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                if (!last) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Summary card shown at the top while browsing inside an archive. */
@Composable
fun ArchiveBanner(session: ArchiveSession, onExtractAll: () -> Unit) {
    val index = session.index
    val compressed = session.file.length()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (index.hasEncryptedEntries || session.password != null) Icons.Default.Lock else Icons.Default.FolderZip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("${session.format.label} archive", style = MaterialTheme.typography.titleSmall)
                val parts = listOfNotNull(
                    "${index.fileCount} ${if (index.fileCount == 1) "file" else "files"}",
                    Format.size(index.totalSize),
                    Format.ratio(compressed, index.totalSize),
                )
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onExtractAll, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Extract")
            }
        }
    }
}

/** Shown at the top of the storage usage view: what this folder holds and how full the volume is. */
@Composable
fun StorageBanner(vm: BrowserViewModel) {
    val volume = vm.currentVolume
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (vm.sizing) "Adding up… ${Format.size(vm.sizeViewTotal)} so far" else "This folder holds ${Format.size(vm.sizeViewTotal)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (volume != null && volume.total > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { 1f - volume.free.toFloat() / volume.total },
                        modifier = Modifier.fillMaxWidth(),
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${volume.label}: ${Format.size(volume.free)} free of ${Format.size(volume.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { vm.showSizes(false) }) { Text("Done") }
        }
    }
}

@Composable
fun TrashBanner() {
    Text(
        "Items are deleted for good ${io.github.l0gic0l.filefold.core.Trash.KEEP_DAYS} days after they are put here. " +
            "Touch and hold an item to restore it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
fun EmptyState(title: String) {
    CenteredMessage(
        icon = { Icon(Icons.Default.FolderOpen, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline) },
        title = title,
    )
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    CenteredMessage(
        icon = { Icon(Icons.Default.Error, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error) },
        title = message,
        action = { OutlinedButton(onClick = onRetry) { Text("Try again") } },
    )
}

@Composable
private fun CenteredMessage(icon: @Composable () -> Unit, title: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
