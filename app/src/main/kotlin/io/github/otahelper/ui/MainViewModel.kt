package io.github.otahelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.otahelper.core.Backup
import io.github.otahelper.core.OtaController
import io.github.otahelper.core.OtaStatus
import io.github.otahelper.core.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The controller actions the user can start from this screen. */
internal enum class UiAction { PREPARE, CANCEL, PATCH, REBOOT }

internal data class UiState(
    val status: OtaStatus,
    val settings: Settings = Settings(),
    /** Newest first. */
    val backups: List<Backup> = emptyList(),
    /** An action is running, started here or by the background watcher (auto patch). */
    val busy: Boolean = false,
    /** The action started from this screen, if any; drives which button shows the inline spinner. */
    val running: UiAction? = null,
    /** A user-initiated refresh (pull or retry) is running. */
    val refreshing: Boolean = false,
)

/** Wraps [OtaController] for the UI: one [state] stream, serialized actions, and one-shot result [messages]. */
internal class MainViewModel(private val controller: OtaController) : ViewModel() {
    private val running = MutableStateFlow<UiAction?>(null)
    private val refreshing = MutableStateFlow(false)
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    private var refreshJob: Job? = null

    /** [io.github.otahelper.core.ActionResult.message] of every finished action, for a Snackbar. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val state: StateFlow<UiState> = combine(
        controller.status,
        controller.settings,
        controller.backups,
        controller.busy,
        combine(running, refreshing) { action, manual -> action to manual },
    ) { status, settings, backups, busy, local ->
        UiState(
            status = status,
            settings = settings,
            backups = backups.sortedByDescending { it.modifiedMillis },
            busy = busy || local.first != null,
            running = local.first,
            refreshing = local.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), snapshot())

    fun run(action: UiAction) {
        if (running.value != null || controller.busy.value) return
        running.value = action
        viewModelScope.launch {
            try {
                val result = when (action) {
                    UiAction.PREPARE -> controller.prepare()
                    UiAction.CANCEL -> controller.cancel()
                    UiAction.PATCH -> controller.patch()
                    UiAction.REBOOT -> controller.reboot()
                }
                if (result.message.isNotBlank()) messageChannel.send(result.message)
            } finally {
                running.value = null
            }
        }
    }

    /** Re-reads device state; [userInitiated] shows the pull-to-refresh indicator. Overlapping calls are dropped. */
    fun refresh(userInitiated: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshing.value = userInitiated
            try {
                controller.refresh()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun setAutoPatch(enabled: Boolean) {
        viewModelScope.launch { controller.setAutoPatch(enabled) }
    }

    suspend fun readLog(): List<String> = controller.readLog()

    private fun snapshot() = UiState(
        status = controller.status.value,
        settings = controller.settings.value,
        backups = controller.backups.value.sortedByDescending { it.modifiedMillis },
        busy = controller.busy.value,
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(controller: OtaController): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(controller) }
        }
    }
}
