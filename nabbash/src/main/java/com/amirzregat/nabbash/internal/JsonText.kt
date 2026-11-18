package com.amirzregat.nabbash.internal

/** Small JSON text helpers that need no parser and no Android classes. */
internal object JsonText {

    /** [value] as a quoted, escaped JSON string. */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    fun looksLikeJson(text: String): Boolean {
        val start = text.trimStart().take(1)
        return start == "{" || start == "["
    }

    /**
     * Re-indents JSON for reading. It never parses, so numbers keep their exact
     * spelling and a body that was cut at the size limit still formats.
     */
    fun pretty(src: String): String {
        if (!looksLikeJson(src)) return src
        val n = src.length
        val out = StringBuilder(n + n / 4)
        var indent = 0
        var i = 0
        fun newline() {
            out.append('\n')
            repeat(indent) { out.append("  ") }
        }
        while (i < n) {
            val c = src[i]
            when {
                c == '"' -> {
                    var j = i + 1
                    while (j < n) {
                        if (src[j] == '\\') j += 2 else if (src[j] == '"') break else j++
                    }
                    val end = minOf(j + 1, n)
                    out.append(src, i, end)
                    i = end
                    continue
                }
                c == '{' || c == '[' -> {
                    var j = i + 1
                    while (j < n && src[j].isWhitespace()) j++
                    if (j < n && (src[j] == '}' || src[j] == ']')) {
                        out.append(c).append(src[j])
                        i = j + 1
                        continue
                    }
                    out.append(c)
                    indent++
                    newline()
                }
                c == '}' || c == ']' -> {
                    if (indent > 0) indent--
                    newline()
                    out.append(c)
                }
                c == ',' -> {
                    out.append(c)
                    newline()
                }
                c == ':' -> out.append(": ")
                c.isWhitespace() -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
