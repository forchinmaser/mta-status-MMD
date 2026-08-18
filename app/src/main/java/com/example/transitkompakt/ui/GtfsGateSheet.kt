package com.example.transitkompakt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.transitkompakt.vm.GateState
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.progress_indicator.LinearProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * First-run subway cache gate: Ask (download prompt) / Downloading (progress)
 * / Failed (retry). One sheet, one fixed height across all three so switching
 * states never resizes it.
 *
 * No scrim — [ModalBottomSheetMMD]'s `scrimColor = Color.Transparent` — since
 * modality here is behavioural, not visual: nothing behind the sheet is ever
 * mounted while it's showing (see TransitApp's caller), and greying the
 * background would be a full-panel E Ink repaint for no information.
 *
 * Known gap: MMD's `SheetStateMMD.show()` always animates toward Expanded;
 * the underlying snap-without-animation path is private to the vendored
 * component, so this cannot fully suppress that transition without editing
 * mmd-core (out of bounds — see HANDOFFUI.md). In practice this is a single
 * short slide on first appearance only, and E Ink's own refresh latency likely
 * makes it visually moot, but it's not the literal "single redraw" the
 * prototype describes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GtfsGateSheet(
    state: GateState,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    if (state !is GateState.Ask && state !is GateState.Downloading && state !is GateState.Failed) return

    ModalBottomSheetMMD(
        onDismissRequest = {},
        sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(Design.GateSheetHeight)
                .padding(Design.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Design.Gap)
        ) {
            when (state) {
                is GateState.Ask -> {
                    TextMMD(
                        text = "This app needs to locally cache GTFS data from mta.info.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ButtonMMD(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        TextMMD(if (state.sizeLabel != null) "Download ${state.sizeLabel}" else "Download")
                    }
                    OutlinedButtonMMD(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        TextMMD("Cancel")
                    }
                }

                is GateState.Downloading -> {
                    TextMMD(
                        text = "Downloading from mta.info…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // LinearProgressIndicatorMMD draws at a fixed 240x8dp regardless of the
                    // modifier passed in (it appends its own .size() internally), so no
                    // fillMaxWidth() here — it would be silently overridden anyway.
                    LinearProgressIndicatorMMD(progress = { state.fraction })
                }

                is GateState.Failed -> {
                    TextMMD(
                        text = "Download failed: ${state.reason}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ButtonMMD(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { TextMMD("Retry") }
                    OutlinedButtonMMD(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { TextMMD("Cancel") }
                }

                else -> Unit
            }
        }
    }
}
