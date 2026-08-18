package com.example.transitkompakt.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.transitkompakt.data.Feed
import com.example.transitkompakt.data.GtfsImporter
import com.example.transitkompakt.data.GtfsSources
import com.example.transitkompakt.data.Mode
import com.example.transitkompakt.data.Route
import com.example.transitkompakt.data.RouteStub
import com.example.transitkompakt.BuildConfig
import com.example.transitkompakt.data.TransitRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a route list knows about its feed right now. */
sealed interface Catalogue {
    data object Idle : Catalogue
    data class Working(val label: String, val downloading: Boolean) : Catalogue
    data class Ready(val routes: List<RouteStub>) : Catalogue
    data class Failed(val reason: String) : Catalogue
}

/**
 * What the currently-open route knows about its own stop diagram(s). Separate
 * from [Catalogue]: opening a route no longer needs the whole borough's stop
 * data, only this one route's, fetched on tap and cached for a while.
 */
sealed interface RouteDetail {
    data object Idle : RouteDetail
    data class Working(val code: String) : RouteDetail
    data class Ready(val routes: List<Route>) : RouteDetail
    data class Failed(val code: String, val reason: String) : RouteDetail
}

class TransitViewModel(private val repo: TransitRepository) : ViewModel() {

    private val _feed = MutableStateFlow<Feed>(Feed.Idle)
    val feed: StateFlow<Feed> = _feed.asStateFlow()

    private val _subway = MutableStateFlow<Catalogue>(Catalogue.Idle)
    val subway: StateFlow<Catalogue> = _subway.asStateFlow()

    private val _bus = MutableStateFlow<Catalogue>(Catalogue.Idle)
    val bus: StateFlow<Catalogue> = _bus.asStateFlow()

    private val _detail = MutableStateFlow<RouteDetail>(RouteDetail.Idle)
    val detail: StateFlow<RouteDetail> = _detail.asStateFlow()

    val boroughs: List<String> get() = repo.boroughs

    /** Route count per borough, once that borough's feed has been parsed. */
    val boroughCounts: Map<String, Int>
        get() = (_bus.value as? Catalogue.Ready)?.routes
            ?.groupingBy { it.borough ?: "" }?.eachCount().orEmpty()

    private var busJob: Job? = null
    private var detailJob: Job? = null

    /**
     * TRAIN tap: start the subway route list and the alert feed together, so
     * both are usually in hand before the rider has finished reading the list.
     */
    fun onModeSelected(mode: Mode) {
        prefetchAlerts(mode)
        if (mode == Mode.TRAIN) loadSubway()
    }

    private fun prefetchAlerts(mode: Mode) {
        repo.cachedAlerts(mode)?.let { _feed.value = Feed.Ready(it); return }
        _feed.value = Feed.Loading
        viewModelScope.launch {
            repo.prefetchAlerts(mode).fold(
                onSuccess = { _feed.value = Feed.Ready(it) },
                onFailure = { _feed.value = Feed.Failed(it.message ?: "No connection") }
            )
        }
    }

    fun loadSubway() {
        if (_subway.value is Catalogue.Ready || _subway.value is Catalogue.Working) return
        if (!BuildConfig.USE_LIVE_FEEDS) { seedInto(_subway, Mode.TRAIN, null); return }
        repo.routeListFor(GtfsSources.SUBWAY)?.let { _subway.value = Catalogue.Ready(it); return }
        _subway.value = Catalogue.Working("subway", true)
        viewModelScope.launch {
            repo.loadRouteList(GtfsSources.SUBWAY, Mode.TRAIN, null) { p -> _subway.value = p.toCatalogue() }
                .fold(
                    onSuccess = { _subway.value = Catalogue.Ready(it) },
                    onFailure = { _subway.value = fallback(Mode.TRAIN, it) }
                )
        }
    }

    /** BUS tap on a borough: fetch just that borough's route list (codes + names). */
    fun loadBorough(borough: String) {
        if (!BuildConfig.USE_LIVE_FEEDS) { seedInto(_bus, Mode.BUS, borough); return }
        val url = GtfsSources.BUS[borough] ?: return
        repo.routeListFor(url)?.let { _bus.value = Catalogue.Ready(it); return }
        busJob?.cancel()
        _bus.value = Catalogue.Working(borough, true)
        busJob = viewModelScope.launch {
            repo.loadRouteList(url, Mode.BUS, borough) { p -> _bus.value = p.toCatalogue() }
                .fold(
                    onSuccess = { _bus.value = Catalogue.Ready(it) },
                    onFailure = { _bus.value = fallback(Mode.BUS, it, borough) }
                )
        }
    }

    /**
     * Route tap: fetch that one route's stop diagram(s) — both directions
     * come back from a single call, since GTFS gives them together, so
     * switching direction on the detail screen needs no second fetch.
     */
    fun openRoute(stub: RouteStub) {
        if (!BuildConfig.USE_LIVE_FEEDS) {
            viewModelScope.launch {
                val seeded = repo.seedCatalog().routes
                    .filter { it.code == stub.code && it.transitMode == stub.transitMode }
                _detail.value = if (seeded.isNotEmpty()) RouteDetail.Ready(seeded)
                else RouteDetail.Failed(stub.code, "Not in sample data")
            }
            return
        }
        val feedUrl = if (stub.transitMode == Mode.TRAIN) GtfsSources.SUBWAY else GtfsSources.BUS[stub.borough]
        if (feedUrl == null) { _detail.value = RouteDetail.Failed(stub.code, "Unknown feed"); return }
        repo.cachedRouteDetail(feedUrl, stub.id)?.let { _detail.value = RouteDetail.Ready(it); return }
        detailJob?.cancel()
        _detail.value = RouteDetail.Working(stub.code)
        detailJob = viewModelScope.launch {
            repo.loadRouteDetail(feedUrl, stub.id, stub.code, stub.name, stub.transitMode, stub.borough).fold(
                onSuccess = { _detail.value = RouteDetail.Ready(it) },
                onFailure = { _detail.value = RouteDetail.Failed(stub.code, it.message ?: "Route unavailable") }
            )
        }
    }

    fun retryAlerts(mode: Mode) {
        _feed.value = Feed.Loading
        viewModelScope.launch {
            repo.refreshAlerts(mode).fold(
                onSuccess = { _feed.value = Feed.Ready(it) },
                onFailure = { _feed.value = Feed.Failed(it.message ?: "No connection") }
            )
        }
    }

    fun alerts(route: Route) = repo.alertsFor(route)

    /** dev flavor: bundled sample data only, no network. */
    private fun seedInto(target: MutableStateFlow<Catalogue>, mode: Mode, borough: String?) {
        viewModelScope.launch {
            val stubs = repo.seedCatalog().routes
                .filter { it.transitMode == mode && (borough == null || it.borough == borough) }
                .map { RouteStub(id = it.id, mode = it.mode, code = it.code, name = it.name, borough = it.borough) }
            target.value = Catalogue.Ready(stubs)
        }
    }

    /** Offline or feed down: fall back to the bundled sample so screens still work. */
    private suspend fun fallback(mode: Mode, error: Throwable, borough: String? = null): Catalogue {
        val seeded = repo.seedCatalog().routes
            .filter { it.transitMode == mode && (borough == null || it.borough == borough) }
            .map { RouteStub(id = it.id, mode = it.mode, code = it.code, name = it.name, borough = it.borough) }
        return if (seeded.isEmpty()) Catalogue.Failed(error.message ?: "Feed unavailable")
        else Catalogue.Ready(seeded)
    }
}

private fun GtfsImporter.Progress.toCatalogue(): Catalogue = when (this) {
    is GtfsImporter.Progress.Downloading -> Catalogue.Working(label, true)
    is GtfsImporter.Progress.Parsing -> Catalogue.Working(label, false)
    is GtfsImporter.Progress.Done -> Catalogue.Working("", false)
}
