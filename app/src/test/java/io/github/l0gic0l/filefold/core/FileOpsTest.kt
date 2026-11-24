package io.github.l0gic0l.filefold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

class FileOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(path: String, text: String = path): File =
        File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test
    fun `unique target adds a counter before the extension`() {
        write("report.pdf")
        write("report (1).pdf")
        write("backup.tar.gz")
        write("noext")
        assertEquals("report (2).pdf", FileOps.uniqueTarget(tmp.root, "report.pdf").name)
        assertEquals("backup (1).tar.gz", FileOps.uniqueTarget(tmp.root, "backup.tar.gz").name)
        assertEquals("noext (1)", FileOps.uniqueTarget(tmp.root, "noext").name)
        assertEquals("fresh.txt", FileOps.uniqueTarget(tmp.root, "fresh.txt").name)
    }

    @Test
    fun `names are validated`() {
        assertNull(FileOps.validateName("holiday photo.jpg"))
        assertNotNull(FileOps.validateName(""))
        assertNotNull(FileOps.validateName("  "))
        assertNotNull(FileOps.validateName("a/b"))
        assertNotNull(FileOps.validateName("what?.txt"))
        assertNotNull(FileOps.validateName(".."))
    }

    @Test
    fun `copy duplicates a tree and keeps the original`() {
        write("src/a.txt", "A")
        write("src/sub/b.txt", "B")
        val dest = tmp.newFolder("dest")
        var reported = 0L
        val progress = Progress(onUpdate = { done, _, _ -> reported = done })

        val created = FileOps.copy(listOf(File(tmp.root, "src")), dest, progress)

        assertEquals(listOf(File(dest, "src")), created)
        assertEquals("A", File(dest, "src/a.txt").readText())
        assertEquals("B", File(dest, "src/sub/b.txt").readText())
        assertTrue(File(tmp.root, "src/a.txt").exists())
        assertEquals(2L, progress.total)
        assertEquals(2L, progress.done)
        assertTrue(reported <= 2L)
    }

    @Test
    fun `copy into the same folder renames instead of overwriting`() {
        val original = write("notes.txt", "one")
        val created = FileOps.copy(listOf(original), tmp.root, Progress.None)
        assertEquals("notes (1).txt", created.single().name)
        assertEquals("one", original.readText())
    }

    @Test
    fun `a folder cannot be copied or moved into itself`() {
        write("src/sub/b.txt")
        val src = File(tmp.root, "src")
        assertThrows(IOException::class.java) { FileOps.copy(listOf(src), File(src, "sub"), Progress.None) }
        assertThrows(IOException::class.java) { FileOps.move(listOf(src), src, Progress.None) }
        assertTrue(File(src, "sub/b.txt").exists())
    }

    @Test
    fun `move relocates files and delete removes trees`() {
        val a = write("a.txt", "A")
        write("dir/inner.txt")
        val dest = tmp.newFolder("dest")

        val moved = FileOps.move(listOf(a, File(tmp.root, "dir")), dest, Progress.None)

        assertEquals(setOf("a.txt", "dir"), moved.map { it.name }.toSet())
        assertFalse(a.exists())
        assertEquals("A", File(dest, "a.txt").readText())
        assertTrue(File(dest, "dir/inner.txt").exists())

        FileOps.delete(listOf(File(dest, "dir"), File(dest, "a.txt")), Progress.None)
        assertEquals(0, dest.list()!!.size)
    }

    @Test
    fun `cancelling a copy removes the partial file`() {
        val big = File(tmp.root, "big.bin").apply { writeBytes(ByteArray(512 * 1024)) }
        val dest = tmp.newFolder("dest")
        // Cancel as soon as the first chunk has been written.
        lateinit var progress: Progress
        progress = Progress(isActive = { progress.done == 0L })

        assertThrows(CancellationException::class.java) { FileOps.copy(listOf(big), dest, progress) }
        assertEquals(0, dest.list()!!.size)
    }

    @Test
    fun `listing hides dot files unless asked and reports folder sizes`() {
        write("visible.txt", "12345")
        write(".secret")
        write("folder/one.txt")
        write("folder/two.txt")

        val shown = FileOps.list(tmp.root, showHidden = false).associateBy { it.name }
        assertEquals(setOf("visible.txt", "folder"), shown.keys)
        assertEquals(5L, shown.getValue("visible.txt").size)
        assertEquals(2, shown.getValue("folder").itemCount)
        assertEquals(3, FileOps.list(tmp.root, showHidden = true).size)
        assertThrows(IOException::class.java) { FileOps.list(File(tmp.root, "missing"), showHidden = true) }
    }

    @Test
    fun `rename and create refuse to clobber existing files`() {
        val a = write("a.txt")
        write("b.txt")
        assertThrows(IOException::class.java) { FileOps.rename(a, "b.txt") }
        assertThrows(IOException::class.java) { FileOps.createFile(tmp.root, "a.txt") }
        assertThrows(IOException::class.java) { FileOps.createFolder(tmp.root, "bad:name") }
        assertEquals("c.txt", FileOps.rename(a, "c.txt").name)
        assertTrue(FileOps.createFolder(tmp.root, "new").isDirectory)
    }
}
