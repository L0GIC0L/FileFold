package io.github.l0gic0l.filefold.core

import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/** Fingerprints of a file, for checking a download against the values its publisher lists. */
object Checksums {

    val algorithms = listOf("CRC32", "MD5", "SHA-1", "SHA-256")

    /** Reads [file] once and returns every checksum as lower-case hex, keyed by algorithm name. */
    fun compute(file: File, progress: Progress = Progress.None): Map<String, String> {
        val crc = CRC32()
        val digests = algorithms.drop(1).map { MessageDigest.getInstance(it) }
        progress.total = file.length()
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                progress.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
                digests.forEach { it.update(buffer, 0, read) }
                progress.advance(read.toLong())
            }
        }
        return buildMap {
            put("CRC32", "%08x".format(crc.value))
            digests.forEach { digest -> put(digest.algorithm, digest.digest().joinToString("") { "%02x".format(it) }) }
        }
    }

    /** The algorithm whose result equals [expected] (ignoring case and spaces), or null. */
    fun match(results: Map<String, String>, expected: String): String? {
        val wanted = expected.filterNot { it.isWhitespace() }.lowercase()
        if (wanted.isEmpty()) return null
        return results.entries.firstOrNull { it.value == wanted }?.key
    }
}
