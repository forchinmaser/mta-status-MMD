package com.example.transitkompakt.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.transitkompakt.data.Feed
import com.example.transitkompakt.data.GtfsImporter
import com.example.transitkompakt.data.GtfsSources
import com.example.transitkompakt.data.Mode
import com.example.transitkompakt.data.Route
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
    data class Ready(val routes: List<Route>) : Catalogue
    data class Failed(val reason: String) : Catalogue
}

class TransitViewModel(private val repo: TransitRepository) : ViewModel() {

    private val _feed = MutableStateFlow<Feed>(Feed.Idle)
    val feed: StateFlow<Feed> = _feed.asStateFlow()

    private val _subway = MutableStateFlow<Catalogue>(Catalogue.Idle)
    val subway: StateFlow<Catalogue> = _subway.asStateFlow()

    private val _bus = MutableStateFlow<Catalogue>(Catalogue.Idle)
    val bus: StateFlow<Catalogue> = _bus.asStateFlow()

    val boroughs: List<String> get() = repo.boroughs

    /** Route count per borough, once that borough's feed has been parsed. */
    val boroughCounts: Map<String, Int>
        get() = (_bus.value as? Catalogue.Ready)?.routes
            ?.groupingBy { it.borough ?: "" }?.eachCount().orEmpty()

    private var busJob: Job? = null

    /**
     * TRAIN tap: start the subway route feed and the alert feed together, so
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
        repo.routesFor(GtfsSources.SUBWAY)?.let { _subway.value = Catalogue.Ready(it); return }
        _subway.value = Catalogue.Working("subway", true)
        viewModelScope.launch {
            repo.loadRoutes(GtfsSources.SUBWAY, Mode.TRAIN, null) { p -> _subway.value = p.toCatalogue() }
                .fold(
                    onSuccess = { _subway.value = Catalogue.Ready(it) },
                    onFailure = { _subway.value = fallback(Mode.TRAIN, it) }
                )
        }
    }

    /** BUS tap on a borough: fetch just that borough's feed. */
    fun loadBorough(borough: String) {
        if (!BuildConfig.USE_LIVE_FEEDS) { seedInto(_bus, Mode.BUS, borough); return }
        val url = GtfsSources.BUS[borough] ?: return
        repo.routesFor(url)?.let { _bus.value = Catalogue.Ready(it); return }
        busJob?.cancel()
        _bus.value = Catalogue.Working(borough, true)
        busJob = viewModelScope.launch {
            repo.loadRoutes(url, Mode.BUS, borough) { p -> _bus.value = p.toCatalogue() }
                .fold(
                    onSuccess = { _bus.value = Catalogue.Ready(it) },
                    onFailure = { _bus.value = fallback(Mode.BUS, it, borough) }
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

    fun route(id: String): Route? = repo.route(id)
    fun alerts(route: Route) = repo.alertsFor(route)

    /** dev flavor: bundled sample data only, no network. */
    private fun seedInto(
        target: MutableStateFlow<Catalogue>,
        mode: Mode,
        borough: String?
    ) {
        viewModelScope.launch {
            val routes = repo.seedCatalog().routes.filter {
                it.transitMode == mode && (borough == null || it.borough == borough)
            }
            target.value = Catalogue.Ready(routes)
        }
    }

    /** Offline or feed down: fall back to the bundled sample so screens still work. */
    private suspend fun fallback(mode: Mode, error: Throwable, borough: String? = null): Catalogue {
        val seeded = repo.seedCatalog().routes.filter {
            it.transitMode == mode && (borough == null || it.borough == borough)
        }
        return if (seeded.isEmpty()) Catalogue.Failed(error.message ?: "Feed unavailable")
        else Catalogue.Ready(seeded)
    }
}

private fun GtfsImporter.Progress.toCatalogue(): Catalogue = when (this) {
    is GtfsImporter.Progress.Downloading -> Catalogue.Working(label, true)
    is GtfsImporter.Progress.Parsing -> Catalogue.Working(label, false)
    is GtfsImporter.Progress.Done -> Catalogue.Working("", false)
}
