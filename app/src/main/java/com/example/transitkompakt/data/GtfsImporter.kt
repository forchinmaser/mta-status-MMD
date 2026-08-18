package com.example.transitkompakt.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Turns a GTFS static zip into route data, in two independently-priced passes:
 *
 *  - [importRouteList] reads only routes.txt (tens of KB) to populate a grid
 *    of route codes/names. This is what a borough tap triggers.
 *  - [importRouteDetail] reads trips.txt and stop_times.txt, filtered to one
 *    route's trips only, to build that route's stop diagram(s). This is what
 *    a route tap triggers — the expensive parse happens for one route at a
 *    time instead of every route in the borough up front.
 *
 * Both share one cached zip download (cacheFile): the network fetch is the
 * same either way, since MTA only publishes whole-feed zips, but a borough's
 * stop_times.txt — "hundreds of megabytes uncompressed" for Queens buses —
 * is no longer streamed and parsed for every route just to show a chip grid.
 */
class GtfsImporter(private val context: Context) {

    private val http = OkHttpClient()

    sealed interface Progress {
        data class Downloading(val label: String) : Progress
        data class Parsing(val label: String) : Progress
        data class Done(val routes: Int) : Progress
    }

    private fun cacheFile(url: String) = File(context.cacheDir, GtfsSources.keyFor(url) + ".zip")

    private suspend fun ensureDownloaded(url: String, label: String, onProgress: (Progress) -> Unit): File {
        val zip = cacheFile(url)
        if (!zip.exists() || zip.length() == 0L) {
            onProgress(Progress.Downloading(label))
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code} for $label")
                zip.outputStream().use { out -> res.body!!.byteStream().copyTo(out, 64 * 1024) }
            }
        }
        return zip
    }

    /** routes.txt only: id, code, name. Fast — no trips/stops/stop_times read at all. */
    suspend fun importRouteList(
        url: String,
        mode: Mode,
        borough: String?,
        onProgress: (Progress) -> Unit = {}
    ): Result<List<RouteStub>> = withContext(Dispatchers.IO) {
        runCatching {
            val label = borough ?: "subway"
            val zip = ensureDownloaded(url, label, onProgress)
            onProgress(Progress.Parsing(label))
            val out = ArrayList<RouteStub>()
            readEntry(zip, "routes.txt") { h, row ->
                val id = row.at(h["route_id"])
                if (id.isNotEmpty()) {
                    val short = row.at(h["route_short_name"]).ifEmpty { id }
                    val long = row.at(h["route_long_name"])
                    out += RouteStub(
                        id = id,
                        mode = if (mode == Mode.TRAIN) "train" else "bus",
                        code = short,
                        name = long.ifEmpty { short },
                        borough = borough
                    )
                }
            }
            coroutineContext.ensureActive()
            onProgress(Progress.Done(out.size))
            out.sortedWith(compareBy(RouteCodeComparator) { it.code })
        }.onFailure { Log.w(TAG, "route list import failed for $url", it) }
    }

    /**
     * trips.txt + stop_times.txt, filtered to [routeId]'s trips only. Returns
     * one Route per direction actually present (usually two). Assumes the zip
     * is already cached by a prior [importRouteList] call; downloads it if not.
     */
    suspend fun importRouteDetail(
        url: String,
        routeId: String,
        code: String,
        name: String,
        mode: Mode,
        borough: String?
    ): Result<List<Route>> = withContext(Dispatchers.IO) {
        runCatching {
            val zip = ensureDownloaded(url, borough ?: "subway") {}

            // trips.txt, kept only for this route.
            val tripDir = HashMap<String, String>()        // trip_id -> direction_id
            val headsign = HashMap<String, String>()       // direction -> headsign
            readEntry(zip, "trips.txt") { h, row ->
                val trip = row.at(h["trip_id"])
                val route = row.at(h["route_id"])
                if (trip.isEmpty() || route != routeId) return@readEntry
                val dir = row.at(h["direction_id"]).ifEmpty { "0" }
                tripDir[trip] = dir
                val hs = row.at(h["trip_headsign"])
                if (hs.isNotEmpty()) headsign.putIfAbsent(dir, hs)
            }
            check(tripDir.isNotEmpty()) { "No trips found for $code" }

            // stops.txt: stop_id -> stop_name.
            val stopName = HashMap<String, String>()
            readEntry(zip, "stops.txt") { h, row ->
                val id = row.at(h["stop_id"])
                if (id.isNotEmpty()) stopName[id] = row.at(h["stop_name"])
            }

            // stop_times pass 1: trip length, this route's trips only.
            val tripLen = HashMap<String, Int>()
            readEntry(zip, "stop_times.txt") { h, row ->
                val trip = row.at(h["trip_id"])
                if (trip in tripDir) tripLen[trip] = (tripLen[trip] ?: 0) + 1
            }

            // longest trip per direction is the full-length pattern riders think of.
            val best = HashMap<String, String>()            // dir -> trip_id
            for ((trip, len) in tripLen) {
                val dir = tripDir[trip] ?: continue
                val incumbent = best[dir]
                if (incumbent == null || len > (tripLen[incumbent] ?: 0)) best[dir] = trip
            }
            val wanted = best.values.toHashSet()

            // stop_times pass 2: ordered stops for those trips only.
            val seq = HashMap<String, MutableList<Pair<Int, String>>>()
            readEntry(zip, "stop_times.txt") { h, row ->
                val trip = row.at(h["trip_id"])
                if (trip !in wanted) return@readEntry
                val stop = row.at(h["stop_id"])
                val order = row.at(h["stop_sequence"]).toIntOrNull() ?: return@readEntry
                seq.getOrPut(trip) { ArrayList(40) }.add(order to stop)
            }

            val out = ArrayList<Route>(best.size)
            for ((dir, trip) in best) {
                val ordered = seq[trip].orEmpty().sortedBy { it.first }
                val pairs = ordered.map { (stopName[it.second] ?: it.second) to it.second }
                    .dedupeAdjacentBy { it.second }
                if (pairs.size < 2) continue
                val stops = pairs.map { it.first }
                val stopIds = pairs.map { it.second }
                out += Route(
                    id = "${routeId}_$dir",
                    mode = if (mode == Mode.TRAIN) "train" else "bus",
                    code = code,
                    name = name.ifEmpty { code },
                    direction = headsign[dir]?.let { "To $it" }
                        ?: "${stops.first()} → ${stops.last()}",
                    borough = borough,
                    stops = stops,
                    stopIds = stopIds
                )
            }
            coroutineContext.ensureActive()
            check(out.isNotEmpty()) { "No stop data for $code" }
            out.sortedBy { it.id }
        }.onFailure { Log.w(TAG, "route detail import failed for $url/$routeId", it) }
    }

    /** Streams one CSV entry out of the zip without extracting the archive. */
    private suspend fun readEntry(zip: File, name: String, onRow: (Map<String, Int>, List<String>) -> Unit) {
        java.io.BufferedInputStream(zip.inputStream(), 64 * 1024).use { raw ->
            ZipInputStream(raw).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name.substringAfterLast('/') == name) {
                        val reader = zin.bufferedReader()
                        val headerLine = reader.readLine() ?: return
                        val h = Csv.header(headerLine)
                        var n = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            onRow(h, Csv.split(line))
                            if (++n % 20_000 == 0) coroutineContext.ensureActive()
                        }
                        return
                    }
                    entry = zin.nextEntry
                }
            }
        }
    }

    companion object { private const val TAG = "GtfsImporter" }
}

/** "M15" before "M100"; letters before numbers, as riders expect. */
object RouteCodeComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val pa = a.takeWhile { !it.isDigit() }
        val pb = b.takeWhile { !it.isDigit() }
        if (pa != pb) return pa.compareTo(pb)
        val na = a.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        val nb = b.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        if (na != nb) return na - nb
        return a.compareTo(b)
    }
}

/** Shared ordering for route-code lists. */
val routeCodeOrder: Comparator<String> = RouteCodeComparator

private fun <T> List<T>.dedupeAdjacentBy(key: (T) -> Any?): List<T> =
    filterIndexed { i, s -> i == 0 || key(s) != key(this[i - 1]) }
