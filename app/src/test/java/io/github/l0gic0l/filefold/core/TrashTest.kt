package io.github.l0gic0l.filefold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class TrashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File get() = tmp.root
    private val roots: List<File> get() = listOf(root)
    private val rootOf: (File) -> File? = { root }

    private fun write(path: String, text: String = path): File =
        File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test
    fun `trashed items leave their folder and can be listed`() {
        val a = write("docs/a.txt")
        val dir = write("docs/album/1.jpg").parentFile!!
        val entries = Trash.moveToTrash(listOf(a, dir), rootOf, now = 5000)

        assertFalse(a.exists())
        assertFalse(dir.exists())
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.file.exists() && Trash.isInTrash(it.file.path) })
        assertTrue(File(Trash.dirFor(root), ".nomedia").exists())

        val listed = Trash.list(roots)
        assertEquals(setOf("a.txt", "album"), listed.map { it.originalName }.toSet())
        assertEquals(a.absolutePath, listed.first { it.originalName == "a.txt" }.originalPath)
        assertEquals(5000L, listed.first().deletedAt)
        assertTrue(File(listed.first { it.originalName == "album" }.file, "1.jpg").exists())
    }

    @Test
    fun `restore puts items back with their contents`() {
        val a = write("docs/a.txt", "hello")
        val entries = Trash.moveToTrash(listOf(a), rootOf)
        val restored = Trash.restore(Trash.list(roots))
        assertEquals(listOf(a), restored)
        assertEquals("hello", a.readText())
        assertTrue(Trash.list(roots).isEmpty())
        // Nothing is left behind but the marker file.
        assertEquals(listOf(".nomedia"), Trash.dirFor(root).list()!!.toList())
        assertFalse(entries.single().file.exists())
    }

    @Test
    fun `restore recreates a missing folder and never overwrites`() {
        val a = write("gone/deep/a.txt", "old")
        Trash.moveToTrash(listOf(a), rootOf)
        File(root, "gone").deleteRecursively()
        assertEquals("old", Trash.restore(Trash.list(roots)).single().readText())
        assertTrue(a.exists())

        Trash.moveToTrash(listOf(a), rootOf)
        write("gone/deep/a.txt", "new")
        val back = Trash.restore(Trash.list(roots)).single()
        assertEquals("a (1).txt", back.name)
        assertEquals("old", back.readText())
        assertEquals("new", a.readText())
    }

    @Test
    fun `two items with the same name are kept apart`() {
        val first = write("x/a.txt", "one")
        Trash.moveToTrash(listOf(first), rootOf, now = 1)
        val second = write("x/a.txt", "two")
        Trash.moveToTrash(listOf(second), rootOf, now = 1)
        assertEquals(setOf("one", "two"), Trash.list(roots).map { it.file.readText() }.toSet())
    }

    @Test
    fun `purge deletes for good, and old items are cleaned up by age`() {
        val old = write("old.txt")
        val recent = write("recent.txt")
        Trash.moveToTrash(listOf(old), rootOf, now = 1_000)
        Trash.moveToTrash(listOf(recent), rootOf, now = 9_000)

        assertEquals(1, Trash.purgeOlderThan(roots, cutoff = 5_000))
        assertEquals(listOf("recent.txt"), Trash.list(roots).map { it.originalName })

        Trash.purge(Trash.list(roots))
        assertTrue(Trash.list(roots).isEmpty())
        assertEquals(listOf(".nomedia"), Trash.dirFor(root).list()!!.toList())
    }

    @Test
    fun `a file outside every volume is refused, not deleted`() {
        val a = write("a.txt")
        assertThrows(IOException::class.java) { Trash.moveToTrash(listOf(a), { null }) }
        assertTrue(a.exists())
    }

    @Test
    fun `paths inside the trash are recognised`() {
        assertTrue(Trash.isInTrash("/storage/emulated/0/${Trash.DIR_NAME}/1-0/a.txt"))
        assertTrue(Trash.isInTrash("/storage/emulated/0/${Trash.DIR_NAME}"))
        assertFalse(Trash.isInTrash("/storage/emulated/0/Download/a.txt"))
    }
}
