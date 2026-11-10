package io.github.l0gic0l.filefold.core

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AutoSortMode(val label: String, val description: String) {
    BY_TYPE("By type", "Images, Videos, Audio, Documents, Archives, Apps"),
    BY_DATE("By month", "One folder per month, e.g. 2026-11"),
}

/**
 * Tidies a folder by filing its loose files into sub-folders. Folders that
 * already exist are left alone; only files directly inside the folder move.
 */
object AutoSorter {

    data class Move(val source: File, val targetDir: File)

    data class Plan(val dir: File, val moves: List<Move>) {
        /** Target folder name to number of files going there, in display order. */
        val summary: List<Pair<String, Int>>
            get() = moves.groupingBy { it.targetDir.name }.eachCount().toList().sortedBy { it.first }
    }

    /** What a completed sort did, kept so that it can be undone. */
    data class Result(val moved: List<Pair<File, File>>, val createdDirs: List<File>)

    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    fun folderFor(file: File, mode: AutoSortMode, zone: ZoneId = ZoneId.systemDefault()): String = when (mode) {
        AutoSortMode.BY_TYPE -> when (FileTypes.kindOf(file.name)) {
            FileKind.IMAGE -> "Images"
            FileKind.VIDEO -> "Videos"
            FileKind.AUDIO -> "Audio"
            FileKind.DOCUMENT, FileKind.TEXT -> "Documents"
            FileKind.ARCHIVE -> "Archives"
            FileKind.APK -> "Apps"
            FileKind.FOLDER, FileKind.OTHER -> "Other"
        }
        AutoSortMode.BY_DATE -> monthFormat.format(Instant.ofEpochMilli(file.lastModified()).atZone(zone))
    }

    /** Works out where each loose file would go without touching anything. */
    fun plan(dir: File, mode: AutoSortMode, includeHidden: Boolean = false): Plan {
        val moves = dir.listFiles().orEmpty()
            .filter { it.isFile && (includeHidden || !it.name.startsWith(".")) }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            .map { Move(it, File(dir, folderFor(it, mode))) }
        return Plan(dir, moves)
    }

    fun execute(plan: Plan, progress: Progress = Progress.None): Result {
        val moved = ArrayList<Pair<File, File>>()
        val created = ArrayList<File>()
        progress.total = plan.moves.size.toLong()
        for (move in plan.moves) {
            progress.ensureActive()
            progress.start(move.source.name)
            if (!move.targetDir.exists() && move.targetDir.mkdirs()) created += move.targetDir
            if (move.targetDir.isDirectory) {
                val target = FileOps.uniqueTarget(move.targetDir, move.source.name)
                // Source and target share a parent folder, so this is always a cheap rename.
                if (move.source.renameTo(target)) moved += move.source to target
            }
            progress.advance(1)
        }
        return Result(moved, created)
    }

    /** Puts every file back where it was and removes the folders the sort created. */
    fun undo(result: Result): Int {
        var restored = 0
        for ((original, current) in result.moved.asReversed()) {
            if (current.exists() && !original.exists() && current.renameTo(original)) restored++
        }
        // File.delete only removes empty folders, so anything the user added since stays put.
        result.createdDirs.forEach { it.delete() }
        return restored
    }
}
