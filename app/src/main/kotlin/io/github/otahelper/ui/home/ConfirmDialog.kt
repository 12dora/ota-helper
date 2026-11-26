package io.github.otahelper.ui.home

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.otahelper.R
import io.github.otahelper.ui.UiAction
import io.github.otahelper.ui.components.AppIcons

private class ConfirmCopy(
    val icon: ImageVector,
    @param:StringRes val title: Int,
    @param:StringRes val text: Int,
    @param:StringRes val confirm: Int,
)

/** Every action flashes or restarts, so each asks first in one sentence. */
private fun UiAction.confirmCopy(): ConfirmCopy = when (this) {
    UiAction.PREPARE -> ConfirmCopy(
        icon = AppIcons.SystemUpdate,
        title = R.string.confirm_prepare_title,
        text = R.string.confirm_prepare_text,
        confirm = R.string.action_continue,
    )

    UiAction.CANCEL -> ConfirmCopy(
        icon = AppIcons.ShieldCheck,
        title = R.string.confirm_cancel_title,
        text = R.string.confirm_cancel_text,
        confirm = R.string.action_undo,
    )

    UiAction.REBOOT -> ConfirmCopy(
        icon = AppIcons.Restart,
        title = R.string.confirm_reboot_title,
        text = R.string.confirm_reboot_text,
        confirm = R.string.action_restart,
    )

    UiAction.PATCH -> ConfirmCopy(
        icon = AppIcons.ShieldPlus,
        title = R.string.confirm_patch_title,
        text = R.string.confirm_patch_text,
        confirm = R.string.action_patch_short,
    )
}

/** Asks before [action]; calls [onConfirm] or [onDismiss] (the caller clears its dialog state in both). */
@Composable
internal fun ConfirmDialog(action: UiAction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val copy = action.confirmCopy()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = copy.icon, contentDescription = null) },
        title = { Text(stringResource(copy.title)) },
        text = { Text(stringResource(copy.text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(copy.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
