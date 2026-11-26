package io.github.otahelper.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.otahelper.R
import io.github.otahelper.core.Backup
import io.github.otahelper.core.OtaStatus
import io.github.otahelper.core.Settings
import io.github.otahelper.ui.components.AppIcons
import io.github.otahelper.ui.components.GroupedSurface
import io.github.otahelper.ui.components.InfoRow
import io.github.otahelper.ui.components.SectionHeader
import io.github.otahelper.ui.components.SettingRow
import io.github.otahelper.ui.components.StatusLabel
import io.github.otahelper.ui.theme.Dimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val DEVICE_ROWS = 4
private const val SETTINGS_ROWS = 2
private const val DEFAULT_PARTITION = "init_boot"
private const val SKELETON_MS = 900
private const val SKELETON_MIN_ALPHA = 0.4f

/** 系统版本, 当前槽位, partition state and root manager. */
internal fun LazyListScope.deviceSection(status: OtaStatus) {
    item(key = "device-header", contentType = "header") {
        SectionHeader(stringResource(R.string.section_device), Modifier.animateItem())
    }
    items(count = DEVICE_ROWS, key = { "device-$it" }, contentType = { "info-row" }) { index ->
        val unknown = stringResource(R.string.value_unknown)
        val partition = status.partition.ifEmpty { DEFAULT_PARTITION }
        val (label, value) = when (index) {
            0 -> stringResource(R.string.device_version) to status.version.ifEmpty { unknown }
            1 -> stringResource(R.string.device_slot) to status.slot.ifEmpty { unknown }
            2 -> stringResource(R.string.device_partition, partition) to stringResource(status.bootImage.labelRes())
            else -> stringResource(R.string.device_root) to status.rootManager.ifEmpty { unknown }
        }
        GroupedSurface(index, DEVICE_ROWS, Modifier.animateItem()) { InfoRow(label, value) }
    }
}

/** Placeholder rows for the device card while the first status is loading. */
internal fun LazyListScope.skeletonSection() {
    item(key = "device-header", contentType = "header") {
        SectionHeader(stringResource(R.string.section_device), Modifier.animateItem())
    }
    items(count = DEVICE_ROWS, key = { "skeleton-$it" }, contentType = { "skeleton-row" }) { index ->
        GroupedSurface(index, DEVICE_ROWS, Modifier.animateItem()) { SkeletonRow() }
    }
}

/** 自动修补 switch and the backup location. */
internal fun LazyListScope.settingsSection(settings: Settings, onAutoPatchChange: (Boolean) -> Unit) {
    item(key = "settings-header", contentType = "header") {
        SectionHeader(stringResource(R.string.section_settings), Modifier.animateItem())
    }
    item(key = "settings-auto-patch", contentType = "setting-row") {
        GroupedSurface(0, SETTINGS_ROWS, Modifier.animateItem()) {
            SettingRow(
                title = stringResource(R.string.setting_auto_patch),
                summary = stringResource(R.string.setting_auto_patch_summary),
                icon = AppIcons.Bolt,
                modifier = Modifier.toggleable(
                    value = settings.autoPatch,
                    role = Role.Switch,
                    onValueChange = onAutoPatchChange,
                ),
                content = { Switch(checked = settings.autoPatch, onCheckedChange = null) },
            )
        }
    }
    item(key = "settings-backup-dir", contentType = "setting-row") {
        GroupedSurface(1, SETTINGS_ROWS, Modifier.animateItem()) {
            SettingRow(
                title = stringResource(R.string.setting_backup_dir),
                summary = settings.backupDir,
                icon = AppIcons.Folder,
            )
        }
    }
}

/** One row per build, newest first, with the images it holds. */
internal fun LazyListScope.backupsSection(backups: List<Backup>) {
    item(key = "backups-header", contentType = "header") {
        SectionHeader(stringResource(R.string.section_backups), Modifier.animateItem())
    }
    if (backups.isEmpty()) {
        item(key = "backups-empty", contentType = "setting-row") {
            GroupedSurface(0, 1, Modifier.animateItem()) {
                SettingRow(title = stringResource(R.string.backups_empty))
            }
        }
        return
    }
    items(count = backups.size, key = { "backup-${backups[it].version}" }, contentType = { "backup-row" }) { index ->
        GroupedSurface(index, backups.size, Modifier.animateItem()) { BackupRow(backups[index]) }
    }
}

/** Standalone 日志 entry at the end of the list. */
internal fun LazyListScope.logEntry(onOpenLog: () -> Unit) {
    item(key = "log", contentType = "setting-row") {
        GroupedSurface(
            0,
            1,
            Modifier
                .animateItem()
                .padding(top = Dimens.HeaderTop),
        ) {
            SettingRow(
                title = stringResource(R.string.log_title),
                icon = AppIcons.Log,
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenLog),
                content = {
                    Icon(
                        imageVector = AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

@Composable
private fun BackupRow(backup: Backup) {
    val colors = MaterialTheme.colorScheme
    val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    val date = remember(backup.modifiedMillis) {
        formatter.format(Instant.ofEpochMilli(backup.modifiedMillis).atZone(ZoneId.systemDefault()))
    }
    SettingRow(
        title = backup.version,
        summary = date,
        content = {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Tiny)) {
                if (backup.stockPath != null) {
                    StatusLabel(
                        text = stringResource(R.string.image_stock),
                        container = colors.secondaryContainer,
                        content = colors.onSecondaryContainer,
                    )
                }
                if (backup.patchedPath != null) {
                    StatusLabel(
                        text = stringResource(R.string.image_patched),
                        container = colors.primaryContainer,
                        content = colors.onPrimaryContainer,
                    )
                }
            }
        },
    )
}

@Composable
private fun SkeletonRow() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = SKELETON_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SKELETON_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    val bar = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .padding(horizontal = Dimens.Gutter)
            .graphicsLayer { alpha = pulse },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(Dimens.SkeletonLabel)
                .height(Dimens.SkeletonHeight)
                .clip(CircleShape)
                .background(bar),
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .width(Dimens.SkeletonValue)
                .height(Dimens.SkeletonHeight)
                .clip(CircleShape)
                .background(bar),
        )
    }
}
