package io.github.l0gic0l.filefold.archive

import io.github.l0gic0l.filefold.core.Progress
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A small tree with nesting, an empty folder, a non-ASCII name and a compressible larger file. */
    private fun sampleTree(): File {
        val root = tmp.newFolder("project")
        File(root, "readme.txt").writeText("Hello FileFold")
        File(root, "docs/guide").mkdirs()
        File(root, "docs/guide/intro.md").writeText("# Intro\n")
        File(root, "docs/naïve café.txt").writeText("unicode")
        File(root, "empty").mkdirs()
        File(root, "data.bin").writeBytes(ByteArray(300_000) { (it % 7).toByte() })
        return root
    }

    private fun snapshot(root: File): Map<String, String> = root.walkTopDown()
        .filter { it != root }
        .associate { it.relativeTo(root).invariantSeparatorsPath to if (it.isDirectory) "<dir>" else it.readBytes().contentHashCode().toString() }

    @Test
    fun `every creatable format round trips`() {
        val tree = sampleTree()
        val expected = snapshot(tree)
        for (format in ArchiveFormat.creatable) {
            val archive = File(tmp.root, "out.${format.extension}")
            val progress = Progress()
            Archives.create(archive, format, listOf(tree), progress)
            assertTrue("$format produced an empty archive", archive.length() > 0)
            assertEquals("$format progress", progress.total, progress.done)

            val listed = Archives.list(archive, format)
            assertTrue("$format lists the nested file", listed.any { it.path == "project/docs/guide/intro.md" && !it.isDirectory })
            assertTrue("$format lists the empty folder", listed.any { it.path == "project/empty" && it.isDirectory })

            val dest = tmp.newFolder("extract-${format.name}")
            val written = Archives.extract(archive, format, dest)
            assertEquals("$format file count", 4, written)
            assertEquals("$format contents", expected, snapshot(File(dest, "project")))
        }
    }

    @Test
    fun `format is detected from the name and from magic bytes`() {
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.fromName("Backup.TAR.GZ"))
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.fromName("backup.tgz"))
        assertEquals(ArchiveFormat.GZ, ArchiveFormat.fromName("log.txt.gz"))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.fromName("a.7z"))
        assertNull(ArchiveFormat.fromName("photo.jpg"))
        assertNull(ArchiveFormat.fromName(".zip"))
        assertEquals("photos", ArchiveFormat.stripExtension("photos.tar.xz"))
        assertEquals("log.txt", ArchiveFormat.stripExtension("log.txt.gz"))

        // An APK is a ZIP under another name; sniffing finds it.
        val tree = sampleTree()
        val apk = File(tmp.root, "app.apk")
        Archives.create(apk, ArchiveFormat.ZIP, listOf(tree))
        assertNull(ArchiveFormat.fromName(apk.name))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.of(apk))
        val tar = File(tmp.root, "plain.bin")
        Archives.create(tar, ArchiveFormat.TAR, listOf(tree))
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.sniff(tar))
        assertNull(ArchiveFormat.sniff(File(tree, "readme.txt")))
    }

    @Test
    fun `a selection extracts relative to the folder being browsed`() {
        val archive = File(tmp.root, "out.zip")
        Archives.create(archive, ArchiveFormat.ZIP, listOf(sampleTree()))
        val dest = tmp.newFolder("dest")

        val written = Archives.extract(
            archive, ArchiveFormat.ZIP, dest,
            only = listOf("project/docs/guide", "project/docs/naïve café.txt"),
            baseDir = "project/docs",
        )

        assertEquals(2, written)
        assertEquals(setOf("guide", "guide/intro.md", "naïve café.txt"), snapshot(dest).keys)
    }

    @Test
    fun `a single entry can be pulled out for preview`() {
        val tree = sampleTree()
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR_GZ)) {
            val archive = File(tmp.root, "preview.${format.extension}")
            Archives.create(archive, format, listOf(tree))
            val target = File(tmp.root, "preview-${format.name}/intro.md")
            Archives.extractEntry(archive, format, "project/docs/guide/intro.md", target)
            assertEquals("# Intro\n", target.readText())
            assertThrows(IOException::class.java) { Archives.extractEntry(archive, format, "project/nope.txt", File(tmp.root, "x")) }
        }
    }

    @Test
    fun `entries that try to escape the destination are ignored`() {
        val evil = File(tmp.root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { zip ->
            for (name in listOf("../escaped.txt", "/abs/../../also-escaped.txt", "safe/ok.txt", "C:\\win\\style.txt")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        val dest = tmp.newFolder("jail", "inner")

        assertEquals(setOf("safe/ok.txt", "C:/win/style.txt"), Archives.list(evil, ArchiveFormat.ZIP).map { it.path }.toSet())
        Archives.extract(evil, ArchiveFormat.ZIP, dest)

        assertEquals(setOf("safe", "safe/ok.txt", "C_", "C_/win", "C_/win/style.txt"), snapshot(dest).keys)
        assertFalse(File(dest.parentFile, "escaped.txt").exists())
        assertFalse(File(tmp.root, "also-escaped.txt").exists())
        assertThrows(IOException::class.java) { Archives.safeResolve(dest, "../outside.txt") }
    }

    @Test
    fun `extraction never overwrites an existing file`() {
        val archive = File(tmp.root, "out.zip")
        Archives.create(archive, ArchiveFormat.ZIP, listOf(sampleTree()))
        val dest = tmp.newFolder("dest")
        File(dest, "project").mkdirs()
        File(dest, "project/readme.txt").writeText("mine")

        Archives.extract(archive, ArchiveFormat.ZIP, dest)

        assertEquals("mine", File(dest, "project/readme.txt").readText())
        assertEquals("Hello FileFold", File(dest, "project/readme (1).txt").readText())
    }

    @Test
    fun `a gzip file is treated as an archive of one file`() {
        val gz = File(tmp.root, "notes.txt.gz")
        GZIPOutputStream(gz.outputStream()).use { it.write("compressed notes".toByteArray()) }

        val entry = Archives.list(gz, ArchiveFormat.GZ).single()
        assertEquals("notes.txt", entry.path)
        assertEquals(16L, entry.size)

        val dest = tmp.newFolder("dest")
        assertEquals(1, Archives.extract(gz, ArchiveFormat.GZ, dest))
        assertEquals("compressed notes", File(dest, "notes.txt").readText())
    }

    @Test
    fun `password protected 7z needs the right password`() {
        val archive = File(tmp.root, "secret.7z")
        SevenZOutputFile(archive, "hunter2".toCharArray()).use { out ->
            val source = File(tmp.root, "secret.txt").apply { writeText("classified") }
            out.putArchiveEntry(out.createArchiveEntry(source, "secret.txt"))
            out.write(source.readBytes())
            out.closeArchiveEntry()
        }
        val dest = tmp.newFolder("dest")

        val missing = assertThrows(ArchivePasswordException::class.java) { Archives.extract(archive, ArchiveFormat.SEVEN_Z, dest) }
        assertFalse(missing.wrongPassword)
        val wrong = assertThrows(ArchivePasswordException::class.java) {
            Archives.extract(archive, ArchiveFormat.SEVEN_Z, dest, password = "nope")
        }
        assertTrue(wrong.wrongPassword)
        assertEquals(0, dest.list()!!.size)

        assertEquals(1, Archives.extract(archive, ArchiveFormat.SEVEN_Z, dest, password = "hunter2"))
        assertEquals("classified", File(dest, "secret.txt").readText())
    }

    @Test
    fun `cancelling mid way leaves no archive behind`() {
        val tree = sampleTree()
        val archive = File(tmp.root, "cancelled.zip")
        lateinit var progress: Progress
        progress = Progress(isActive = { progress.done == 0L })

        assertThrows(CancellationException::class.java) { Archives.create(archive, ArchiveFormat.ZIP, listOf(tree), progress) }
        assertFalse(archive.exists())
    }

    @Test
    fun `an archive inside the folder being packed does not include itself`() {
        val tree = sampleTree()
        val archive = File(tree, "self.zip")
        Archives.create(archive, ArchiveFormat.ZIP, listOf(tree))
        assertFalse(Archives.list(archive, ArchiveFormat.ZIP).any { it.path.endsWith("self.zip") })
    }

    @Test
    fun `the index builds a browsable tree with implied folders`() {
        val entries = listOf(
            ArchiveEntryInfo("a/b/c.txt", false, 10, 4, 1000),
            ArchiveEntryInfo("a/b/d.txt", false, 20, 8, 2000),
            ArchiveEntryInfo("a/e.txt", false, 5, 5, 3000),
            ArchiveEntryInfo("top.txt", false, 1, 1, 4000),
            ArchiveEntryInfo("empty/", true, 0, 0, 5000),
            ArchiveEntryInfo("a\\b\\c.txt", false, 11, 4, 1000), // duplicate of the first with Windows slashes
            ArchiveEntryInfo("../evil.txt", false, 99, 99, 0),
        )
        val index = ArchiveIndex(ArchiveFormat.ZIP, entries)

        assertEquals(4, index.fileCount)
        assertEquals(37L, index.totalSize)
        val root = index.list("").associateBy { it.name }
        assertEquals(setOf("a", "empty", "top.txt"), root.keys)
        assertTrue(root.getValue("a").isDirectory)
        assertEquals(36L, root.getValue("a").size)
        assertEquals(2, root.getValue("a").itemCount)
        assertEquals(0, root.getValue("empty").itemCount)
        assertEquals(5000L, root.getValue("empty").modified)
        assertEquals(listOf("c.txt", "d.txt"), index.list("a/b").map { it.name }.sorted())
        assertTrue(index.hasFolder("a/b"))
        assertFalse(index.hasFolder("a/b/c.txt"))
        assertTrue(index.list("missing").isEmpty())
    }
}
