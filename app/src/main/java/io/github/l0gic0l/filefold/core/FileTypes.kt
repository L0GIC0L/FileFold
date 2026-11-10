package io.github.l0gic0l.filefold.core

/** Broad category of a file, used for icons, previews and auto-sorting. */
enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, TEXT, ARCHIVE, APK, OTHER }

object FileTypes {

    private val image = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico", "tif", "tiff", "dng", "raw",
    )
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv", "wmv", "ts", "mpg", "mpeg")
    private val audio = setOf("mp3", "wav", "flac", "ogg", "oga", "opus", "m4a", "aac", "wma", "mid", "midi", "amr")
    private val document = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "mobi",
    )
    private val text = setOf(
        // Plain text and data
        "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "properties", "env", "srt", "vtt", "tex", "rst", "nfo", "diff", "patch", "gitignore", "gradle", "pro",
        // Source code
        "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "cs", "py", "js", "mjs", "ts", "tsx", "jsx", "html", "htm",
        "css", "scss", "php", "rb", "go", "rs", "swift", "sh", "bash", "zsh", "bat", "cmd", "ps1", "sql", "lua", "pl",
        "r", "dart", "vhd", "vhdl", "v", "sv", "asm", "s", "ino", "make", "mk", "cmake", "dockerfile",
    )
    private val archive = setOf(
        "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "tbz", "xz", "txz", "lzma", "tlz", "z", "taz",
        "cpio", "ar", "deb", "arj",
    )
    private val apk = setOf("apk", "apks", "xapk", "apkm")

    /** Images Android can decode natively, i.e. the ones we can preview and thumbnail. */
    private val previewableImage = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "ico")

    /** Lower-cased extension without the dot, or "" when there is none. */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    fun kindOf(name: String, isDirectory: Boolean = false): FileKind {
        if (isDirectory) return FileKind.FOLDER
        val ext = extensionOf(name)
        return when {
            ext in image -> FileKind.IMAGE
            // ".ts" is far more often TypeScript than an MPEG transport stream on a phone.
            ext in text -> FileKind.TEXT
            ext in video -> FileKind.VIDEO
            ext in audio -> FileKind.AUDIO
            ext in document -> FileKind.DOCUMENT
            ext in archive -> FileKind.ARCHIVE
            ext in apk -> FileKind.APK
            else -> FileKind.OTHER
        }
    }

    fun isPreviewableImage(name: String): Boolean = extensionOf(name) in previewableImage

    /** Human readable type label, e.g. "PNG image". */
    fun describe(name: String, isDirectory: Boolean): String {
        val ext = extensionOf(name).uppercase()
        return when (kindOf(name, isDirectory)) {
            FileKind.FOLDER -> "Folder"
            FileKind.IMAGE -> "$ext image"
            FileKind.VIDEO -> "$ext video"
            FileKind.AUDIO -> "$ext audio"
            FileKind.DOCUMENT -> "$ext document"
            FileKind.TEXT -> "$ext text file"
            FileKind.ARCHIVE -> "$ext archive"
            FileKind.APK -> "Android app package"
            FileKind.OTHER -> if (ext.isEmpty()) "File" else "$ext file"
        }
    }
}
