package io.github.l0gic0l.filefold.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** RAR is read-only, so these use real RAR4 and RAR5 samples (see resources/rar/NOTICE.md). */
class RarTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(name: String): File =
        File(javaClass.classLoader!!.getResource("rar/$name")!!.toURI())

    private fun extracted(dir: File): Map<String, Long> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.length() }

    @Test
    fun `rar5 folders, including empty ones and spaces in names, are listed and extracted`() {
        val rar = sample("rar5-subdirs.rar")
        assertEquals(ArchiveFormat.RAR, ArchiveFormat.sniff(rar))

        val index = ArchiveIndex(ArchiveFormat.RAR, Archives.list(rar, ArchiveFormat.RAR))
        assertEquals(4, index.fileCount)
        assertTrue(index.hasFolder("sub/empty"))
        assertEquals(listOf("long fn.txt"), index.list("sub/with space").map { it.name })

        val dest = tmp.newFolder()
        assertEquals(4, Archives.extract(rar, ArchiveFormat.RAR, dest))
        assertEquals(8L, extracted(dest)["sub/with space/long fn.txt"])
        assertTrue(File(dest, "sub/empty").isDirectory)
    }

    @Test
    fun `solid rar4 archive extracts every file, even when only the second is asked for`() {
        val rar = sample("rar3-solid.rar")
        val dest = tmp.newFolder()
        // In a solid archive the second file can only be decoded after the first.
        assertEquals(1, Archives.extract(rar, ArchiveFormat.RAR, dest, only = listOf("stest2.txt")))
        assertEquals(mapOf("stest2.txt" to 2048L), extracted(dest))

        val preview = File(tmp.root, "preview/stest2.txt")
        Archives.extractEntry(rar, ArchiveFormat.RAR, "stest2.txt", preview)
        assertEquals(2048L, preview.length())
    }

    @Test
    fun `unicode file names survive`() {
        val dest = tmp.newFolder()
        Archives.extract(sample("unicode.rar"), ArchiveFormat.RAR, dest)
        assertEquals(setOf("уииоотивл.txt", "𝐀𝐁𝐁𝐂.txt"), extracted(dest).keys)
    }

    @Test
    fun `encrypted data asks for the password and then extracts`() {
        val rar = sample("rar5-psw.rar")
        // Names are readable without the password; the contents are not.
        assertTrue(Archives.list(rar, ArchiveFormat.RAR).all { it.encrypted })
        val dest = tmp.newFolder()
        val missing = assertThrows(ArchivePasswordException::class.java) { Archives.extract(rar, ArchiveFormat.RAR, dest) }
        assertFalse(missing.wrongPassword)
        assertEquals(2, Archives.extract(rar, ArchiveFormat.RAR, dest, password = "password"))
        assertEquals(2048L, extracted(dest)["stest1.txt"])
    }

    @Test
    fun `encrypted headers need the password even to list`() {
        val rar = sample("rar5-hpsw.rar")
        val missing = assertThrows(ArchivePasswordException::class.java) { Archives.list(rar, ArchiveFormat.RAR) }
        assertFalse(missing.wrongPassword)
        assertEquals(2, Archives.list(rar, ArchiveFormat.RAR, password = "password").size)
    }

    @Test
    fun `a symlink that tries to escape the destination is stopped`() {
        val jail = tmp.newFolder("jail", "inner")
        assertThrows(IOException::class.java) {
            Archives.extract(sample("rar5-evil-symlink-traversal.rar"), ArchiveFormat.RAR, jail)
        }
        assertFalse(tmp.root.walkTopDown().any { it.name == "pwned.txt" && !it.startsWith(jail) })
    }
}
