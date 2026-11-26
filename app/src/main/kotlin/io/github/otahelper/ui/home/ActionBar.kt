package io.github.otahelper.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.otahelper.core.Phase
import io.github.otahelper.ui.UiAction
import io.github.otahelper.ui.theme.Dimens

/** What the bar shows; the animation key. */
private data class BarContent(val primary: PrimaryAction, val secondary: SecondaryAction?)

/**
 * Bottom bar with the phase's single primary button, plus 撤销 while prepared or 查看日志 after a failure.
 * Hidden while loading and installing. While [busy] every button is disabled and the one that is running shows
 * an inline spinner.
 */
@Composable
internal fun ActionBar(
    phase: Phase,
    busy: Boolean,
    refreshing: Boolean,
    running: UiAction?,
    onPrimary: (PrimaryAction) -> Unit,
    onUndo: () -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = phase.primaryAction?.let { BarContent(it, phase.secondaryAction) },
        modifier = modifier,
        transitionSpec = { (fadeIn() togetherWith fadeOut()).using(SizeTransform(clip = false)) },
        label = "action-bar",
    ) { content ->
        if (content == null) {
            Spacer(Modifier.navigationBarsPadding())
        } else {
            val onSecondary = if (content.secondary == SecondaryAction.LOG) onOpenLog else onUndo
            ActionRow(content, busy, refreshing, running, onPrimary, onSecondary)
        }
    }
}

@Composable
private fun ActionRow(
    content: BarContent,
    busy: Boolean,
    refreshing: Boolean,
    running: UiAction?,
    onPrimary: (PrimaryAction) -> Unit,
    onSecondary: () -> Unit,
) {
    val action = content.primary
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Dimens.Gutter, vertical = Dimens.BarVertical),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val secondary = content.secondary
            if (secondary != null) {
                TextButton(
                    onClick = onSecondary,
                    // The log stays readable while a retry runs.
                    enabled = !busy || secondary == SecondaryAction.LOG,
                    modifier = Modifier.heightIn(min = Dimens.ButtonHeight),
                ) {
                    ButtonLabel(
                        secondary.label,
                        loading = secondary == SecondaryAction.UNDO && running == UiAction.CANCEL,
                    )
                }
            }
            val loading = when (action) {
                PrimaryAction.RETRY -> refreshing
                PrimaryAction.OPEN_UPDATER -> false
                else -> busy && running != UiAction.CANCEL
            }
            Button(
                onClick = { onPrimary(action) },
                enabled = !busy && !(action == PrimaryAction.RETRY && refreshing),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.ButtonHeight),
            ) {
                ButtonLabel(action.label, loading = loading)
            }
        }
    }
}

@Composable
private fun RowScope.ButtonLabel(@StringRes text: Int, loading: Boolean) {
    AnimatedVisibility(
        visible = loading,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally(),
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .padding(end = Dimens.Small)
                .size(Dimens.ButtonSpinner),
            color = LocalContentColor.current,
            strokeWidth = Dimens.ButtonSpinnerStroke,
        )
    }
    Text(text = stringResource(text), style = MaterialTheme.typography.labelLarge)
}
