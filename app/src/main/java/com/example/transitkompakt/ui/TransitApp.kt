package com.example.transitkompakt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.transitkompakt.data.Mode
import com.example.transitkompakt.data.Route
import com.example.transitkompakt.vm.Catalogue
import com.example.transitkompakt.vm.TransitViewModel
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object TrainList : Screen
    data object BoroughList : Screen
    data class BusRoutes(val borough: String) : Screen
    data class Detail(val routeId: String) : Screen
}

@Composable
fun TransitApp(vm: TransitViewModel) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val subway by vm.subway.collectAsStateWithLifecycle()
    val bus by vm.bus.collectAsStateWithLifecycle()
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val current = stack.last()

    // Alerts start collapsed: real MTA alert text is long, and the stop diagram is
    // the point of the screen. The inverted card header already signals an alert.
    var alertsOpen by remember(current) { mutableStateOf(false) }

    // One list state per region, kept across navigation, so returning to a screen
    // restores the same page instead of repainting from the top.
    val trainState = rememberLazyListState()
    val busState = rememberLazyListState()
    val stopState = rememberLazyListState()
    val alertState = rememberLazyListState()

    val scope = rememberCoroutineScope()
    val active: LazyListState? = when {
        current is Screen.Detail && alertsOpen -> alertState
        current is Screen.Detail -> stopState
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
    fun replace(s: Screen) { stack = stack.dropLast(1) + s }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun home() { stack = listOf(Screen.Home) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (current != Screen.Home) {
            AppTopBar(
                title = when (current) {
                    Screen.TrainList -> "Select train"
                    Screen.BoroughList -> "Select borough"
                    is Screen.BusRoutes -> current.borough
                    is Screen.Detail -> vm.route(current.routeId)?.code ?: ""
                    Screen.Home -> ""
                },
                onBack = ::back,
                onHome = ::home
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
                onRoute = { push(Screen.Detail(it.id)) },
                onRetry = { vm.retryAlerts(Mode.TRAIN); vm.loadSubway() }
            )

            Screen.BoroughList -> BoroughListScreen(
                boroughs = vm.boroughs,
                counts = vm.boroughCounts,
                feed = feed,
                onBorough = { borough ->
                    vm.loadBorough(borough)   // that borough's schedule starts here
                    push(Screen.BusRoutes(borough))
                },
                onRetry = { vm.retryAlerts(Mode.BUS) }
            )

            is Screen.BusRoutes -> BusRouteListScreen(
                borough = current.borough,
                catalogue = bus,
                listState = busState,
                onRoute = { push(Screen.Detail(it.id)) },
                onRetry = { vm.loadBorough(current.borough) }
            )

            is Screen.Detail -> vm.route(current.routeId)?.let { route ->
                val pool = (subway as? Catalogue.Ready)?.routes.orEmpty() +
                    (bus as? Catalogue.Ready)?.routes.orEmpty()
                val siblings: List<Route> = pool.filter { it.code == route.code }
                RouteDetailScreen(
                    route = route,
                    siblings = siblings,
                    alerts = vm.alerts(route),
                    alertsOpen = alertsOpen,
                    stopState = stopState,
                    alertState = alertState,
                    onToggleAlerts = { alertsOpen = !alertsOpen },
                    onDirection = { replace(Screen.Detail(it.id)) }
                )
            }
        }
    }
}
