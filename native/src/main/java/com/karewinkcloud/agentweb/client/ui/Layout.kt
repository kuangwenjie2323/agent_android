package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
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
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f, false))
            AppIcon(R.drawable.aw_down)
        }
    }
}
