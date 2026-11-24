package io.github.l0gic0l.filefold.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextDocumentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun roundTrip(bytes: ByteArray): ByteArray {
        val decoded = TextDocument.decode(bytes)
        return TextDocument.encode(decoded.text, decoded)
    }

    @Test
    fun `plain utf-8 round trips byte for byte`() {
        val bytes = "héllo wörld ✓\nline two\n".toByteArray()
        val decoded = TextDocument.decode(bytes)
        assertEquals("UTF-8", decoded.charset.name())
        assertFalse(decoded.hasBom)
        assertFalse(decoded.crlf)
        assertArrayEquals(bytes, roundTrip(bytes))
    }

    @Test
    fun `windows line endings are hidden while editing and restored on save`() {
        val bytes = "one\r\ntwo\r\nthree".toByteArray()
        val decoded = TextDocument.decode(bytes)
        assertTrue(decoded.crlf)
        assertEquals("one\ntwo\nthree", decoded.text)
        assertEquals("one\r\ntwo\r\nthree\r\nfour", String(TextDocument.encode(decoded.text + "\nfour", decoded)))
    }

    @Test
    fun `byte order marks are kept`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "bom text".toByteArray()
        val decoded = TextDocument.decode(utf8Bom)
        assertTrue(decoded.hasBom)
        assertEquals("bom text", decoded.text)
        assertArrayEquals(utf8Bom, roundTrip(utf8Bom))

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "wide".toByteArray(Charsets.UTF_16LE)
        assertEquals("wide", TextDocument.decode(utf16).text)
        assertFalse(TextDocument.looksBinary(utf16))
        assertArrayEquals(utf16, roundTrip(utf16))
    }

    @Test
    fun `invalid utf-8 falls back to a single byte encoding without losing data`() {
        val latin = byteArrayOf('c'.code.toByte(), 0xE9.toByte(), 'f'.code.toByte(), 0xE9.toByte()) // "café" in Latin-1
        val decoded = TextDocument.decode(latin)
        assertEquals("cé" + "fé", decoded.text)
        assertArrayEquals(latin, roundTrip(latin))
    }

    @Test
    fun `binary data is recognised`() {
        assertTrue(TextDocument.looksBinary(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01)))
        assertFalse(TextDocument.looksBinary("just text".toByteArray()))
    }

    @Test
    fun `save replaces the file and leaves no temporary file`() {
        val file = File(tmp.root, "notes.txt").apply { writeText("old\r\ntext") }
        val decoded = TextDocument.load(file)
        TextDocument.save(file, decoded.text.replace("old", "new"), decoded)
        assertEquals("new\r\ntext", file.readText())
        assertEquals(listOf("notes.txt"), tmp.root.list()!!.toList())
    }
}
