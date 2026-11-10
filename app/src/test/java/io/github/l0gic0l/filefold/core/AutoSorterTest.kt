package io.github.l0gic0l.filefold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class AutoSorterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(name: String) = File(tmp.root, name).apply { writeText(name) }

    @Test
    fun `sorting by type files everything into category folders`() {
        listOf("a.jpg", "b.PNG", "clip.mp4", "song.mp3", "cv.pdf", "notes.txt", "old.zip", "game.apk", "mystery.xyz", ".hidden")
            .forEach(::touch)
        File(tmp.root, "Existing").mkdir()

        val plan = AutoSorter.plan(tmp.root, AutoSortMode.BY_TYPE)
        assertEquals(
            listOf("Apps" to 1, "Archives" to 1, "Audio" to 1, "Documents" to 2, "Images" to 2, "Other" to 1, "Videos" to 1),
            plan.summary,
        )

        val result = AutoSorter.execute(plan)

        assertEquals(9, result.moved.size)
        assertTrue(File(tmp.root, "Images/a.jpg").exists())
        assertTrue(File(tmp.root, "Images/b.PNG").exists())
        assertTrue(File(tmp.root, "Documents/notes.txt").exists())
        assertTrue(File(tmp.root, "Other/mystery.xyz").exists())
        // Hidden files and existing folders are left alone.
        assertTrue(File(tmp.root, ".hidden").exists())
        assertTrue(File(tmp.root, "Existing").isDirectory)
    }

    @Test
    fun `sorting twice does nothing the second time`() {
        touch("a.jpg")
        AutoSorter.execute(AutoSorter.plan(tmp.root, AutoSortMode.BY_TYPE))
        assertTrue(AutoSorter.plan(tmp.root, AutoSortMode.BY_TYPE).moves.isEmpty())
    }

    @Test
    fun `a name clash in the target folder keeps both files`() {
        File(tmp.root, "Images").mkdir()
        File(tmp.root, "Images/a.jpg").writeText("old")
        touch("a.jpg")

        AutoSorter.execute(AutoSorter.plan(tmp.root, AutoSortMode.BY_TYPE))

        assertEquals("old", File(tmp.root, "Images/a.jpg").readText())
        assertEquals("a.jpg", File(tmp.root, "Images/a (1).jpg").readText())
    }

    @Test
    fun `undo restores the folder exactly`() {
        listOf("a.jpg", "cv.pdf", "song.mp3").forEach(::touch)
        val before = tmp.root.list()!!.toSet()

        val result = AutoSorter.execute(AutoSorter.plan(tmp.root, AutoSortMode.BY_TYPE))
        assertFalse(File(tmp.root, "a.jpg").exists())
        val restored = AutoSorter.undo(result)

        assertEquals(3, restored)
        assertEquals(before, tmp.root.list()!!.toSet())
    }

    @Test
    fun `sorting by date groups by month`() {
        val zone = ZoneId.of("UTC")
        fun dated(name: String, date: LocalDate) =
            touch(name).apply { setLastModified(date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()) }
        val march = dated("m.txt", LocalDate.of(2026, 3, 14))
        val november = dated("n.txt", LocalDate.of(2025, 11, 2))

        assertEquals("2026-03", AutoSorter.folderFor(march, AutoSortMode.BY_DATE, zone))
        assertEquals("2025-11", AutoSorter.folderFor(november, AutoSortMode.BY_DATE, zone))
    }
}
