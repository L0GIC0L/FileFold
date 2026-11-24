package io.github.l0gic0l.filefold.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    /** A text buffer that records into the history the way the editor's TextWatcher does. */
    private class Doc(initial: String) {
        val history = EditHistory()
        val text = StringBuilder(initial)
        var clock = 0L
        private val target = EditHistory.Target { start, end, replacement -> text.replace(start, end, replacement.toString()) }

        fun edit(start: Int, end: Int, replacement: String, after: Long = 100) {
            clock += after
            val removed = text.substring(start, end)
            text.replace(start, end, replacement)
            history.record(start, removed, replacement, clock)
        }

        fun type(at: Int, word: String) = word.forEachIndexed { i, c -> edit(at + i, at + i, c.toString()) }
        fun undo() = history.undo(target)
        fun redo() = history.redo(target)
    }

    @Test
    fun `a burst of typing undoes in one step`() {
        val doc = Doc("Hello")
        doc.type(5, " world")
        assertEquals("Hello world", doc.text.toString())
        assertEquals(5, doc.undo())
        assertEquals("Hello", doc.text.toString())
        assertFalse(doc.history.canUndo)
        doc.redo()
        assertEquals("Hello world", doc.text.toString())
    }

    @Test
    fun `a pause or a new line starts a new step`() {
        val doc = Doc("")
        doc.type(0, "one")
        doc.edit(3, 3, "\n")
        doc.edit(4, 4, "two", after = 5000)
        doc.undo()
        assertEquals("one\n", doc.text.toString())
        doc.undo()
        assertEquals("one", doc.text.toString())
        doc.undo()
        assertEquals("", doc.text.toString())
    }

    @Test
    fun `keyboard rewriting the word being typed merges`() {
        // Many keyboards replace the whole composing word on each key press.
        val doc = Doc("I ")
        doc.edit(2, 2, "h")
        doc.edit(2, 3, "he")
        doc.edit(2, 4, "hel")
        doc.edit(2, 5, "help")
        assertEquals("I help", doc.text.toString())
        doc.undo()
        assertEquals("I ", doc.text.toString())
    }

    @Test
    fun `backspaces group together and undo restores the text`() {
        val doc = Doc("abcdef")
        doc.edit(5, 6, "")
        doc.edit(4, 5, "")
        doc.edit(3, 4, "")
        assertEquals("abc", doc.text.toString())
        doc.undo()
        assertEquals("abcdef", doc.text.toString())
    }

    @Test
    fun `saved state is tracked through undo and redo`() {
        val doc = Doc("x")
        doc.type(1, "yz")
        val saved = doc.history.markSaved()
        // Typing straight after saving must not merge into the saved step.
        doc.edit(3, 3, "!")
        assertTrue(doc.history.stateId != saved)
        doc.undo()
        assertEquals(saved, doc.history.stateId)
        assertEquals("xyz", doc.text.toString())
        doc.undo()
        assertEquals(0L, doc.history.stateId)
        doc.redo()
        assertEquals(saved, doc.history.stateId)
    }

    @Test
    fun `a new edit clears redo`() {
        val doc = Doc("a")
        doc.edit(1, 1, "b", after = 5000)
        doc.undo()
        assertTrue(doc.history.canRedo)
        doc.edit(1, 1, "c", after = 5000)
        assertFalse(doc.history.canRedo)
        assertEquals(-1, doc.redo())
    }

    @Test
    fun `line counter follows the offset both ways and survives edits below it`() {
        val text = StringBuilder("a\nb\nc\nd\ne")
        val counter = LineCounter()
        assertEquals(2, counter.lineAt(text, 4))
        assertEquals(4, counter.lineAt(text, 8))
        assertEquals(1, counter.lineAt(text, 2))
        // An edit after the remembered offset keeps the cache.
        counter.invalidateFrom(6)
        text.insert(6, "x\n")
        assertEquals(1, counter.lineAt(text, 2))
        // An edit before it resets the cache and the answer is still right.
        counter.invalidateFrom(0)
        text.insert(0, "new\n")
        assertEquals(2, counter.lineAt(text, 6))
        assertEquals(0, counter.lineAt(text, 0))
        assertEquals(counter.lineAt(text, text.length), text.count { it == '\n' })
    }
}
