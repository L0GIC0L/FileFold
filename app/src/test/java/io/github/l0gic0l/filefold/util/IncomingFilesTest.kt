package io.github.l0gic0l.filefold.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingFilesTest {

    private val primary = "/storage/emulated/0"

    @Test
    fun `storage provider ids map to paths on the right volume`() {
        assertEquals("$primary/Download/a.zip", IncomingFiles.pathForStorageDocument("primary:Download/a.zip", primary))
        assertEquals(primary, IncomingFiles.pathForStorageDocument("primary:", primary))
        assertEquals("/storage/1A2B-3C4D/Music", IncomingFiles.pathForStorageDocument("1A2B-3C4D:Music", primary))
        assertEquals("$primary/Documents/notes.txt", IncomingFiles.pathForStorageDocument("home:notes.txt", primary))
        assertNull(IncomingFiles.pathForStorageDocument("no-volume", primary))
        assertNull(IncomingFiles.pathForStorageDocument(":Download", primary))
    }

    @Test
    fun `FileFold's own links map back to their files`() {
        val cache = "/data/user/0/io.github.l0gic0l.filefold/cache"
        assertEquals("/storage/emulated/0/Download", IncomingFiles.pathForOwnLink("/storage/emulated/0/Download", primary, cache))
        assertEquals("$primary/DCIM/a.jpg", IncomingFiles.pathForOwnLink("/external/DCIM/a.jpg", primary, cache))
        assertEquals("$cache/defaults/FileFold sample.zip", IncomingFiles.pathForOwnLink("/cache/defaults/FileFold sample.zip", primary, cache))
        assertNull(IncomingFiles.pathForOwnLink("/elsewhere/a.txt", primary, cache))
    }

    @Test
    fun `mime patterns match exact types and whole families`() {
        assertTrue(IncomingFiles.mimeMatches("image/png", listOf("image/*")))
        assertTrue(IncomingFiles.mimeMatches("application/pdf", listOf("image/*", "application/pdf")))
        assertTrue(IncomingFiles.mimeMatches("Image/JPEG", listOf("image/jpeg")))
        assertTrue(IncomingFiles.mimeMatches("video/mp4", listOf("*/*")))
        assertTrue(IncomingFiles.mimeMatches("video/mp4", emptyList()))
        assertFalse(IncomingFiles.mimeMatches("video/mp4", listOf("image/*")))
        assertFalse(IncomingFiles.mimeMatches("application/zip", listOf("application/pdf")))
        // "image/*" is a family, not a prefix of any word starting with "image".
        assertFalse(IncomingFiles.mimeMatches("imagery/x", listOf("image/*")))
    }
}
