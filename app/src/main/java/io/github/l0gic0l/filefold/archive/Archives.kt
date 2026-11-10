package io.github.l0gic0l.filefold.archive

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.WrongPasswordException
import io.github.l0gic0l.filefold.core.FileOps
import io.github.l0gic0l.filefold.core.Progress
import io.github.l0gic0l.filefold.core.copyTo
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.arj.ArjArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import org.tukaani.xz.LZMA2Options
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files

/** One entry as stored in an archive. [path] uses forward slashes and never starts with one. */
data class ArchiveEntryInfo(
    val path: String,
    val isDirectory: Boolean,
    /** Uncompressed size in bytes, -1 when the format does not record it. */
    val size: Long,
    /** Compressed size in bytes, -1 when the format does not record it per entry. */
    val compressedSize: Long,
    /** Last modified time in epoch milliseconds, 0 when unknown. */
    val modified: Long,
    val encrypted: Boolean = false,
)

/** Thrown when an archive needs a password, or was given the wrong one. */
class ArchivePasswordException(val wrongPassword: Boolean) :
    IOException(if (wrongPassword) "Wrong password" else "This archive is password protected")

/**
 * Reads and writes archives. Everything here blocks, so call it from a
 * background thread.
 */
object Archives {

    /**
     * LZMA2 preset used for 7z and XZ output. Preset 4 needs roughly 48 MB to
     * compress, about half of the desktop default, which keeps us comfortably
     * inside a phone app's heap.
     */
    private const val LZMA_PRESET = 4

    /** Entry names are always read and written as UTF-8, whatever the device default. */
    private const val UTF_8 = "UTF-8"

    /** Formats where the entry list can be read without decompressing the data. */
    private val randomAccess = setOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR)

    /** Lists every entry. Entries with unsafe paths (containing "..") are left out. */
    fun list(file: File, format: ArchiveFormat, password: String? = null): List<ArchiveEntryInfo> {
        val entries = ArrayList<ArchiveEntryInfo>()
        visit(file, format, password, readsData = false, rawProgress = null) { info, _ ->
            normalizeEntryPath(info.path)?.let { entries += info.copy(path = it) }
            true
        }
        return entries
    }

    /**
     * Extracts into [destDir] and returns the number of files written.
     *
     * @param only when given, just these entries are extracted; a folder path
     *   selects everything beneath it.
     * @param baseDir folder inside the archive that maps onto [destDir], so that
     *   extracting a selection does not recreate its parent folders.
     */
    fun extract(
        file: File,
        format: ArchiveFormat,
        destDir: File,
        password: String? = null,
        only: Collection<String>? = null,
        baseDir: String = "",
        progress: Progress = Progress.None,
    ): Int {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("Could not create \"${destDir.name}\"")
        val wanted: (String) -> Boolean =
            if (only == null) { _ -> true } else { path -> only.any { path == it || path.startsWith("$it/") } }

        // Formats with an index tell us the exact output size up front. For
        // streamed formats we track how far through the archive file we are.
        val countRaw = format !in randomAccess
        progress.total = if (countRaw) {
            file.length()
        } else {
            list(file, format, password).filter { !it.isDirectory && wanted(it.path) }.sumOf { it.size.coerceAtLeast(0) }
        }

        var written = 0
        visit(file, format, password, readsData = true, rawProgress = if (countRaw) progress else null) { info, open ->
            progress.ensureActive()
            val path = normalizeEntryPath(info.path)
            if (path == null || !wanted(path)) return@visit true
            val relative = if (baseDir.isEmpty()) path else path.removePrefix("$baseDir/")
            val target = safeResolve(destDir, relative)
            if (info.isDirectory) {
                target.mkdirs()
            } else {
                progress.start(target.name)
                val parent = target.parentFile!!
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create folder \"${parent.name}\"")
                // Existing files are never overwritten; the new one gets a numbered name.
                val output = if (target.exists()) FileOps.uniqueTarget(parent, target.name) else target
                writeEntry(open, output, progress, count = !countRaw)
                if (info.modified > 0) output.setLastModified(info.modified)
                written++
            }
            true
        }
        return written
    }

    /** Extracts a single file entry to [target], for previews. */
    fun extractEntry(file: File, format: ArchiveFormat, entryPath: String, target: File, password: String? = null) {
        var found = false
        visit(file, format, password, readsData = true, rawProgress = null) { info, open ->
            if (info.isDirectory || normalizeEntryPath(info.path) != entryPath) return@visit true
            target.parentFile?.mkdirs()
            writeEntry(open, target, Progress.None, count = false)
            found = true
            false
        }
        if (!found) throw IOException("\"$entryPath\" was not found in the archive")
    }

    /** Packs [sources] (files and folders) into a new archive at [output]. */
    fun create(output: File, format: ArchiveFormat, sources: List<File>, progress: Progress = Progress.None) {
        require(format.canCreate) { "${format.label} archives cannot be created" }
        progress.total = FileOps.totalSize(sources, progress)
        try {
            when (format) {
                ArchiveFormat.ZIP -> ZipArchiveOutputStream(output).use { zip ->
                    walk(sources, output) { file, name ->
                        zip.putArchiveEntry(ZipArchiveEntry(file, if (file.isDirectory) "$name/" else name))
                        if (file.isFile) file.inputStream().use { it.copyTo(zip, progress) }
                        zip.closeArchiveEntry()
                    }
                }

                ArchiveFormat.SEVEN_Z -> SevenZOutputFile(output).use { sevenZ ->
                    sevenZ.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(LZMA_PRESET))))
                    val sink = object : OutputStream() {
                        override fun write(b: Int) = sevenZ.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = sevenZ.write(b, off, len)
                    }
                    walk(sources, output) { file, name ->
                        sevenZ.putArchiveEntry(sevenZ.createArchiveEntry(file, name))
                        if (file.isFile) file.inputStream().use { it.copyTo(sink, progress) }
                        sevenZ.closeArchiveEntry()
                    }
                }

                else -> TarArchiveOutputStream(compressor(format, output.outputStream().buffered()), UTF_8).use { tar ->
                    // POSIX (pax) headers lift the classic tar limits on name length and file
                    // size, and record non-ASCII names in a way every modern tar understands.
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    tar.setAddPaxHeadersForNonAsciiNames(true)
                    walk(sources, output) { file, name ->
                        tar.putArchiveEntry(TarArchiveEntry(file, name))
                        if (file.isFile) file.inputStream().use { it.copyTo(tar, progress) }
                        tar.closeArchiveEntry()
                    }
                }
            }
        } catch (e: Throwable) {
            // A failed or cancelled archive is useless, so do not leave it behind.
            output.delete()
            throw e
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Walks the archive, handing each entry to [visitor] along with a function
     * that opens its data. The visitor returns false to stop early.
     */
    private fun visit(
        file: File,
        format: ArchiveFormat,
        password: String?,
        readsData: Boolean,
        rawProgress: Progress?,
        visitor: (ArchiveEntryInfo, open: () -> InputStream) -> Boolean,
    ) {
        try {
            when {
                format == ArchiveFormat.ZIP -> visitZip(file, visitor)
                format == ArchiveFormat.SEVEN_Z -> visitSevenZ(file, password, visitor)
                format == ArchiveFormat.RAR -> visitRar(file, password, readsData, visitor)
                format.isSingleFile -> visitSingleFile(file, format, rawProgress, visitor)
                else -> visitStream(file, format, rawProgress, visitor)
            }
        } catch (e: PasswordRequiredException) {
            throw ArchivePasswordException(wrongPassword = false)
        }
    }

    private fun visitZip(file: File, visitor: (ArchiveEntryInfo, () -> InputStream) -> Boolean) {
        ZipFile.builder().setFile(file).get().use { zip ->
            for (entry in zip.entries) {
                if (entry.isUnixSymlink) continue
                val encrypted = entry.generalPurposeBit.usesEncryption()
                val info = ArchiveEntryInfo(
                    path = entry.name,
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    compressedSize = entry.compressedSize,
                    modified = entry.time.coerceAtLeast(0),
                    encrypted = encrypted,
                )
                val proceed = visitor(info) {
                    if (encrypted) throw IOException("Password protected ZIP archives are not supported")
                    if (!zip.canReadEntryData(entry)) throw IOException("\"${entry.name}\" uses an unsupported compression method")
                    zip.getInputStream(entry)
                }
                if (!proceed) break
            }
        }
    }

    private fun visitSevenZ(file: File, password: String?, visitor: (ArchiveEntryInfo, () -> InputStream) -> Boolean) {
        val builder = SevenZFile.builder().setFile(file)
        if (password != null) builder.setPassword(password.toCharArray())
        val sevenZ = try {
            builder.get()
        } catch (e: PasswordRequiredException) {
            throw ArchivePasswordException(wrongPassword = false)
        } catch (e: IOException) {
            // With encrypted headers a wrong password shows up as a corrupt archive.
            if (password != null) throw ArchivePasswordException(wrongPassword = true) else throw e
        }
        sevenZ.use {
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                if (entry.isAntiItem) continue
                val info = ArchiveEntryInfo(
                    path = entry.name ?: ArchiveFormat.stripExtension(file.name),
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    compressedSize = -1,
                    modified = if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else 0,
                )
                val proceed = visitor(info) {
                    object : InputStream() {
                        private val single = ByteArray(1)
                        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
                        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                            sevenZ.read(b, off, len)
                        } catch (e: PasswordRequiredException) {
                            throw ArchivePasswordException(wrongPassword = false)
                        } catch (e: IOException) {
                            if (password != null) throw ArchivePasswordException(wrongPassword = true) else throw e
                        }
                    }
                }
                if (!proceed) break
            }
        }
    }

    private fun visitRar(
        file: File,
        password: String?,
        readsData: Boolean,
        visitor: (ArchiveEntryInfo, () -> InputStream) -> Boolean,
    ) {
        try {
            Archive(file, password).use { rar ->
                if (password == null && rar.isEncrypted) throw ArchivePasswordException(wrongPassword = false)
                val solid = rar.mainHeader?.isSolid == true
                for (header in rar.fileHeaders) {
                    var opened = false
                    val info = ArchiveEntryInfo(
                        path = header.fileName,
                        isDirectory = header.isDirectory,
                        size = header.fullUnpackSize,
                        compressedSize = header.fullPackSize,
                        modified = header.mTime?.time ?: 0,
                        encrypted = header.isEncrypted,
                    )
                    val proceed = visitor(info) {
                        opened = true
                        if (header.isEncrypted && password == null) throw ArchivePasswordException(wrongPassword = false)
                        rar.getInputStream(header)
                    }
                    if (!proceed) break
                    // In a solid archive each file builds on the ones before it, so a
                    // skipped file still has to be decoded for later ones to come out right.
                    if (readsData && solid && !opened && !header.isDirectory) rar.extractFile(header, NullOutputStream)
                }
            }
        } catch (e: WrongPasswordException) {
            throw ArchivePasswordException(wrongPassword = true)
        } catch (e: UnsupportedRarEncryptedException) {
            throw IOException("This RAR archive uses an encryption method that is not supported")
        } catch (e: RarException) {
            throw IOException("This RAR archive is damaged or uses an unsupported feature", e)
        }
    }

    /** TAR and its compressed variants, plus AR, CPIO and ARJ: read front to back. */
    private fun visitStream(
        file: File,
        format: ArchiveFormat,
        rawProgress: Progress?,
        visitor: (ArchiveEntryInfo, () -> InputStream) -> Boolean,
    ) {
        val raw = decompressor(format, openRaw(file, rawProgress))
        val archive: ArchiveInputStream<*> = when (format) {
            ArchiveFormat.AR -> ArArchiveInputStream(raw)
            ArchiveFormat.CPIO -> CpioArchiveInputStream(raw, UTF_8)
            ArchiveFormat.ARJ -> ArjArchiveInputStream(raw, UTF_8)
            else -> TarArchiveInputStream(raw, UTF_8)
        }
        archive.use {
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!archive.canReadEntryData(entry)) continue
                // Links and device nodes have no place on shared storage, and links are a classic attack vector.
                if (entry is TarArchiveEntry && !(entry.isFile || entry.isDirectory)) continue
                val info = ArchiveEntryInfo(
                    path = entry.name,
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    compressedSize = -1,
                    modified = entry.lastModifiedDate?.time ?: 0,
                )
                // The entry's data is simply the archive stream until the next header,
                // so closing the entry must not close the archive.
                val proceed = visitor(info) { NonClosingInputStream(archive) }
                if (!proceed) break
            }
        }
    }

    /** .gz, .bz2, .xz and friends: one compressed file, presented as an archive with a single entry. */
    private fun visitSingleFile(
        file: File,
        format: ArchiveFormat,
        rawProgress: Progress?,
        visitor: (ArchiveEntryInfo, () -> InputStream) -> Boolean,
    ) {
        val info = ArchiveEntryInfo(
            path = ArchiveFormat.stripExtension(file.name),
            isDirectory = false,
            size = if (format == ArchiveFormat.GZ) gzipUncompressedSize(file) else -1,
            compressedSize = file.length(),
            modified = file.lastModified(),
        )
        visitor(info) { decompressor(format, openRaw(file, rawProgress)) }
    }

    private fun openRaw(file: File, progress: Progress?): InputStream {
        val input: InputStream = file.inputStream()
        return BufferedInputStream(if (progress != null) CountingInputStream(input, progress) else input, 64 * 1024)
    }

    private fun decompressor(format: ArchiveFormat, input: InputStream): InputStream = try {
        when (format) {
            // "true" keeps reading when several compressed streams were concatenated into one file.
            ArchiveFormat.TAR_GZ, ArchiveFormat.GZ ->
                GzipCompressorInputStream.builder().setInputStream(input).setDecompressConcatenated(true).get()
            ArchiveFormat.TAR_BZ2, ArchiveFormat.BZ2 -> BZip2CompressorInputStream(input, true)
            ArchiveFormat.TAR_XZ, ArchiveFormat.XZ ->
                XZCompressorInputStream.builder().setInputStream(input).setDecompressConcatenated(true).get()
            ArchiveFormat.TAR_LZMA, ArchiveFormat.LZMA -> LZMACompressorInputStream(input)
            ArchiveFormat.TAR_Z, ArchiveFormat.Z -> ZCompressorInputStream(input)
            else -> input
        }
    } catch (e: Throwable) {
        input.close()
        throw e
    }

    /** gzip stores the original size (modulo 4 GiB) in its last four bytes. */
    private fun gzipUncompressedSize(file: File): Long = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 18) return -1
            raf.seek(raf.length() - 4)
            val b = ByteArray(4)
            raf.readFully(b)
            (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        }
    } catch (_: IOException) {
        -1
    }

    private fun writeEntry(open: () -> InputStream, output: File, progress: Progress, count: Boolean) {
        try {
            open().use { input -> output.outputStream().use { input.copyTo(it, progress, count) } }
        } catch (e: Throwable) {
            output.delete()
            throw e
        }
    }

    // ---------------------------------------------------------------- writing

    private fun compressor(format: ArchiveFormat, out: OutputStream): OutputStream = when (format) {
        ArchiveFormat.TAR_GZ -> GzipCompressorOutputStream(out)
        ArchiveFormat.TAR_BZ2 -> BZip2CompressorOutputStream(out)
        ArchiveFormat.TAR_XZ ->
            XZCompressorOutputStream.builder().setOutputStream(out).setLzma2Options(LZMA2Options(LZMA_PRESET)).get()
        else -> out
    }

    /**
     * Visits [sources] depth first with the name each should have inside the
     * archive. Symbolic links are skipped, as is the archive being written
     * should it live inside one of the folders being packed.
     */
    private fun walk(sources: List<File>, output: File, visit: (file: File, entryName: String) -> Unit) {
        val outputPath = output.absolutePath
        fun recurse(file: File, name: String) {
            if (file.absolutePath == outputPath || Files.isSymbolicLink(file.toPath())) return
            visit(file, name)
            if (file.isDirectory) {
                file.listFiles().orEmpty().sortedBy { it.name }.forEach { recurse(it, "$name/${it.name}") }
            }
        }
        sources.forEach { recurse(it, it.name) }
    }

    // ----------------------------------------------------------------- safety

    /**
     * Cleans an entry path: forward slashes only, no leading slash, no empty or
     * "." segments. Returns null for paths that try to climb out with "..".
     */
    fun normalizeEntryPath(raw: String): String? {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /**
     * Maps an entry path onto a file inside [destDir], guaranteeing the result
     * cannot escape it (the "zip slip" attack). Characters that shared storage
     * rejects are replaced so that archives made on other systems still extract.
     */
    internal fun safeResolve(destDir: File, entryPath: String): File {
        val cleaned = entryPath.split('/').joinToString(File.separator) { segment ->
            segment.map { if (it in ":*?\"<>|\\" || it.code < 32) '_' else it }.joinToString("")
        }
        val target = File(destDir, cleaned)
        val root = destDir.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(root)) throw IOException("Blocked unsafe path in archive: $entryPath")
        return target
    }

    private class CountingInputStream(input: InputStream, private val progress: Progress) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) progress.advance(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) progress.advance(it.toLong()) }
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}
