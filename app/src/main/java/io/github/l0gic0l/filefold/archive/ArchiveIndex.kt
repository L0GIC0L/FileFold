package io.github.l0gic0l.filefold.archive

import io.github.l0gic0l.filefold.core.FileItem

/**
 * Turns an archive's flat entry list into a folder tree that can be browsed
 * like a normal directory, without extracting anything.
 */
class ArchiveIndex(val format: ArchiveFormat, entries: List<ArchiveEntryInfo>) {

    private class Folder(var modified: Long = 0, var size: Long = 0)

    private val children = HashMap<String, MutableList<FileItem>>()
    private val folderPaths: Set<String>

    /** Number of files (not folders) in the archive. */
    val fileCount: Int

    /** Total uncompressed size of all files whose size is known. */
    val totalSize: Long

    /** True when at least one entry is password protected. */
    val hasEncryptedEntries: Boolean = entries.any { it.encrypted }

    init {
        val folders = LinkedHashMap<String, Folder>()
        val files = LinkedHashMap<String, ArchiveEntryInfo>()

        // Many archives never list their folders explicitly, so every parent of
        // every entry is created on demand.
        fun ensureFolder(path: String): Folder? {
            if (path.isEmpty()) return null
            return folders.getOrPut(path) {
                ensureFolder(parentOf(path))
                Folder()
            }
        }

        for (entry in entries) {
            val path = Archives.normalizeEntryPath(entry.path) ?: continue
            if (entry.isDirectory) {
                ensureFolder(path)?.modified = entry.modified
            } else {
                ensureFolder(parentOf(path))
                files[path] = entry.copy(path = path)
            }
        }
        // An entry cannot be both a file and a folder; the folder wins.
        files.keys.removeAll(folders.keys)

        for (file in files.values) {
            var dir = parentOf(file.path)
            while (dir.isNotEmpty()) {
                folders.getValue(dir).size += file.size.coerceAtLeast(0)
                dir = parentOf(dir)
            }
        }

        for ((path, folder) in folders) {
            children.getOrPut(parentOf(path)) { ArrayList() } +=
                FileItem(name = nameOf(path), path = path, isDirectory = true, size = folder.size, modified = folder.modified)
        }
        for (file in files.values) {
            children.getOrPut(parentOf(file.path)) { ArrayList() } += FileItem(
                name = nameOf(file.path),
                path = file.path,
                isDirectory = false,
                size = file.size,
                modified = file.modified,
                compressedSize = file.compressedSize,
            )
        }
        children.getOrPut("") { ArrayList() }

        folderPaths = folders.keys.toHashSet()
        fileCount = files.size
        totalSize = files.values.sumOf { it.size.coerceAtLeast(0) }
    }

    /** True when [dir] is a folder inside the archive ("" is the root). */
    fun hasFolder(dir: String): Boolean = dir.isEmpty() || dir in folderPaths

    /** The contents of a folder inside the archive, unsorted. "" is the root. */
    fun list(dir: String): List<FileItem> =
        children[dir].orEmpty().map { if (it.isDirectory) it.copy(itemCount = children[it.path]?.size ?: 0) else it }

    companion object {
        fun parentOf(path: String): String = path.substringBeforeLast('/', "")
        fun nameOf(path: String): String = path.substringAfterLast('/')
    }
}
