package io.github.l0gic0l.filefold.core

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Blocking file system operations. Call these from a background thread. */
object FileOps {

    /** Characters Android's shared storage (FAT/exFAT rules) refuses in a file name. */
    private const val ILLEGAL_NAME_CHARS = "/\\:*?\"<>|"

    fun toItem(file: File): FileItem {
        val isDirectory = file.isDirectory
        return FileItem(
            name = file.name,
            path = file.absolutePath,
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else file.length(),
            modified = file.lastModified(),
            itemCount = if (isDirectory) file.list()?.size ?: -1 else -1,
        )
    }

    /** Lists a folder, unsorted. Throws when the folder cannot be read. */
    fun list(dir: File, showHidden: Boolean): List<FileItem> {
        val children = dir.listFiles()
            ?: throw IOException(if (dir.exists()) "Android does not allow access to this folder" else "Folder no longer exists")
        return children.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map(::toItem)
            .toList()
    }

    /** Returns an error message for an unusable file name, or null when it is fine. */
    fun validateName(name: String): String? = when {
        name.isBlank() -> "Name cannot be empty"
        name == "." || name == ".." -> "That name is reserved"
        name.any { it in ILLEGAL_NAME_CHARS || it.code < 32 } -> "Name cannot contain $ILLEGAL_NAME_CHARS"
        name.toByteArray().size > 255 -> "Name is too long"
        else -> null
    }

    /**
     * A file in [dir] that does not exist yet. When [name] is taken a counter
     * is added before the extension: "report.pdf" becomes "report (1).pdf".
     */
    fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        // Keep compound extensions such as ".tar.gz" together.
        val lower = name.lowercase()
        val compound = listOf(".tar.gz", ".tar.bz2", ".tar.xz").firstOrNull { lower.endsWith(it) && lower.length > it.length }
        val dot = if (compound != null) name.length - compound.length else name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($counter)$ext")
            counter++
        }
        return candidate
    }

    fun createFolder(parent: File, name: String): File {
        validateName(name)?.let { throw IOException(it) }
        val dir = File(parent, name)
        if (dir.exists()) throw IOException("\"$name\" already exists")
        if (!dir.mkdir()) throw IOException("Could not create folder")
        return dir
    }

    fun createFile(parent: File, name: String): File {
        validateName(name)?.let { throw IOException(it) }
        val file = File(parent, name)
        if (!file.createNewFile()) throw IOException("\"$name\" already exists")
        return file
    }

    fun rename(file: File, newName: String): File {
        validateName(newName)?.let { throw IOException(it) }
        val target = File(file.parentFile, newName)
        if (target.exists()) throw IOException("\"$newName\" already exists")
        if (!file.renameTo(target)) throw IOException("Could not rename \"${file.name}\"")
        return target
    }

    /** Total size in bytes of the given files, descending into folders. */
    fun totalSize(files: Collection<File>, progress: Progress = Progress.None): Long {
        var total = 0L
        for (file in files) {
            progress.ensureActive()
            total += if (isRealDirectory(file)) totalSize(file.listFiles()?.asList().orEmpty(), progress) else file.length()
        }
        return total
    }

    /** Number of files (not folders) under the given files. */
    fun countFiles(files: Collection<File>): Int =
        files.sumOf { if (isRealDirectory(it)) countFiles(it.listFiles()?.asList().orEmpty()) else 1 }

    /** Copies [sources] into [destDir], renaming on conflict. Returns the created top-level files. */
    fun copy(sources: List<File>, destDir: File, progress: Progress): List<File> {
        checkDestination(sources, destDir)
        progress.total = totalSize(sources, progress)
        return sources.map { source ->
            val target = uniqueTarget(destDir, source.name)
            copyRecursively(source, target, progress)
            target
        }
    }

    /** Moves [sources] into [destDir], renaming on conflict. Returns the moved top-level files. */
    fun move(sources: List<File>, destDir: File, progress: Progress): List<File> {
        checkDestination(sources, destDir)
        val moved = ArrayList<File>(sources.size)
        // A rename is instant when source and destination share a volume, so try
        // that first and only fall back to copy-then-delete for the leftovers.
        val needCopy = ArrayList<Pair<File, File>>()
        for (source in sources) {
            progress.ensureActive()
            if (source.parentFile?.canonicalPath == destDir.canonicalPath) {
                moved += source
                continue
            }
            val target = uniqueTarget(destDir, source.name)
            progress.start(source.name)
            if (source.renameTo(target)) moved += target else needCopy += source to target
        }
        if (needCopy.isNotEmpty()) {
            progress.total = totalSize(needCopy.map { it.first }, progress)
            for ((source, target) in needCopy) {
                copyRecursively(source, target, progress)
                deleteRecursively(source, Progress.None)
                moved += target
            }
        }
        return moved
    }

    fun delete(files: List<File>, progress: Progress) {
        progress.total = 0
        for (file in files) {
            progress.start(file.name)
            deleteRecursively(file, progress)
        }
    }

    private fun checkDestination(sources: List<File>, destDir: File) {
        if (!destDir.isDirectory) throw IOException("Destination folder no longer exists")
        val dest = destDir.canonicalPath + File.separator
        for (source in sources) {
            if (!source.exists()) throw IOException("\"${source.name}\" no longer exists")
            if (source.isDirectory && dest.startsWith(source.canonicalPath + File.separator)) {
                throw IOException("Cannot put \"${source.name}\" inside itself")
            }
        }
    }

    private fun copyRecursively(source: File, target: File, progress: Progress) {
        progress.ensureActive()
        if (Files.isSymbolicLink(source.toPath())) return
        if (source.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) throw IOException("Could not create \"${target.name}\"")
            for (child in source.listFiles().orEmpty()) copyRecursively(child, File(target, child.name), progress)
        } else {
            progress.start(source.name)
            try {
                source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output, progress) } }
            } catch (e: Throwable) {
                // Never leave a half-written file behind after a failure or cancellation.
                target.delete()
                throw e
            }
        }
        target.setLastModified(source.lastModified())
    }

    private fun deleteRecursively(file: File, progress: Progress) {
        progress.ensureActive()
        if (isRealDirectory(file)) {
            for (child in file.listFiles().orEmpty()) deleteRecursively(child, progress)
        }
        if (!file.delete() && file.exists()) throw IOException("Could not delete \"${file.name}\"")
    }

    /** True for a directory that is not a symbolic link, i.e. one that is safe to descend into. */
    private fun isRealDirectory(file: File): Boolean = file.isDirectory && !Files.isSymbolicLink(file.toPath())
}
