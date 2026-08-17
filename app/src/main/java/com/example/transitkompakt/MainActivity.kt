package com.example.transitkompakt

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.transitkompakt.data.TransitRepository
import com.example.transitkompakt.ui.HardwareKeys
import com.example.transitkompakt.ui.TransitApp
import com.example.transitkompakt.vm.TransitViewModel
import com.mudita.mmd.ThemeMMD

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = TransitRepository(applicationContext)
        setContent {
            // ThemeMMD supplies eInkColorScheme + eInkTypography (Lato) and
            // disables ripple app-wide. No local theme of our own.
            ThemeMMD {
                val vm: TransitViewModel = viewModel(factory = factory(repo))
                TransitApp(vm)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        HardwareKeys.handle(keyCode, event) || super.onKeyDown(keyCode, event)

    private fun factory(repo: TransitRepository) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TransitViewModel(repo) as T
    }
}
