package io.github.l0gic0l.filefold.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.l0gic0l.filefold.core.FileOps
import java.io.File
import java.io.IOException

/**
 * Turns what another app hands FileFold (a file:// or content:// link) into a file on disk.
 * Most links from the system's own providers point at a real path FileFold can open in place;
 * anything else is copied so it can at least be looked at.
 */
object IncomingFiles {

    private const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
    private const val DOWNLOADS_DOCUMENTS = "com.android.providers.downloads.documents"

    /** MIME types other apps use when they mean "this is a folder". */
    val folderTypes = setOf("resource/folder", "inode/directory", "vnd.android.document/directory", "x-directory/normal")

    /**
     * The path for a document id of Android's external storage provider: "primary:Download/a.zip"
     * is on internal storage, "1A2B-3C4D:Music" on the SD card with that volume id.
     */
    fun pathForStorageDocument(docId: String, primaryRoot: String, storageRoot: String = "/storage"): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1).trim('/')
        val root = when (volume) {
            "primary" -> primaryRoot
            "home" -> "$primaryRoot/Documents"
            else -> "$storageRoot/$volume"
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /**
     * The path behind one of FileFold's own links (see res/xml/file_paths.xml), such as the ones
     * Settings makes when helping to set FileFold as the default: "/storage/…", "/external/…" or "/cache/…".
     */
    fun pathForOwnLink(linkPath: String, primaryRoot: String, cacheRoot: String): String? {
        val parts = linkPath.trimStart('/').split('/', limit = 2)
        val rest = parts.getOrNull(1).orEmpty()
        val root = when (parts[0]) {
            "storage" -> "/storage"
            "external" -> primaryRoot
            "cache" -> cacheRoot
            else -> return null
        }
        return if (rest.isEmpty()) root else "$root/$rest"
    }

    /** The readable file or folder [uri] points at, or null when it only exists inside another app. */
    fun resolve(context: Context, uri: Uri): File? {
        val primary = Storage.primaryRoot.absolutePath
        val path = when (uri.scheme) {
            "file" -> uri.path
            "content" -> when (uri.authority) {
                "${context.packageName}.files" -> uri.path?.let { pathForOwnLink(it, primary, context.cacheDir.absolutePath) }
                EXTERNAL_STORAGE_DOCUMENTS -> documentId(uri)?.let { pathForStorageDocument(it, primary) }
                // The Downloads provider names files on disk with a "raw:" id; others need a lookup.
                DOWNLOADS_DOCUMENTS -> documentId(uri)?.takeIf { it.startsWith("raw:") }?.removePrefix("raw:") ?: dataColumn(context, uri)
                else -> dataColumn(context, uri)
            }
            else -> null
        } ?: return null
        return File(path).takeIf { it.exists() && it.canRead() }
    }

    private fun documentId(uri: Uri): String? = try {
        if (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(null, uri)) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * The path the media store records for a file. The column is deprecated for apps without
     * access to all files; FileFold has that access, and anything that fails falls back to a copy.
     */
    @Suppress("DEPRECATION")
    private fun dataColumn(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Copies a file that only exists inside another app into [dir], keeping its name. */
    fun copyToCache(context: Context, uri: Uri, dir: File): File {
        val name = displayName(context, uri)?.takeIf { FileOps.validateName(it) == null }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { FileOps.validateName(it) == null }
            ?: "file"
        // A folder per copy, so two different files with the same name never collide.
        val target = File(File(dir, System.nanoTime().toString()).apply { mkdirs() }, name)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("FileFold could not read this file")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        return target
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * True when [mime] is matched by any of [patterns]: an exact type such as "application/pdf", or a
     * whole family written as "image/" followed by a star. No patterns means anything goes.
     */
    fun mimeMatches(mime: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return true
        val lower = mime.lowercase()
        return patterns.any { raw ->
            val pattern = raw.lowercase().trim()
            pattern == "*/*" || pattern == "*" || pattern == lower ||
                (pattern.endsWith("/*") && lower.startsWith(pattern.dropLast(1)))
        }
    }
}
