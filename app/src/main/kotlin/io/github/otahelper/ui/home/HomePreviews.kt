package io.github.otahelper.ui.home

import android.content.res.Configuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import io.github.otahelper.core.OtaStatus
import io.github.otahelper.core.Phase
import io.github.otahelper.core.Settings
import io.github.otahelper.ui.PreviewOtaController
import io.github.otahelper.ui.PreviewOtaController.Companion.sampleStatus
import io.github.otahelper.ui.UiState
import io.github.otahelper.ui.theme.OtaTheme

/** Renders [HomeScreen] with [PreviewOtaController]'s sample data. */
@Composable
private fun HomePreview(status: OtaStatus, busy: Boolean = false) {
    OtaTheme {
        HomeScreen(
            state = UiState(
                status = status,
                settings = Settings(),
                backups = PreviewOtaController.sampleBackups().sortedByDescending { it.modifiedMillis },
                busy = busy,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onPrimary = {},
            onUndo = {},
            onRefresh = {},
            onAutoPatchChange = {},
            onOpenLog = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun LoadingPreview() = HomePreview(sampleStatus(Phase.LOADING))

@Preview(showSystemUi = true)
@Composable
private fun NoRootPreview() = HomePreview(sampleStatus(Phase.NO_ROOT))

@Preview(showSystemUi = true)
@Composable
private fun UnsupportedPreview() = HomePreview(sampleStatus(Phase.UNSUPPORTED))

@Preview(name = "Light", showSystemUi = true)
@Preview(name = "Dark", showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun IdlePreview() = HomePreview(sampleStatus(Phase.IDLE))

@Preview(showSystemUi = true)
@Composable
private fun PreparedPreview() = HomePreview(sampleStatus(Phase.PREPARED))

@Preview(name = "Light", showSystemUi = true)
@Preview(name = "Dark", showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InstallingPreview() = HomePreview(sampleStatus(Phase.INSTALLING))

@Preview(showSystemUi = true)
@Composable
private fun InstalledPatchingPreview() = HomePreview(sampleStatus(Phase.INSTALLED), busy = true)

@Preview(showSystemUi = true)
@Composable
private fun PatchedPreview() = HomePreview(sampleStatus(Phase.PATCHED))

@Preview(name = "Light", showSystemUi = true)
@Preview(name = "Dark", showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FailedPreview() = HomePreview(sampleStatus(Phase.FAILED))

@Preview(showSystemUi = true)
@Composable
private fun ErrorPreview() = HomePreview(sampleStatus(Phase.IDLE).copy(error = "Root was lost after a restart."))
