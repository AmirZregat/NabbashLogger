package com.amirzregat.nabbash.internal

internal const val TYPE_NETWORK = 0
internal const val TYPE_MESSAGE = 1

internal const val LEVEL_WARNING = 3
internal const val LEVEL_ERROR = 4

internal val LEVEL_NAMES = listOf("verbose", "debug", "info", "warning", "error")

internal enum class EventFilter { ALL, NETWORK, LOGS, ERRORS }

internal data class SessionRow(
    val id: Long,
    val startedAt: Long,
    val lastEventAt: Long,
    val appVersion: String,
    val device: String,
    val eventCount: Int,
    val errorCount: Int,
    val sizeBytes: Long,
    val isCurrent: Boolean,
)

/**
 * One stored event. List queries leave the heavy fields (headers and bodies) null and
 * shorten [message] and [error]; [LogStore.event] returns everything.
 */
internal data class LogEvent(
    val id: Long = 0,
    val sessionId: Long = 0,
    val type: Int,
    val time: Long,
    val level: Int = 1,
    val isError: Boolean = false,
    val label: String? = null,
    val message: String? = null,
    val method: String? = null,
    val url: String? = null,
    val status: Int? = null,
    val durationMs: Long? = null,
    val requestContentType: String? = null,
    val requestHeaders: List<Pair<String, String>>? = null,
    val requestBody: String? = null,
    val requestSize: Long? = null,
    val responseContentType: String? = null,
    val responseHeaders: List<Pair<String, String>>? = null,
    val responseBody: String? = null,
    val responseSize: Long? = null,
    val error: String? = null,
) {
    val isNetwork: Boolean get() = type == TYPE_NETWORK

    /** Rough stored size, used for the storage limit. */
    fun storedSize(): Long {
        var n = 64L
        n += (label?.length ?: 0) + (message?.length ?: 0) + (method?.length ?: 0) + (url?.length ?: 0)
        n += (requestBody?.length ?: 0) + (responseBody?.length ?: 0) + (error?.length ?: 0)
        requestHeaders?.forEach { n += it.first.length + it.second.length + 3 }
        responseHeaders?.forEach { n += it.first.length + it.second.length + 3 }
        return n
    }
}

internal fun encodeHeaders(headers: List<Pair<String, String>>?): String? {
    if (headers.isNullOrEmpty()) return null
    return headers.joinToString("\n") { (name, value) ->
        name.replace('\n', ' ').replace('\r', ' ') + ": " + value.replace('\n', ' ').replace('\r', ' ')
    }
}

internal fun decodeHeaders(text: String?): List<Pair<String, String>> {
    if (text.isNullOrEmpty()) return emptyList()
    return text.split('\n').mapNotNull { line ->
        val i = line.indexOf(": ")
        if (i <= 0) null else line.substring(0, i) to line.substring(i + 2)
    }
}

/** `/v1/accounts?page=2` for `https://api.example.com/v1/accounts?page=2`. */
internal fun urlPath(url: String): String {
    val scheme = url.indexOf("://")
    if (scheme < 0) return url
    val slash = url.indexOf('/', scheme + 3)
    return if (slash < 0) "/" else url.substring(slash)
}

internal fun urlHost(url: String): String {
    val scheme = url.indexOf("://")
    if (scheme < 0) return ""
    val start = scheme + 3
    var end = url.length
    for (i in start until url.length) {
        val c = url[i]
        if (c == '/' || c == '?' || c == '#') {
            end = i
            break
        }
    }
    return url.substring(start, end)
}
