package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.R

@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = PageGutter + 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

/** Page side margin shared by every tab. */
internal val PageGutter = 16.dp

/** Position of a row inside a grouped card, so stacked lazy items read as one rounded card. */
enum class RowPosition { ONLY, FIRST, MIDDLE, LAST }
internal fun rowPosition(index: Int, count: Int) = when {
    count <= 1 -> RowPosition.ONLY; index == 0 -> RowPosition.FIRST; index == count - 1 -> RowPosition.LAST; else -> RowPosition.MIDDLE
}

/** Section heading above a grouped card (conversations by day, sessions by project, settings groups). */
@Composable
internal fun GroupLabel(title: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 8.dp)) {
        SectionLabel(title)
        if (detail != null) Text(detail, maxLines = 1, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Circular initials badge used for models and accounts. */
@Composable
internal fun Avatar(text: String, accent: Boolean = false) {
    // Rounded-square artwork, like album covers in a library list; the account uses the accent.
    Surface(shape = if (accent) CircleShape else RoundedCornerShape(10.dp),
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.titleSmall) }
    }
}

/** One row of a grouped list card: the single list style for conversations, sessions and settings. */
@Composable
internal fun ListRow(title: String, subtitle: String? = null, position: RowPosition = RowPosition.ONLY, enabled: Boolean = true,
    titleLines: Int = 1, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null, grouped: Boolean = false, onClick: () -> Unit) {
    // Plain rows (Apple Music library lists) by default; inset grouped cards for settings.
    val radius = if (grouped) 12.dp else 0.dp
    val top = if (position == RowPosition.ONLY || position == RowPosition.FIRST) radius else 0.dp
    val bottom = if (position == RowPosition.ONLY || position == RowPosition.LAST) radius else 0.dp
    val first = position == RowPosition.ONLY || position == RowPosition.FIRST
    Surface(onClick, enabled = enabled, shape = RoundedCornerShape(top, top, bottom, bottom),
        color = if (grouped) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()) {
        Column {
            if (!first) HorizontalDivider(Modifier.padding(start = if (leading != null) (if (grouped) 68.dp else 56.dp) else 16.dp),
                thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = if (grouped) 16.dp else 4.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, maxLines = titleLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    extra?.invoke()
                }
                trailing?.invoke()
            }
        }
    }
}

@Composable
internal fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun EmptyState(icon: Int, title: String, message: String? = null,
    actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { AppIcon(icon) }
        }
        Text(title, Modifier.widthIn(max = 400.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message != null) Text(message, Modifier.widthIn(max = 400.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onAction != null && actionLabel != null) FilledTonalButton(onAction, Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
    }
}

@Composable
internal fun LoadingState(label: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StatusNotice(message: String, busy: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ComposerSurface(maxHeight: Dp = 320.dp, actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.heightIn(max = maxHeight).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            // Keep send/stop and creation controls reachable when text, attachments or the IME grow.
            if (actions != null) Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
        }
    }
}

@Composable
internal fun ComposerChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    Surface(onClick, modifier = modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f, false))
            AppIcon(R.drawable.aw_down)
        }
    }
}
