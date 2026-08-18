package com.example.transitkompakt.ui

import androidx.compose.ui.unit.dp

/**
 * The only app-level design constants; colours and type come from MMD.
 *
 * Every value here exists to keep an element on the SAME pixels from one screen
 * to the next, which is what keeps an E Ink panel from redrawing boundaries that
 * did not move:
 *
 *  - One row pitch for every full-width row, so the home TRAIN/BUS rows land
 *    exactly where the third and fourth borough rows land.
 *  - Two chip widths only, in fixed-pitch rows, so a route chip is in the same
 *    column on every page of every borough.
 *  - Paged regions are whole multiples of their row pitch, so a page turn always
 *    repaints a clean rectangle and never leaves a half row of ink.
 */
object Design {
    /**
     * Stop rows step in whole pages of this many items.
     *
     * GHOSTING CONSTANT — was 5. The detail screen no longer draws the route-name
     * label or the filled route badge (both moved into the top bar as
     * "6 · Lexington Av Local"), which frees 64dp + 36dp. With the 54dp alert
     * card closed, 349dp remain for the diagram: seven whole 44dp rows.
     */
    const val STOPS_PER_PAGE = 7

    /** A text page turn keeps this many lines of context. Unchanged. */
    const val ALERT_LINE_OVERLAP = 2

    /**
     * Chips invert for this long as a route grid first paints, and again on each
     * page turn, to clear ghosting left by the previous grid. Not a pitch — safe
     * to tune. 0 disables the flash.
     */
    const val CHIP_FLASH_MS = 100L

    val ScreenPadding = 16.dp
    val Gap = 12.dp

    /** Full-width rows: boroughs, home mode buttons, retry rows. */
    val RowHeight = 70.dp
    val RowGap = 8.dp

    /** Chips: one narrow size for 2-4 characters, one wide for long/SBS codes. */
    val ChipHeight = 52.dp
    val ChipGap = 8.dp
    val ChipWidth = 58.dp
    val ChipWidthWide = 124.dp
    const val CHIPS_PER_ROW = 4
    const val WIDE_CHIPS_PER_ROW = 2

    /** Alert text sits on a fixed line grid so it can page by whole lines. */
    val AlertLineHeight = 22.dp

    val StopRowHeight = 44.dp
    val BulletSize = 14.dp
    val TerminusBulletSize = 16.dp

    /** A code longer than this gets the wide chip. */
    const val WIDE_CODE_LENGTH = 4

    fun isWideCode(code: String) = code.length > WIDE_CODE_LENGTH
}
