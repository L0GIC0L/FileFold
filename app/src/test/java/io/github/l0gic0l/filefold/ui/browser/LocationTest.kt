package io.github.l0gic0l.filefold.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTest {

    private val root = Location.Disk("/storage/emulated/0")
    private val download = Location.Disk("/storage/emulated/0/Download")
    private val downloads2 = Location.Disk("/storage/emulated/0/Downloads2")
    private val zipRoot = Location.InArchive("/storage/emulated/0/Download/a.zip", "")
    private val zipDocs = Location.InArchive("/storage/emulated/0/Download/a.zip", "docs")
    private val zipDocsDeep = Location.InArchive("/storage/emulated/0/Download/a.zip", "docs/old")

    @Test
    fun `inside follows folder nesting, through archives too`() {
        assertTrue(download.isInside(root))
        assertTrue(zipRoot.isInside(download))
        assertTrue(zipDocs.isInside(zipRoot))
        assertTrue(zipDocsDeep.isInside(zipDocs))
        assertTrue(zipDocsDeep.isInside(root))

        assertFalse(root.isInside(download))
        assertFalse(download.isInside(download))
        assertFalse(zipDocs.isInside(zipDocs))
        assertFalse(zipRoot.isInside(zipDocs))
        // A shared name prefix is not nesting.
        assertFalse(downloads2.isInside(download))
        assertFalse(Location.InArchive("/storage/emulated/0/Download/a.zip", "docs2").isInside(zipDocs))
    }

    @Test
    fun `locations survive being saved and read back`() {
        for (loc in listOf(root, download, zipRoot, zipDocsDeep, Location.Disk("/path/with!/odd:chars"))) {
            assertEquals(loc, Location.decode(loc.encode()))
        }
        assertNull(Location.decode("garbage"))
        assertNull(Location.decode("a:no-separator"))
    }

    @Test
    fun `scroll memory is kept per tab`() {
        assertFalse(Page(1, download).scrollKey == Page(2, download).scrollKey)
    }
}
