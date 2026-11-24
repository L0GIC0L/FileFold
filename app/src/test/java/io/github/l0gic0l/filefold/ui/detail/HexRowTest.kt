package io.github.l0gic0l.filefold.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class HexRowTest {

    @Test
    fun `a full row shows offset, bytes and text`() {
        val bytes = "Hello, world!\n\u0000\u00ff".toByteArray(Charsets.ISO_8859_1)
        assertEquals(
            "00000010  48 65 6C 6C 6F 2C 20 77  6F 72 6C 64 21 0A 00 FF  Hello, world!...",
            hexRow(0x10, bytes, 16),
        )
    }

    @Test
    fun `a short last row keeps the text column lined up`() {
        val full = hexRow(0, "ABCDEFGH".toByteArray(), 8)
        val short = hexRow(8, "AB".toByteArray(), 8)
        assertEquals("00000000  41 42 43 44  45 46 47 48  ABCDEFGH", full)
        assertEquals("00000008  41 42                     AB", short)
        assertEquals(full.indexOf("ABCDEFGH"), short.indexOf("AB", startIndex = 12))
    }
}
