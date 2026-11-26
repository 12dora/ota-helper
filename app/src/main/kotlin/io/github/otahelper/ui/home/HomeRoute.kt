package io.github.otahelper.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.otahelper.ui.MainViewModel
import io.github.otahelper.ui.UiAction
import kotlinx.coroutines.launch

/** Hidden in the SDK as `Settings.ACTION_SYSTEM_UPDATE_SETTINGS`. */
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
    val scope = rememberCoroutineScope()
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
                PrimaryAction.OPEN_UPDATER -> scope.launch { context.openSystemUpdates(viewModel) }
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
 * Where the user installs the update.
 * - Xiaomi (incl. Redmi/POCO): the HyperOS updater, launched through root because its activity is
 *   permission-protected; the standard system-update intent there opens Google's page instead, so skip it.
 * - Other brands: Settings › System update, which is the OEM's own page on Pixel and most AOSP-based ROMs.
 * Settings is the last resort everywhere.
 */
private suspend fun Context.openSystemUpdates(viewModel: MainViewModel) {
    if (isXiaomi()) {
        if (!viewModel.openSystemUpdate()) tryStart(Intent(Settings.ACTION_SETTINGS))
        return
    }
    sequenceOf(Intent(ACTION_SYSTEM_UPDATE_SETTINGS), Intent(Settings.ACTION_SETTINGS)).firstOrNull(::tryStart)
}

private fun isXiaomi(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (ignored: ActivityNotFoundException) {
    false
} catch (ignored: SecurityException) {
    false
}
