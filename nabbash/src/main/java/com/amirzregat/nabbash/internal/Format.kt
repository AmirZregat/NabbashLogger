package com.amirzregat.nabbash.internal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> String.format(Locale.US, "%.1f kB", bytes / 1_000.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
}

internal fun formatDuration(ms: Long): String =
    if (ms < 1_000) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1_000.0)

internal fun plural(count: Int, one: String, many: String = one + "s"): String =
    "$count ${if (count == 1) one else many}"

/**
 * Date formatting with a fixed locale, so exports look the same on every device.
 * Not thread-safe: make one per thread that needs it.
 */
internal class Stamps(private val zone: TimeZone = TimeZone.getDefault()) {
    private fun format(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }

    private val millis = format("HH:mm:ss.SSS")
    private val seconds = format("HH:mm:ss")
    private val minutes = format("HH:mm")
    private val dateTime = format("yyyy-MM-dd HH:mm:ss")
    private val iso = format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    private val day = format("yyyy-MM-dd")
    private val dayLabel = format("d MMM")
    private val fileName = format("yyyy-MM-dd_HHmmss")

    fun millis(t: Long): String = millis.format(Date(t))
    fun seconds(t: Long): String = seconds.format(Date(t))
    fun minutes(t: Long): String = minutes.format(Date(t))
    fun dateTime(t: Long): String = dateTime.format(Date(t))
    fun iso(t: Long): String = iso.format(Date(t))
    fun day(t: Long): String = day.format(Date(t))
    fun dayLabel(t: Long): String = dayLabel.format(Date(t))
    fun fileName(t: Long): String = fileName.format(Date(t))
}
