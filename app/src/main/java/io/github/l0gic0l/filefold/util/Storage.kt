package io.github.l0gic0l.filefold.util

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

/** A shortcut in the navigation drawer or rail. */
data class Place(val label: String, val path: String, val icon: ImageVector, val isVolume: Boolean)

object Storage {

    val primaryRoot: File get() = Environment.getExternalStorageDirectory()

    /** Whether "All files access" has been granted. */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** Storage volumes: internal storage plus any SD card or USB drive that is mounted. */
    fun volumes(context: Context): List<Place> {
        val manager = context.getSystemService(StorageManager::class.java)
        return manager.storageVolumes.mapNotNull { volume ->
            val dir = volume.directory ?: return@mapNotNull null
            if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            Place(
                label = if (volume.isPrimary) "Internal storage" else volume.getDescription(context),
                path = dir.absolutePath,
                icon = if (volume.isPrimary) Icons.Default.PhoneAndroid else Icons.Default.SdCard,
                isVolume = true,
            )
        }.ifEmpty { listOf(Place("Internal storage", primaryRoot.absolutePath, Icons.Default.PhoneAndroid, true)) }
    }

    /** The standard folders everyone uses, when they exist. */
    fun shortcuts(): List<Place> = listOf(
        Triple("Downloads", Environment.DIRECTORY_DOWNLOADS, Icons.Default.Download),
        Triple("Documents", Environment.DIRECTORY_DOCUMENTS, Icons.Default.Description),
        Triple("Pictures", Environment.DIRECTORY_PICTURES, Icons.Default.PhotoLibrary),
        Triple("Camera", Environment.DIRECTORY_DCIM, Icons.Default.CameraAlt),
        Triple("Music", Environment.DIRECTORY_MUSIC, Icons.Default.MusicNote),
        Triple("Movies", Environment.DIRECTORY_MOVIES, Icons.Default.Movie),
    ).map { (label, dir, icon) -> Place(label, File(primaryRoot, dir).absolutePath, icon, false) }
        .filter { File(it.path).isDirectory }

    /** The volume root that contains [path], so breadcrumbs and "up" never climb above it. */
    fun volumeRootOf(volumes: List<Place>, path: String): String? =
        volumes.map { it.path }.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }
}
