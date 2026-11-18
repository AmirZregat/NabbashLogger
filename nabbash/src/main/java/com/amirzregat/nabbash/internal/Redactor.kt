package com.amirzregat.nabbash.internal

import java.util.Locale

internal const val REDACTED = "••••••"

/**
 * Masks secrets before anything is written to disk. Pure Kotlin, no Android types,
 * so it is covered by plain JVM unit tests.
 *
 * Everything here works on the raw text instead of parsing and re-serialising it:
 * the stored body stays byte-for-byte what the server sent apart from the masked
 * values, and a body that was cut at the size limit is still handled.
 */
internal class Redactor(
    extraHeaders: Collection<String> = emptyList(),
    extraBodyKeys: Collection<String> = emptyList(),
    private val redactHeaders: Boolean = true,
    private val redactFields: Boolean = true,
) {
    private val headerNames: Set<String> =
        (DEFAULT_HEADERS + extraHeaders).map { it.trim().lowercase(Locale.ROOT) }.toSet()

    private val exactKeys: Set<String> =
        (DEFAULT_EXACT_KEYS + extraBodyKeys).map(::normalize).filter { it.isNotEmpty() }.toSet()

    fun header(name: String, value: String): String =
        if (redactHeaders && name.trim().lowercase(Locale.ROOT) in headerNames) REDACTED else value

    fun isSensitiveKey(key: String): Boolean {
        val n = normalize(key)
        if (n.isEmpty()) return false
        if (n in exactKeys) return true
        return DEFAULT_CONTAINS_KEYS.any { n.contains(it) }
    }

    /** Drops `user:password@` and masks sensitive query parameters. */
    fun url(url: String): String {
        // Credentials embedded in a URL are never useful in a log, so this is not switchable.
        val withoutUserInfo = USER_INFO.replace(url) { it.groupValues[1] }
        if (!redactFields) return withoutUserInfo
        val q = withoutUserInfo.indexOf('?')
        if (q < 0) return withoutUserInfo
        val hash = withoutUserInfo.indexOf('#', q).let { if (it < 0) withoutUserInfo.length else it }
        return withoutUserInfo.substring(0, q + 1) +
            params(withoutUserInfo.substring(q + 1, hash)) +
            withoutUserInfo.substring(hash)
    }

    fun body(body: String, contentType: String?): String {
        if (!redactFields || body.isEmpty()) return body
        val type = contentType?.lowercase(Locale.ROOT).orEmpty()
        val start = body.trimStart().take(1)
        val masked = when {
            type.contains("x-www-form-urlencoded") -> params(body)
            type.contains("json") || start == "{" || start == "[" -> escapedJson(json(body))
            type.contains("xml") || start == "<" -> xml(body)
            else -> body
        }
        return text(masked)
    }

    /**
     * Masks secrets that appear in free text: header values, log messages, error
     * descriptions and whatever is left of a body. Covers `Bearer xyz` / `Basic xyz`,
     * `user:password@` inside a URL and `token=xyz` pairs, so a URL that a server echoes
     * back (a `Location` header, a "next page" link) is masked like the request URL was.
     */
    fun text(text: String): String {
        val withoutUserInfo = if (text.contains("://")) INLINE_USER_INFO.replace(text) { it.groupValues[1] } else text
        if (!redactFields) return withoutUserInfo
        val withoutSchemes = AUTH_SCHEME.replace(withoutUserInfo) { "${it.groupValues[1]} $REDACTED" }
        if (!withoutSchemes.contains('=')) return withoutSchemes
        return INLINE_PAIR.replace(withoutSchemes) { m ->
            if (isSensitiveKey(percentDecode(m.groupValues[1]))) "${m.groupValues[1]}=$REDACTED" else m.value
        }
    }

    // region key=value&key=value

    private fun params(source: String): String =
        source.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) pair
            else if (isSensitiveKey(percentDecode(pair.substring(0, eq)))) pair.substring(0, eq + 1) + REDACTED
            else pair
        }

    private fun percentDecode(s: String): String =
        try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }

    // endregion

    // region JSON

    private fun json(src: String): String {
        val n = src.length
        val out = StringBuilder(n)
        var i = 0
        while (i < n) {
            val c = src[i]
            if (c != '"') {
                out.append(c)
                i++
                continue
            }
            val end = stringEnd(src, i)
            out.append(src, i, end)
            var colon = end
            while (colon < n && src[colon].isWhitespace()) colon++
            // A string followed by ':' is an object key. `colon < n` also proves the string was closed.
            if (colon < n && src[colon] == ':' && isSensitiveKey(unescape(src.substring(i + 1, end - 1)))) {
                out.append(src, end, colon + 1)
                var v = colon + 1
                while (v < n && src[v].isWhitespace()) {
                    out.append(src[v])
                    v++
                }
                val valueEnd = valueEnd(src, v)
                if (v < n) {
                    if (src.regionMatches(v, "null", 0, 4) && valueEnd - v == 4) out.append("null")
                    else out.append('"').append(REDACTED).append('"')
                }
                i = valueEnd
            } else {
                i = end
            }
        }
        return out.toString()
    }

    /** Index just past the closing quote of the string that opens at [start], or the end of input. */
    private fun stringEnd(src: String, start: Int): Int {
        var i = start + 1
        while (i < src.length) {
            when (src[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return src.length
    }

    private fun valueEnd(src: String, start: Int): Int {
        val n = src.length
        if (start >= n) return n
        return when (src[start]) {
            '"' -> stringEnd(src, start)
            '{', '[' -> {
                var depth = 0
                var i = start
                while (i < n) {
                    when (src[i]) {
                        '"' -> {
                            i = stringEnd(src, i)
                            continue
                        }
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) return i + 1
                        }
                    }
                    i++
                }
                n
            }
            else -> {
                var i = start
                while (i < n && src[i] != ',' && src[i] != '}' && src[i] != ']' && !src[i].isWhitespace()) i++
                i
            }
        }
    }

    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                if (next == 'u' && i + 5 < s.length) {
                    val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                }
                out.append(next)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** JSON that was itself put inside a JSON string: `"payload":"{\"password\":\"x\"}"`. */
    private fun escapedJson(src: String): String {
        if (!src.contains("\\\"")) return src
        return ESCAPED_PAIR.replace(src) { m ->
            if (isSensitiveKey(m.groupValues[1])) "\\\"${m.groupValues[1]}\\\"${m.groupValues[2]}\\\"$REDACTED\\\""
            else m.value
        }
    }

    // endregion

    // region XML

    private fun xml(src: String): String =
        XML_ELEMENT.replace(src) { m ->
            val tag = m.groupValues[1]
            if (isSensitiveKey(tag.substringAfterLast(':'))) "<$tag${m.groupValues[2]}>$REDACTED</$tag>" else m.value
        }

    // endregion

    private fun normalize(key: String): String {
        val out = StringBuilder(key.length)
        for (c in key) if (c.isLetterOrDigit()) out.append(c.lowercaseChar())
        return out.toString()
    }

    internal companion object {
        val DEFAULT_HEADERS = listOf(
            "Authorization",
            "Proxy-Authorization",
            "Cookie",
            "Set-Cookie",
            "X-Api-Key",
            "Api-Key",
            "X-Auth-Token",
            "X-Access-Token",
            "X-Refresh-Token",
            "X-Csrf-Token",
            "X-Xsrf-Token",
            "X-Amz-Security-Token",
        )

        /** A field is masked when its normalised name contains one of these. */
        val DEFAULT_CONTAINS_KEYS = listOf(
            "password", "passwd", "passcode", "secret", "token", "apikey",
            "cardnumber", "cvv", "privatekey", "credential", "authorization",
        )

        /** Short names that are only masked on an exact match ("pin" must not hit "shipping"). */
        val DEFAULT_EXACT_KEYS = listOf(
            "pin", "mpin", "otp", "pwd", "cvc", "pan", "ssn", "sessionid", "jsessionid",
        )

        private val USER_INFO = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/?#@]*@")
        private val INLINE_USER_INFO = Regex("\\b([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/?#@\\s\"'<>\\\\]*@")
        private val INLINE_PAIR =
            Regex("(?<![A-Za-z0-9_.%\\-])([A-Za-z0-9_.%\\-\\[\\]]{1,64})=([^&#\\s\"'<>;,\\\\]+)")
        private val AUTH_SCHEME = Regex("\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=\\-]{8,}", RegexOption.IGNORE_CASE)
        private val ESCAPED_PAIR =
            Regex("\\\\\"([A-Za-z0-9_.\\-]{1,64})\\\\\"(\\s*:\\s*)\\\\\"(.*?)\\\\\"")
        private val XML_ELEMENT = Regex("<([A-Za-z_][\\w:.\\-]*)(\\s[^<>]*)?>([^<]*)</\\1>")
    }
}
