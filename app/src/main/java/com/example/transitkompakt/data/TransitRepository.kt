package com.example.transitkompakt.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Foreground-only data layer.
 *
 *  - Route catalogue comes from MTA GTFS static (every subway line, every bus
 *    route). A borough/mode tap fetches just that feed's route list
 *    (routes.txt — fast); a route tap fetches that route's stop diagram on
 *    top of it (trips.txt + stop_times.txt, filtered to that route's trips).
 *    Route detail is cached in memory for CACHE_TTL_MS — the same window as
 *    alerts, deliberately: the stop diagram's filled/empty dots are a join of
 *    the two at render time (GtfsImporter.MAX_KEPT_ZIP_BYTES governs whether
 *    a re-parse after expiry costs network or just local CPU). The route
 *    list itself has no expiry — it only changes if MTA restructures a whole
 *    line, not within a session. assets/routes.json is a first-run fallback
 *    so the app is never empty on a device that has not been online.
 *  - Live alerts are pulled per mode and cached in memory for CACHE_TTL_MS.
 *  - No WorkManager, AlarmManager, JobScheduler, Service, or boot receiver
 *    exists in this project, so nothing refreshes while the app is closed.
 */
class TransitRepository(private val context: Context) {

    private val client = MtaAlertsClient()
    private val importer = GtfsImporter(context)
    private val json = Json { ignoreUnknownKeys = true }

    private var seed: Catalog? = null
    private val routeListCache = mutableMapOf<String, List<RouteStub>>()
    private val routeDetailCache = mutableMapOf<String, Pair<List<Route>, Long>>()
    private val alertCache = mutableMapOf<Mode, AlertBundle>()

    /** Bundled fallback: the small hand-checked set, used until a feed lands. */
    suspend fun seedCatalog(): Catalog = seed ?: withContext(Dispatchers.IO) {
        val text = context.assets.open("routes.json").bufferedReader().use { it.readText() }
        json.decodeFromString(Catalog.serializer(), text).also { seed = it }
    }

    val boroughs: List<String> get() = GtfsSources.BUS.keys.toList()

    fun routeListFor(feedUrl: String): List<RouteStub>? = routeListCache[feedUrl]

    suspend fun loadRouteList(
        feedUrl: String,
        mode: Mode,
        borough: String?,
        onProgress: (GtfsImporter.Progress) -> Unit = {}
    ): Result<List<RouteStub>> {
        routeListCache[feedUrl]?.let { return Result.success(it) }
        return importer.importRouteList(feedUrl, mode, borough, onProgress)
            .onSuccess { routeListCache[feedUrl] = it }
    }

    fun cachedRouteDetail(feedUrl: String, routeId: String): List<Route>? {
        val (routes, fetchedAt) = routeDetailCache[detailKey(feedUrl, routeId)] ?: return null
        return routes.takeIf { System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS }
    }

    /** Fired on a route tap: the stop diagram(s) for one route, both directions. */
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
            .onSuccess { routeDetailCache[detailKey(feedUrl, routeId)] = it to System.currentTimeMillis() }
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

    private fun detailKey(feedUrl: String, routeId: String) = "$feedUrl|$routeId"

    companion object {
        /** Shared by alerts and route detail — see the class doc for why. */
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
