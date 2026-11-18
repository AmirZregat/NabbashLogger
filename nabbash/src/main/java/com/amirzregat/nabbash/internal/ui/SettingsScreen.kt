package com.amirzregat.nabbash.internal.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amirzregat.nabbash.internal.Core
import com.amirzregat.nabbash.internal.SessionRow
import com.amirzregat.nabbash.internal.formatBytes
import com.amirzregat.nabbash.internal.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val HOUR = 60L * 60L * 1000L
private const val DAY = 24L * HOUR
private val RETENTION_PRESETS = listOf(1 * DAY, 3 * DAY, 7 * DAY, 30 * DAY)
private val STORAGE_PRESETS_MB = listOf(10, 25, 50, 100, 250)

/** A change that deletes stored sessions as soon as it is applied, held until confirmed. */
private class PendingChange(val title: String, val text: String, val apply: () -> Unit)

private enum class Redaction { HEADERS, FIELDS }

@Composable
internal fun SettingsScreen(core: Core, nav: Nav) {
    val c = LocalColors.current
    val view = LocalView.current
    var eff by remember { mutableStateOf(core.effective) }
    var redactHeaders by remember { mutableStateOf(core.settings.redactHeaders) }
    var redactFields by remember { mutableStateOf(core.settings.redactFields) }
    var sessions by remember { mutableStateOf<List<SessionRow>>(emptyList()) }
    var usedBytes by remember { mutableStateOf(0L) }

    var customOpen by remember { mutableStateOf(false) }
    var storageOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingChange?>(null) }
    var redactionOff by remember { mutableStateOf<Redaction?>(null) }

    LaunchedEffect(Unit) {
        core.store.version.collect {
            val result = withContext(Dispatchers.IO) {
                runCatching { core.store.sessions(core.sessionId) to core.store.totalBytes() }.getOrNull()
            }
            if (result != null) {
                sessions = result.first
                usedBytes = result.second
            }
            delay(500)
        }
    }

    fun changed() {
        core.configChanged()
        eff = core.effective
    }

    fun setRetention(ms: Long) {
        val cutoff = System.currentTimeMillis() - ms
        val doomed = sessions.count { !it.isCurrent && it.lastEventAt < cutoff }
        val apply = {
            core.settings.retentionMs = ms
            changed()
        }
        if (doomed == 0) {
            apply()
        } else {
            pending = PendingChange(
                title = "Delete ${plural(doomed, "older session")}?",
                text = "Keeping sessions for ${spanLabel(ms)} deletes ${plural(doomed, "session")} now. This can't be undone.",
                apply = apply,
            )
        }
    }

    fun setStorage(mb: Int) {
        val apply = {
            core.settings.maxStorageMb = mb
            changed()
        }
        if (usedBytes <= mb * 1024L * 1024L) {
            apply()
        } else {
            pending = PendingChange(
                title = "Delete the oldest sessions?",
                text = "The logs use ${formatBytes(usedBytes)}. With a $mb MB limit the oldest sessions are deleted now until " +
                    "the rest fits. This can't be undone.",
                apply = apply,
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Settings", onBack = nav.pop)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            SectionTitle("Keep sessions for")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in RETENTION_PRESETS) {
                    Chip(spanLabel(preset), eff.retentionMs == preset, { if (eff.retentionMs != preset) setRetention(preset) })
                }
                val custom = eff.retentionMs !in RETENTION_PRESETS
                Chip(if (custom) "Custom: ${spanLabel(eff.retentionMs)}" else "Custom", custom, { customOpen = true })
            }
            Note("Older sessions are deleted automatically. The session being recorded is never deleted.")

            SectionTitle("Storage")
            ValueRow("Storage limit", "${eff.maxStorageMb} MB", onClick = { storageOpen = true })
            ValueRow("Used now", "${formatBytes(usedBytes)} · ${plural(sessions.size, "session")}")

            SectionTitle("Privacy")
            ToggleRow(
                title = "Redact sensitive headers",
                subtitle = "Authorization, Cookie, Set-Cookie, X-Api-Key and similar",
                checked = redactHeaders,
                onChange = { on ->
                    if (on) {
                        core.settings.redactHeaders = true
                        redactHeaders = true
                        changed()
                    } else {
                        redactionOff = Redaction.HEADERS
                    }
                },
            )
            ToggleRow(
                title = "Redact body fields",
                subtitle = "password, token, pin, otp and similar, in bodies and URLs",
                checked = redactFields,
                onChange = { on ->
                    if (on) {
                        core.settings.redactFields = true
                        redactFields = true
                        changed()
                    } else {
                        redactionOff = Redaction.FIELDS
                    }
                },
            )

            SectionTitle("Console")
            ToggleRow(
                title = "Shake to open",
                subtitle = if (eff.launcherShortcut) "Also in the launcher shortcut menu" else "Shake the device to open this console",
                checked = eff.shakeToOpen,
                onChange = { on ->
                    core.settings.shakeToOpen = on
                    changed()
                },
            )

            Spacer(Modifier.height(12.dp))
            Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { clearOpen = true }
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(NIcons.Trash, contentDescription = null, tint = c.danger, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Clear all sessions", color = c.danger, fontSize = 15.sp)
            }
            Hairline()

            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(NIcons.Shield, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Logs stay in this app's private storage and leave the device only when you share them.",
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }

    if (customOpen) {
        CustomRetentionDialog(
            currentMs = eff.retentionMs,
            onDismiss = { customOpen = false },
            onConfirm = { ms ->
                customOpen = false
                setRetention(ms)
            },
        )
    }

    if (storageOpen) {
        AlertDialog(
            onDismissRequest = { storageOpen = false },
            title = { Text("Storage limit") },
            text = {
                Column {
                    for (mb in STORAGE_PRESETS_MB) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = eff.maxStorageMb == mb, role = Role.RadioButton) {
                                    storageOpen = false
                                    if (eff.maxStorageMb != mb) setStorage(mb)
                                }
                                .defaultMinSize(minHeight = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = eff.maxStorageMb == mb, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text("$mb MB")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("When the limit is reached, the oldest sessions are deleted first.", color = c.textMuted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { storageOpen = false }) { Text("Cancel") } },
        )
    }

    pending?.let { change ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(change.title) },
            text = { Text(change.text) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    change.apply()
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    redactionOff?.let { which ->
        AlertDialog(
            onDismissRequest = { redactionOff = null },
            title = { Text("Store these values as sent?") },
            text = {
                Text(
                    if (which == Redaction.HEADERS) {
                        "New entries will keep tokens, cookies and API keys from headers. Anyone you share the logs with " +
                            "will see them. Entries already stored stay redacted."
                    } else {
                        "New entries will keep passwords, tokens, PINs and one-time codes from bodies and URLs. Anyone you " +
                            "share the logs with will see them. Entries already stored stay redacted."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    redactionOff = null
                    if (which == Redaction.HEADERS) {
                        core.settings.redactHeaders = false
                        redactHeaders = false
                    } else {
                        core.settings.redactFields = false
                        redactFields = false
                    }
                    changed()
                }) { Text("Stop redacting", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { redactionOff = null }) { Text("Keep redacting") } },
        )
    }

    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("Delete all sessions?") },
            text = { Text("Every stored session and everything logged so far in this one is deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    clearOpen = false
                    core.clearAll {
                        view.post { Toast.makeText(view.context, "All sessions deleted", Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("Delete all", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CustomRetentionDialog(currentMs: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val c = LocalColors.current
    val startInDays = currentMs % DAY == 0L
    var days by remember { mutableStateOf(startInDays) }
    var number by remember { mutableStateOf((if (startInDays) currentMs / DAY else (currentMs / HOUR).coerceAtLeast(1)).toString()) }
    val value = number.toLongOrNull()
    val ms = value?.let { it * if (days) DAY else HOUR }
    val valid = ms != null && ms >= HOUR && ms <= 365 * DAY

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep sessions for") },
        text = {
            Column {
                OutlinedTextField(
                    value = number,
                    onValueChange = { text -> number = text.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Hours", !days, { days = false })
                    Chip("Days", days, { days = true })
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Between 1 hour and 365 days.",
                    color = if (valid) c.textMuted else c.danger,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (ms != null && valid) onConfirm(ms) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ValueRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Text(value, color = c.textMuted, fontSize = 14.sp)
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(NIcons.Chevron, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, color = c.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        color = LocalColors.current.textMuted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}
