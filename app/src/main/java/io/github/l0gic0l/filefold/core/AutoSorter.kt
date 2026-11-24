package io.github.l0gic0l.filefold.core

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AutoSortMode(val label: String, val description: String) {
    BY_TYPE("By type", "Images, Videos, Audio, Documents, Code, Archives, Apps"),
    BY_DATE("By month", "One folder per month, e.g. 2026-11"),
}

/** The folders auto-sort files into by type. Every kind of file belongs to exactly one group. */
enum class SortGroup(val defaultFolder: String, val kinds: Set<FileKind>) {
    IMAGES("Images", setOf(FileKind.IMAGE, FileKind.DESIGN)),
    VIDEOS("Videos", setOf(FileKind.VIDEO, FileKind.SUBTITLE)),
    AUDIO("Audio", setOf(FileKind.AUDIO)),
    DOCUMENTS(
        "Documents",
        setOf(
            FileKind.PDF, FileKind.DOCUMENT, FileKind.SPREADSHEET, FileKind.PRESENTATION, FileKind.EBOOK,
            FileKind.TEXT, FileKind.CONTACT, FileKind.CALENDAR,
        ),
    ),
    CODE("Code", setOf(FileKind.CODE, FileKind.WEB, FileKind.DATA)),
    ARCHIVES("Archives", setOf(FileKind.ARCHIVE, FileKind.DISK_IMAGE)),
    APPS("Apps", setOf(FileKind.APK, FileKind.EXECUTABLE)),
    OTHER("Other", setOf(FileKind.FONT, FileKind.DATABASE, FileKind.MODEL_3D, FileKind.TORRENT, FileKind.KEY, FileKind.OTHER));

    companion object {
        fun of(kind: FileKind): SortGroup = entries.firstOrNull { kind in it.kinds } ?: OTHER
    }
}

/** Whether a group is sorted, and the folder (relative to the folder being sorted) it goes into. */
data class GroupRule(val enabled: Boolean, val folder: String)

data class AutoSortConfig(
    val mode: AutoSortMode = AutoSortMode.BY_TYPE,
    val groups: Map<SortGroup, GroupRule> = SortGroup.entries.associateWith { GroupRule(true, it.defaultFolder) },
    /** Also tidy sub-folders away into [foldersTarget]. */
    val moveFolders: Boolean = false,
    val foldersTarget: String = "Misc",
    val includeHidden: Boolean = false,
) {
    fun rule(group: SortGroup): GroupRule = groups[group] ?: GroupRule(true, group.defaultFolder)
}

/**
 * Tidies a folder by filing what is directly inside it into sub-folders:
 * files by type or by month, and optionally the sub-folders themselves into a
 * folder of their own. Nothing is overwritten, the folders it sorts into are
 * never moved, and a sort can be undone.
 */
object AutoSorter {

    data class Move(val source: File, val targetDir: File)

    data class SummaryRow(val folder: String, val files: Int, val folders: Int)

    data class Plan(val dir: File, val moves: List<Move>) {
        /** Where things go, in display order: destination relative to [dir], with file and folder counts. */
        val summary: List<SummaryRow>
            get() = moves.groupBy { it.targetDir.relativeTo(dir).invariantSeparatorsPath }
                .map { (folder, list) -> SummaryRow(folder, list.count { !it.source.isDirectory }, list.count { it.source.isDirectory }) }
                .sortedWith { a, b -> NaturalOrder.compare(a.folder, b.folder) }
    }

    /** What a completed sort did, kept so that it can be undone. */
    data class Result(val moved: List<Pair<File, File>>, val createdDirs: List<File>)

    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")
    private val monthFolder = Regex("""\d{4}-\d{2}""")

    /**
     * Cleans a destination typed in settings, such as " Sorted / Images ", into "Sorted/Images".
     * Returns null when it is empty or a part of it is not a valid folder name.
     */
    fun cleanPath(path: String): String? {
        val parts = path.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { FileOps.validateName(it) != null }) return null
        return parts.joinToString("/")
    }

    /** The folder, relative to the one being sorted, that [file] belongs in; null to leave it where it is. */
    fun folderFor(file: File, config: AutoSortConfig, zone: ZoneId = ZoneId.systemDefault()): String? = when (config.mode) {
        AutoSortMode.BY_TYPE -> {
            val group = SortGroup.of(FileTypes.kindOf(file.name))
            val rule = config.rule(group)
            if (rule.enabled) cleanPath(rule.folder) ?: group.defaultFolder else null
        }
        AutoSortMode.BY_DATE -> monthFormat.format(Instant.ofEpochMilli(file.lastModified()).atZone(zone))
    }

    /** Works out where everything would go without touching anything. */
    fun plan(dir: File, config: AutoSortConfig, zone: ZoneId = ZoneId.systemDefault()): Plan {
        val foldersTarget = cleanPath(config.foldersTarget) ?: "Misc"
        // Folders that files are sorted into are never tidied away themselves, even when empty right now.
        val reserved = buildSet {
            SortGroup.entries.forEach { add((cleanPath(config.rule(it).folder) ?: it.defaultFolder).substringBefore('/')) }
            add(foldersTarget.substringBefore('/'))
        }
        val moves = dir.listFiles().orEmpty()
            .filter { config.includeHidden || !it.name.startsWith(".") }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            .mapNotNull { child ->
                when {
                    child.isFile -> folderFor(child, config, zone)?.let { Move(child, File(dir, it)) }
                    child.isDirectory && config.moveFolders && !Files.isSymbolicLink(child.toPath()) &&
                        child.name !in reserved && !monthFolder.matches(child.name) -> Move(child, File(dir, foldersTarget))
                    else -> null
                }
            }
        return Plan(dir, moves)
    }

    fun execute(plan: Plan, progress: Progress = Progress.None): Result {
        val moved = ArrayList<Pair<File, File>>()
        val created = ArrayList<File>()
        progress.total = plan.moves.size.toLong()
        for (move in plan.moves) {
            progress.ensureActive()
            progress.start(move.source.name)
            makeDirs(move.targetDir, created)
            val intoItself = move.source.isDirectory &&
                (move.targetDir.canonicalPath + File.separator).startsWith(move.source.canonicalPath + File.separator)
            if (move.targetDir.isDirectory && !intoItself) {
                val target = FileOps.uniqueTarget(move.targetDir, move.source.name)
                // Source and target are on the same volume, so this is always a cheap rename.
                if (move.source.renameTo(target)) moved += move.source to target
            }
            progress.advance(1)
        }
        return Result(moved, created)
    }

    /** Creates [dir] and any missing parents, recording each folder it creates (outermost first). */
    private fun makeDirs(dir: File, created: MutableList<File>) {
        if (dir.isDirectory) return
        dir.parentFile?.let { makeDirs(it, created) }
        if (dir.mkdir()) created += dir
    }

    /** Puts everything back where it was and removes the folders the sort created. */
    fun undo(result: Result): Int {
        var restored = 0
        for ((original, current) in result.moved.asReversed()) {
            if (current.exists() && !original.exists() && current.renameTo(original)) restored++
        }
        // Innermost first. File.delete only removes empty folders, so anything added since stays put.
        result.createdDirs.asReversed().forEach { it.delete() }
        return restored
    }
}
