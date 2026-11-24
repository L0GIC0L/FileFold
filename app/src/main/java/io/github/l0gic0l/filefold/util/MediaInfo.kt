package io.github.l0gic0l.filefold.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.core.FileTypes
import io.github.l0gic0l.filefold.ui.detail.InfoRow
import java.io.File

/** Extra facts for the details pane that come from inside a file: picture size, running time, app version. */
object MediaInfo {

    fun rows(context: Context, file: File): List<InfoRow> = try {
        when (FileTypes.kindOf(file.name)) {
            FileKind.IMAGE -> imageRows(file)
            FileKind.VIDEO, FileKind.AUDIO -> mediaRows(file)
            FileKind.APK -> apkRows(context, file)
            else -> emptyList()
        }
    } catch (_: Exception) {
        // A damaged or unusual file simply has no extra facts.
        emptyList()
    }

    private fun imageRows(file: File): List<InfoRow> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return emptyList()
        val megapixels = bounds.outWidth.toLong() * bounds.outHeight / 1_000_000.0
        val suffix = if (megapixels >= 0.95) " (%.1f MP)".format(megapixels) else ""
        return listOf(InfoRow("Dimensions", "${bounds.outWidth} × ${bounds.outHeight}$suffix"))
    }

    private fun mediaRows(file: File): List<InfoRow> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            fun text(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            return buildList {
                text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { add(InfoRow("Duration", duration(it))) }
                val width = text(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = text(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (width != null && height != null) add(InfoRow("Resolution", "$width × $height"))
                text(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { add(InfoRow("Title", it)) }
                text(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { add(InfoRow("Artist", it)) }
                text(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { add(InfoRow("Album", it)) }
                text(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { add(InfoRow("Bit rate", "${it / 1000} kbps")) }
            }
        } finally {
            retriever.release()
        }
    }

    private fun apkRows(context: Context, file: File): List<InfoRow> {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.path, 0) ?: return emptyList()
        val app = info.applicationInfo
        // The label lives in the APK's own resources, so point the lookup at the file.
        app?.sourceDir = file.path
        app?.publicSourceDir = file.path
        return buildList {
            app?.let { add(InfoRow("App name", pm.getApplicationLabel(it).toString())) }
            add(InfoRow("Package", info.packageName))
            add(InfoRow("Version", "${info.versionName ?: "?"} (${info.longVersionCode})"))
            app?.let { add(InfoRow("Needs", "Android API ${it.minSdkVersion} or newer · built for API ${it.targetSdkVersion}")) }
        }
    }

    /** "3:07" or "1:02:45". */
    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }
}
