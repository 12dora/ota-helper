package io.github.otahelper.ui.home

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.otahelper.R
import io.github.otahelper.core.BootImageState
import io.github.otahelper.core.EngineStatus
import io.github.otahelper.core.Phase
import io.github.otahelper.ui.components.AppIcons

/** The single primary button of each phase. */
internal enum class PrimaryAction(@param:StringRes val label: Int) {
    PREPARE(R.string.action_prepare),
    OPEN_UPDATER(R.string.action_open_updater),
    PATCH(R.string.action_patch),
    REBOOT(R.string.action_reboot),
    RETRY(R.string.action_retry),
}

/** The four user-visible steps of an update: 准备 → 安装 → 修补 → 重启. */
internal enum class Step(@param:StringRes val label: Int) {
    PREPARE(R.string.step_prepare),
    INSTALL(R.string.step_install),
    PATCH(R.string.step_patch),
    RESTART(R.string.step_restart),
}

internal val Phase.primaryAction: PrimaryAction?
    get() = when (this) {
        Phase.IDLE -> PrimaryAction.PREPARE
        Phase.PREPARED -> PrimaryAction.OPEN_UPDATER
        Phase.INSTALLED -> PrimaryAction.PATCH
        Phase.PATCHED -> PrimaryAction.REBOOT
        Phase.NO_ROOT, Phase.UNSUPPORTED -> PrimaryAction.RETRY
        Phase.LOADING, Phase.INSTALLING -> null
    }

/** The step the user is on, or null outside the update flow (loading and error states). */
internal val Phase.step: Step?
    get() = when (this) {
        Phase.IDLE -> Step.PREPARE
        Phase.PREPARED, Phase.INSTALLING -> Step.INSTALL
        Phase.INSTALLED -> Step.PATCH
        Phase.PATCHED -> Step.RESTART
        Phase.LOADING, Phase.NO_ROOT, Phase.UNSUPPORTED -> null
    }

internal val Phase.icon: ImageVector
    get() = when (this) {
        Phase.LOADING, Phase.PREPARED, Phase.INSTALLING -> AppIcons.SystemUpdate
        Phase.NO_ROOT -> AppIcons.Lock
        Phase.UNSUPPORTED -> AppIcons.ShieldAlert
        Phase.IDLE -> AppIcons.ShieldCheck
        Phase.INSTALLED -> AppIcons.ShieldPlus
        Phase.PATCHED -> AppIcons.Restart
    }

@StringRes
internal fun Phase.titleRes(): Int = when (this) {
    Phase.LOADING -> R.string.phase_loading_title
    Phase.NO_ROOT -> R.string.phase_no_root_title
    Phase.UNSUPPORTED -> R.string.phase_unsupported_title
    Phase.IDLE -> R.string.phase_idle_title
    Phase.PREPARED -> R.string.phase_prepared_title
    Phase.INSTALLING -> R.string.phase_installing_title
    Phase.INSTALLED -> R.string.phase_installed_title
    Phase.PATCHED -> R.string.phase_patched_title
}

/** One short supporting line: what happens now, or what the user should do next. */
@StringRes
internal fun Phase.bodyRes(busy: Boolean, autoPatch: Boolean): Int = when (this) {
    Phase.LOADING -> R.string.phase_loading_body
    Phase.NO_ROOT -> R.string.phase_no_root_body
    Phase.UNSUPPORTED -> R.string.phase_unsupported_body
    Phase.IDLE -> if (busy) R.string.phase_idle_busy else R.string.phase_idle_body
    Phase.PREPARED -> if (busy) R.string.phase_prepared_busy else R.string.phase_prepared_body
    Phase.INSTALLING -> if (autoPatch) R.string.phase_installing_auto else R.string.phase_installing_manual
    Phase.INSTALLED -> if (busy) R.string.phase_installed_busy else R.string.phase_installed_body
    Phase.PATCHED -> if (busy) R.string.phase_patched_busy else R.string.phase_patched_body
}

@StringRes
internal fun EngineStatus.progressLabel(): Int = when (this) {
    EngineStatus.DOWNLOADING -> R.string.engine_downloading
    EngineStatus.VERIFYING -> R.string.engine_verifying
    EngineStatus.FINALIZING -> R.string.engine_finalizing
    else -> R.string.engine_installing
}

@StringRes
internal fun BootImageState.labelRes(): Int = when (this) {
    BootImageState.PATCHED -> R.string.image_patched
    BootImageState.STOCK -> R.string.image_stock
    BootImageState.UNKNOWN -> R.string.value_unknown
}
