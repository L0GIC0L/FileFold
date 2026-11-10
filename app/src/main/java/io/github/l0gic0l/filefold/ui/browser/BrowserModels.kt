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
