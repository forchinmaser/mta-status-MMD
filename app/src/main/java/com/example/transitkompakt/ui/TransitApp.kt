package com.example.transitkompakt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.transitkompakt.data.Mode
import com.example.transitkompakt.data.RouteStub
import com.example.transitkompakt.vm.Catalogue
import com.example.transitkompakt.vm.RouteDetail
import com.example.transitkompakt.vm.TransitViewModel
import com.mudita.mmd.components.text.TextMMD
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object TrainList : Screen
    data object BoroughList : Screen
    data class BusRoutes(val borough: String) : Screen
    data class Detail(val stub: RouteStub) : Screen
}

@Composable
fun TransitApp(vm: TransitViewModel) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val subway by vm.subway.collectAsStateWithLifecycle()
    val bus by vm.bus.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val current = stack.last()

    // Alerts start collapsed: real MTA alert text is long, and the stop diagram is
    // the point of the screen. The inverted card header already signals an alert.
    var alertsOpen by remember(current) { mutableStateOf(false) }

    // Which direction of the open route is showing. Both directions come back
    // from one fetch (see TransitViewModel.openRoute), so switching direction
    // just changes which of them is selected — no second request.
    var selectedId by remember(current) { mutableStateOf<String?>(null) }

    // Route tap: fetch this route's stop diagram(s). Cheap no-op if the store
    // already has it — permanently, unless app storage is cleared — or a
    // background full-feed pass already covered it.
    LaunchedEffect(current) {
        if (current is Screen.Detail) vm.openRoute(current.stub)
    }

    // One list state per region, kept across navigation, so returning to a screen
    // restores the same page instead of repainting from the top.
    val trainState = rememberLazyListState()
    val busState = rememberLazyListState()
    val stopState = rememberLazyListState()
    val alertState = rememberLazyListState()

    val scope = rememberCoroutineScope()
    val active: LazyListState? = when {
        current is Screen.Detail && detail is RouteDetail.Ready && alertsOpen -> alertState
        current is Screen.Detail && detail is RouteDetail.Ready -> stopState
        current == Screen.TrainList -> trainState
        current is Screen.BusRoutes -> busState
        else -> null
    }

    // Hardware keys page whichever region is on screen. Step matches that region:
    // whole stop pages, a text page less two lines of context, a full grid page.
    DisposableEffect(active, alertsOpen, current) {
        HardwareKeys.onPage = if (active == null) null else { dir ->
            scope.launch {
                val info = active.layoutInfo
                val visible = info.visibleItemsInfo.size.coerceAtLeast(1)
                val step = when {
                    current is Screen.Detail && alertsOpen ->
                        (visible - Design.ALERT_LINE_OVERLAP).coerceAtLeast(1)
                    current is Screen.Detail -> Design.STOPS_PER_PAGE.coerceAtMost(visible)
                    else -> visible
                }
                val target = (active.firstVisibleItemIndex + dir * step)
                    .coerceIn(0, (info.totalItemsCount - 1).coerceAtLeast(0))
                active.scrollToItem(target)
            }
            Unit
        }
        onDispose { HardwareKeys.onPage = null }
    }

    fun push(s: Screen) { stack = stack + s }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }

    // Once loaded, the title follows the shown direction ("A · Far Rockaway-
    // Mott Av") and the swap control; before that, the stub's line name is
    // the best available label. No Home action anywhere — back is the only
    // way out of a screen, all the way to Home.
    val readyRoutes = (detail as? RouteDetail.Ready)?.routes
    val shownRoute = readyRoutes?.let { rs -> rs.firstOrNull { it.id == selectedId } ?: rs.first() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (current != Screen.Home) {
            AppTopBar(
                title = when (current) {
                    Screen.TrainList -> "Select train"
                    Screen.BoroughList -> "Select borough"
                    is Screen.BusRoutes -> current.borough
                    is Screen.Detail -> when {
                        shownRoute != null -> "${shownRoute.code} · ${shownRoute.direction.removePrefix("To ")}"
                        current.stub.name.isBlank() -> current.stub.code
                        else -> "${current.stub.code} · ${current.stub.name}"
                    }
                    Screen.Home -> ""
                },
                onBack = ::back
            )
        }

        when (current) {
            Screen.Home -> HomeScreen { picked ->
                vm.onModeSelected(picked)   // alert + subway feeds start on this tap
                push(if (picked == Mode.TRAIN) Screen.TrainList else Screen.BoroughList)
            }

            Screen.TrainList -> TrainListScreen(
                catalogue = subway,
                feed = feed,
                listState = trainState,
                onRoute = { push(Screen.Detail(it)) },
                onRetry = { vm.retryAlerts(Mode.TRAIN); vm.loadSubway() }
            )

            Screen.BoroughList -> BoroughListScreen(
                boroughs = vm.boroughs,
                counts = vm.boroughCounts,
                feed = feed,
                onBorough = { borough ->
                    vm.loadBorough(borough)   // that borough's route list starts here
                    push(Screen.BusRoutes(borough))
                },
                onRetry = { vm.retryAlerts(Mode.BUS) }
            )

            is Screen.BusRoutes -> BusRouteListScreen(
                borough = current.borough,
                catalogue = bus,
                listState = busState,
                onRoute = { push(Screen.Detail(it)) },
                onRetry = { vm.loadBorough(current.borough) }
            )

            is Screen.Detail -> when (val d = detail) {
                is RouteDetail.Ready -> {
                    val route = shownRoute ?: d.routes.first()
                    RouteDetailScreen(
                        route = route,
                        hasAlternate = d.routes.size > 1,
                        reversed = d.routes.size > 1 && route.id != d.routes.first().id,
                        alerts = vm.alerts(route),
                        alertsOpen = alertsOpen,
                        stopState = stopState,
                        alertState = alertState,
                        onToggleAlerts = { alertsOpen = !alertsOpen },
                        onSwap = { d.routes.firstOrNull { it.id != route.id }?.let { selectedId = it.id } }
                    )
                }

                // The chip grid used to hold a route's whole stop diagram up
                // front; now a tap fetches it, so the detail screen needs its
                // own loading state — same text pattern the route grids use.
                is RouteDetail.Working -> Column(
                    modifier = Modifier.fillMaxSize().padding(Design.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(Design.RowGap)
                ) {
                    TextMMD("Reading ${d.code} schedule…", style = MaterialTheme.typography.bodyMedium)
                }

                is RouteDetail.Failed -> Column(
                    modifier = Modifier.fillMaxSize().padding(Design.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(Design.RowGap)
                ) {
                    TextMMD("Route unavailable: ${d.reason}", style = MaterialTheme.typography.bodyMedium)
                    ListRowButton("Try again", onClick = { vm.openRoute(current.stub) })
                }

                RouteDetail.Idle -> Unit
            }
        }
    }
}
