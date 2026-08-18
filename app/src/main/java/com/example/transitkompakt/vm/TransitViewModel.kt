package com.example.transitkompakt.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.example.transitkompakt.data.Feed
import com.example.transitkompakt.data.GtfsDownloadController
import com.example.transitkompakt.data.GtfsImporter
import com.example.transitkompakt.data.GtfsSources
import com.example.transitkompakt.data.Mode
import com.example.transitkompakt.data.Route
import com.example.transitkompakt.data.RouteStub
import com.example.transitkompakt.data.SubwayDownloadWorker
import com.example.transitkompakt.BuildConfig
import com.example.transitkompakt.data.TransitRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** First-run subway cache gate — see GtfsGateSheet for the UI it drives. */
sealed interface GateState {
    data object Checking : GateState
    data object Ready : GateState
    data class Ask(val sizeLabel: String?) : GateState
    data class Downloading(val fraction: Float) : GateState
    data class Failed(val reason: String) : GateState
}

/** What a route list knows about its feed right now. */
sealed interface Catalogue {
    data object Idle : Catalogue
    data class Working(val label: String, val downloading: Boolean) : Catalogue
    data class Ready(val routes: List<RouteStub>) : Catalogue
    data class Failed(val reason: String) : Catalogue
}

/**
 * What the currently-open route knows about its own stop diagram(s). Separate
 * from [Catalogue]: opening a route no longer needs the whole feed's stop
 * data, only this one route's — though a background full-feed pass, fired
 * right after the route list loads, often has it ready before a tap needs it.
 */
sealed interface RouteDetail {
    data object Idle : RouteDetail
    data class Working(val code: String) : RouteDetail
    data class Ready(val routes: List<Route>) : RouteDetail
    data class Failed(val code: String, val reason: String) : RouteDetail
}

class TransitViewModel(
    private val repo: TransitRepository,
    private val downloads: GtfsDownloadController
) : ViewModel() {

    private val _gate = MutableStateFlow<GateState>(GateState.Checking)
    val gate: StateFlow<GateState> = _gate.asStateFlow()

    private var gateObserved = false

    /** Called once, up front: decides whether the first-run sheet is needed at all. */
    fun checkGate() {
        if (_gate.value !is GateState.Checking) return
        if (!BuildConfig.USE_LIVE_FEEDS || repo.isSubwayCached()) { _gate.value = GateState.Ready; return }
        observeDownload()
        viewModelScope.launch { _gate.value = GateState.Ask(downloads.probeSizeLabel(GtfsSources.SUBWAY)) }
    }

    fun startDownload() {
        downloads.enqueue()
        _gate.value = GateState.Downloading(0f)
    }

    private fun observeDownload() {
        if (gateObserved) return
        gateObserved = true
        viewModelScope.launch {
            downloads.workInfoFlow().collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.ENQUEUED -> _gate.value = GateState.Downloading(0f)
                    WorkInfo.State.RUNNING -> {
                        val fraction = info.progress.getFloat(SubwayDownloadWorker.KEY_FRACTION, 0f)
                        _gate.value = GateState.Downloading(fraction)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        repo.refresh(GtfsSources.SUBWAY)
                        _gate.value = GateState.Ready
                    }
                    WorkInfo.State.FAILED -> {
                        val reason = info.outputData.getString(SubwayDownloadWorker.KEY_ERROR) ?: "Download failed"
                        _gate.value = GateState.Failed(reason)
                    }
                    WorkInfo.State.CANCELLED -> _gate.value = GateState.Failed("Cancelled")
                    WorkInfo.State.BLOCKED -> Unit
                }
            }
        }
    }

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
     * In practice the subway route list is already on disk by the time this
     * runs, since the first-run sheet fetches it before Home is reachable.
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
        repo.routeListFor(GtfsSources.SUBWAY)?.let { stubs ->
            _subway.value = Catalogue.Ready(stubs)
            prefetchAllDetail(GtfsSources.SUBWAY, stubs, Mode.TRAIN, null)
            return
        }
        _subway.value = Catalogue.Working("subway", true)
        viewModelScope.launch {
            repo.loadRouteList(GtfsSources.SUBWAY, Mode.TRAIN, null, onProgress = { p -> _subway.value = p.toCatalogue() })
                .fold(
                    onSuccess = {
                        _subway.value = Catalogue.Ready(it)
                        prefetchAllDetail(GtfsSources.SUBWAY, it, Mode.TRAIN, null)
                    },
                    onFailure = { _subway.value = fallback(Mode.TRAIN, it) }
                )
        }
    }

    /** BUS tap on a borough: fetch just that borough's route list (codes + names). */
    fun loadBorough(borough: String) {
        if (!BuildConfig.USE_LIVE_FEEDS) { seedInto(_bus, Mode.BUS, borough); return }
        val url = GtfsSources.BUS[borough] ?: return
        repo.routeListFor(url)?.let { stubs ->
            _bus.value = Catalogue.Ready(stubs)
            prefetchAllDetail(url, stubs, Mode.BUS, borough)
            return
        }
        busJob?.cancel()
        _bus.value = Catalogue.Working(borough, true)
        busJob = viewModelScope.launch {
            repo.loadRouteList(url, Mode.BUS, borough, onProgress = { p -> _bus.value = p.toCatalogue() })
                .fold(
                    onSuccess = {
                        _bus.value = Catalogue.Ready(it)
                        prefetchAllDetail(url, it, Mode.BUS, borough)
                    },
                    onFailure = { _bus.value = fallback(Mode.BUS, it, borough) }
                )
        }
    }

    /**
     * Fired right after a feed's route list is in hand: parse every route's
     * stops in one pass, in the background, so a tap that lands a moment
     * later usually finds the store already warm instead of triggering its
     * own scan. A no-op if the store already has the full feed.
     */
    private fun prefetchAllDetail(feedUrl: String, stubs: List<RouteStub>, mode: Mode, borough: String?) {
        if (!BuildConfig.USE_LIVE_FEEDS) return
        viewModelScope.launch { repo.loadAllRouteDetail(feedUrl, stubs, mode, borough) }
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
