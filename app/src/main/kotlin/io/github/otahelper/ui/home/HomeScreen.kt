package io.github.otahelper.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import io.github.otahelper.R
import io.github.otahelper.core.Phase
import io.github.otahelper.ui.UiState
import io.github.otahelper.ui.components.ErrorBanner
import io.github.otahelper.ui.theme.Dimens

/** The whole app: hero, optional error, device, settings, backups and log, with the action bar at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: UiState,
    snackbarHostState: SnackbarHostState,
    onPrimary: (PrimaryAction) -> Unit,
    onUndo: () -> Unit,
    onRefresh: () -> Unit,
    onAutoPatchChange: (Boolean) -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, scrollBehavior = scrollBehavior)
        },
        bottomBar = {
            ActionBar(
                phase = state.status.phase,
                busy = state.busy,
                refreshing = state.refreshing,
                running = state.running,
                onPrimary = onPrimary,
                onUndo = onUndo,
                onOpenLog = onOpenLog,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            HomeList(state, onAutoPatchChange, onOpenLog)
        }
    }
}

@Composable
private fun HomeList(state: UiState, onAutoPatchChange: (Boolean) -> Unit, onOpenLog: () -> Unit) {
    val status = state.status
    // The banner only adds information: never when the hero already shows the error, and not for the plain
    // "grant root" message in NO_ROOT, which the hero body says already (ROOT_LOST with its image path stays).
    val plainNoRoot = stringResource(R.string.core_msg_no_root)
    val error = status.error.takeUnless {
        status.phase.errorIsBody || (status.phase == Phase.NO_ROOT && it == plainNoRoot)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Dimens.Gutter, end = Dimens.Gutter, bottom = Dimens.ListBottom),
    ) {
        item(key = "hero", contentType = "hero") {
            PhaseHero(status = status, busy = state.busy, autoPatch = state.settings.autoPatch)
        }
        if (error != null) {
            item(key = "error", contentType = "error") {
                ErrorBanner(
                    message = error,
                    modifier = Modifier
                        .animateItem()
                        .padding(top = Dimens.Gutter),
                )
            }
        }
        when (status.phase) {
            Phase.LOADING -> skeletonSection()

            Phase.NO_ROOT -> {
                settingsSection(state.settings, onAutoPatchChange)
                logEntry(onOpenLog)
            }

            Phase.FAILED -> {
                // The log is the way out of a failed flash, so it comes first.
                logEntry(onOpenLog)
                deviceSection(status)
                backupsSection(state.backups)
                settingsSection(state.settings, onAutoPatchChange)
            }

            else -> {
                deviceSection(status)
                settingsSection(state.settings, onAutoPatchChange)
                backupsSection(state.backups)
                logEntry(onOpenLog)
            }
        }
    }
}
