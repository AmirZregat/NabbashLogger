package com.amirzregat.nabbash.internal

/** Who and what an export is about. */
internal class ExportMeta(
    val appName: String,
    val packageName: String,
    val device: String,
    val exportedAt: Long,
)

/**
 * Builds the shared files. Pure Kotlin and streaming: events are written one at a
 * time, so a large session is never held in memory.
 *
 * Everything written here was already redacted when it was stored.
 */
internal object Exporter {

    private const val RULE = "════════════════════════════════════════════════════════════"

    // region text

    fun text(
        out: Appendable,
        meta: ExportMeta,
        sessions: List<SessionRow>,
        stamps: Stamps,
        eventsOf: (sessionId: Long, each: (LogEvent) -> Unit) -> Unit,
    ) {
        out.append("Nabbash log export\n")
        out.append("App:       ${meta.appName} (${meta.packageName})\n")
        out.append("Device:    ${meta.device}\n")
        out.append("Exported:  ${stamps.dateTime(meta.exportedAt)}\n")
        out.append("Sessions:  ${sessions.size}\n")
        out.append("Sensitive values are masked as $REDACTED\n")
        for (session in sessions) {
            out.append('\n').append(RULE).append('\n')
            out.append("Session ${session.id}")
            if (session.isCurrent) out.append(" (current)")
            out.append(" · ${stamps.dateTime(session.startedAt)} – ${stamps.seconds(session.lastEventAt)}\n")
            out.append("App version ${session.appVersion} · ${plural(session.eventCount, "event")}")
            out.append(" · ${plural(session.errorCount, "error")}\n")
            out.append(RULE).append('\n')
            eventsOf(session.id) { event ->
                out.append('\n')
                eventText(out, event, stamps)
            }
        }
    }

    fun eventText(event: LogEvent, stamps: Stamps): String =
        StringBuilder().also { eventText(it, event, stamps) }.toString()

    private fun eventText(out: Appendable, e: LogEvent, stamps: Stamps) {
        out.append('[').append(stamps.millis(e.time)).append("] ")
        if (!e.isNetwork) {
            out.append(LEVEL_NAMES.getOrElse(e.level) { "debug" }.uppercase())
            out.append(' ').append(e.label.orEmpty()).append(": ").append(e.message.orEmpty()).append('\n')
            e.error?.let { indented(out, it, "    ") }
            return
        }
        out.append(e.method.orEmpty()).append(' ').append(e.url.orEmpty()).append(" → ")
        out.append(e.status?.toString() ?: "FAILED")
        val facts = listOfNotNull(e.durationMs?.let(::formatDuration), e.responseSize?.let(::formatBytes))
        if (facts.isNotEmpty()) out.append(" (").append(facts.joinToString(", ")).append(')')
        out.append('\n')
        e.error?.let { out.append("  Error: ").append(it).append('\n') }
        section(out, "Request headers", e.requestHeaders)
        body(out, "Request body", e.requestBody)
        section(out, "Response headers", e.responseHeaders)
        body(out, "Response body", e.responseBody)
    }

    private fun section(out: Appendable, title: String, headers: List<Pair<String, String>>?) {
        if (headers.isNullOrEmpty()) return
        out.append("  ").append(title).append(":\n")
        for ((name, value) in headers) out.append("    ").append(name).append(": ").append(value).append('\n')
    }

    private fun body(out: Appendable, title: String, body: String?) {
        if (body.isNullOrEmpty()) return
        out.append("  ").append(title).append(":\n")
        indented(out, JsonText.pretty(body), "    ")
    }

    private fun indented(out: Appendable, text: String, indent: String) {
        for (line in text.trimEnd().split('\n')) out.append(indent).append(line.trimEnd('\r')).append('\n')
    }

    // endregion

    // region JSON

    fun json(
        out: Appendable,
        meta: ExportMeta,
        sessions: List<SessionRow>,
        stamps: Stamps,
        eventsOf: (sessionId: Long, each: (LogEvent) -> Unit) -> Unit,
    ) {
        out.append("{\n")
        out.append("  \"generator\": \"Nabbash\",\n")
        out.append("  \"formatVersion\": 1,\n")
        out.append("  \"exportedAt\": ").append(JsonText.quote(stamps.iso(meta.exportedAt))).append(",\n")
        out.append("  \"app\": {\"name\": ").append(JsonText.quote(meta.appName))
        out.append(", \"package\": ").append(JsonText.quote(meta.packageName)).append("},\n")
        out.append("  \"device\": ").append(JsonText.quote(meta.device)).append(",\n")
        out.append("  \"redactedValue\": ").append(JsonText.quote(REDACTED)).append(",\n")
        out.append("  \"sessions\": [")
        sessions.forEachIndexed { index, session ->
            out.append(if (index == 0) "\n" else ",\n")
            out.append("    {\n")
            out.append("      \"id\": ${session.id},\n")
            out.append("      \"current\": ${session.isCurrent},\n")
            out.append("      \"startedAt\": ").append(JsonText.quote(stamps.iso(session.startedAt))).append(",\n")
            out.append("      \"lastEventAt\": ").append(JsonText.quote(stamps.iso(session.lastEventAt))).append(",\n")
            out.append("      \"appVersion\": ").append(JsonText.quote(session.appVersion)).append(",\n")
            out.append("      \"events\": [")
            var first = true
            eventsOf(session.id) { event ->
                out.append(if (first) "\n" else ",\n")
                first = false
                out.append("        ")
                eventJson(out, event, stamps)
            }
            out.append(if (first) "]\n" else "\n      ]\n")
            out.append("    }")
        }
        out.append(if (sessions.isEmpty()) "]\n" else "\n  ]\n")
        out.append("}\n")
    }

    private fun eventJson(out: Appendable, e: LogEvent, stamps: Stamps) {
        out.append("{\"type\": ").append(if (e.isNetwork) "\"network\"" else "\"log\"")
        out.append(", \"time\": ").append(JsonText.quote(stamps.iso(e.time)))
        if (e.isNetwork) {
            field(out, "method", e.method)
            field(out, "url", e.url)
            out.append(", \"status\": ").append(e.status?.toString() ?: "null")
            out.append(", \"durationMs\": ").append(e.durationMs?.toString() ?: "null")
            out.append(", \"request\": ")
            exchange(out, e.requestContentType, e.requestHeaders, e.requestBody, e.requestSize)
            out.append(", \"response\": ")
            exchange(out, e.responseContentType, e.responseHeaders, e.responseBody, e.responseSize)
            field(out, "error", e.error)
        } else {
            field(out, "level", LEVEL_NAMES.getOrElse(e.level) { "debug" })
            field(out, "label", e.label)
            field(out, "message", e.message)
            field(out, "stackTrace", e.error)
        }
        out.append('}')
    }

    private fun exchange(
        out: Appendable,
        contentType: String?,
        headers: List<Pair<String, String>>?,
        body: String?,
        size: Long?,
    ) {
        out.append("{\"contentType\": ").append(contentType?.let(JsonText::quote) ?: "null")
        out.append(", \"sizeBytes\": ").append(size?.toString() ?: "null")
        out.append(", \"headers\": [")
        headers.orEmpty().forEachIndexed { i, (name, value) ->
            if (i > 0) out.append(", ")
            out.append("{\"name\": ").append(JsonText.quote(name))
            out.append(", \"value\": ").append(JsonText.quote(value)).append('}')
        }
        out.append("], \"body\": ").append(body?.let(JsonText::quote) ?: "null").append('}')
    }

    private fun field(out: Appendable, name: String, value: String?) {
        out.append(", \"").append(name).append("\": ").append(value?.let(JsonText::quote) ?: "null")
    }

    // endregion

    /** A shell command that repeats the request. Masked values stay masked. */
    fun curl(e: LogEvent): String {
        val out = StringBuilder("curl -X ").append(e.method ?: "GET").append(' ').append(shellQuote(e.url.orEmpty()))
        for ((name, value) in e.requestHeaders.orEmpty()) {
            if (name.equals("Content-Length", ignoreCase = true)) continue
            out.append(" \\\n  -H ").append(shellQuote("$name: $value"))
        }
        val body = e.requestBody
        if (!body.isNullOrEmpty() && !body.startsWith("[body not captured")) {
            out.append(" \\\n  --data-raw ").append(shellQuote(body))
        }
        return out.toString()
    }

    private fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}
