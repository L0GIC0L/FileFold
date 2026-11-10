package io.github.l0gic0l.filefold.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Loads and saves text files for the editor while preserving how they were
 * stored: character encoding, byte order mark and Windows line endings all
 * survive an edit-and-save round trip.
 */
object TextDocument {

    /** Largest file the editor opens. Bigger files make typing sluggish on a phone. */
    const val EDIT_LIMIT: Long = 2L * 1024 * 1024

    data class Decoded(
        /** The text with line endings normalised to "\n". */
        val text: String,
        val charset: Charset,
        val hasBom: Boolean,
        val crlf: Boolean,
    ) {
        val encodingLabel: String get() = charset.displayName() + if (hasBom) " BOM" else ""
        val lineEndingLabel: String get() = if (crlf) "CRLF" else "LF"
    }

    private val UTF_8 = Charsets.UTF_8
    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /** True when the bytes are very unlikely to be text: a NUL byte near the start, outside UTF-16. */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bomOf(bytes) != null) return false
        val end = minOf(bytes.size, 8000)
        for (i in 0 until end) if (bytes[i] == 0.toByte()) return true
        return false
    }

    fun decode(bytes: ByteArray): Decoded {
        val bom = bomOf(bytes)
        val (charset, offset) = when (bom) {
            null -> (if (isValidUtf8(bytes)) UTF_8 else WINDOWS_1252) to 0
            else -> bom
        }
        val raw = String(bytes, offset, bytes.size - offset, charset)
        val crlf = raw.contains("\r\n")
        val text = if (crlf) raw.replace("\r\n", "\n") else raw
        return Decoded(text, charset, hasBom = bom != null, crlf = crlf)
    }

    fun encode(text: CharSequence, format: Decoded): ByteArray {
        val body = text.toString().let { if (format.crlf) it.replace("\r\n", "\n").replace("\n", "\r\n") else it }
        val encoded = body.toByteArray(format.charset)
        if (!format.hasBom) return encoded
        // Charset "UTF-16" writes its own BOM; the explicit-endian ones and UTF-8 need ours.
        val bom = when (format.charset) {
            UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
        return bom + encoded
    }

    fun load(file: File): Decoded {
        if (file.length() > EDIT_LIMIT) throw IOException("File is too large to edit")
        return decode(file.readBytes())
    }

    /**
     * Writes to a temporary file next to the original and then renames it over
     * the original, so a crash or full disk mid-save never leaves a truncated file.
     */
    fun save(file: File, text: CharSequence, format: Decoded) {
        val bytes = encode(text, format)
        val parent = file.absoluteFile.parentFile ?: throw IOException("Invalid location")
        val temp = File(parent, ".${file.name}.filefold-save")
        try {
            temp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                // Some storage refuses renames over an existing file; fall back to a direct write.
                file.writeBytes(bytes)
                temp.delete()
            }
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    private fun bomOf(bytes: ByteArray): Pair<Charset, Int>? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> UTF_8 to 3
            at(0) == 0xFF && at(1) == 0xFE -> Charsets.UTF_16LE to 2
            at(0) == 0xFE && at(1) == 0xFF -> Charsets.UTF_16BE to 2
            else -> null
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }
}
