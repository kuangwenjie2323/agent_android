package com.karewinkcloud.agentweb.client.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S

/*
 * Apple Music's settings and pickers are inset grouped lists: a grey footnote above each card, rows in one
 * rounded card split by inset hairlines, a red check on the chosen row, short choices as a segmented
 * control, and a sheet title bar with a centred title and a red "Done".
 */

@Composable private fun dark() = MaterialTheme.colorScheme.surface.luminance() < .5f

/** The sheet behind the cards: black in dark mode, Apple's light grey otherwise. */
@Composable internal fun groupedPageColor() = if (dark()) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer

/** The cards on that page. */
@Composable internal fun groupedCardColor() = if (dark()) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface

/** Centred title and a red "Done", like an Apple Music sheet's navigation bar. */
@Composable
internal fun SheetHeader(title: String, onDone: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp)) {
        Text(title, Modifier.align(Alignment.Center).padding(horizontal = 72.dp).semantics { heading() }, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        TextButton(onDone, Modifier.align(Alignment.CenterEnd).heightIn(min = 48.dp)) {
            Text(tr(S.done), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

/** The grey footnote-sized title above a card or a control. */
@Composable
internal fun GroupLabel(text: String) = Text(text, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp).semantics { heading() },
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A grey footnote-sized title, the card, and an optional grey footnote under it. */
@Composable
internal fun GroupedSection(header: String?, modifier: Modifier = Modifier, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        if (header != null) GroupLabel(header)
        Surface(color = groupedCardColor(), shape = RoundedCornerShape(12.dp)) { Column(Modifier.fillMaxWidth(), content = content) }
        if (footer != null) Text(footer, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The hairline between two rows of a card, inset from the leading edge as on iOS. */
@Composable
internal fun RowDivider(start: Dp = 16.dp) = HorizontalDivider(Modifier.padding(start = start), thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.outlineVariant)

/** One choice in a card: title, optional grey subtitle, and a red check when it is the chosen one. */
@Composable
internal fun CheckRow(title: String, selected: Boolean, enabled: Boolean = true, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(painterResource(R.drawable.aw_check), null, Modifier.padding(start = 12.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.primary)
    }
}

/** Title on the left; the current value in grey and a chevron on the right. */
@Composable
internal fun ValueRow(title: String, value: String, enabled: Boolean = true, chevron: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chevron) Icon(painterResource(R.drawable.aw_chevron), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * A value chosen from a short list in place: the row opens a menu at the row with a check on the current
 * value (Apple's pop-up button), instead of a dialog.
 */
@Composable
internal fun MenuValueRow(title: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ValueRow(title, options.find { it.first == value }?.second?.substringBefore('\n') ?: value.ifBlank { tr(S.select_value) },
            enabled, chevron = true) { open = true }
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.TopEnd), shape = RoundedCornerShape(12.dp),
            containerColor = groupedCardColor()) {
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label.substringBefore('\n'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onChange(key); open = false },
                    leadingIcon = { Box(Modifier.size(20.dp)) { if (key == value) Icon(painterResource(R.drawable.aw_check), null,
                        Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) } })
            }
        }
    }
}

/** iOS segmented control: a grey track with the chosen segment raised on it, sliding when it changes. */
@Composable
internal fun SegmentedControl(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    if (options.isEmpty()) return
    val dark = dark()
    val track = if (dark) Color(0xff1c1c1e) else Color(0xffe9e9eb)
    val knob = if (dark) Color(0xff636366) else Color.White
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    BoxWithConstraints(modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(9.dp)).background(track).padding(2.dp)) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * index, spring(dampingRatio = .85f, stiffness = 600f), label = "segment")
        Box(Modifier.offset(x = offset).width(segment).fillMaxHeight().shadow(if (dark) 0.dp else 2.dp, RoundedCornerShape(7.dp))
            .clip(RoundedCornerShape(7.dp)).background(knob))
        // Separators show only between two unselected segments, as on iOS; drawn over the track so they
        // take no width from the segments the knob lines up with.
        for (i in 1 until options.size) if (i != index && i != index + 1)
            Box(Modifier.offset(x = segment * i).width(1.dp).fillMaxHeight().padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.outlineVariant))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (key, label) ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                    .selectable(i == index, role = Role.Tab) { onSelect(key) }, contentAlignment = Alignment.Center) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                        fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** iOS slider: a thin track filled in the accent colour and a round white knob with a soft shadow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppleSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest, activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent)
    Slider(value, onValueChange, modifier, colors = colors,
        thumb = { Box(Modifier.size(26.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White)) },
        track = { state -> SliderDefaults.Track(sliderState = state, modifier = Modifier.height(4.dp), colors = colors,
            drawStopIndicator = null, thumbTrackGapSize = 0.dp) })
}
