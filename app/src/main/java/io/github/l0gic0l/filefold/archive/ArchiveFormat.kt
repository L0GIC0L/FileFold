package io.github.l0gic0l.filefold.archive

import java.io.File

/**
 * Every archive and compression format the app understands. All of them can be
 * browsed and extracted; the ones with [canCreate] can also be written.
 */
enum class ArchiveFormat(val label: String, val extensions: List<String>, val canCreate: Boolean = false) {
    ZIP("ZIP", listOf("zip"), canCreate = true),
    SEVEN_Z("7z", listOf("7z"), canCreate = true),
    TAR("TAR", listOf("tar"), canCreate = true),
    TAR_GZ("TAR.GZ", listOf("tar.gz", "tgz"), canCreate = true),
    TAR_BZ2("TAR.BZ2", listOf("tar.bz2", "tbz2", "tbz"), canCreate = true),
    TAR_XZ("TAR.XZ", listOf("tar.xz", "txz"), canCreate = true),
    TAR_LZMA("TAR.LZMA", listOf("tar.lzma", "tlz")),
    TAR_Z("TAR.Z", listOf("tar.z", "taz")),
    RAR("RAR", listOf("rar")),
    GZ("GZIP", listOf("gz")),
    BZ2("BZIP2", listOf("bz2")),
    XZ("XZ", listOf("xz")),
    LZMA("LZMA", listOf("lzma")),
    Z("Z", listOf("z")),
    AR("AR", listOf("ar", "deb")),
    CPIO("CPIO", listOf("cpio")),
    ARJ("ARJ", listOf("arj"));

    /** The extension used when creating an archive of this format. */
    val extension: String get() = extensions.first()

    /** True for formats that compress exactly one file and carry no file names of their own. */
    val isSingleFile: Boolean get() = this == GZ || this == BZ2 || this == XZ || this == LZMA || this == Z

    companion object {
        val creatable: List<ArchiveFormat> = entries.filter { it.canCreate }

        // Longest extension first, so "notes.tar.gz" is TAR.GZ rather than GZIP.
        private val bySuffix = entries
            .flatMap { format -> format.extensions.map { ".$it" to format } }
            .sortedByDescending { it.first.length }

        /** Detects the format from a file name, or null when it is not an archive. */
        fun fromName(name: String): ArchiveFormat? {
            val lower = name.lowercase()
            return bySuffix.firstOrNull { (suffix, _) -> lower.length > suffix.length && lower.endsWith(suffix) }?.second
        }

        /**
         * Detects the format from the file's leading bytes. This is what lets
         * ZIP-based containers such as APK, JAR, DOCX and EPUB be browsed.
         */
        fun sniff(file: File): ArchiveFormat? {
            val head = ByteArray(262)
            var read = 0
            try {
                file.inputStream().use { input ->
                    while (read < head.size) {
                        val n = input.read(head, read, head.size - read)
                        if (n < 0) break
                        read += n
                    }
                }
            } catch (_: Exception) {
                return null
            }
            fun startsWith(vararg magic: Int) =
                read >= magic.size && magic.indices.all { head[it] == magic[it].toByte() }
            return when {
                startsWith(0x50, 0x4B, 0x03, 0x04) || startsWith(0x50, 0x4B, 0x05, 0x06) -> ZIP
                startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> SEVEN_Z
                startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> RAR
                startsWith(0x1F, 0x8B) -> GZ
                startsWith(0x42, 0x5A, 0x68) -> BZ2
                startsWith(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> XZ
                read >= 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar" -> TAR
                else -> null
            }
        }

        fun of(file: File): ArchiveFormat? = fromName(file.name) ?: sniff(file)

        /** The file name with its archive extension removed: "photos.tar.gz" becomes "photos". */
        fun stripExtension(name: String): String {
            val lower = name.lowercase()
            val suffix = bySuffix.firstOrNull { (suffix, _) -> lower.length > suffix.length && lower.endsWith(suffix) }?.first
            return when {
                suffix != null -> name.dropLast(suffix.length)
                name.lastIndexOf('.') > 0 -> name.substringBeforeLast('.')
                else -> name
            }
        }
    }
}
