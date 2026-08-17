package com.example.transitkompakt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.transitkompakt.data.Alert
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Alert card.
 *
 * Closed, it is one row and the stop diagram owns the screen. Open, it takes the
 * whole screen and the caller drops the diagram entirely — alert text and diagram
 * never compete for the same pixels, so opening the card repaints one region
 * instead of reflowing two.
 *
 * Open, the body pages on a fixed line grid (LazyColumnMMD over pre-split lines),
 * keeping ALERT_LINE_OVERLAP lines of context per turn so a sentence broken
 * across a page boundary is still readable.
 */
@Composable
fun AlertCard(
    alerts: List<Alert>,
    code: String,
    open: Boolean,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onToggle: () -> Unit
) {
    val hasAlerts = alerts.isNotEmpty()
    // An active alert inverts the header — the one place the app fills a surface,
    // so alerts read at a glance without being read.
    val headerBg = if (hasAlerts) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface
    val headerFg = if (hasAlerts) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface

    CardMMD(onClick = onToggle, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextMMD(
                text = if (hasAlerts) "${alerts.size} active alert${if (alerts.size > 1) "s" else ""}"
                else "Good service",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = headerFg,
                modifier = Modifier.weight(1f)
            )
            TextMMD(if (open) "▾" else "▸", style = MaterialTheme.typography.bodyMedium, color = headerFg)
        }

        if (open) {
            HorizontalDividerMMD(thickness = 1.dp)
            val lines = alertLines(alerts, code)
            LazyColumnMMD(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                state = listState,
                scrollStep = 1
            ) {
                items(lines.size) { i ->
                    val line = lines[i]
                    TextMMD(
                        text = line.text,
                        style = if (line.isHeading) MaterialTheme.typography.labelSmall
                        else MaterialTheme.typography.bodySmall,
                        fontWeight = if (line.isHeading) FontWeight.Bold else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

internal data class AlertLine(val text: String, val isHeading: Boolean)

/**
 * Flattens alerts to a list of lines so the card can page by whole lines.
 * MtaAlertsClient has already normalised the feed's icon placeholders and
 * pipe-delimited station runs into newlines.
 */
internal fun alertLines(alerts: List<Alert>, code: String): List<AlertLine> {
    if (alerts.isEmpty()) {
        return listOf(AlertLine("No active alerts or delays reported for $code right now.", false))
    }
    val out = mutableListOf<AlertLine>()
    alerts.forEachIndexed { i, a ->
        if (i > 0) out += AlertLine("", false)
        out += AlertLine(a.type.uppercase(), true)
        a.text.split("\n").filter { it.isNotBlank() }.forEach { out += AlertLine(it, false) }
    }
    return out
}
