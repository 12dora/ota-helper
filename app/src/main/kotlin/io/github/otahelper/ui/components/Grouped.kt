package io.github.otahelper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.otahelper.ui.theme.Dimens

/** Large corners on the outside of a group, small ones between its rows (Android settings look). */
private fun groupedShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) Dimens.CornerOuter else Dimens.CornerInner
    val bottom = if (index == count - 1) Dimens.CornerOuter else Dimens.CornerInner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Row [index] of a rounded group of [count] rows; each row is its own lazy item. */
@Composable
internal fun GroupedSurface(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (index == count - 1) 0.dp else Dimens.RowGap),
        shape = groupedShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = { Column(content = content) },
    )
}

/** Small primary-colored label above a group. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dimens.HeaderTop)
            .heightIn(min = Dimens.HeaderHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical)
            .semantics { heading() },
    )
}

/** A read-only label/value pair. */
@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * A settings row: optional leading [icon], [title], optional [summary], and [content] at the end (a switch,
 * chevron or labels). Clicks and toggles are attached by the caller through [modifier] so the whole row is the
 * touch target.
 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    content: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content?.invoke()
    }
}

/** A static pill label, e.g. which images a backup holds. Not interactive, so not a chip. */
@Composable
internal fun StatusLabel(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = CircleShape, color = container, contentColor = content) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Dimens.ChipHorizontal, vertical = Dimens.ChipVertical),
        )
    }
}

/** Error-tinted inline banner; announced politely when it appears. */
@Composable
internal fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CornerOuter),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = Dimens.Gutter, vertical = Dimens.Gutter)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = AppIcons.Alert, contentDescription = null)
            Text(text = message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
