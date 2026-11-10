package io.github.l0gic0l.filefold.ui.detail

import android.os.SystemClock
import android.text.Editable
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.l0gic0l.filefold.core.TextDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the text editor knows about one open file. It lives in the
 * ViewModel, so edits survive folding, unfolding and rotation; the editor view
 * on screen attaches to it and detaches when it goes away.
 */
class EditorSession(val file: File, val readOnly: Boolean, private val format: TextDocument.Decoded) : TextWatcher {

    private var savedText: String = format.text
    /** The text while no view is attached; the attached view holds the live copy. */
    private var detachedText: CharSequence = format.text
    private var view: CodeEditText? = null
    private val history = EditHistory()
    private var savedState = 0L
    private var applyingHistory = false
    private val cursorLines = LineCounter()
    private var keptScrollY = 0
    private var keptSelection = 0 to 0

    /** Called for Ctrl+S, so saving can run in the UI's coroutine scope. */
    var onSaveShortcut: (() -> Unit)? = null

    var isDirty by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var lineCount by mutableIntStateOf(countNewlines(format.text) + 1)
        private set
    var cursorLine by mutableIntStateOf(1)
        private set
    var cursorColumn by mutableIntStateOf(1)
        private set

    /** Share of a large file handed to the editor view so far, 0 to 1; 1 once it is all in. */
    var loadProgress by mutableStateOf(1f)
        private set
    private var loader: Runnable? = null
    /** While loading: the whole text, and how much of it is in the view. */
    private var loadSource: CharSequence = ""
    private var loadedUpTo = 0
    /** True while the loader appends, so its appends are not taken for typing. */
    private var appending = false
    private var attachedBefore = false

    val encodingLabel: String get() = format.encodingLabel
    val lineEndingLabel: String get() = format.lineEndingLabel

    /** The current text, live from the view when one is attached (plus any part still loading). */
    val text: CharSequence
        get() {
            val live = view?.text ?: return detachedText
            return if (loader != null) TextUtils.concat(live, loadSource.subSequence(loadedUpTo, loadSource.length)) else live
        }

    // ------------------------------------------------------------ view lifecycle

    fun attach(editor: CodeEditText) {
        view = editor
        editor.totalLines = lineCount
        val full = detachedText
        if (full.length <= FIRST_CHUNK) {
            editor.setText(full)
            finishAttach(editor)
            return
        }
        // A big file goes in a slice per frame: the first screenful shows at once, the app never
        // freezes while the rest is laid out, and each append is laid out on its own. Editing
        // works throughout; the loader only ever adds to the end.
        loadSource = full
        loadedUpTo = lineBoundary(full, FIRST_CHUNK)
        editor.setText(full.subSequence(0, loadedUpTo))
        loadProgress = loadedUpTo.toFloat() / full.length
        finishAttach(editor)
        loader = object : Runnable {
            override fun run() {
                if (view !== editor) return
                val deadline = SystemClock.uptimeMillis() + FRAME_BUDGET_MS
                appending = true
                try {
                    while (loadedUpTo < full.length && SystemClock.uptimeMillis() < deadline) {
                        val end = lineBoundary(full, loadedUpTo + SLICE)
                        editor.text.append(full, loadedUpTo, end)
                        loadedUpTo = end
                    }
                } finally {
                    appending = false
                }
                loadProgress = loadedUpTo.toFloat() / full.length
                if (loadedUpTo < full.length) {
                    editor.postOnAnimation(this)
                } else {
                    loader = null
                    loadSource = ""
                }
            }
        }.also { editor.postOnAnimation(it) }
    }

    /** The first line break at or after [from], so slices end on whole lines. */
    private fun lineBoundary(text: CharSequence, from: Int): Int {
        if (from >= text.length) return text.length
        var i = from
        val limit = minOf(text.length, from + SLICE)
        while (i < limit && text[i] != '\n') i++
        return if (i < limit) i + 1 else limit
    }

    private fun finishAttach(editor: CodeEditText) {
        if (loader == null && loadSource.isEmpty()) loadProgress = 1f
        if (readOnly) {
            editor.keyListener = null
            editor.setTextIsSelectable(true)
        }
        editor.addTextChangedListener(this)
        editor.listener = object : CodeEditText.Listener {
            override fun onSelectionChanged(start: Int, end: Int) = updateCursor(start)
            override fun onUndo() = undo()
            override fun onRedo() = redo()
            override fun onShortcut(keyCode: Int, shift: Boolean): Boolean = when (keyCode) {
                KeyEvent.KEYCODE_S -> { onSaveShortcut?.invoke(); true }
                KeyEvent.KEYCODE_F -> { findVisible = true; true }
                KeyEvent.KEYCODE_G -> { find(forward = !shift); true }
                else -> false
            }
        }
        // Coming back (after a fold or rotation that rebuilt the view): put the cursor and scroll back.
        if (attachedBefore) {
            val length = editor.text.length
            editor.setSelection(keptSelection.first.coerceIn(0, length), keptSelection.second.coerceIn(0, length))
            val scroll = keptScrollY
            editor.post { editor.scrollTo(0, scroll) }
        }
        attachedBefore = true
    }

    fun detach(editor: CodeEditText) {
        if (view !== editor) return
        // Mid-load the view holds only the first part; the rest is still in loadSource.
        detachedText = text.toString()
        loader?.let { editor.removeCallbacks(it) }
        loader = null
        loadSource = ""
        loadProgress = 1f
        editor.removeTextChangedListener(this)
        editor.listener = null
        keptScrollY = editor.scrollY
        keptSelection = editor.selectionStart to editor.selectionEnd
        view = null
    }

    // ------------------------------------------------------------ recording edits

    private var removedText: String = ""

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        if (appending) return
        removedText = if (count == 0) "" else s.subSequence(start, start + count).toString()
        view?.invalidateLinesFrom(start)
        cursorLines.invalidateFrom(start)
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        // The loader's appends are already counted in lineCount and are not edits.
        if (appending) return
        val inserted = s.subSequence(start, start + count)
        lineCount += countNewlines(inserted) - countNewlines(removedText)
        view?.totalLines = lineCount
        if (!applyingHistory) history.record(start, removedText, inserted, SystemClock.uptimeMillis())
        removedText = ""
        refreshState()
    }

    override fun afterTextChanged(s: Editable) = Unit

    private fun refreshState() {
        canUndo = !readOnly && history.canUndo
        canRedo = !readOnly && history.canRedo
        isDirty = !readOnly && history.stateId != savedState
    }

    private fun updateCursor(offset: Int) {
        val text = text
        val at = offset.coerceIn(0, text.length)
        cursorLine = cursorLines.lineAt(text, at) + 1
        var lineStart = at
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        cursorColumn = at - lineStart + 1
    }

    // ------------------------------------------------------------ undo, save, revert

    fun undo() = applyHistory { history.undo(it) }

    fun redo() = applyHistory { history.redo(it) }

    private fun applyHistory(step: (EditHistory.Target) -> Int) {
        val editor = view ?: return
        if (readOnly) return
        val editable = editor.text
        applyingHistory = true
        val cursor = try {
            step { start, end, replacement -> editable.replace(start, end, replacement) }
        } finally {
            applyingHistory = false
        }
        if (cursor >= 0) {
            editor.setSelection(cursor.coerceIn(0, editable.length))
            editor.bringPointIntoView(cursor.coerceIn(0, editable.length))
        }
        refreshState()
    }

    suspend fun save() {
        if (readOnly) return
        saving = true
        try {
            val snapshot = text.toString()
            withContext(Dispatchers.IO) { TextDocument.save(file, snapshot, format) }
            savedText = snapshot
            savedState = history.markSaved()
            refreshState()
        } finally {
            saving = false
        }
    }

    /** Throws away unsaved edits. */
    fun revert() {
        val editor = view
        history.clear()
        savedState = 0L
        lineCount = countNewlines(savedText) + 1
        if (editor != null) {
            // Through detach and attach, so a large file reloads in slices like when it first opened.
            detach(editor)
            detachedText = savedText
            attach(editor)
        } else {
            detachedText = savedText
        }
        refreshState()
    }

    // ------------------------------------------------------------ find & replace

    var findVisible by mutableStateOf(false)
    var findQuery by mutableStateOf("")
        private set
    var replaceQuery by mutableStateOf("")
    var matchCase by mutableStateOf(false)
        private set
    var matchCount by mutableIntStateOf(0)
        private set

    /** Colour for the highlighted match, set from the theme. */
    var highlightColor: Int = 0x66FFC107
    private var highlight: BackgroundColorSpan? = null

    fun updateFindQuery(query: String) {
        findQuery = query
        recount()
    }

    fun toggleMatchCase() {
        matchCase = !matchCase
        recount()
    }

    fun closeFind() {
        findVisible = false
        clearHighlight()
    }

    private fun recount() {
        val query = findQuery
        if (query.isEmpty()) {
            matchCount = 0
            clearHighlight()
            return
        }
        val text = text
        var count = 0
        var index = text.indexOf(query, 0, ignoreCase = !matchCase)
        while (index >= 0) {
            count++
            index = text.indexOf(query, index + query.length, ignoreCase = !matchCase)
        }
        matchCount = count
    }

    /** Selects and shows the next (or previous) match, wrapping around. Returns false when there is none. */
    fun find(forward: Boolean = true): Boolean {
        val editor = view ?: return false
        val query = findQuery
        if (query.isEmpty()) return false
        val text = editor.text
        val ignoreCase = !matchCase
        val index = if (forward) {
            text.indexOf(query, editor.selectionEnd, ignoreCase).takeIf { it >= 0 } ?: text.indexOf(query, 0, ignoreCase)
        } else {
            text.lastIndexOf(query, editor.selectionStart - 1, ignoreCase).takeIf { it >= 0 }
                ?: text.lastIndexOf(query, text.length, ignoreCase)
        }
        if (index < 0) return false
        clearHighlight()
        // A highlight span shows the match even while the find box, not the text, has focus.
        highlight = BackgroundColorSpan(highlightColor).also {
            text.setSpan(it, index, index + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        editor.setSelection(index, index + query.length)
        editor.bringPointIntoView(index)
        return true
    }

    private fun clearHighlight() {
        highlight?.let { view?.text?.removeSpan(it) }
        highlight = null
    }

    /** Replaces the selected match, then moves on to the next one. */
    fun replaceCurrent() {
        val editor = view ?: return
        if (readOnly || findQuery.isEmpty()) return
        val start = editor.selectionStart
        val end = editor.selectionEnd
        val selected = editor.text.subSequence(minOf(start, end), maxOf(start, end)).toString()
        if (start != end && selected.equals(findQuery, ignoreCase = !matchCase)) {
            clearHighlight()
            editor.text.replace(minOf(start, end), maxOf(start, end), replaceQuery)
        }
        find(forward = true)
        recount()
    }

    /** Replaces every match in one undoable step. Returns how many were replaced. */
    fun replaceAll(): Int {
        val editor = view ?: return 0
        // Replacing everything while the file is still arriving would miss the part not yet in.
        if (readOnly || findQuery.isEmpty() || loader != null) return 0
        val query = findQuery
        val ignoreCase = !matchCase
        val original = editor.text.toString()
        val out = StringBuilder(original.length)
        var count = 0
        var from = 0
        var index = original.indexOf(query, 0, ignoreCase)
        while (index >= 0) {
            out.append(original, from, index).append(replaceQuery)
            from = index + query.length
            count++
            index = original.indexOf(query, from, ignoreCase)
        }
        if (count == 0) return 0
        out.append(original, from, original.length)
        clearHighlight()
        editor.text.replace(0, editor.text.length, out)
        editor.setSelection(0)
        recount()
        return count
    }

    private companion object {
        /** Files up to this many characters go into the editor in one go. */
        const val FIRST_CHUNK = 96 * 1024
        const val SLICE = 32 * 1024
        const val FRAME_BUDGET_MS = 10L

        fun countNewlines(text: CharSequence): Int {
            var n = 0
            for (i in text.indices) if (text[i] == '\n') n++
            return n
        }
    }
}
