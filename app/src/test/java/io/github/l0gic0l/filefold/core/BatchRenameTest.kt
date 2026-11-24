package io.github.l0gic0l.filefold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.ZoneOffset

class BatchRenameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(path: String, text: String = path): File =
        File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun names(steps: List<BatchRename.Step>) = steps.map { it.newName }

    @Test
    fun `numbering keeps extensions and pads to the same width`() {
        val files = (1..12).map { write("IMG_$it.jpg") }
        val steps = BatchRename.plan(files, BatchRename.Rule("Holiday {n}"))
        assertEquals("Holiday 01.jpg", steps.first().newName)
        assertEquals("Holiday 12.jpg", steps.last().newName)
        assertEquals(listOf("Trip 7.jpg", "Trip 8.jpg"), names(BatchRename.plan(files.take(2), BatchRename.Rule("Trip {n}", start = 7))))
    }

    @Test
    fun `name, date and replace`() {
        val a = write("draft_report.txt").apply { setLastModified(1_793_577_600_000L) } // 2026-11-02 UTC
        val archive = write("draft_backup.tar.gz")
        val folder = File(tmp.root, "draft.v2").apply { mkdir() }
        val rule = BatchRename.Rule("{date} {name}", find = "DRAFT_", replace = "")
        assertEquals("2026-11-02 report.txt", BatchRename.plan(listOf(a), rule, ZoneOffset.UTC).single().newName)
        // Compound extensions stay whole, and a folder's dots are part of its name.
        assertEquals(listOf("backup-old.tar.gz", "draft.v2-old"),
            names(BatchRename.plan(listOf(archive, folder), BatchRename.Rule("{name}-old", find = "draft_", replace = ""))))
    }

    @Test
    fun `problems are reported before anything is touched`() {
        val a = write("a.txt")
        val b = write("b.txt")
        write("taken.txt")
        assertNotNull(BatchRename.problem(BatchRename.plan(listOf(a, b), BatchRename.Rule("{name}"))))
        assertTrue(BatchRename.problem(BatchRename.plan(listOf(a, b), BatchRename.Rule("same")))!!.contains("both"))
        assertTrue(BatchRename.problem(BatchRename.plan(listOf(a), BatchRename.Rule("taken")))!!.contains("already exists"))
        assertNotNull(BatchRename.problem(BatchRename.plan(listOf(a), BatchRename.Rule("a/b"))))
        assertNotNull(BatchRename.problem(BatchRename.plan(listOf(a), BatchRename.Rule(""))))
        assertNull(BatchRename.problem(BatchRename.plan(listOf(a, b), BatchRename.Rule("{name} copy"))))
        assertThrows(IOException::class.java) { BatchRename.execute(BatchRename.plan(listOf(a, b), BatchRename.Rule("same"))) }
        assertTrue(a.exists() && b.exists())
    }

    @Test
    fun `the same new name in different folders is fine`() {
        val a = write("one/a.txt")
        val b = write("two/b.txt")
        assertNull(BatchRename.problem(BatchRename.plan(listOf(a, b), BatchRename.Rule("notes"))))
    }

    @Test
    fun `renames, including onto each other's names, and undoes`() {
        val one = write("1.txt", "first")
        val two = write("2.txt", "second")
        // Shifting the numbers up means 1 → 2 while 2 → 3: the target of one is the source of the next.
        val steps = listOf(BatchRename.Step(one, "2.txt"), BatchRename.Step(two, "3.txt"))
        assertNull(BatchRename.problem(steps))
        val renamed = BatchRename.execute(steps)
        assertEquals("first", File(tmp.root, "2.txt").readText())
        assertEquals("second", File(tmp.root, "3.txt").readText())
        assertEquals(setOf("2.txt", "3.txt"), tmp.root.list()!!.toSet())

        assertEquals(2, BatchRename.undo(renamed))
        assertEquals("first", one.readText())
        assertEquals("second", two.readText())
        assertEquals(setOf("1.txt", "2.txt"), tmp.root.list()!!.toSet())
    }

    @Test
    fun `a straight swap works`() {
        val a = write("a.txt", "A")
        val b = write("b.txt", "B")
        BatchRename.execute(listOf(BatchRename.Step(a, "b.txt"), BatchRename.Step(b, "a.txt")))
        assertEquals("B", a.readText())
        assertEquals("A", b.readText())
    }
}
