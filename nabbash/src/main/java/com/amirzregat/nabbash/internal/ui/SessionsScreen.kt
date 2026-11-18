package com.amirzregat.nabbash.internal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amirzregat.nabbash.internal.Core
import com.amirzregat.nabbash.internal.SessionRow
import com.amirzregat.nabbash.internal.Stamps
import com.amirzregat.nabbash.internal.formatBytes
import com.amirzregat.nabbash.internal.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val HOUR_MS = 60L * 60L * 1000L
private const val DAY_MS = 24L * HOUR_MS

private val IdSetSaver = Saver<Set<Long>, LongArray>(save = { it.toLongArray() }, restore = { it.toSet() })

/** "7 days", "12 hours": how a retention period is written everywhere in the console. */
internal fun spanLabel(ms: Long): String = when {
    ms >= DAY_MS && ms % DAY_MS == 0L -> plural((ms / DAY_MS).toInt(), "day")
    ms >= 2 * DAY_MS -> plural((ms / DAY_MS).toInt(), "day")
    else -> plural((ms / HOUR_MS).toInt().coerceAtLeast(1), "hour")
}

@Composable
internal fun SessionsScreen(core: Core, nav: Nav, share: ShareController) {
    val c = LocalColors.current
    val stamps = remember { Stamps() }
    var sessions by remember { mutableStateOf<List<SessionRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(emptySet<Long>()) }
    var preselected by rememberSaveable { mutableStateOf(false) }
    val retentionMs = core.effective.retentionMs

    LaunchedEffect(Unit) {
        core.store.version.collect {
            val rows = withContext(Dispatchers.IO) { runCatching { core.store.sessions(core.sessionId) }.getOrNull() }
            if (rows != null) {
                sessions = rows
                if (!preselected && rows.isNotEmpty()) {
                    // The session being recorded is what people share most often.
                    preselected = true
                    selected = rows.filter { it.isCurrent }.mapTo(HashSet()) { it.id }
                } else {
                    val ids = rows.mapTo(HashSet()) { it.id }
                    selected = selected.filterTo(HashSet()) { it in ids }
                }
            }
            loaded = true
            delay(500)
        }
    }

    val allSelected = sessions.isNotEmpty() && selected.size == sessions.size
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Sessions", onBack = nav.pop) {
            if (sessions.isNotEmpty()) {
                TextAction(if (allSelected) "Select none" else "Select all") {
                    selected = if (allSelected) emptySet() else sessions.mapTo(HashSet()) { it.id }
                }
            }
        }
        if (share.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        val now = System.currentTimeMillis()
        val today = stamps.day(now)
        val yesterday = stamps.day(now - DAY_MS)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            var lastDay: String? = null
            for (session in sessions) {
                val day = stamps.day(session.startedAt)
                if (day != lastDay) {
                    lastDay = day
                    val title = when (day) {
                        today -> "Today"
                        yesterday -> "Yesterday"
                        else -> stamps.dayLabel(session.startedAt)
                    }
                    item(key = "day:$day") { SectionTitle(title) }
                }
                item(key = session.id) {
                    SessionItem(
                        session = session,
                        stamps = stamps,
                        checked = session.id in selected,
                        expiresInMs = if (session.isCurrent) null else session.lastEventAt + retentionMs - now,
                        onToggle = { on -> selected = if (on) selected + session.id else selected - session.id },
                        onOpen = { nav.push(Screen.Console(session.id)) },
                    )
                    Hairline(Modifier.padding(start = 16.dp))
                }
            }
            if (loaded) {
                item(key = "footer") {
                    Text(
                        text = "Sessions older than ${spanLabel(retentionMs)} are deleted automatically.",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = c.textMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        BottomBar {
            val count = selected.size
            val picked = { sessions.filter { it.id in selected } }
            BarButton(
                text = if (count == 0) "Share as text" else "Share $count as text",
                onClick = { share.sessions(picked(), asJson = false) },
                modifier = Modifier.weight(1.4f),
                primary = true,
                enabled = count > 0 && !share.busy,
                icon = NIcons.Share,
            )
            BarButton(
                text = "As JSON",
                onClick = { share.sessions(picked(), asJson = true) },
                modifier = Modifier.weight(1f),
                enabled = count > 0 && !share.busy,
            )
        }
    }
}

@Composable
private fun SessionItem(
    session: SessionRow,
    stamps: Stamps,
    checked: Boolean,
    expiresInMs: Long?,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val c = LocalColors.current
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
                .defaultMinSize(minHeight = 64.dp)
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${stamps.minutes(session.startedAt)} – " +
                            if (session.isCurrent) "now" else stamps.minutes(session.lastEventAt),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (session.isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        Pill("Current", c.success, c.successBg)
                    }
                }
                val facts = buildList {
                    add(plural(session.eventCount, "event"))
                    if (session.errorCount > 0) add(plural(session.errorCount, "error"))
                    add(formatBytes(session.sizeBytes))
                    // Only worth saying once the end is near.
                    if (expiresInMs != null && expiresInMs < 3 * DAY_MS) {
                        add(
                            when {
                                expiresInMs < HOUR_MS -> "deleted within an hour"
                                expiresInMs < 2 * DAY_MS -> "deleted in ${plural((expiresInMs / HOUR_MS).toInt(), "hour")}"
                                else -> "deleted in ${plural((expiresInMs / DAY_MS).toInt(), "day")}"
                            },
                        )
                    }
                }
                Text(facts.joinToString(" · "), color = c.textMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconAction(NIcons.Chevron, "Open this session", tint = c.textMuted, onClick = onOpen)
        Spacer(Modifier.width(4.dp))
    }
}
