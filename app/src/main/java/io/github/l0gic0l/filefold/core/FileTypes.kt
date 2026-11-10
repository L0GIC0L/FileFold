package io.github.l0gic0l.filefold.core

/**
 * Broad category of a file, used for icons, colours, previews and auto-sorting.
 * [label] names the category in settings; [noun] describes one file, e.g. "PNG image".
 */
enum class FileKind(val label: String, val noun: String, val isText: Boolean = false) {
    FOLDER("Folders", "folder"),
    IMAGE("Images", "image"),
    DESIGN("Design files", "design file"),
    VIDEO("Videos", "video"),
    AUDIO("Audio", "audio file"),
    PDF("PDFs", "PDF document"),
    DOCUMENT("Documents", "document"),
    SPREADSHEET("Spreadsheets", "spreadsheet"),
    PRESENTATION("Presentations", "presentation"),
    EBOOK("E-books", "e-book"),
    TEXT("Text", "text file", isText = true),
    CODE("Source code", "source file", isText = true),
    WEB("Web files", "web file", isText = true),
    DATA("Data and config", "data file", isText = true),
    SUBTITLE("Subtitles", "subtitle file", isText = true),
    ARCHIVE("Archives", "archive"),
    DISK_IMAGE("Disk images", "disk image"),
    APK("Android apps", "Android app package"),
    EXECUTABLE("Programs", "program"),
    FONT("Fonts", "font"),
    DATABASE("Databases", "database"),
    CONTACT("Contacts", "contact card", isText = true),
    CALENDAR("Calendars", "calendar", isText = true),
    MODEL_3D("3D models", "3D model"),
    TORRENT("Torrents", "torrent"),
    KEY("Keys and certificates", "key or certificate"),
    OTHER("Other files", "file"),
}

object FileTypes {

    private val byExtension: Map<String, FileKind> = buildMap {
        fun add(kind: FileKind, vararg extensions: String) = extensions.forEach { put(it, kind) }
        add(FileKind.IMAGE, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "ico", "tif", "tiff", "dng", "raw",
            "cr2", "cr3", "nef", "arw", "orf", "rw2", "jxl")
        add(FileKind.DESIGN, "svg", "psd", "ai", "xd", "sketch", "fig", "xcf", "kra", "eps", "cdr", "afdesign", "afphoto")
        add(FileKind.VIDEO, "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv", "wmv", "mpg", "mpeg", "m2ts", "mts", "vob")
        add(FileKind.AUDIO, "mp3", "wav", "flac", "ogg", "oga", "opus", "m4a", "aac", "wma", "mid", "midi", "amr", "aiff", "aif", "ape")
        add(FileKind.PDF, "pdf")
        add(FileKind.DOCUMENT, "doc", "docx", "odt", "rtf", "pages", "wpd", "dot", "dotx")
        add(FileKind.SPREADSHEET, "xls", "xlsx", "ods", "csv", "tsv", "numbers", "xlsm")
        add(FileKind.PRESENTATION, "ppt", "pptx", "odp", "pps", "ppsx")
        add(FileKind.EBOOK, "epub", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "djvu")
        add(FileKind.TEXT, "txt", "md", "markdown", "log", "rst", "nfo", "tex", "org", "diff", "patch")
        add(FileKind.CODE, "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "cs", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "php", "rb", "go", "rs", "swift", "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "sql", "lua", "pl", "r", "dart",
            "vhd", "vhdl", "v", "sv", "asm", "s", "ino", "make", "mk", "cmake", "dockerfile", "gradle", "pro", "scala", "groovy",
            "m", "mm", "hs", "ex", "exs", "clj", "zig", "nim", "jl", "f90", "ipynb")
        add(FileKind.WEB, "html", "htm", "xhtml", "css", "scss", "sass", "less", "vue", "svelte")
        add(FileKind.DATA, "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "env", "plist",
            "gitignore", "gitattributes", "editorconfig", "lock", "jsonl", "ndjson")
        add(FileKind.SUBTITLE, "srt", "vtt", "ass", "ssa", "sub")
        add(FileKind.ARCHIVE, "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "tbz", "xz", "txz", "lzma", "tlz", "z",
            "taz", "cpio", "ar", "deb", "arj", "zst", "lz4", "cab", "rpm")
        add(FileKind.DISK_IMAGE, "iso", "img", "dmg", "vmdk", "qcow2", "vdi", "wim")
        add(FileKind.APK, "apk", "apks", "xapk", "apkm", "aab")
        add(FileKind.EXECUTABLE, "exe", "msi", "appimage", "run", "com", "elf", "bin")
        add(FileKind.FONT, "ttf", "otf", "woff", "woff2", "fon", "ttc")
        add(FileKind.DATABASE, "db", "sqlite", "sqlite3", "realm", "mdb", "accdb", "dbf")
        add(FileKind.CONTACT, "vcf", "vcard")
        add(FileKind.CALENDAR, "ics", "ical")
        add(FileKind.MODEL_3D, "stl", "obj", "fbx", "gltf", "glb", "3mf", "blend", "dae", "ply", "step", "stp")
        add(FileKind.TORRENT, "torrent")
        add(FileKind.KEY, "pem", "crt", "cer", "der", "p12", "pfx", "jks", "keystore", "asc", "gpg", "pub", "ppk", "csr")
    }

    /** File names without an extension that are always text. */
    private val textNames = setOf("readme", "license", "licence", "makefile", "dockerfile", "changelog", "authors", "copying")

    /** Images Android can decode natively, i.e. the ones we can preview and thumbnail. */
    private val previewableImage = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "ico")

    /** Lower-cased extension without the dot, or "" when there is none. */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    fun kindOf(name: String, isDirectory: Boolean = false): FileKind {
        if (isDirectory) return FileKind.FOLDER
        return byExtension[extensionOf(name)] ?: FileKind.OTHER
    }

    fun isPreviewableImage(name: String): Boolean = extensionOf(name) in previewableImage

    /** True for files the editor should open straight away, rather than showing details. */
    fun isText(name: String): Boolean =
        kindOf(name).isText || extensionOf(name) == "svg" || name.lowercase() in textNames

    /** Human readable type, e.g. "PNG image". */
    fun describe(name: String, isDirectory: Boolean): String {
        val kind = kindOf(name, isDirectory)
        val ext = extensionOf(name).uppercase()
        return when (kind) {
            FileKind.FOLDER -> "Folder"
            FileKind.APK, FileKind.PDF -> kind.noun.replaceFirstChar { it.uppercase() }
            FileKind.OTHER -> if (ext.isEmpty()) "File" else "$ext file"
            else -> "$ext ${kind.noun}"
        }
    }
}
