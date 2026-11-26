package io.github.otahelper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.otahelper.BuildConfig
import io.github.otahelper.OtaApp
import io.github.otahelper.core.OtaController
import io.github.otahelper.ui.home.HomeRoute
import io.github.otahelper.ui.theme.OtaTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(controller()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OtaTheme {
                HomeRoute(viewModel)
            }
        }
    }

    /**
     * The real controller, or in debug builds started with `--ez demo true` an in-memory one that simulates the
     * whole flow, so the UI can be exercised on a device without root.
     */
    private fun controller(): OtaController = if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO, false)) {
        PreviewOtaController.demo(this)
    } else {
        (application as OtaApp).controller
    }

    private companion object {
        const val EXTRA_DEMO = "demo"
    }
}
