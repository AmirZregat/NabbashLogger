package com.amirzregat.nabbash.internal

import java.io.IOException
import java.util.Locale
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.GzipSource

/**
 * Reads request and response bodies for the interceptor without changing what the
 * app itself sends or receives. Only loaded when the host app uses OkHttp.
 */
internal object HttpCapture {

    class Captured(val text: String?, val size: Long?, val contentType: String?)

    private const val MAX_REQUEST_BYTES = 1_000_000L

    fun headers(headers: Headers): List<Pair<String, String>> =
        List(headers.size) { headers.name(it) to headers.value(it) }

    fun describe(error: Throwable): String = "${error.javaClass.name}: ${error.message.orEmpty()}"

    fun request(request: Request, max: Int): Captured {
        val body = request.body ?: return Captured(null, null, null)
        val type = body.contentType()
        val typeText = type?.toString()
        val length = try {
            body.contentLength()
        } catch (_: IOException) {
            -1L
        }
        val size = length.takeIf { it >= 0 }
        val encoding = request.header("Content-Encoding")?.trim()?.lowercase(Locale.ROOT)
        val skipped = when {
            // Writing these here would consume a body that can only be written once.
            body.isDuplex() -> "duplex stream"
            body.isOneShot() -> "one-shot stream"
            body is MultipartBody -> "multipart, ${body.size} parts"
            encoding != null && encoding != "identity" -> "encoded with $encoding"
            length < 0 -> "streamed, length unknown"
            length > MAX_REQUEST_BYTES -> "too large to capture"
            type != null && !isText(type) -> "binary"
            else -> null
        }
        if (skipped != null) return Captured(placeholder(skipped, typeText, size), size, typeText)

        val buffer = Buffer()
        body.writeTo(buffer)
        if (type == null && !looksLikeText(buffer)) return Captured(placeholder("binary", typeText, size), size, typeText)
        val charset = type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return Captured(buffer.readString(minOf(buffer.size, max + 1L), charset), size, typeText)
    }

    fun response(response: Response, max: Int): Captured {
        val body = response.body ?: return Captured(null, null, null)
        val type = body.contentType()
        val typeText = type?.toString()
        val declared = body.contentLength().takeIf { it >= 0 }
        val code = response.code
        if (response.request.method == "HEAD" || code in 100..199 || code == 204 || code == 304) {
            return Captured(null, null, typeText)
        }
        val encoding = response.header("Content-Encoding")?.trim()?.lowercase(Locale.ROOT)
        val gzip = encoding == "gzip"
        val skipped = when {
            // Peeking a stream that never ends would hold the caller back.
            type?.subtype.equals("event-stream", ignoreCase = true) -> "event stream"
            encoding != null && encoding != "identity" && !gzip -> "encoded with $encoding"
            type != null && !isText(type) -> "binary"
            else -> null
        }
        if (skipped != null) return Captured(placeholder(skipped, typeText, declared), declared, typeText)

        val limit = max + 1L
        // peekBody leaves the real body untouched for the app to read.
        val raw = Buffer()
        raw.writeAll(response.peekBody(limit).source())
        val rawSize = raw.size
        val data = if (gzip) gunzip(raw, limit) else raw
        val size = declared ?: rawSize.takeIf { it < limit }
        if (type == null && !looksLikeText(data)) return Captured(placeholder("binary", typeText, size), size, typeText)
        val charset = type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return Captured(data.readString(minOf(data.size, limit), charset), size, typeText)
    }

    private fun gunzip(compressed: Buffer, limit: Long): Buffer {
        val out = Buffer()
        try {
            GzipSource(compressed).use { gz ->
                var total = 0L
                while (total < limit) {
                    val read = gz.read(out, limit - total)
                    if (read == -1L) break
                    total += read
                }
            }
        } catch (_: IOException) {
            // The compressed data was cut at the size limit: keep what was decoded so far.
        }
        return out
    }

    private fun placeholder(reason: String, type: String?, size: Long?): String {
        val details = listOfNotNull(reason, type, size?.let { formatBytes(it) })
        return "[body not captured: ${details.joinToString(", ")}]"
    }

    private fun isText(type: MediaType): Boolean {
        if (type.type.equals("text", ignoreCase = true)) return true
        val sub = type.subtype.lowercase(Locale.ROOT)
        return TEXT_SUBTYPES.any { sub.contains(it) }
    }

    private val TEXT_SUBTYPES =
        listOf("json", "xml", "html", "javascript", "x-www-form-urlencoded", "graphql", "yaml", "csv", "plain")

    /** True when the first characters decode as readable text. */
    private fun looksLikeText(buffer: Buffer): Boolean {
        return try {
            val prefix = Buffer()
            buffer.copyTo(prefix, 0, minOf(buffer.size, 64L))
            for (i in 0 until 16) {
                if (prefix.exhausted()) break
                val codePoint = prefix.readUtf8CodePoint()
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) return false
                if (codePoint == 0xFFFD) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
