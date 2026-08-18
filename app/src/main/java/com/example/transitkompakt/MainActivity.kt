package com.example.transitkompakt

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.transitkompakt.data.GtfsDownloadController
import com.example.transitkompakt.data.TransitRepository
import com.example.transitkompakt.ui.GtfsGateSheet
import com.example.transitkompakt.ui.HardwareKeys
import com.example.transitkompakt.ui.TransitApp
import com.example.transitkompakt.vm.GateState
import com.example.transitkompakt.vm.TransitViewModel
import com.mudita.mmd.ThemeMMD

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = TransitRepository(applicationContext)
        val downloads = GtfsDownloadController(applicationContext)
        setContent {
            // ThemeMMD supplies eInkColorScheme + eInkTypography (Lato) and
            // disables ripple app-wide. No local theme of our own.
            ThemeMMD {
                val vm: TransitViewModel = viewModel(factory = factory(repo, downloads))
                val gate by vm.gate.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) { vm.checkGate() }

                when (gate) {
                    // Nothing behind the sheet is mounted at all while it's up — the
                    // README's "nothing behind responds" as a structural guarantee
                    // rather than something to police with input-blocking.
                    GateState.Checking -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                    GateState.Ready -> TransitApp(vm)
                    else -> {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                        GtfsGateSheet(
                            state = gate,
                            onDownload = vm::startDownload,
                            onRetry = vm::startDownload,
                            onCancel = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        HardwareKeys.handle(keyCode, event) || super.onKeyDown(keyCode, event)

    private fun factory(repo: TransitRepository, downloads: GtfsDownloadController) =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TransitViewModel(repo, downloads) as T
        }
}
