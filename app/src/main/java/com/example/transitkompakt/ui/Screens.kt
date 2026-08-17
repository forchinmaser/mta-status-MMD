package com.example.transitkompakt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.transitkompakt.data.Alert
import com.example.transitkompakt.data.Feed
import com.example.transitkompakt.data.Mode
import com.example.transitkompakt.data.Route
import com.example.transitkompakt.data.routeCodeOrder
import com.example.transitkompakt.vm.Catalogue
import com.mudita.mmd.components.chips.FilterChipMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Home.
 *
 * The wordmark occupies the first two row slots of the SAME stack the mode
 * buttons sit in, so TRAIN and BUS land exactly where the third and fourth
 * borough rows land on the Bus screen — tapping BUS moves no boundary.
 */
@Composable
fun HomeScreen(onMode: (Mode) -> Unit) = Column(
    modifier = Modifier.fillMaxSize().padding(Design.ScreenPadding),
    verticalArrangement = Arrangement.spacedBy(Design.RowGap)
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(Design.RowHeight * 2 + Design.RowGap),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextMMD("Transit", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        TextMMD("Live NYC subway & bus status", style = MaterialTheme.typography.bodySmall)
    }
    ListRowButton("TRAIN", "All subway lines") { onMode(Mode.TRAIN) }
    ListRowButton("BUS", "All routes, by borough") { onMode(Mode.BUS) }
}

@Composable
fun TrainListScreen(
    catalogue: Catalogue,
    feed: Feed,
    listState: LazyListState,
    onRoute: (Route) -> Unit,
    onRetry: () -> Unit
) = RouteGridScreen("Subway lines", catalogue, true, feed, listState, onRoute, onRetry)

@Composable
fun BusRouteListScreen(
    borough: String,
    catalogue: Catalogue,
    listState: LazyListState,
    onRoute: (Route) -> Unit,
    onRetry: () -> Unit
) = RouteGridScreen("$borough routes", catalogue, false, null, listState, onRoute, onRetry)

@Composable
private fun RouteGridScreen(
    label: String,
    catalogue: Catalogue,
    circle: Boolean,
    feed: Feed?,
    listState: LazyListState,
    onRoute: (Route) -> Unit,
    onRetry: () -> Unit
) = Column(
    modifier = Modifier.fillMaxSize().padding(Design.ScreenPadding),
    verticalArrangement = Arrangement.spacedBy(Design.Gap)
) {
    SectionLabel(label)
    when (catalogue) {
        Catalogue.Idle -> Unit

        is Catalogue.Working -> TextMMD(
            text = if (catalogue.downloading) "Downloading ${catalogue.label} schedule…"
            else "Reading ${catalogue.label} schedule…",
            style = MaterialTheme.typography.bodyMedium
        )

        is Catalogue.Failed -> Column(verticalArrangement = Arrangement.spacedBy(Design.RowGap)) {
            TextMMD("Schedule unavailable: ${catalogue.reason}", style = MaterialTheme.typography.bodyMedium)
            ListRowButton("Try again", onClick = onRetry)
        }

        is Catalogue.Ready -> {
            // One chip per route code; direction is picked on the detail screen.
            val byCode = remember(catalogue.routes) {
                catalogue.routes.groupBy { it.code }.toList()
                    .sortedWith(compareBy(routeCodeOrder) { it.first })
            }
            ChipGrid(
                codes = byCode.map { it.first },
                circle = circle,
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { code -> byCode.firstOrNull { it.first == code }?.second?.first()?.let(onRoute) }
            SourceLine("${byCode.size} routes · live from MTA")
        }
    }
    if (feed != null) FeedStatus(feed, onRetry)
}

/**
 * Borough list. No section label: the six rows plus Express only fit without it,
 * and the top bar already says which mode you are in.
 */
@Composable
fun BoroughListScreen(
    boroughs: List<String>,
    counts: Map<String, Int>,
    feed: Feed,
    onBorough: (String) -> Unit,
    onRetry: () -> Unit
) = Column(
    modifier = Modifier.fillMaxSize().padding(Design.ScreenPadding),
    verticalArrangement = Arrangement.spacedBy(Design.RowGap)
) {
    boroughs.forEach { b ->
        ListRowButton(b, counts[b]?.let { "$it routes" }) { onBorough(b) }
    }
    if (feed is Feed.Failed) {
        Spacer(Modifier.weight(1f))
        FeedStatus(feed, onRetry)
    }
}

@Composable
fun RouteDetailScreen(
    route: Route,
    siblings: List<Route>,
    alerts: List<Alert>,
    alertsOpen: Boolean,
    stopState: LazyListState,
    alertState: LazyListState,
    onToggleAlerts: () -> Unit,
    onDirection: (Route) -> Unit
) {
    val affected = remember(route.id, alerts) {
        route.stops.filter { stop -> alerts.any { a -> a.stopNames.any { stop.contains(it, true) } } }.toSet()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(Design.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Design.Gap)
    ) {
        // Label first, then the badge — so the badge sits on exactly the pixels
        // the tapped chip occupied on the previous screen.
        SectionLabel(route.name)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineChipFilled(route.code, circle = route.transitMode == Mode.TRAIN)
            Spacer(Modifier.width(Design.Gap))
            TextMMD("${route.stops.size} stops", style = MaterialTheme.typography.bodySmall)
        }

        if (siblings.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                siblings.forEach { alt ->
                    FilterChipMMD(
                        selected = alt.id == route.id,
                        onClick = { onDirection(alt) },
                        label = {
                            TextMMD(
                                text = alt.direction.removePrefix("To ").take(18),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }

        AlertCard(
            alerts = alerts,
            code = route.code,
            open = alertsOpen,
            listState = alertState,
            modifier = if (alertsOpen) Modifier.weight(1f) else Modifier,
            onToggle = onToggleAlerts
        )

        // Open, the alert card owns the screen and the diagram is not drawn at all.
        if (!alertsOpen) {
            TextMMD(
                text = "● stop   ▢ start / end   ○ affected by alert",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            StopList(
                stops = route.stops,
                affected = affected,
                state = stopState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun FeedStatus(feed: Feed, onRetry: () -> Unit) = when (feed) {
    is Feed.Loading -> SourceLine("Loading alerts from mta.info…")
    is Feed.Ready -> SourceLine("Live snapshot · mta.info")
    is Feed.Failed -> ListRowButton("Alerts unavailable · retry", onClick = onRetry)
    Feed.Idle -> Unit
}
