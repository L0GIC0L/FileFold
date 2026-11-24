package io.github.l0gic0l.filefold.core

import java.io.File
import java.io.IOException

/**
 * A recycle bin. Deleted items are moved into a hidden folder at the top of
 * their own storage volume, which is instant, and can be put back later.
 *
 * Layout: `<volume>/.FileFoldTrash/<id>/<original name>` holds the item, and
 * `<volume>/.FileFoldTrash/<id>.info` records where it came from and when.
 */
object Trash {

    const val DIR_NAME = ".FileFoldTrash"

    /** Days an item stays in the trash before it is removed for good. */
    const val KEEP_DAYS = 30

    private const val INFO_SUFFIX = ".info"

    /** One trashed item. [file] is where it is now; [originalPath] is where it was. */
    data class Entry(val id: String, val file: File, val originalPath: String, val deletedAt: Long) {
        val originalName: String get() = File(originalPath).name
        internal val holder: File get() = file.parentFile!!
        internal val info: File get() = File(holder.parentFile, holder.name + INFO_SUFFIX)
    }

    fun dirFor(volumeRoot: File): File = File(volumeRoot, DIR_NAME)

    /** True for paths that are inside a trash folder. */
    fun isInTrash(path: String): Boolean = "/$DIR_NAME/" in path || path.endsWith("/$DIR_NAME")

    /**
     * Moves [files] into the trash of the volume each lives on, as told by [volumeRootOf].
     * Returns the entries created, which is what [restore] needs to undo it.
     */
    fun moveToTrash(
        files: List<File>,
        volumeRootOf: (File) -> File?,
        progress: Progress = Progress.None,
        now: Long = System.currentTimeMillis(),
    ): List<Entry> {
        val entries = ArrayList<Entry>(files.size)
        for ((index, file) in files.withIndex()) {
            progress.ensureActive()
            progress.start(file.name)
            if (!file.exists()) continue
            val root = volumeRootOf(file)
                ?: throw IOException("\"${file.name}\" is not on a storage volume with a trash. Delete it permanently instead.")
            val trash = dirFor(root)
            if (!trash.isDirectory && !trash.mkdirs()) throw IOException("Could not create the trash folder")
            // Keeps trashed photos and music out of the gallery and music apps.
            File(trash, ".nomedia").createNewFile()

            var id = "$now-$index"
            var holder = File(trash, id)
            var bump = 0
            while (holder.exists()) {
                id = "$now-$index-${++bump}"
                holder = File(trash, id)
            }
            if (!holder.mkdir()) throw IOException("Could not move \"${file.name}\" to the trash")
            val target = File(holder, file.name)
            val entry = Entry(id, target, file.absolutePath, now)
            entry.info.writeText("${file.absolutePath}\n$now\n")
            if (!file.renameTo(target)) {
                entry.info.delete()
                holder.delete()
                throw IOException("Could not move \"${file.name}\" to the trash")
            }
            entries += entry
        }
        return entries
    }

    /** Everything in the trash of the given volumes, newest first. */
    fun list(volumeRoots: List<File>): List<Entry> {
        val entries = ArrayList<Entry>()
        for (root in volumeRoots) {
            val trash = dirFor(root)
            for (info in trash.listFiles().orEmpty()) {
                if (!info.isFile || !info.name.endsWith(INFO_SUFFIX)) continue
                val id = info.name.removeSuffix(INFO_SUFFIX)
                val lines = try {
                    info.readLines()
                } catch (_: IOException) {
                    continue
                }
                val originalPath = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
                val item = File(trash, id).listFiles()?.firstOrNull() ?: continue
                entries += Entry(id, item, originalPath, lines.getOrNull(1)?.toLongOrNull() ?: info.lastModified())
            }
        }
        return entries.sortedByDescending { it.deletedAt }
    }

    /**
     * Puts entries back where they came from, recreating the folder if it has gone and
     * picking a free name if something else now has theirs. Returns the restored files.
     */
    fun restore(entries: List<Entry>, progress: Progress = Progress.None): List<File> {
        val restored = ArrayList<File>(entries.size)
        for (entry in entries) {
            progress.ensureActive()
            progress.start(entry.originalName)
            if (!entry.file.exists()) {
                forget(entry)
                continue
            }
            val original = File(entry.originalPath)
            val parent = original.parentFile ?: throw IOException("Could not restore \"${entry.originalName}\"")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not recreate \"${parent.name}\"")
            val target = FileOps.uniqueTarget(parent, original.name)
            if (!entry.file.renameTo(target)) throw IOException("Could not restore \"${entry.originalName}\"")
            forget(entry)
            restored += target
        }
        return restored
    }

    /** Deletes entries for good. */
    fun purge(entries: List<Entry>, progress: Progress = Progress.None) {
        for (entry in entries) {
            progress.start(entry.originalName)
            FileOps.delete(listOf(entry.holder), progress)
            entry.info.delete()
        }
    }

    /** Removes everything that went into the trash before [cutoff] (epoch milliseconds). Returns how many. */
    fun purgeOlderThan(volumeRoots: List<File>, cutoff: Long): Int {
        val old = list(volumeRoots).filter { it.deletedAt < cutoff }
        purge(old)
        return old.size
    }

    private fun forget(entry: Entry) {
        entry.info.delete()
        entry.holder.delete()
    }
}
