package io.github.l0gic0l.filefold.core

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * Carries progress out of, and cancellation into, a long running blocking
 * operation such as a copy or an extraction.
 */
class Progress(
    private val isActive: () -> Boolean = { true },
    private val onUpdate: (done: Long, total: Long, current: String) -> Unit = { _, _, _ -> },
) {
    /** Total number of bytes expected, or 0 when it is not known up front. */
    var total: Long = 0
    var done: Long = 0
        private set
    private var current: String = ""
    private var lastReport = 0L

    fun start(name: String) {
        current = name
        report(force = true)
    }

    fun advance(bytes: Long) {
        done += bytes
        report(force = false)
    }

    /** Throws when the user has cancelled, unwinding the operation. */
    fun ensureActive() {
        if (!isActive()) throw CancellationException("Cancelled")
    }

    private fun report(force: Boolean) {
        val now = System.nanoTime()
        // Throttled so that a fast copy does not flood the UI with updates.
        if (force || now - lastReport > 50_000_000L) {
            lastReport = now
            onUpdate(done, total, current)
        }
    }

    companion object {
        val None = Progress()
    }
}

/**
 * Copies a stream while honouring cancellation. With [count] the copied bytes
 * are added to the progress; pass false when progress is measured elsewhere.
 */
fun InputStream.copyTo(out: OutputStream, progress: Progress, count: Boolean = true): Long {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        progress.ensureActive()
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        copied += read
        if (count) progress.advance(read.toLong())
    }
    return copied
}
