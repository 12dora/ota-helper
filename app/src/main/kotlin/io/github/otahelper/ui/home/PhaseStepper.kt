package io.github.otahelper.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import io.github.otahelper.R
import io.github.otahelper.ui.components.AppIcons
import io.github.otahelper.ui.theme.Dimens

private enum class StepState { DONE, CURRENT, UPCOMING }

/** Four segments with labels; finished steps get a check, the current one is emphasized. */
@Composable
internal fun PhaseStepper(current: Step, modifier: Modifier = Modifier) {
    val steps = Step.entries
    val description = stringResource(
        R.string.step_description,
        current.ordinal + 1,
        steps.size,
        stringResource(current.label),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Dimens.StepGap),
    ) {
        steps.forEach { step ->
            val state = when {
                step.ordinal < current.ordinal -> StepState.DONE
                step == current -> StepState.CURRENT
                else -> StepState.UPCOMING
            }
            StepItem(step, state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StepItem(step: Step, state: StepState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val bar by animateColorAsState(
        targetValue = if (state == StepState.UPCOMING) colors.surfaceContainerHighest else colors.primary,
        label = "step-bar",
    )
    val text by animateColorAsState(
        targetValue = when (state) {
            StepState.DONE -> colors.onSurface
            StepState.CURRENT -> colors.primary
            StepState.UPCOMING -> colors.onSurfaceVariant
        },
        label = "step-label",
    )
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dimens.StepBar)
                .clip(CircleShape)
                .background(bar),
        )
        Spacer(Modifier.height(Dimens.Small))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = state == StepState.DONE,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier
                        .padding(end = Dimens.Tiny)
                        .size(Dimens.StepCheck),
                )
            }
            Text(
                text = stringResource(step.label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (state == StepState.CURRENT) FontWeight.SemiBold else FontWeight.Normal,
                color = text,
                maxLines = 1,
            )
        }
    }
}
