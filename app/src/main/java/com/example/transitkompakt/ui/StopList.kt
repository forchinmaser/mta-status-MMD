package com.example.transitkompakt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Stop list.
 *
 * This is MMD's own LazyColumnMMD, which is exactly the behaviour asked for and
 * the reason not to hand-roll it: scrolling is discrete (scrollStep items per
 * drag, so rows land on the same pixel rows and the panel does not smear), and
 * it draws MMD's vertical scrollbar with chevrons — tap to page, long-press to
 * jump to either end, tap the track to seek.
 */
@Composable
fun StopList(
    stops: List<String>,
    affected: Set<String>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState()
) {
    LazyColumnMMD(
        modifier = modifier,
        state = state,
        scrollStep = Design.STOPS_PER_PAGE,
        verticalArrangement = Arrangement.Top
    ) {
        items(stops.size) { i ->
            StopRow(
                label = stops[i],
                isFirst = i == 0,
                isLast = i == stops.lastIndex,
                isAffected = stops[i] in affected
            )
        }
    }
}

@Composable
private fun StopRow(label: String, isFirst: Boolean, isLast: Boolean, isAffected: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val terminus = isFirst || isLast
    Row(
        modifier = Modifier.fillMaxWidth().height(Design.StopRowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(30.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight().width(2.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().background(if (isFirst) paper else ink))
                Box(Modifier.weight(1f).fillMaxWidth().background(if (isLast) paper else ink))
            }
            val shape = if (terminus) RoundedCornerShape(3.dp) else CircleShape
            Box(
                modifier = Modifier
                    .size(if (terminus || isAffected) Design.TerminusBulletSize else Design.BulletSize)
                    .clip(shape)
                    .background(if (isAffected) paper else ink)
                    .then(if (isAffected) Modifier.border(3.dp, ink, shape) else Modifier)
            )
        }
        Spacer(Modifier.width(8.dp))
        TextMMD(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isAffected) FontWeight.Bold else null,
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
        if (terminus) {
            Spacer(Modifier.width(6.dp))
            TextMMD(
                text = if (isFirst) "START" else "END",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
