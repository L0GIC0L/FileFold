package io.github.l0gic0l.filefold.ui.detail

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.l0gic0l.filefold.archive.ArchiveIndex
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.core.FileTypes
import io.github.l0gic0l.filefold.ui.browser.BrowserViewModel
import io.github.l0gic0l.filefold.ui.components.KindBadge
import io.github.l0gic0l.filefold.ui.components.TabletopLayout
import io.github.l0gic0l.filefold.util.Launch
import java.io.File

/** Image preview with pinch and double-tap zoom. In tabletop posture the details sit on the lower half. */
@Composable
fun ImageScreen(vm: BrowserViewModel, target: DetailTarget, content: DetailContent.Image, showBack: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            DetailTopBar(content.info.name, showBack, onBack, subtitle = content.info.typeLabel) {
                IconButton(onClick = { showInfo = !showInfo }) { Icon(Icons.Default.Info, "Details") }
                IconButton(onClick = { Launch.openWith(context, content.file) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with") }
                IconButton(onClick = { Launch.share(context, listOf(content.file)) }) { Icon(Icons.Default.Share, "Share") }
            }
        },
    ) { padding ->
        TabletopLayout(
            modifier = Modifier.padding(padding),
            top = { ZoomableImage(content.file) },
            bottom = { tabletop ->
                if (tabletop || showInfo) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            InfoRows(content.info.rows)
                            EntryActions(vm, target)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ZoomableImage(file: File) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(file) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .transformable(transform),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(file)
                // A saved edit gives the file a new timestamp, which must not hit the old cached bitmap.
                .memoryCacheKey("${file.path}@${file.lastModified()}")
                .build(),
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** Details and actions for anything that is not text or an image. */
@Composable
fun InfoScreen(vm: BrowserViewModel, target: DetailTarget, info: FileInfo, showBack: Boolean, onBack: () -> Unit) {
    Scaffold(topBar = { DetailTopBar(info.name, showBack, onBack) }) { padding ->
        TabletopLayout(
            modifier = Modifier.padding(padding),
            top = { tabletop ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (tabletop) Arrangement.Center else Arrangement.Top,
                ) {
                    InfoHeader(info)
                    if (!tabletop) {
                        Spacer(Modifier.height(20.dp))
                        InfoActions(vm, target, info, onBack)
                        Spacer(Modifier.height(20.dp))
                        InfoRows(info.rows)
                    }
                }
            },
            bottom = { tabletop ->
                if (tabletop) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        InfoActions(vm, target, info, onBack)
                        Spacer(Modifier.height(12.dp))
                        InfoRows(info.rows)
                    }
                }
            },
        )
    }
}

@Composable
private fun InfoHeader(info: FileInfo) {
    KindBadge(FileTypes.kindOf(info.name, info.isDirectory), info.name, size = 88.dp)
    Spacer(Modifier.height(12.dp))
    Text(info.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(info.typeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (info.note != null) {
        Spacer(Modifier.height(12.dp))
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
            Text(info.note, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoActions(vm: BrowserViewModel, target: DetailTarget, info: FileInfo, onBack: () -> Unit) {
    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val file = info.localFile
        val kind = FileTypes.kindOf(info.name, info.isDirectory)
        if ((info.browsableArchive || kind == FileKind.ARCHIVE) && target is DetailTarget.Disk) {
            ActionButton("Browse contents", Icons.Default.FolderZip, primary = true) {
                vm.browseAsArchive(target.path)
                onBack()
            }
        }
        if (info.canOpenAsText) ActionButton("Open as text", Icons.AutoMirrored.Filled.Article) { vm.forceOpenAsText() }
        if (file != null && !info.isDirectory) {
            ActionButton("Open with", Icons.AutoMirrored.Filled.OpenInNew, primary = !info.browsableArchive) { Launch.openWith(context, file) }
            ActionButton("Share", Icons.Default.Share) { Launch.share(context, listOf(file)) }
        }
        if (target is DetailTarget.Disk && kind == FileKind.ARCHIVE) {
            ActionButton("Extract…", Icons.Default.Unarchive) { vm.promptExtract(target.path) }
        }
        EntryActions(vm, target)
    }
}

/** For a file inside an archive: pull it out next to the archive. */
@Composable
private fun EntryActions(vm: BrowserViewModel, target: DetailTarget) {
    if (target is DetailTarget.Entry) {
        ActionButton("Extract…", Icons.Default.Unarchive) {
            vm.promptExtract(target.archivePath, listOf(target.entryPath), ArchiveIndex.parentOf(target.entryPath))
        }
    }
}

@Composable
private fun ActionButton(text: String, icon: ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text)
        }
    }
    if (primary) FilledTonalButton(onClick = onClick) { content() } else OutlinedButton(onClick = onClick) { content() }
}

@Composable
private fun InfoRows(rows: List<InfoRow>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Column {
                Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(row.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
