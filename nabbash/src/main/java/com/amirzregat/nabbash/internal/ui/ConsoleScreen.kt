package com.amirzregat.nabbash.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amirzregat.nabbash.internal.Core
import com.amirzregat.nabbash.internal.EventFilter
import com.amirzregat.nabbash.internal.LEVEL_ERROR
import com.amirzregat.nabbash.internal.LEVEL_NAMES
import com.amirzregat.nabbash.internal.LEVEL_WARNING
import com.amirzregat.nabbash.internal.LogEvent
import com.amirzregat.nabbash.internal.SessionRow
import com.amirzregat.nabbash.internal.Stamps
import com.amirzregat.nabbash.internal.formatBytes
import com.amirzregat.nabbash.internal.formatDuration
import com.amirzregat.nabbash.internal.plural
import com.amirzregat.nabbash.internal.urlHost
import com.amirzregat.nabbash.internal.urlPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val LIST_LIMIT = 500

@Composable
internal fun ConsoleScreen(core: Core, sessionId: Long?, isRoot: Boolean, nav: Nav, share: ShareController) {
    val c = LocalColors.current
    val stamps = remember { Stamps() }
    var filter by rememberSaveable { mutableStateOf(EventFilter.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var events by remember { mutableStateOf<List<LogEvent>>(emptyList()) }
    var session by remember { mutableStateOf<SessionRow?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId, filter, query) {
        // Re-reads after every write, at most a few times a second.
        core.store.version.collect {
            val id = sessionId ?: core.sessionId
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    core.store.sessions(core.sessionId).firstOrNull { it.id == id } to
                        core.store.events(id, filter, query, LIST_LIMIT)
                }.getOrNull()
            }
            if (result != null) {
                session = result.first
                events = result.second
            }
            loaded = true
            delay(250)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = if (isRoot) "Nabbash" else "Session", onBack = if (isRoot) null else nav.pop) {
            IconAction(if (searching) NIcons.Close else NIcons.Search, if (searching) "Close search" else "Search") {
                searching = !searching
                if (!searching) query = ""
            }
            Box {
                IconAction(NIcons.Share, "Share this session") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Share session as text (.txt)") },
                        onClick = {
                            menuOpen = false
                            session?.let { share.sessions(listOf(it), asJson = false) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share session as JSON (.json)") },
                        onClick = {
                            menuOpen = false
                            session?.let { share.sessions(listOf(it), asJson = true) }
                        },
                    )
                    if (isRoot) {
                        DropdownMenuItem(
                            text = { Text("Choose sessions…") },
                            onClick = {
                                menuOpen = false
                                nav.push(Screen.Sessions)
                            },
                        )
                    }
                }
            }
            if (isRoot) IconAction(NIcons.Settings, "Settings") { nav.push(Screen.Settings) }
        }
        if (share.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        if (searching) SearchField(query) { query = it }

        SessionStrip(session, stamps, onClick = if (isRoot) ({ nav.push(Screen.Sessions) }) else null)

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("All", filter == EventFilter.ALL, { filter = EventFilter.ALL })
            Chip("Network", filter == EventFilter.NETWORK, { filter = EventFilter.NETWORK })
            Chip("Logs", filter == EventFilter.LOGS, { filter = EventFilter.LOGS })
            val errors = session?.errorCount ?: 0
            Chip(
                text = if (errors > 0) "Errors $errors" else "Errors",
                selected = filter == EventFilter.ERRORS,
                onClick = { filter = EventFilter.ERRORS },
                danger = errors > 0,
            )
        }
        Hairline()

        if (events.isEmpty()) {
            EmptyState(
                loaded = loaded,
                filtered = filter != EventFilter.ALL || query.isNotBlank(),
                current = session?.isCurrent ?: (sessionId == null),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { event ->
                    EventRow(event, stamps) { nav.push(Screen.Detail(event.id)) }
                    Hairline(Modifier.padding(start = 16.dp))
                }
                if (events.size >= LIST_LIMIT) {
                    item {
                        Text(
                            "Showing the newest $LIST_LIMIT entries. Search to narrow down, or share the session to get all of it.",
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = c.textMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val c = LocalColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(c.surface1, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NIcons.Search, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).defaultMinSize(minHeight = 44.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("URL, status, message or label", color = c.textMuted, fontSize = 14.sp)
            BasicTextField(
                value = query,
                onValueChange = { onChange(it.take(200)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                textStyle = TextStyle(color = c.textPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
    }
}

@Composable
private fun SessionStrip(session: SessionRow?, stamps: Stamps, onClick: (() -> Unit)?) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "All sessions", onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (session == null || session.isCurrent) {
            Pill("Current session", c.success, c.successBg)
        } else {
            Pill("Earlier session", c.textSecondary, c.surface2)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = when {
                session == null -> "starting…"
                session.isCurrent -> "started ${stamps.minutes(session.startedAt)} · ${plural(session.eventCount, "event")}"
                else -> "${stamps.dayLabel(session.startedAt)}, ${stamps.minutes(session.startedAt)} – " +
                    "${stamps.minutes(session.lastEventAt)} · ${plural(session.eventCount, "event")}"
            },
            modifier = Modifier.weight(1f),
            color = c.textMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) Icon(NIcons.Chevron, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EventRow(event: LogEvent, stamps: Stamps, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (event.isNetwork) {
            val url = event.url.orEmpty()
            Text(
                text = event.method.orEmpty(),
                modifier = Modifier.width(54.dp),
                color = c.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Column(Modifier.weight(1f)) {
                Text(urlPath(url), fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(
                    urlHost(url).ifEmpty { null },
                    event.durationMs?.let(::formatDuration),
                    event.responseSize?.let(::formatBytes),
                )
                Text(facts.joinToString(" · "), color = c.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            StatusPill(event.status, failed = event.error != null)
        } else {
            val error = event.level >= LEVEL_ERROR
            val warning = event.level == LEVEL_WARNING
            Box(Modifier.width(54.dp)) {
                Icon(
                    imageVector = if (error || warning) NIcons.Warning else NIcons.Message,
                    contentDescription = if (error) "Error" else if (warning) "Warning" else null,
                    tint = if (error) c.danger else if (warning) c.warning else c.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = event.message.orEmpty(),
                    color = if (error) c.danger else c.textPrimary,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${event.label.orEmpty()} · ${LEVEL_NAMES.getOrElse(event.level) { "debug" }} · ${stamps.seconds(event.time)}",
                    color = c.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(loaded: Boolean, filtered: Boolean, current: Boolean) {
    val c = LocalColors.current
    if (!loaded) return
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (filtered) "Nothing matches" else "Nothing logged yet",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                filtered -> "Try another filter or search term."
                current -> "Add NabbashInterceptor() to your OkHttp client or call Nabbash.log(\"…\"), then use the app."
                else -> "This session has no entries."
            },
            color = c.textMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}
