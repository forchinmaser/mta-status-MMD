package com.example.transitkompakt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mudita.mmd.components.lazy.LazyColumnMMD
import kotlinx.coroutines.delay

/**
 * Route grid.
 *
 * A borough can carry 200+ routes, so this pages rather than scrolls, on MMD's
 * LazyColumnMMD (discrete steps + MMD's chevron scrollbar). Rows are built
 * explicitly at a fixed pitch, and chips come in two fixed widths only — narrow
 * codes four per row, long/SBS codes two per row — so a chip boundary is in the
 * same column on every page of every borough and the panel redraws less.
 *
 * Ghosting: a grid page replaces a whole screen of chips at once, which is the
 * worst case for imprint. Every chip is drawn filled for Design.CHIP_FLASH_MS as
 * a page first paints, then returns to outlined — one deliberate inversion in
 * place of a slow fade of the old page.
 */
@Composable
fun ChipGrid(
    codes: List<String>,
    circle: Boolean,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    onPick: (String) -> Unit
) {
    val rows = remember(codes, circle) { chipRows(codes, circle) }

    // Re-fires on arrival at the screen and on every page turn.
    val pageKey = state.firstVisibleItemIndex
    var flashing by remember { mutableStateOf(Design.CHIP_FLASH_MS > 0L) }
    LaunchedEffect(codes, pageKey) {
        if (Design.CHIP_FLASH_MS > 0L) {
            flashing = true
            delay(Design.CHIP_FLASH_MS)
            flashing = false
        }
    }

    LazyColumnMMD(
        modifier = modifier,
        state = state,
        // A full page per step: the whole grid is repainted anyway, so a partial
        // shift would only cost extra ghosting.
        scrollStep = 1,
        verticalArrangement = Arrangement.spacedBy(Design.ChipGap)
    ) {
        items(rows.size) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(Design.ChipGap)) {
                rows[r].forEach { code ->
                    LineChip(code = code, circle = circle, inverted = flashing) { onPick(code) }
                }
            }
        }
    }
}

/** Narrow codes first, four per row; long/SBS codes after, two per row. */
internal fun chipRows(codes: List<String>, circle: Boolean): List<List<String>> {
    if (circle) return codes.chunked(Design.CHIPS_PER_ROW)
    val (wide, narrow) = codes.partition { Design.isWideCode(it) }
    return narrow.chunked(Design.CHIPS_PER_ROW) + wide.chunked(Design.WIDE_CHIPS_PER_ROW)
}
