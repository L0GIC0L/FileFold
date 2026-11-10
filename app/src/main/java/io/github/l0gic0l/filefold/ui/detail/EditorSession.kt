package io.github.l0gic0l.filefold.ui.detail

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import io.github.l0gic0l.filefold.core.TextDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the text editor knows about one open file. It lives in the
 * ViewModel, so edits survive folding, unfolding and rotation.
 */
class EditorSession(val file: File, val readOnly: Boolean, private val format: TextDocument.Decoded) {

    val state = TextFieldState(format.text, initialSelection = TextRange(0))

    private var savedText: CharSequence by mutableStateOf(format.text)

    /** True when the text differs from what is on disk. Undoing back to the saved text clears it. */
    val isDirty: Boolean by derivedStateOf { !readOnly && !state.text.contentEquals(savedText) }

    var saving by mutableStateOf(false)
        private set

    val encodingLabel: String get() = format.encodingLabel
    val lineEndingLabel: String get() = format.lineEndingLabel

    /** Start offset of every line, for line numbers and the cursor position readout. */
    val lineStarts: IntArray by derivedStateOf {
        val text = state.text
        val starts = ArrayList<Int>(text.length / 32 + 1)
        starts += 0
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        starts.toIntArray()
    }

    /** 1-based line and column of the cursor. */
    val cursor: Pair<Int, Int> by derivedStateOf {
        val offset = state.selection.start
        val line = lineIndexOf(offset)
        (line + 1) to (offset - lineStarts[line] + 1)
    }

    fun lineIndexOf(offset: Int): Int {
        val idx = lineStarts.binarySearch(offset)
        return if (idx >= 0) idx else -idx - 2
    }

    suspend fun save() {
        if (readOnly) return
        saving = true
        try {
            val snapshot = state.text.toString()
            withContext(Dispatchers.IO) { TextDocument.save(file, snapshot, format) }
            savedText = snapshot
        } finally {
            saving = false
        }
    }

    /** Throws away unsaved edits. */
    fun revert() {
        val saved = savedText
        state.edit { replace(0, length, saved) }
    }

    // ------------------------------------------------------------ find & replace

    var findVisible by mutableStateOf(false)
    val findQuery = TextFieldState()
    val replaceQuery = TextFieldState()
    var matchCase by mutableStateOf(false)

    /** Offset the editor should scroll to after a find, consumed by the UI. */
    var scrollRequest by mutableIntStateOf(-1)

    val matchCount: Int by derivedStateOf {
        val query = findQuery.text.toString()
        if (query.isEmpty()) return@derivedStateOf 0
        val text = state.text
        var count = 0
        var index = text.indexOf(query, 0, ignoreCase = !matchCase)
        while (index >= 0) {
            count++
            index = text.indexOf(query, index + query.length, ignoreCase = !matchCase)
        }
        count
    }

    /** Selects the next (or previous) match, wrapping around. Returns false when there is none. */
    fun find(forward: Boolean = true): Boolean {
        val query = findQuery.text.toString()
        if (query.isEmpty()) return false
        val text = state.text
        val current = state.selection
        val ignoreCase = !matchCase
        val index = if (forward) {
            text.indexOf(query, current.max, ignoreCase).takeIf { it >= 0 } ?: text.indexOf(query, 0, ignoreCase)
        } else {
            text.lastIndexOf(query, current.min - 1, ignoreCase).takeIf { it >= 0 }
                ?: text.lastIndexOf(query, text.length, ignoreCase)
        }
        if (index < 0) return false
        state.edit { selection = TextRange(index, index + query.length) }
        scrollRequest = index
        return true
    }

    /** Replaces the selected match, then moves on to the next one. */
    fun replaceCurrent() {
        if (readOnly) return
        val query = findQuery.text.toString()
        if (query.isEmpty()) return
        val current = state.selection
        val selected = state.text.subSequence(current.min, current.max).toString()
        if (selected.equals(query, ignoreCase = !matchCase) && !current.collapsed) {
            val replacement = replaceQuery.text.toString()
            state.edit {
                replace(current.min, current.max, replacement)
                selection = TextRange(current.min + replacement.length)
            }
        }
        find(forward = true)
    }

    /** Replaces every match in one undoable step. Returns how many were replaced. */
    fun replaceAll(): Int {
        if (readOnly) return 0
        val query = findQuery.text.toString()
        if (query.isEmpty()) return 0
        val replacement = replaceQuery.text.toString()
        val text = state.text.toString()
        val ignoreCase = !matchCase
        val out = StringBuilder(text.length)
        var count = 0
        var from = 0
        var index = text.indexOf(query, 0, ignoreCase)
        while (index >= 0) {
            out.append(text, from, index).append(replacement)
            from = index + query.length
            count++
            index = text.indexOf(query, from, ignoreCase)
        }
        if (count == 0) return 0
        out.append(text, from, text.length)
        state.edit {
            replace(0, length, out)
            selection = TextRange(0)
        }
        return count
    }
}
