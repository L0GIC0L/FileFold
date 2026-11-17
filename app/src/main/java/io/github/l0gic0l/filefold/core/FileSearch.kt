package io.github.l0gic0l.filefold.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * What a search is looking for. Every part that is set must match: the words
 * in [text], the type [group], and the [recent] and [large] filters.
 */
data class SearchQuery(
    /** Words that must all appear in the name, or a pattern with * and ? such as "IMG_*.jpg". */
    val text: String = "",
    val group: SortGroup? = null,
    /** Only files changed in the last [FileSearch.RECENT_DAYS] days. */
    val recent: Boolean = false,
    /** Only files of [FileSearch.LARGE_BYTES] or more. */
    val large: Boolean = false,
) {
    /** False while there is nothing to look for, in which case the folder is shown as usual. */
    val isActive: Boolean get() = text.isNotBlank() || group != null || recent || large

    private val terms = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    private val pattern: Regex? = text.trim().takeIf { '*' in it || '?' in it }?.let(FileSearch::globToRegex)

    fun matchesName(name: String): Boolean = when {
        pattern != null -> pattern.matches(name)
        else -> {
            val lower = name.lowercase()
            terms.all { it in lower }
        }
    }

    /** Whether one file or folder matches. [now] is the current time in epoch milliseconds. */
    fun matches(name: String, isDirectory: Boolean, size: Long, modified: Long, now: Long): Boolean {
        // The filters describe files, so folders only ever match a plain search by name.
        if (isDirectory && (group != null || large || recent)) return false
        if (group != null && FileTypes.kindOf(name) !in group.kinds) return false
        if (large && size < FileSearch.LARGE_BYTES) return false
        if (recent && modified < now - FileSearch.RECENT_DAYS * 86_400_000L) return false
        return matchesName(name)
    }
}

/** Finds files anywhere below a folder. */
object FileSearch {

    const val MAX_RESULTS = 2000
    const val RECENT_DAYS = 7
    const val LARGE_BYTES = 50L * 1024 * 1024

    /** Turns "IMG_*.jp?g" into a regular expression matching the whole name, ignoring case. */
    fun globToRegex(glob: String): Regex {
        val out = StringBuilder()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) out.append(Regex.escape(literal.toString()))
            literal.clear()
        }
        for (c in glob) {
            when (c) {
                '*' -> { flush(); out.append(".*") }
                '?' -> { flush(); out.append('.') }
                else -> literal.append(c)
            }
        }
        flush()
        return Regex(out.toString(), RegexOption.IGNORE_CASE)
    }

    /**
     * Walks everything below [root] and hands matches to [onFound] in small batches as they
     * turn up, so results can be shown while the search is still running. Folders that cannot
     * be read are skipped, links are not followed, and the trash is never searched. Stops
     * after [MAX_RESULTS]. Returns true when it stopped early because of that limit.
     */
    fun search(
        root: File,
        query: SearchQuery,
        showHidden: Boolean,
        now: Long = System.currentTimeMillis(),
        progress: Progress = Progress.None,
        onFound: (List<FileItem>) -> Unit,
    ): Boolean {
        val batch = ArrayList<FileItem>()
        var found = 0
        var lastFlush = System.nanoTime()
        fun flush(force: Boolean) {
            val time = System.nanoTime()
            if (batch.isEmpty() || (!force && batch.size < 64 && time - lastFlush < 150_000_000L)) return
            onFound(ArrayList(batch))
            batch.clear()
            lastFlush = time
        }

        fun consider(path: Path, attrs: BasicFileAttributes): Boolean {
            val name = path.fileName?.toString() ?: return true
            if (query.matches(name, attrs.isDirectory, attrs.size(), attrs.lastModifiedTime().toMillis(), now)) {
                batch += FileItem(
                    name = name,
                    path = path.toString(),
                    isDirectory = attrs.isDirectory,
                    size = if (attrs.isDirectory) 0 else attrs.size(),
                    modified = attrs.lastModifiedTime().toMillis(),
                )
                found++
                flush(force = false)
            }
            return found < MAX_RESULTS
        }

        val rootPath = root.toPath()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                progress.ensureActive()
                if (dir == rootPath) return FileVisitResult.CONTINUE
                val name = dir.fileName.toString()
                if (name == Trash.DIR_NAME || (!showHidden && name.startsWith("."))) return FileVisitResult.SKIP_SUBTREE
                return if (consider(dir, attrs)) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!showHidden && file.fileName.toString().startsWith(".")) return FileVisitResult.CONTINUE
                // Links are listed by the walk as "other" files; leave them out, like the copy code does.
                if (attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                return if (consider(file, attrs)) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        flush(force = true)
        return found >= MAX_RESULTS
    }

    /** Size in bytes of everything below [dir], skipping what cannot be read. */
    fun folderSize(dir: File, progress: Progress = Progress.None): Long {
        var total = 0L
        try {
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    progress.ensureActive()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // A folder that vanished mid-walk simply counts for what was seen.
        }
        return total
    }
}
