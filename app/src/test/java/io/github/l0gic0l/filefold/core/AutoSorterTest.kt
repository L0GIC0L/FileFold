package io.github.l0gic0l.filefold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val byType = AutoSortConfig()

    private fun touch(name: String) = File(tmp.root, name).apply { parentFile!!.mkdirs(); writeText(name) }
    private fun folder(name: String) = File(tmp.root, name).apply { mkdirs() }
    private fun names(dir: File = tmp.root) = dir.list()!!.toSet()

    @Test
    fun `sorting by type files everything into category folders`() {
        listOf("a.jpg", "b.PNG", "clip.mp4", "song.mp3", "cv.pdf", "notes.txt", "old.zip", "game.apk", "mystery.xyz", "Main.kt", ".hidden")
            .forEach(::touch)
        folder("Existing")

        val plan = AutoSorter.plan(tmp.root, byType)
        assertEquals(
            listOf("Apps" to 1, "Archives" to 1, "Audio" to 1, "Code" to 1, "Documents" to 2, "Images" to 2, "Other" to 1, "Videos" to 1),
            plan.summary.map { it.folder to it.files },
        )

        val result = AutoSorter.execute(plan)

        assertEquals(10, result.moved.size)
        assertTrue(File(tmp.root, "Images/b.PNG").exists())
        assertTrue(File(tmp.root, "Code/Main.kt").exists())
        assertTrue(File(tmp.root, "Other/mystery.xyz").exists())
        // Hidden files and, by default, folders are left alone.
        assertTrue(File(tmp.root, ".hidden").exists())
        assertTrue(File(tmp.root, "Existing").isDirectory)
    }

    @Test
    fun `sorting twice does nothing the second time`() {
        touch("a.jpg")
        AutoSorter.execute(AutoSorter.plan(tmp.root, byType))
        assertTrue(AutoSorter.plan(tmp.root, byType).moves.isEmpty())
    }

    @Test
    fun `a name clash in the target folder keeps both files`() {
        touch("Images/a.jpg").writeText("old")
        touch("a.jpg")

        AutoSorter.execute(AutoSorter.plan(tmp.root, byType))

        assertEquals("old", File(tmp.root, "Images/a.jpg").readText())
        assertEquals("a.jpg", File(tmp.root, "Images/a (1).jpg").readText())
    }

    @Test
    fun `groups can be switched off, renamed, nested and share a folder`() {
        listOf("a.jpg", "song.mp3", "cv.pdf", "Main.kt").forEach(::touch)
        val config = byType.copy(
            groups = byType.groups +
                (SortGroup.AUDIO to GroupRule(enabled = false, folder = "Audio")) +
                (SortGroup.IMAGES to GroupRule(enabled = true, folder = " Sorted / Pictures ")) +
                (SortGroup.CODE to GroupRule(enabled = true, folder = "Documents")),
        )

        val result = AutoSorter.execute(AutoSorter.plan(tmp.root, config))

        assertTrue(File(tmp.root, "song.mp3").exists())
        assertTrue(File(tmp.root, "Sorted/Pictures/a.jpg").exists())
        assertEquals(setOf("cv.pdf", "Main.kt"), names(File(tmp.root, "Documents")))
        // The nested folder and its parent were both created, outermost first.
        assertEquals(listOf("Documents", "Pictures", "Sorted").sorted(), result.createdDirs.map { it.name }.sorted())
    }

    @Test
    fun `folders can be moved into misc, but sorting folders never are`() {
        touch("a.jpg")
        touch("Projects/plan.txt")
        touch("Holiday/beach.jpg")
        folder("Images")
        folder("Misc")
        folder(".git")
        val config = byType.copy(moveFolders = true)

        val plan = AutoSorter.plan(tmp.root, config)
        assertEquals(listOf("Images" to 1, "Misc" to 0), plan.summary.map { it.folder to it.files })
        assertEquals(2, plan.summary.first { it.folder == "Misc" }.folders)

        AutoSorter.execute(plan)

        assertEquals(setOf("Images", "Misc", ".git"), names())
        assertEquals(setOf("Projects", "Holiday"), names(File(tmp.root, "Misc")))
        assertTrue(File(tmp.root, "Misc/Projects/plan.txt").exists())
    }

    @Test
    fun `folders go to a chosen, nested destination and undo brings them back`() {
        touch("Old stuff/x.txt")
        touch("notes.txt")
        val before = tmp.root.walkTopDown().map { it.relativeTo(tmp.root).path }.toSet()
        val config = byType.copy(moveFolders = true, foldersTarget = "Archive/Folders")

        val result = AutoSorter.execute(AutoSorter.plan(tmp.root, config))
        assertTrue(File(tmp.root, "Archive/Folders/Old stuff/x.txt").exists())

        assertEquals(2, AutoSorter.undo(result))
        assertEquals(before, tmp.root.walkTopDown().map { it.relativeTo(tmp.root).path }.toSet())
    }

    @Test
    fun `undo restores the folder exactly`() {
        listOf("a.jpg", "cv.pdf", "song.mp3").forEach(::touch)
        val before = names()

        val result = AutoSorter.execute(AutoSorter.plan(tmp.root, byType))
        assertFalse(File(tmp.root, "a.jpg").exists())

        assertEquals(3, AutoSorter.undo(result))
        assertEquals(before, names())
    }

    @Test
    fun `sorting by date groups by month and leaves month folders alone`() {
        val zone = ZoneId.of("UTC")
        fun dated(name: String, date: LocalDate) =
            touch(name).apply { setLastModified(date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()) }
        val march = dated("m.txt", LocalDate.of(2026, 3, 14))
        val november = dated("n.txt", LocalDate.of(2025, 11, 2))
        val config = byType.copy(mode = AutoSortMode.BY_DATE, moveFolders = true)
        folder("2024-01")

        assertEquals("2026-03", AutoSorter.folderFor(march, config, zone))
        assertEquals("2025-11", AutoSorter.folderFor(november, config, zone))
        assertTrue(AutoSorter.plan(tmp.root, config, zone).moves.none { it.source.name == "2024-01" })
    }

    @Test
    fun `destination names are cleaned and invalid ones rejected`() {
        assertEquals("Sorted/Images", AutoSorter.cleanPath("  Sorted // Images/ "))
        assertEquals("Misc", AutoSorter.cleanPath("Misc"))
        assertNull(AutoSorter.cleanPath("   "))
        assertNull(AutoSorter.cleanPath("bad:name"))
        assertNull(AutoSorter.cleanPath("../escape"))
    }
}
