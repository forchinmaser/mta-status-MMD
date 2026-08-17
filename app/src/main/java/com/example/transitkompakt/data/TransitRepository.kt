package com.example.transitkompakt.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Foreground-only data layer.
 *
 *  - Route catalogue comes from MTA GTFS static (every subway line, every bus
 *    route). Feeds are downloaded on demand, parsed once, and kept on disk;
 *    assets/routes.json is only a first-run fallback so the app is never empty
 *    on a device that has not been online yet.
 *  - Live alerts are pulled per mode and cached in memory for CACHE_TTL_MS.
 *  - No WorkManager, AlarmManager, JobScheduler, Service, or boot receiver
 *    exists in this project, so nothing refreshes while the app is closed.
 */
class TransitRepository(private val context: Context) {

    private val client = MtaAlertsClient()
    private val importer = GtfsImporter(context)
    private val json = Json { ignoreUnknownKeys = true }

    private var seed: Catalog? = null
    private val routesByFeed = mutableMapOf<String, List<Route>>()
    private val alertCache = mutableMapOf<Mode, AlertBundle>()

    /** Bundled fallback: the small hand-checked set, used until a feed lands. */
    suspend fun seedCatalog(): Catalog = seed ?: withContext(Dispatchers.IO) {
        val text = context.assets.open("routes.json").bufferedReader().use { it.readText() }
        json.decodeFromString(Catalog.serializer(), text).also { seed = it }
    }

    val boroughs: List<String> get() = GtfsSources.BUS.keys.toList()

    fun routesFor(feedUrl: String): List<Route>? =
        routesByFeed[feedUrl] ?: importer.parsedOrNull(feedUrl)?.also { routesByFeed[feedUrl] = it }

    suspend fun loadRoutes(
        feedUrl: String,
        mode: Mode,
        borough: String?,
        onProgress: (GtfsImporter.Progress) -> Unit = {}
    ): Result<List<Route>> {
        routesFor(feedUrl)?.let { return Result.success(it) }
        return importer.import(feedUrl, mode, borough, onProgress)
            .onSuccess { routesByFeed[feedUrl] = it }
    }

    fun route(id: String): Route? = routesByFeed.values.firstNotNullOfOrNull { list ->
        list.firstOrNull { it.id == id }
    } ?: seed?.routes?.firstOrNull { it.id == id }

    fun cachedAlerts(mode: Mode): AlertBundle? =
        alertCache[mode]?.takeIf { System.currentTimeMillis() - it.fetchedAtMillis < CACHE_TTL_MS }

    /** Fired on the TRAIN / BUS tap, alongside the route-feed prefetch. */
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

    companion object { const val CACHE_TTL_MS = 5 * 60 * 1000L }
}
