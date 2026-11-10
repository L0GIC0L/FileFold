package io.github.l0gic0l.filefold.ui.detail

import java.io.File

/** What the detail pane is showing. The [key] is what the list-detail navigator remembers. */
sealed interface DetailTarget {
    val key: String
    val name: String

    data class Disk(val path: String) : DetailTarget {
        override val key: String get() = "disk:$path"
        override val name: String get() = File(path).name
    }

    data class Entry(val archivePath: String, val entryPath: String) : DetailTarget {
        override val key: String get() = "entry:$archivePath$SEPARATOR$entryPath"
        override val name: String get() = entryPath.substringAfterLast('/')
    }

    companion object {
        private const val SEPARATOR = "!/"

        /** Rebuilds a target from its key, e.g. after the process was killed in the background. */
        fun fromKey(key: String): DetailTarget? = when {
            key.startsWith("disk:") -> Disk(key.removePrefix("disk:"))
            key.startsWith("entry:") -> key.removePrefix("entry:").split(SEPARATOR, limit = 2)
                .takeIf { it.size == 2 }?.let { Entry(it[0], it[1]) }
            else -> null
        }
    }
}

/** A labelled fact shown in the info pane, e.g. "Size" to "4.2 MB". */
data class InfoRow(val label: String, val value: String)

data class FileInfo(
    val name: String,
    val isDirectory: Boolean,
    val typeLabel: String,
    val rows: List<InfoRow>,
    /** A real file that can be handed to other apps, if there is one. */
    val localFile: File?,
    /** True when the file is a ZIP-style container (APK, JAR, DOCX, EPUB...) that can be browsed. */
    val browsableArchive: Boolean,
    /** Shown above the rows, e.g. why a file could not be opened in the editor. */
    val note: String? = null,
    /** True when the user may force the file open as text. */
    val canOpenAsText: Boolean = false,
)

sealed interface DetailContent {
    data object Loading : DetailContent
    data class Failed(val message: String) : DetailContent
    data class Text(val editor: EditorSession) : DetailContent
    data class Image(val file: File, val info: FileInfo) : DetailContent
    data class Info(val info: FileInfo) : DetailContent
}

data class DetailState(val target: DetailTarget, val content: DetailContent) {
    val isDirty: Boolean get() = (content as? DetailContent.Text)?.editor?.isDirty == true
}
