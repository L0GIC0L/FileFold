package io.github.l0gic0l.filefold.util

import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    // Building a date formatter is slow, and a fast scroll formats hundreds of dates, so reuse one.
    // java.time formatters are immutable and thread safe; rebuilt only if the locale or zone changes.
    @Volatile
    private var shortDateFormat: Triple<Locale, ZoneId, DateTimeFormatter>? = null

    fun shortDate(millis: Long): String {
        if (millis <= 0) return ""
        val locale = Locale.getDefault()
        val zone = ZoneId.systemDefault()
        val cached = shortDateFormat?.takeIf { it.first == locale && it.second == zone }
            ?: Triple(locale, zone, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone))
                .also { shortDateFormat = it }
        return cached.third.format(Instant.ofEpochMilli(millis))
    }

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
