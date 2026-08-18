package com.example.transitkompakt.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Foreground-only data layer.
 *
 *  - Route catalogue comes from MTA GTFS static (every subway line, every bus
 *    route). A borough/mode tap fetches just that feed's route list
 *    (routes.txt — fast); stop detail (trips.txt + stop_times.txt) is filled
 *    in either by a background full-feed pass right after the route list
 *    loads, or by a per-route fallback if a tap lands before that finishes.
 *    All of it — route list and stop detail alike, subway and bus alike — is
 *    persisted to disk via [GtfsCacheStore] and kept indefinitely: this is
 *    static schedule data, not live status, so unlike alerts there is no
 *    expiry. The only ways it goes stale are the user's own choice — clearing
 *    app storage or reinstalling. assets/routes.json is a first-run fallback
 *    so the app is never empty on a device that has not been online.
 *  - Live alerts are pulled per mode and cached in memory for CACHE_TTL_MS —
 *    the one thing here that must keep refreshing on its own, since it's
 *    live service status rather than schedule structure.
 *  - No WorkManager work runs from here — the subway first-run download is
 *    driven by its own worker (see GtfsDownloadWorker); this class only reads
 *    and writes the store it leaves behind.
 */
class TransitRepository(private val context: Context) {

    private val client = MtaAlertsClient()
    private val importer = GtfsImporter(context)
    private val store = GtfsCacheStore(context)
    private val json = Json { ignoreUnknownKeys = true }

    private var seed: Catalog? = null
    private val records = ConcurrentHashMap<String, GtfsFeedRecord>()
    private val alertCache = ConcurrentHashMap<Mode, AlertBundle>()

    /** Bundled fallback: the small hand-checked set, used until a feed lands. */
    suspend fun seedCatalog(): Catalog = seed ?: withContext(Dispatchers.IO) {
        val text = context.assets.open("routes.json").bufferedReader().use { it.readText() }
        json.decodeFromString(Catalog.serializer(), text).also { seed = it }
    }

    val boroughs: List<String> get() = GtfsSources.BUS.keys.toList()

    private fun recordFor(feedUrl: String): GtfsFeedRecord =
        records[feedUrl] ?: (store.read(feedUrl) ?: GtfsFeedRecord()).also { records[feedUrl] = it }

    private fun updateRecord(feedUrl: String, transform: (GtfsFeedRecord) -> GtfsFeedRecord) {
        val updated = transform(recordFor(feedUrl))
        records[feedUrl] = updated
        store.write(feedUrl, updated)
    }

    fun routeListFor(feedUrl: String): List<RouteStub>? =
        recordFor(feedUrl).routes.takeIf { it.isNotEmpty() }

    suspend fun loadRouteList(
        feedUrl: String,
        mode: Mode,
        borough: String?,
        onProgress: (GtfsImporter.Progress) -> Unit = {},
        onBytes: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<List<RouteStub>> {
        routeListFor(feedUrl)?.let { return Result.success(it) }
        return importer.importRouteList(
            feedUrl, mode, borough, onProgress,
            onMeta = { meta ->
                updateRecord(feedUrl) {
                    it.copy(etag = meta.etag, lastModified = meta.lastModified, contentLength = meta.contentLength)
                }
            },
            onBytes = onBytes
        ).onSuccess { stubs ->
            updateRecord(feedUrl) { it.copy(routes = stubs, fetchedAtMillis = System.currentTimeMillis()) }
        }
    }

    /**
     * Re-reads a feed's record from disk, discarding whatever this instance
     * had cached in memory. Needed after a *different* TransitRepository
     * instance — the download worker builds its own — has written fresh data
     * for the same feed, since each instance only reads a feed from disk once
     * and mirrors it in memory after that.
     */
    fun refresh(feedUrl: String) {
        records[feedUrl] = store.read(feedUrl) ?: GtfsFeedRecord()
    }

    /** Whatever stop data this feed has so far — partial until [isDetailComplete]. */
    fun cachedRouteDetail(feedUrl: String, routeId: String): List<Route>? =
        recordFor(feedUrl).detail.filter { it.id.startsWith("${routeId}_") }.takeIf { it.isNotEmpty() }

    /** True once the background full-feed pass has covered every route. */
    fun isDetailComplete(feedUrl: String): Boolean = recordFor(feedUrl).detailComplete

    /** Fired on a route tap: fills in just that route if the full-feed pass hasn't finished yet. */
    suspend fun loadRouteDetail(
        feedUrl: String,
        routeId: String,
        code: String,
        name: String,
        mode: Mode,
        borough: String?
    ): Result<List<Route>> {
        cachedRouteDetail(feedUrl, routeId)?.let { return Result.success(it) }
        return importer.importRouteDetail(feedUrl, routeId, code, name, mode, borough)
            .onSuccess { fresh -> updateRecord(feedUrl) { it.copy(detail = mergeDetail(it.detail, fresh)) } }
    }

    /** Fired in the background right after a feed's route list loads: every route at once. */
    suspend fun loadAllRouteDetail(
        feedUrl: String,
        stubs: List<RouteStub>,
        mode: Mode,
        borough: String?
    ): Result<List<Route>> {
        val existing = recordFor(feedUrl)
        if (existing.detailComplete) return Result.success(existing.detail)
        return importer.importAllRouteDetail(feedUrl, stubs, mode, borough)
            .onSuccess { fresh -> updateRecord(feedUrl) { it.copy(detail = fresh, detailComplete = true) } }
    }

    fun cachedAlerts(mode: Mode): AlertBundle? =
        alertCache[mode]?.takeIf { System.currentTimeMillis() - it.fetchedAtMillis < CACHE_TTL_MS }

    /** Fired on the TRAIN / BUS tap, alongside the route-list prefetch. */
    suspend fun prefetchAlerts(mode: Mode): Result<AlertBundle> {
        cachedAlerts(mode)?.let { return Result.success(it) }
        return runCatching { client.fetch(mode) }.onSuccess { alertCache[mode] = it }
    }

    suspend fun refreshAlerts(mode: Mode): Result<AlertBundle> =
        runCatching { client.fetch(mode) }.onSuccess { alertCache[mode] = it }

    fun alertsFor(route: Route): List<Alert> {
        val bundle = cachedAlerts(route.transitMode) ?: return emptyList()
        return bundle.alertsByRoute[route.code].orEmpty()
    }

    /** Gate check for the first-run sheet: is the whole subway catalogue already local? */
    fun isSubwayCached(): Boolean {
        val r = recordFor(GtfsSources.SUBWAY)
        return r.routes.isNotEmpty() && r.detailComplete
    }

    private fun mergeDetail(existing: List<Route>, fresh: List<Route>): List<Route> {
        val byId = existing.associateBy { it.id }.toMutableMap()
        fresh.forEach { byId[it.id] = it }
        return byId.values.sortedBy { it.id }
    }

    companion object {
        /** Alerts only — route/stop structure has no expiry, see the class doc. */
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
