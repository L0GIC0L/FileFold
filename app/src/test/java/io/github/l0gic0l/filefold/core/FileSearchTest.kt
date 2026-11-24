package io.github.l0gic0l.filefold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class FileSearchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun write(path: String, bytes: Int = 4, ageDays: Int = 100): File = File(tmp.root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(bytes))
        setLastModified(now - ageDays * day)
    }

    private fun find(query: SearchQuery, showHidden: Boolean = false, root: File = tmp.root): List<String> {
        val found = ArrayList<FileItem>()
        FileSearch.search(root, query, showHidden, now) { found += it }
        return found.map { it.path.removePrefix(tmp.root.path + "/") }.sorted()
    }

    @Test
    fun `finds names at any depth, ignoring case`() {
        write("Report.pdf")
        write("work/2026/annual report final.docx")
        write("work/notes.txt")
        assertEquals(listOf("Report.pdf", "work/2026/annual report final.docx"), find(SearchQuery("report")))
    }

    @Test
    fun `every word must appear, in any order`() {
        write("annual report final.docx")
        write("report draft.docx")
        assertEquals(listOf("annual report final.docx"), find(SearchQuery("final  REPORT")))
    }

    @Test
    fun `folders match by name too`() {
        write("Holiday photos/a.jpg")
        write("other/holiday.txt")
        assertEquals(listOf("Holiday photos", "other/holiday.txt"), find(SearchQuery("holiday")))
    }

    @Test
    fun `wildcards match the whole name`() {
        write("IMG_001.jpg")
        write("IMG_002.jpeg")
        write("my IMG_003.jpg")
        write("IMG_004.png")
        // A question mark stands for exactly one character.
        assertEquals(listOf("IMG_002.jpeg"), find(SearchQuery("img_*.jp?g")))
        assertEquals(listOf("IMG_001.jpg", "IMG_002.jpeg"), find(SearchQuery("IMG_*.jp*")))
        // Characters that mean something in a regular expression are taken literally.
        write("a+b (1).txt")
        assertEquals(listOf("a+b (1).txt"), find(SearchQuery("a+b (?).*")))
    }

    @Test
    fun `type filter lists a kind of file everywhere, without any text`() {
        write("a.jpg")
        write("deep/er/b.PNG")
        write("deep/c.mp4")
        write("deep/images/readme.txt")
        assertEquals(listOf("a.jpg", "deep/er/b.PNG"), find(SearchQuery(group = SortGroup.IMAGES)))
        assertEquals(listOf("deep/c.mp4"), find(SearchQuery(group = SortGroup.VIDEOS)))
    }

    @Test
    fun `recent and large filters`() {
        write("old.txt", ageDays = 30)
        write("new.txt", ageDays = 2)
        write("docs/fresh.pdf", ageDays = 0)
        // Only files: the folder "docs" was changed just now too, but is not listed.
        assertEquals(listOf("docs/fresh.pdf", "new.txt"), find(SearchQuery(recent = true)))

        val big = write("big.bin")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(FileSearch.LARGE_BYTES) }
        assertEquals(listOf("big.bin"), find(SearchQuery(large = true)))
        assertEquals(emptyList<String>(), find(SearchQuery("old", large = true)))
    }

    @Test
    fun `hidden items and the trash are left out`() {
        write(".secret/match.txt")
        write(".match.txt")
        write("match.txt")
        write("${Trash.DIR_NAME}/1-0/match.txt")
        assertEquals(listOf("match.txt"), find(SearchQuery("match")))
        assertEquals(listOf(".match.txt", ".secret/match.txt", "match.txt"), find(SearchQuery("match"), showHidden = true))
    }

    @Test
    fun `the folder being searched is not a result`() {
        write("notes/notes.txt")
        assertEquals(listOf("notes/notes.txt"), find(SearchQuery("notes"), root = File(tmp.root, "notes")))
    }

    @Test
    fun `stops at the limit and says so`() {
        repeat(FileSearch.MAX_RESULTS + 50) { write("many/f$it.txt") }
        var count = 0
        val truncated = FileSearch.search(tmp.root, SearchQuery("f"), false, now) { count += it.size }
        assertTrue(truncated)
        assertEquals(FileSearch.MAX_RESULTS, count)
        assertFalse(FileSearch.search(tmp.root, SearchQuery("f1.txt"), false, now) { })
    }

    @Test
    fun `can be cancelled`() {
        write("a/b/c.txt")
        assertThrows(CancellationException::class.java) {
            FileSearch.search(tmp.root, SearchQuery("c"), false, now, Progress(isActive = { false })) { }
        }
    }

    @Test
    fun `an empty query is not active`() {
        assertFalse(SearchQuery("   ").isActive)
        assertTrue(SearchQuery("x").isActive)
        assertTrue(SearchQuery(group = SortGroup.AUDIO).isActive)
        assertTrue(SearchQuery(recent = true).isActive)
    }

    @Test
    fun `folder size adds up everything below`() {
        write("a/one.bin", bytes = 100)
        write("a/b/two.bin", bytes = 250)
        write("a/b/c/.hidden", bytes = 50)
        assertEquals(400L, FileSearch.folderSize(File(tmp.root, "a")))
        assertEquals(0L, FileSearch.folderSize(File(tmp.root, "missing")))
    }
}
