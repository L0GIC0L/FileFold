package io.github.l0gic0l.filefold.ui.detail

/**
 * Undo and redo for the text editor, recorded as text replacements.
 *
 * Small edits close together in time merge into one step, so undo removes a
 * word or a burst of typing rather than single letters. This includes the
 * keyboard's habit of replacing a whole word while you type it.
 */
class EditHistory(private val maxSteps: Int = 1000) {

    /** Anything that can have a range of its text replaced, e.g. an Android Editable. */
    fun interface Target {
        fun replace(start: Int, end: Int, text: CharSequence)
    }

    private class Step(val id: Long, var start: Int, var removed: String, var inserted: String, var time: Long)

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    private var nextId = 1L
    private var savedId = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Identifies the current text: 0 for the text as loaded, otherwise the last step applied. */
    val stateId: Long get() = undoStack.lastOrNull()?.id ?: 0L

    /** Remembers the current state as saved. Later typing never merges into it. */
    fun markSaved(): Long {
        savedId = stateId
        return savedId
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        savedId = 0L
    }

    /** Records that [removed] at [start] was replaced by [inserted]. [now] is in milliseconds. */
    fun record(start: Int, removed: CharSequence, inserted: CharSequence, now: Long) {
        if (removed.isEmpty() && inserted.isEmpty()) return
        redoStack.clear()
        val last = undoStack.lastOrNull()
        if (last != null && last.id != savedId && now - last.time < MERGE_WINDOW_MS && merge(last, start, removed, inserted)) {
            last.time = now
            return
        }
        undoStack.addLast(Step(nextId++, start, removed.toString(), inserted.toString(), now))
        if (undoStack.size > maxSteps) undoStack.removeFirst()
    }

    /** Undoes the last step. Returns where the cursor should go, or -1 when there was nothing to undo. */
    fun undo(target: Target): Int {
        val step = undoStack.removeLastOrNull() ?: return -1
        target.replace(step.start, step.start + step.inserted.length, step.removed)
        redoStack.addLast(step)
        return step.start + step.removed.length
    }

    /** Redoes the last undone step. Returns where the cursor should go, or -1 when there was nothing to redo. */
    fun redo(target: Target): Int {
        val step = redoStack.removeLastOrNull() ?: return -1
        target.replace(step.start, step.start + step.removed.length, step.inserted)
        undoStack.addLast(step)
        return step.start + step.inserted.length
    }

    private fun merge(last: Step, start: Int, removed: CharSequence, inserted: CharSequence): Boolean {
        // A new line starts a new step, as does anything big.
        if (inserted.contains('\n') || inserted.length > 64 || removed.length > 64) return false
        val lastEnd = last.start + last.inserted.length
        return when {
            // Typing on, or the keyboard rewriting part of what was just typed.
            start >= last.start && start + removed.length <= lastEnd -> {
                val from = start - last.start
                last.inserted = last.inserted.replaceRange(from, from + removed.length, inserted)
                true
            }
            // Backspace past the start of what was typed, or a run of backspaces.
            inserted.isEmpty() && start + removed.length == last.start -> {
                last.start = start
                last.removed = removed.toString() + last.removed
                true
            }
            // Forward delete held down.
            inserted.isEmpty() && last.inserted.isEmpty() && start == last.start -> {
                last.removed += removed
                true
            }
            else -> false
        }
    }

    private companion object {
        const val MERGE_WINDOW_MS = 1500L
    }
}

/**
 * Works out which line an offset is on without rescanning the whole text each
 * time. It remembers the last answer and counts only the newlines between the
 * old and new offset, which is cheap while scrolling or moving the cursor.
 */
class LineCounter {
    private var offset = 0
    private var line = 0

    /** Call before the text changes at [position]; answers before it stay valid. */
    fun invalidateFrom(position: Int) {
        if (position < offset) {
            offset = 0
            line = 0
        }
    }

    /** 0-based line index of [target] in [text]. */
    fun lineAt(text: CharSequence, target: Int): Int {
        val to = target.coerceIn(0, text.length)
        if (offset > text.length) {
            offset = 0
            line = 0
        }
        if (to >= offset) {
            for (i in offset until to) if (text[i] == '\n') line++
        } else {
            for (i in to until offset) if (text[i] == '\n') line--
        }
        offset = to
        return line
    }
}
