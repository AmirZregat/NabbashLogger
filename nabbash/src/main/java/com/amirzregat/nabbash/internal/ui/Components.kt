package com.amirzregat.nabbash.internal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    mono: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(NIcons.Back, "Back", onClick = onBack)
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
            fontSize = if (mono) 15.sp else 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
internal fun IconAction(
    icon: ImageVector,
    description: String,
    tint: Color = LocalColors.current.textSecondary,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun TextAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = LocalColors.current.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun Pill(text: String, fg: Color, bg: Color, mono: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
    )
}

/** Green for success, amber for client errors, red for server errors and failed calls. */
@Composable
internal fun StatusPill(status: Int?, failed: Boolean) {
    val c = LocalColors.current
    when {
        status == null -> if (failed) Pill("Failed", c.danger, c.dangerBg) else Pill("—", c.textMuted, c.surface2)
        status >= 500 -> Pill(status.toString(), c.danger, c.dangerBg, mono = true)
        status >= 400 -> Pill(status.toString(), c.warning, c.warningBg, mono = true)
        else -> Pill(status.toString(), c.success, c.successBg, mono = true)
    }
}

@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit, danger: Boolean = false) {
    val c = LocalColors.current
    val fg = when {
        selected -> c.accent
        danger -> c.danger
        else -> c.textSecondary
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 34.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.accentBg else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) c.accent else c.borderStrong), RoundedCornerShape(50))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
        color = LocalColors.current.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalColors.current.border))
}

@Composable
internal fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Hairline()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun BarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = LocalColors.current
    val fg = when {
        !enabled -> c.textMuted
        primary -> c.onAccent
        else -> c.textPrimary
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(if (primary && enabled) c.accent else if (primary) c.surface2 else Color.Transparent)
            .then(if (primary) Modifier else Modifier.border(BorderStroke(1.dp, c.borderStrong), shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
