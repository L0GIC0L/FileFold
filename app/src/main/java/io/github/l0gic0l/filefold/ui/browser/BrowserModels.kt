package io.github.l0gic0l.filefold.ui.browser

import io.github.l0gic0l.filefold.archive.ArchiveFormat
import io.github.l0gic0l.filefold.archive.ArchiveIndex
import io.github.l0gic0l.filefold.core.AutoSorter
import io.github.l0gic0l.filefold.core.FileItem
import java.io.File

/** Where the browser is looking: a real folder, or a folder inside an archive. */
sealed interface Location {
    /** Unique key, used to remember the scroll position of each folder. */
    val key: String

    data class Disk(val path: String) : Location {
        override val key: String get() = path
    }

    /** [dir] is the folder inside the archive, "" for its root. */
    data class InArchive(val archivePath: String, val dir: String) : Location {
        override val key: String get() = "$archivePath!/$dir"
    }

    /** The path on disk: the folder itself, or the archive file for a folder inside an archive. */
    val diskPath: String
        get() = when (this) {
            is Disk -> path
            is InArchive -> archivePath
        }

    /** True when this location lies somewhere inside [other], at any depth. */
    fun isInside(other: Location): Boolean = when (other) {
        is Disk -> diskPath.startsWith(other.path.trimEnd('/') + "/")
        is InArchive -> this is InArchive && archivePath == other.archivePath && dir != other.dir &&
            (other.dir.isEmpty() || dir.startsWith(other.dir + "/"))
    }

    /** A string form for saving open tabs, read back by [decode]. */
    fun encode(): String = when (this) {
        is Disk -> "d:$path"
        is InArchive -> "a:$archivePath!/$dir"
    }

    companion object {
        fun decode(value: String): Location? = when {
            value.startsWith("d:") -> Disk(value.removePrefix("d:"))
            value.startsWith("a:") -> value.removePrefix("a:").split("!/", limit = 2)
                .takeIf { it.size == 2 }?.let { InArchive(it[0], it[1]) }
            else -> null
        }
    }
}

/** One browser tab. Each tab has its own folder; scroll positions are remembered per tab. */
data class BrowserTab(val id: Long, val location: Location)

/** What the folder area shows: a folder, in a particular tab. Transitions animate between pages. */
data class Page(val tabId: Long, val location: Location) {
    /** Key for remembering this page's scroll position. */
    val scrollKey: String get() = "$tabId|${location.key}"
}

/** An archive that is open for browsing. The index is cached so moving between its folders is instant. */
data class ArchiveSession(
    val file: File,
    val format: ArchiveFormat,
    val index: ArchiveIndex,
    val password: String?,
    val lastModified: Long,
)

/** One step in the breadcrumb trail. */
data class Crumb(val label: String, val location: Location)

/** Items waiting to be pasted. */
sealed interface Clipboard {
    val count: Int

    data class Files(val files: List<File>, val cut: Boolean) : Clipboard {
        override val count: Int get() = files.size
    }

    /** Entries copied out of an archive; pasting them extracts them. */
    data class Entries(
        val archive: File,
        val format: ArchiveFormat,
        val password: String?,
        val entries: List<String>,
        val baseDir: String,
    ) : Clipboard {
        override val count: Int get() = entries.size
    }
}

/** A long running job shown in the progress dialog. */
data class OperationState(val title: String, val done: Long, val total: Long, val current: String)

/** A snackbar message, optionally with one action such as "Undo". */
data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

sealed interface BrowserDialog {
    data object NewFolder : BrowserDialog
    data object NewFile : BrowserDialog
    data class Rename(val item: FileItem) : BrowserDialog
    data class Delete(val items: List<FileItem>) : BrowserDialog
    data class Compress(val files: List<File>, val suggestedName: String) : BrowserDialog

    /**
     * Extract [archive] (or just [entries] from it) into a folder next to it.
     * [singleRoot] is true when everything already sits in one top-level folder,
     * in which case a new wrapping folder is not needed.
     */
    data class Extract(
        val archive: File,
        val format: ArchiveFormat,
        val entries: List<String>?,
        val baseDir: String,
        val singleRoot: Boolean,
    ) : BrowserDialog

    data class Password(val archiveName: String, val wrong: Boolean, val onSubmit: (String) -> Unit, val onCancel: () -> Unit) :
        BrowserDialog

    data class AutoSort(val dir: File) : BrowserDialog
    data class AutoSortPreview(val plan: AutoSorter.Plan) : BrowserDialog

    /** The open file has unsaved edits and the user is about to leave it. */
    data class UnsavedChanges(val fileName: String, val onSave: () -> Unit, val onDiscard: () -> Unit) : BrowserDialog
}
