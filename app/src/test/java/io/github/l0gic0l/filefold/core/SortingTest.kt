package io.github.l0gic0l.filefold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortingTest {

    private fun file(name: String, size: Long = 0, modified: Long = 0) = FileItem(name, "/x/$name", false, size, modified)
    private fun folder(name: String, modified: Long = 0) = FileItem(name, "/x/$name", true, 0, modified)

    @Test
    fun `natural order compares digit runs numerically`() {
        val sorted = listOf("file10.txt", "file2.txt", "File1.txt", "file02.txt", "alpha", "Beta").sortedWith(NaturalOrder)
        // Equal numbers ("02" and "2") fall back to a plain comparison, which keeps the order stable.
        assertEquals(listOf("alpha", "Beta", "File1.txt", "file02.txt", "file2.txt", "file10.txt"), sorted)
    }

    @Test
    fun `natural order handles numbers too large for a long`() {
        assertTrue(NaturalOrder.compare("a99999999999999999999999", "a100000000000000000000000") < 0)
    }

    @Test
    fun `folders come first regardless of direction`() {
        val items = listOf(file("b.txt"), folder("zeta"), file("a.txt"), folder("alpha"))
        val ascending = items.sortedWith(SortOptions(SortBy.NAME, ascending = true).comparator()).map { it.name }
        val descending = items.sortedWith(SortOptions(SortBy.NAME, ascending = false).comparator()).map { it.name }
        assertEquals(listOf("alpha", "zeta", "a.txt", "b.txt"), ascending)
        assertEquals(listOf("zeta", "alpha", "b.txt", "a.txt"), descending)
    }

    @Test
    fun `folders mix in when folders first is off`() {
        val items = listOf(file("b.txt"), folder("c"), file("a.txt"))
        val sorted = items.sortedWith(SortOptions(SortBy.NAME, foldersFirst = false).comparator()).map { it.name }
        assertEquals(listOf("a.txt", "b.txt", "c"), sorted)
    }

    @Test
    fun `sorts by size, date and type with name as tie breaker`() {
        val items = listOf(file("b.png", 5, 300), file("a.txt", 9, 100), file("c.png", 5, 200))
        fun names(by: SortBy, asc: Boolean) = items.sortedWith(SortOptions(by, asc).comparator()).map { it.name }
        assertEquals(listOf("b.png", "c.png", "a.txt"), names(SortBy.SIZE, true))
        assertEquals(listOf("a.txt", "b.png", "c.png"), names(SortBy.SIZE, false))
        assertEquals(listOf("b.png", "c.png", "a.txt"), names(SortBy.DATE, false))
        assertEquals(listOf("b.png", "c.png", "a.txt"), names(SortBy.TYPE, true))
    }

    @Test
    fun `file kinds are detected from the extension`() {
        assertEquals(FileKind.IMAGE, FileTypes.kindOf("Photo.JPG"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("notes.md"))
        assertEquals(FileKind.ARCHIVE, FileTypes.kindOf("backup.tar.gz"))
        assertEquals(FileKind.APK, FileTypes.kindOf("app.apk"))
        assertEquals(FileKind.OTHER, FileTypes.kindOf("README"))
        assertEquals(FileKind.OTHER, FileTypes.kindOf(".hidden"))
        assertEquals(FileKind.FOLDER, FileTypes.kindOf("photos.zip", isDirectory = true))
    }
}
