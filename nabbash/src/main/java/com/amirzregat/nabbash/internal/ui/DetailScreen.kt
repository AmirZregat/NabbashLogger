package com.amirzregat.nabbash.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amirzregat.nabbash.internal.Core
import com.amirzregat.nabbash.internal.Exporter
import com.amirzregat.nabbash.internal.JsonText
import com.amirzregat.nabbash.internal.LEVEL_NAMES
import com.amirzregat.nabbash.internal.LogEvent
import com.amirzregat.nabbash.internal.REDACTED
import com.amirzregat.nabbash.internal.Stamps
import com.amirzregat.nabbash.internal.formatBytes
import com.amirzregat.nabbash.internal.formatDuration
import com.amirzregat.nabbash.internal.urlHost
import com.amirzregat.nabbash.internal.urlPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longer bodies are shown cut; sharing always sends everything that was stored. */
private const val MAX_SHOWN_CHARS = 40_000

private enum class DetailTab(val title: String) { OVERVIEW("Overview"), REQUEST("Request"), RESPONSE("Response") }

@Composable
internal fun DetailScreen(core: Core, eventId: Long, nav: Nav, share: ShareController) {
    val c = LocalColors.current
    val stamps = remember { Stamps() }
    var event by remember { mutableStateOf<LogEvent?>(null) }
    var missing by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(DetailTab.OVERVIEW) }

    LaunchedEffect(eventId) {
        val found = withContext(Dispatchers.IO) { runCatching { core.store.event(eventId) }.getOrNull() }
        event = found
        missing = found == null
    }

    val e = event
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = when {
                e == null -> ""
                e.isNetwork -> "${e.method.orEmpty()} ${urlPath(e.url.orEmpty())}"
                else -> e.label.orEmpty().ifEmpty { "Log" }
            },
            onBack = nav.pop,
            mono = e?.isNetwork == true,
        ) {
            if (e != null && e.isNetwork) {
                StatusPill(e.status, failed = e.error != null)
                Spacer(Modifier.width(12.dp))
            }
        }
        if (share.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        if (e == null) {
            if (missing) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("This entry is no longer stored.", color = c.textMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
            return@Column
        }

        if (e.isNetwork) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (t in DetailTab.entries) Chip(t.title, tab == t, { tab = t })
            }
            Hairline()
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (!e.isNetwork) {
                messageItems(e, stamps)
            } else {
                when (tab) {
                    DetailTab.OVERVIEW -> overviewItems(e, stamps)
                    DetailTab.REQUEST -> exchangeItems(e.requestHeaders, e.requestBody)
                    DetailTab.RESPONSE -> exchangeItems(e.responseHeaders, e.responseBody)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        BottomBar {
            if (e.isNetwork) {
                BarButton("Copy as cURL", { share.copy("cURL", Exporter.curl(e)) }, Modifier.weight(1f), icon = NIcons.Copy)
            } else {
                BarButton("Copy", { share.copy("Log", e.message.orEmpty()) }, Modifier.weight(1f), icon = NIcons.Copy)
            }
            BarButton("Share", { share.event(e) }, Modifier.weight(1f), primary = true, enabled = !share.busy, icon = NIcons.Share)
        }
    }
}

private fun LazyListScope.overviewItems(e: LogEvent, stamps: Stamps) {
    item { SectionTitle("Summary") }
    item {
        val url = e.url.orEmpty()
        Column {
            KeyValue("Host", urlHost(url).ifEmpty { "—" })
            KeyValue("Status", e.status?.toString() ?: if (e.error != null) "Failed" else "—")
            KeyValue("Duration", e.durationMs?.let(::formatDuration) ?: "—")
            KeyValue("Time", stamps.millis(e.time))
            e.requestSize?.let { KeyValue("Sent", formatBytes(it)) }
            e.responseSize?.let { KeyValue("Received", formatBytes(it)) }
        }
    }
    if (e.error != null) {
        item { SectionTitle("Error") }
        item { CodeBlock(e.error, danger = true) }
    }
    item { SectionTitle("URL") }
    item { CodeBlock(e.url.orEmpty()) }
}

private fun LazyListScope.exchangeItems(headers: List<Pair<String, String>>?, body: String?) {
    item { SectionTitle("Headers") }
    if (headers.isNullOrEmpty()) {
        item { Muted("No headers") }
    } else {
        item {
            Column { for ((name, value) in headers) KeyValue(name, value, mono = true) }
        }
    }
    item { SectionTitle("Body") }
    if (body.isNullOrEmpty()) {
        item { Muted("No body") }
    } else {
        item { CodeBlock(body, pretty = true) }
    }
}

private fun LazyListScope.messageItems(e: LogEvent, stamps: Stamps) {
    item { SectionTitle("Message") }
    item { CodeBlock(e.message.orEmpty(), pretty = true) }
    item { SectionTitle("Details") }
    item {
        Column {
            KeyValue("Label", e.label.orEmpty().ifEmpty { "—" })
            KeyValue("Level", LEVEL_NAMES.getOrElse(e.level) { "debug" })
            KeyValue("Time", stamps.millis(e.time))
        }
    }
    if (e.error != null) {
        item { SectionTitle("Stack trace") }
        item { CodeBlock(e.error, danger = true) }
    }
}

@Composable
private fun KeyValue(name: String, value: String, mono: Boolean = false) {
    val c = LocalColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(0.38f).padding(end = 12.dp),
            color = c.textMuted,
            fontSize = 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
        if (value == REDACTED) {
            // The value was masked before it was stored; the original is not on the device.
            Row(Modifier.weight(0.62f), verticalAlignment = Alignment.CenterVertically) {
                Icon(NIcons.Lock, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("redacted", color = c.textMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic)
            }
        } else {
            SelectionContainer(Modifier.weight(0.62f)) {
                Text(value, fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
            }
        }
    }
}

@Composable
private fun CodeBlock(text: String, pretty: Boolean = false, danger: Boolean = false) {
    val c = LocalColors.current
    val cut = text.length > MAX_SHOWN_CHARS
    val shown = remember(text, pretty) {
        val part = if (cut) text.substring(0, MAX_SHOWN_CHARS) else text
        if (pretty) JsonText.pretty(part) else part
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SelectionContainer {
            Text(
                text = shown,
                modifier = Modifier.fillMaxWidth().background(c.surface1, RoundedCornerShape(8.dp)).padding(12.dp),
                color = if (danger) c.danger else c.textPrimary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (cut) {
            Text(
                "Showing the first ${MAX_SHOWN_CHARS / 1000},000 characters. Share to get all of it.",
                modifier = Modifier.padding(top = 6.dp),
                color = c.textMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = LocalColors.current.textMuted, fontSize = 13.sp)
}
