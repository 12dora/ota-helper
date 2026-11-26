package io.github.otahelper.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.github.otahelper.core.EngineStatus
import io.github.otahelper.core.OtaStatus
import io.github.otahelper.core.Phase
import io.github.otahelper.ui.theme.Dimens
import java.text.NumberFormat

/** What the hero shows; [phase] is the animation key, so text changes within a phase swap in place. */
private data class HeroCopy(val phase: Phase, val title: String, val body: String)

/**
 * The top of the screen: phase badge, large title, one supporting line, install progress while installing,
 * and the 准备 → 安装 → 修补 → 重启 stepper while an update is in flight.
 */
@Composable
internal fun PhaseHero(status: OtaStatus, busy: Boolean, autoPatch: Boolean, modifier: Modifier = Modifier) {
    val phase = status.phase
    val copy = HeroCopy(
        phase = phase,
        title = stringResource(phase.titleRes()),
        body = status.error.takeIf { phase.errorIsBody }
            ?: stringResource(phase.bodyRes(busy, autoPatch)),
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = Dimens.Small, end = Dimens.Small, top = Dimens.HeroTop, bottom = Dimens.HeroBottom),
    ) {
        AnimatedContent(
            targetState = copy,
            contentKey = { it.phase },
            transitionSpec = {
                val enter = tween<Float>(durationMillis = ENTER_MS, delayMillis = EXIT_MS)
                (fadeIn(animationSpec = enter) + scaleIn(animationSpec = enter, initialScale = ENTER_SCALE))
                    .togetherWith(fadeOut(animationSpec = tween(durationMillis = EXIT_MS)))
                    .using(SizeTransform(clip = false))
            },
            label = "hero",
        ) { target ->
            HeroHeadline(target, spinning = busy || target.phase == Phase.LOADING)
        }
        AnimatedVisibility(visible = phase == Phase.INSTALLING, enter = expandIn, exit = shrinkOut) {
            InstallProgress(
                engine = status.engine,
                progress = status.progress ?: 0f,
                modifier = Modifier.padding(top = Dimens.HeroBlockGap),
            )
        }
        val step = phase.step
        AnimatedVisibility(visible = step != null, enter = expandIn, exit = shrinkOut) {
            PhaseStepper(current = step ?: Step.PREPARE, modifier = Modifier.padding(top = Dimens.HeroBlockGap))
        }
    }
}

@Composable
private fun HeroHeadline(copy: HeroCopy, spinning: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        PhaseBadge(copy.phase, spinning)
        Spacer(Modifier.height(Dimens.HeroTitleGap))
        Text(
            text = copy.title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        Spacer(Modifier.height(Dimens.Small))
        Text(
            text = copy.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A tinted circle with the phase glyph; a spinning ring around it while something is running. */
@Composable
private fun PhaseBadge(phase: Phase, spinning: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (phase) {
        Phase.LOADING -> colors.surfaceContainerHighest to colors.onSurfaceVariant
        Phase.NO_ROOT, Phase.UNSUPPORTED, Phase.FAILED -> colors.errorContainer to colors.onErrorContainer
        Phase.PATCHED -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.primaryContainer to colors.onPrimaryContainer
    }
    Box(Modifier.size(Dimens.HeroRing), contentAlignment = Alignment.Center) {
        if (spinning) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = colors.primary,
                strokeWidth = Dimens.RingStroke,
                trackColor = Color.Transparent,
            )
        }
        Surface(modifier = Modifier.size(Dimens.HeroBadge), shape = CircleShape, color = container) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = phase.icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(Dimens.HeroIcon),
                )
            }
        }
    }
}

/** update_engine's stage on the left, percent on the right, and a determinate bar. */
@Composable
private fun InstallProgress(engine: EngineStatus, progress: Float, modifier: Modifier = Modifier) {
    val fraction = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = fraction, label = "install-progress")
    val percent = remember { NumberFormat.getPercentInstance() }.format(fraction.toDouble())
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = stringResource(engine.progressLabel()),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = percent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(Dimens.Small))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.ProgressBar),
        )
    }
}

private const val ENTER_MS = 260
private const val EXIT_MS = 120
private const val ENTER_SCALE = 0.94f
private val expandIn = fadeIn() + expandVertically()
private val shrinkOut = fadeOut() + shrinkVertically()
