package io.github.otahelper.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.otahelper.ui.MainViewModel
import io.github.otahelper.ui.UiAction

private const val UPDATER_PACKAGE = "com.android.updater"

/** Hidden in the SDK as `Settings.ACTION_SYSTEM_UPDATE_SETTINGS`; HyperOS routes it to its updater. */
private const val ACTION_SYSTEM_UPDATE_SETTINGS = "android.settings.SYSTEM_UPDATE_SETTINGS"

/**
 * Stateful entry: wires [MainViewModel] to [HomeScreen] and owns the transient UI (confirmation dialogs, log
 * sheet, Snackbar, notification permission). Refreshes every time the screen resumes.
 */
@Composable
internal fun HomeRoute(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirming by rememberSaveable { mutableStateOf<UiAction?>(null) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    // The watcher posts progress and "patched" notifications; ask right before the flow starts, then prepare
    // whatever the answer was.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.run(UiAction.PREPARE)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    HomeScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onPrimary = { action ->
            when (action) {
                PrimaryAction.PREPARE -> confirming = UiAction.PREPARE
                PrimaryAction.OPEN_UPDATER -> context.openSystemUpdates()
                PrimaryAction.PATCH -> confirming = UiAction.PATCH
                PrimaryAction.REBOOT -> confirming = UiAction.REBOOT
                PrimaryAction.RETRY -> viewModel.refresh(userInitiated = true)
            }
        },
        onUndo = { confirming = UiAction.CANCEL },
        onRefresh = { viewModel.refresh(userInitiated = true) },
        onAutoPatchChange = viewModel::setAutoPatch,
        onOpenLog = { showLog = true },
    )

    confirming?.let { action ->
        ConfirmDialog(
            action = action,
            onConfirm = {
                confirming = null
                if (action == UiAction.PREPARE && !context.canPostNotifications()) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.run(action)
                }
            },
            onDismiss = { confirming = null },
        )
    }
    if (showLog) {
        LogSheet(loadLog = viewModel::readLog, onDismiss = { showLog = false })
    }
}

private fun Context.canPostNotifications(): Boolean =
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Opens 设置 › 系统更新: the updater's own main activity, else the system-update settings page, else Settings.
 * Resolving the updater needs `<queries><package android:name="com.android.updater" /></queries>`.
 */
private fun Context.openSystemUpdates() {
    val candidates = sequenceOf(
        { packageManager.getLaunchIntentForPackage(UPDATER_PACKAGE) },
        { Intent(ACTION_SYSTEM_UPDATE_SETTINGS) },
        { Intent(Settings.ACTION_SETTINGS) },
    )
    candidates.mapNotNull { it() }.firstOrNull(::tryStart)
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (ignored: ActivityNotFoundException) {
    false
} catch (ignored: SecurityException) {
    false
}
