package io.github.l0gic0l.filefold.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.l0gic0l.filefold.core.FileItem
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.core.FileTypes
import io.github.l0gic0l.filefold.ui.theme.LocalFileColors
import java.io.File

fun iconFor(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Default.Folder
    FileKind.IMAGE -> Icons.Default.Image
    FileKind.DESIGN -> Icons.Default.Brush
    FileKind.VIDEO -> Icons.Default.Movie
    FileKind.AUDIO -> Icons.Default.AudioFile
    FileKind.PDF -> Icons.Default.PictureAsPdf
    FileKind.DOCUMENT -> Icons.Default.Description
    FileKind.SPREADSHEET -> Icons.Default.TableChart
    FileKind.PRESENTATION -> Icons.Default.Slideshow
    FileKind.EBOOK -> Icons.AutoMirrored.Filled.MenuBook
    FileKind.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    FileKind.CODE -> Icons.Default.Code
    FileKind.WEB -> Icons.Default.Html
    FileKind.DATA -> Icons.Default.DataObject
    FileKind.SUBTITLE -> Icons.Default.Subtitles
    FileKind.ARCHIVE -> Icons.Default.FolderZip
    FileKind.DISK_IMAGE -> Icons.Default.Album
    FileKind.APK -> Icons.Default.Android
    FileKind.EXECUTABLE -> Icons.Default.Terminal
    FileKind.FONT -> Icons.Default.FontDownload
    FileKind.DATABASE -> Icons.Default.Storage
    FileKind.CONTACT -> Icons.Default.ContactPage
    FileKind.CALENDAR -> Icons.Default.CalendarMonth
    FileKind.MODEL_3D -> Icons.Default.ViewInAr
    FileKind.TORRENT -> Icons.Default.CloudDownload
    FileKind.KEY -> Icons.Default.Key
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/**
 * A rounded tile with the file's type icon. Images on disk show a thumbnail
 * instead once it has loaded.
 */
@Composable
fun FileIcon(item: FileItem, size: Dp, modifier: Modifier = Modifier, showThumbnail: Boolean = true) {
    val (container, content) = LocalFileColors.current[item.kind]
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = modifier.size(size).clip(shape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        var thumbnailLoaded by remember(item.path) { mutableStateOf(false) }
        if (!thumbnailLoaded) {
            Icon(iconFor(item.kind), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
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

/** A file kind's tile on its own, as in the info pane and the colour settings. */
@Composable
fun KindBadge(kind: FileKind, size: Dp = 72.dp, modifier: Modifier = Modifier) {
    val (container, content) = LocalFileColors.current[kind]
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(iconFor(kind), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
    }
}
