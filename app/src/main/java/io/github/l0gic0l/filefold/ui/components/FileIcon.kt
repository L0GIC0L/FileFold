package io.github.l0gic0l.filefold.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.l0gic0l.filefold.core.FileItem
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.core.FileTypes
import java.io.File

fun iconFor(kind: FileKind, name: String): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Default.Folder
    FileKind.IMAGE -> Icons.Default.Image
    FileKind.VIDEO -> Icons.Default.Movie
    FileKind.AUDIO -> Icons.Default.AudioFile
    FileKind.DOCUMENT -> if (FileTypes.extensionOf(name) == "pdf") Icons.Default.PictureAsPdf else Icons.Default.Description
    FileKind.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    FileKind.ARCHIVE -> Icons.Default.FolderZip
    FileKind.APK -> Icons.Default.Android
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Container and content colours per kind, taken from the Material You palette so they follow the wallpaper. */
@Composable
private fun colorsFor(kind: FileKind): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (kind) {
        FileKind.FOLDER -> c.primaryContainer to c.onPrimaryContainer
        FileKind.IMAGE, FileKind.VIDEO -> c.tertiaryContainer to c.onTertiaryContainer
        FileKind.AUDIO, FileKind.APK -> c.secondaryContainer to c.onSecondaryContainer
        FileKind.ARCHIVE -> c.errorContainer to c.onErrorContainer
        else -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
}

/**
 * A rounded tile with the file's type icon. Images on disk show a thumbnail
 * instead once it has loaded.
 */
@Composable
fun FileIcon(item: FileItem, size: Dp, modifier: Modifier = Modifier, showThumbnail: Boolean = true) {
    val (container, content) = colorsFor(item.kind)
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = modifier.size(size).clip(shape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        var thumbnailLoaded by remember(item.path) { mutableStateOf(false) }
        if (!thumbnailLoaded) {
            Icon(iconFor(item.kind, item.name), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
        }
        if (showThumbnail && item.kind == FileKind.IMAGE && FileTypes.isPreviewableImage(item.name)) {
            AsyncImage(
                model = File(item.path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { thumbnailLoaded = true },
            )
        }
    }
}

/** Larger icon used in the info pane. */
@Composable
fun KindBadge(kind: FileKind, name: String, size: Dp = 72.dp) {
    val (container, content) = colorsFor(kind)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(iconFor(kind, name), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
    }
}
