package io.github.l0gic0l.filefold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChecksumsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `known values for a known file`() {
        val file = tmp.newFile("abc.txt").apply { writeText("abc") }
        val sums = Checksums.compute(file)
        assertEquals("352441c2", sums["CRC32"])
        assertEquals("900150983cd24fb0d6963f7d28e17f72", sums["MD5"])
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sums["SHA-1"])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sums["SHA-256"])
        assertEquals(Checksums.algorithms, sums.keys.toList())
    }

    @Test
    fun `an empty file and a file larger than the buffer`() {
        val empty = tmp.newFile("empty")
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Checksums.compute(empty)["MD5"])
        val big = tmp.newFile("big").apply { writeBytes(ByteArray(1_000_000) { 'a'.code.toByte() }) }
        // The well-known digest of one million letters "a".
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", Checksums.compute(big)["SHA-1"])
    }

    @Test
    fun `matching ignores case and spaces`() {
        val file = tmp.newFile("abc.txt").apply { writeText("abc") }
        val sums = Checksums.compute(file)
        assertEquals("MD5", Checksums.match(sums, " 900150983CD24FB0 D6963F7D28E17F72\n"))
        assertEquals("SHA-1", Checksums.match(sums, "a9993e364706816aba3e25717850c26c9cd0d89d"))
        assertNull(Checksums.match(sums, "deadbeef"))
        assertNull(Checksums.match(sums, "  "))
    }
}
