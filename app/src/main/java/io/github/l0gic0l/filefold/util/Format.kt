package io.github.l0gic0l.filefold.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Format {

    /** "0 B", "12 KB", "3.4 MB": base 1024, one decimal place below 10 units. */
    fun size(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
        val value = bytes / 1024.0.pow(exp)
        val pattern = if (value < 10) "%.1f %s" else "%.0f %s"
        return String.format(Locale.getDefault(), pattern, value, units[exp - 1])
    }

    fun date(millis: Long): String =
        if (millis <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun shortDate(millis: Long): String =
        if (millis <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    fun items(count: Int): String = when (count) {
        -1 -> "Folder"
        1 -> "1 item"
        else -> "$count items"
    }

    /** How much smaller the archive is than its contents, e.g. "62% saved". */
    fun ratio(compressed: Long, original: Long): String? {
        if (compressed < 0 || original <= 0) return null
        val saved = ((1 - compressed.toDouble() / original) * 100).toInt()
        return if (saved > 0) "$saved% saved" else null
    }
}
