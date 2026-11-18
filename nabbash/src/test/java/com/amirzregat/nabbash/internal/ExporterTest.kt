package com.amirzregat.nabbash.internal

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterTest {

    private val stamps = Stamps(TimeZone.getTimeZone("UTC"))
    private val meta = ExportMeta("Sample \"App\"", "com.example.sample", "Pixel 7 · Android 16", 1_763_370_000_000)

    private val session = SessionRow(
        id = 7,
        startedAt = 1_763_366_400_000,
        lastEventAt = 1_763_366_460_000,
        appVersion = "1.0 (1)",
        device = "Pixel 7",
        eventCount = 2,
        errorCount = 1,
        sizeBytes = 2048,
        isCurrent = true,
    )

    private val network = LogEvent(
        id = 1,
        sessionId = 7,
        type = TYPE_NETWORK,
        time = 1_763_366_401_250,
        method = "POST",
        url = "https://api.example.com/v1/login?api_key=$REDACTED",
        status = 200,
        durationMs = 412,
        requestContentType = "application/json",
        requestHeaders = listOf("Authorization" to REDACTED, "Content-Type" to "application/json", "Content-Length" to "44"),
        requestBody = """{"user":"amir","password":"$REDACTED","note":"it's fine"}""",
        requestSize = 44,
        responseContentType = "application/json",
        responseHeaders = listOf("Content-Type" to "application/json"),
        responseBody = """{"ok":true,"lines":"a\nb"}""",
        responseSize = 1200,
    )

    private val message = LogEvent(
        id = 2,
        sessionId = 7,
        type = TYPE_MESSAGE,
        time = 1_763_366_402_000,
        level = LEVEL_ERROR,
        isError = true,
        label = "Checkout",
        message = "Payment failed\twith a \"quote\"",
        error = "java.lang.IllegalStateException: boom\n\tat com.example.Pay.run(Pay.kt:12)",
    )

    private val failed = LogEvent(
        id = 3,
        sessionId = 7,
        type = TYPE_NETWORK,
        time = 1_763_366_403_000,
        isError = true,
        method = "GET",
        url = "https://nabbash.invalid/ping",
        durationMs = 31,
        error = "java.net.UnknownHostException: nabbash.invalid",
    )

    private val eventsOf: (Long, (LogEvent) -> Unit) -> Unit = { id, each ->
        if (id == 7L) listOf(network, message, failed).forEach(each)
    }

    @Test
    fun `text export has the header, the session and every event`() {
        val out = StringBuilder().also { Exporter.text(it, meta, listOf(session), stamps, eventsOf) }.toString()
        assertTrue(out, out.startsWith("Nabbash log export\n"))
        assertTrue(out, out.contains("App:       Sample \"App\" (com.example.sample)"))
        assertTrue(out, out.contains("Sessions:  1"))
        assertTrue(out, out.contains("Session 7 (current)"))
        assertTrue(out, out.contains("App version 1.0 (1) · 2 events · 1 error\n"))
        assertTrue(out, out.contains("] POST https://api.example.com/v1/login?api_key=$REDACTED → 200 (412 ms"))
        assertTrue(out, out.contains("    Authorization: $REDACTED"))
        assertTrue(out, out.contains("] ERROR Checkout: Payment failed"))
        assertTrue(out, out.contains("    java.lang.IllegalStateException: boom"))
        assertTrue(out, out.contains("→ FAILED"))
        assertTrue(out, out.contains("java.net.UnknownHostException: nabbash.invalid"))
    }

    @Test
    fun `text export pretty prints json bodies`() {
        val out = Exporter.eventText(network, stamps)
        assertTrue(out, out.contains("  Request body:\n    {\n      \"user\": \"amir\","))
    }

    @Test
    fun `json export is valid json with the documented fields`() {
        val out = StringBuilder().also { Exporter.json(it, meta, listOf(session), stamps, eventsOf) }.toString()
        val root = MiniJson(out).parse() as Map<*, *>
        assertEquals("Nabbash", root["generator"])
        assertEquals(1.0, root["formatVersion"])
        assertEquals(REDACTED, root["redactedValue"])
        assertEquals("Sample \"App\"", (root["app"] as Map<*, *>)["name"])
        val sessions = root["sessions"] as List<*>
        assertEquals(1, sessions.size)
        val s = sessions[0] as Map<*, *>
        assertEquals(7.0, s["id"])
        assertEquals(true, s["current"])
        val events = s["events"] as List<*>
        assertEquals(3, events.size)

        val first = events[0] as Map<*, *>
        assertEquals("network", first["type"])
        assertEquals("POST", first["method"])
        assertEquals(200.0, first["status"])
        assertEquals(412.0, first["durationMs"])
        val request = first["request"] as Map<*, *>
        assertEquals(network.requestBody, request["body"])
        assertEquals(44.0, request["sizeBytes"])
        val header = (request["headers"] as List<*>)[0] as Map<*, *>
        assertEquals("Authorization", header["name"])
        assertEquals(REDACTED, header["value"])
        assertEquals(network.responseBody, (first["response"] as Map<*, *>)["body"])

        val second = events[1] as Map<*, *>
        assertEquals("log", second["type"])
        assertEquals("error", second["level"])
        assertEquals("Checkout", second["label"])
        assertEquals(message.message, second["message"])
        assertEquals(message.error, second["stackTrace"])

        val third = events[2] as Map<*, *>
        assertEquals(null, third["status"])
        assertEquals(failed.error, third["error"])
    }

    @Test
    fun `json export of nothing is still valid`() {
        val none = StringBuilder().also { Exporter.json(it, meta, emptyList(), stamps) { _, _ -> } }.toString()
        assertEquals(emptyList<Any>(), (MiniJson(none).parse() as Map<*, *>)["sessions"])
        val empty = StringBuilder().also { Exporter.json(it, meta, listOf(session), stamps) { _, _ -> } }.toString()
        val s = ((MiniJson(empty).parse() as Map<*, *>)["sessions"] as List<*>)[0] as Map<*, *>
        assertEquals(emptyList<Any>(), s["events"])
    }

    @Test
    fun `curl repeats the request and keeps masked values masked`() {
        val out = Exporter.curl(network)
        assertTrue(out, out.startsWith("curl -X POST 'https://api.example.com/v1/login?api_key=$REDACTED'"))
        assertTrue(out, out.contains("-H 'Authorization: $REDACTED'"))
        assertFalse(out, out.contains("Content-Length"))
        // A single quote in the body must not end the shell string.
        assertTrue(out, out.contains("""it'\''s fine"""))
        assertTrue(out, out.contains("--data-raw '"))
    }

    @Test
    fun `curl leaves out a body that was not captured`() {
        val out = Exporter.curl(network.copy(requestBody = "[body not captured: binary, image/png, 1.2 kB]"))
        assertFalse(out, out.contains("--data-raw"))
    }

    @Test
    fun `pretty printer indents and keeps strings intact`() {
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    true,\n    \"x,y:{z}\"\n  ],\n  \"c\": {}\n}", JsonText.pretty("""{"a":1,"b":[true,"x,y:{z}"],"c":{}}"""))
        assertEquals("not json", JsonText.pretty("not json"))
        // Cut in the middle of a string: must not throw.
        JsonText.pretty("""{"a":"unterminated\""")
        JsonText.pretty("""{"a":[1,2""")
    }

    @Test
    fun `quote escapes control characters`() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001\"", JsonText.quote("a\"b\\c\n\t\u0001"))
    }

    @Test
    fun `sizes, durations and counts read naturally`() {
        assertEquals("412 B", formatBytes(412))
        assertEquals("1.2 kB", formatBytes(1200))
        assertEquals("1.8 MB", formatBytes(1_800_000))
        assertEquals("412 ms", formatDuration(412))
        assertEquals("1.2 s", formatDuration(1200))
        assertEquals("1 event", plural(1, "event"))
        assertEquals("23 events", plural(23, "event"))
    }

    @Test
    fun `headers survive storage and cannot inject lines`() {
        val headers = listOf("A" to "1", "Set-Cookie" to "x=y; Path=/", "Evil" to "v\nInjected: yes")
        val decoded = decodeHeaders(encodeHeaders(headers))
        assertEquals(3, decoded.size)
        assertEquals("Set-Cookie" to "x=y; Path=/", decoded[1])
        assertEquals("Evil" to "v Injected: yes", decoded[2])
    }
}

/** Just enough of a JSON parser to prove the export is well formed. */
private class MiniJson(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        val value = value()
        ws()
        check(i == s.length) { "trailing text at $i" }
        return value
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> {
                i++
                val map = LinkedHashMap<String, Any?>()
                ws()
                if (s[i] == '}') { i++; return map }
                while (true) {
                    ws()
                    val key = string()
                    ws()
                    check(s[i++] == ':') { "expected ':' at ${i - 1}" }
                    map[key] = value()
                    ws()
                    when (s[i++]) {
                        ',' -> continue
                        '}' -> return map
                        else -> error("expected ',' or '}' at ${i - 1}")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                map
            }
            '[' -> {
                i++
                val list = ArrayList<Any?>()
                ws()
                if (s[i] == ']') { i++; return list }
                while (true) {
                    list.add(value())
                    ws()
                    when (s[i++]) {
                        ',' -> continue
                        ']' -> return list
                        else -> error("expected ',' or ']' at ${i - 1}")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                list
            }
            '"' -> string()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> {
                check(c == '-' || c.isDigit()) { "unexpected '$c' at $i" }
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
                s.substring(start, i).toDouble()
            }
        }
    }

    private fun expect(word: String) {
        check(s.startsWith(word, i)) { "expected $word at $i" }
        i += word.length
    }

    private fun string(): String {
        check(s[i] == '"') { "expected string at $i" }
        i++
        val out = StringBuilder()
        while (true) {
            val c = s[i++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> when (val e = s[i++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> { out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> out.append(e)
                }
                else -> {
                    check(c >= ' ') { "raw control character at ${i - 1}" }
                    out.append(c)
                }
            }
        }
    }
}
