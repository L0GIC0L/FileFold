package io.github.l0gic0l.filefold.core

/**
 * One row in the browser. It is either a real file on disk or an entry inside
 * an archive; [path] is an absolute path in the first case and the path within
 * the archive in the second.
 */
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    /** Size in bytes. For folders inside an archive this is the total of their contents. */
    val size: Long,
    /** Last modified time in epoch milliseconds, 0 when unknown. */
    val modified: Long,
    /** Number of direct children for folders, -1 when unknown. */
    val itemCount: Int = -1,
    /** Compressed size for archive entries, -1 when not applicable. */
    val compressedSize: Long = -1,
) {
    /** Worked out once, not on every redraw while scrolling. */
    val kind: FileKind = FileTypes.kindOf(name, isDirectory)
    val isHidden: Boolean get() = name.startsWith(".")
}
