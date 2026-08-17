package com.example.transitkompakt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mudita.mmd.components.lazy.LazyColumnMMD

/**
 * Route grid.
 *
 * A borough can carry 200+ routes, so this pages rather than scrolls, on MMD's
 * LazyColumnMMD (discrete steps + MMD's chevron scrollbar). Rows are built
 * explicitly at a fixed pitch, and chips come in two fixed widths only — narrow
 * codes four per row, long/SBS codes two per row — so a chip boundary is in the
 * same column on every page of every borough and the panel redraws less.
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
                rows[r].forEach { code -> LineChip(code, circle) { onPick(code) } }
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
