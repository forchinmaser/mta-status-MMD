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

/** Versioning signal captured off the one real download of a feed, if any. */
data class FeedMeta(val etag: String?, val lastModified: String?, val contentLength: Long?)

/**
 * Turns a GTFS static zip into route data, in three independently-priced ways:
 *
 *  - [importRouteList] reads only routes.txt (tens of KB) to populate a grid
 *    of route codes/names. This is what a borough tap triggers, and it's the
 *    only thing that has to finish before the grid can render.
 *  - [importAllRouteDetail] reads trips.txt and stop_times.txt once for every
 *    route in the feed, in a single pass. TransitViewModel fires this in the
 *    background right after the grid populates, so by the time a rider has
 *    actually picked a route, its stop diagram is often already sitting in
 *    TransitRepository's persistent store.
 *  - [importRouteDetail] does the same thing but filtered to one route's
 *    trips — the fallback for a tap that lands before the background pass
 *    above has finished. It still has to stream the whole stop_times.txt to
 *    find its rows (the file isn't indexed), so it's not cheap on its own;
 *    it exists so a fast tap isn't blocked on the full-feed pass, not as a
 *    substitute for it.
 *
 * All three share one downloaded zip ([zipFile]). What happens to that zip
 * after use depends on its size: at or under [MAX_KEPT_ZIP_BYTES] it's left
 * on disk indefinitely, so a later re-parse costs no network. Over that size
 * — a big borough's stop_times.txt can be "hundreds of megabytes
 * uncompressed" — it's deleted right after use, and the next request for
 * that feed re-downloads it from scratch. That trades bandwidth for bounded
 * storage on the few feeds large enough to matter. This is independent of
 * how long the *parsed* route data is kept — that's TransitRepository's
 * persistent store, which (unlike this zip) never expires on its own.
 */
class GtfsImporter(private val context: Context) {

    private val http = OkHttpClient()

    sealed interface Progress {
        data class Downloading(val label: String) : Progress
        data class Parsing(val label: String) : Progress
        data class Done(val routes: Int) : Progress
    }

    private fun zipFile(url: String) = File(context.filesDir, GtfsSources.keyFor(url) + ".zip")

    private class Downloaded(val file: File, val meta: FeedMeta?)

    /**
     * Downloads to a `.part` sibling and renames into place only once the
     * whole body has landed. A download interrupted mid-stream (app killed,
     * process reclaimed) leaves only the `.part` file — never a file at the
     * real zip path — so the next attempt can't mistake a truncated file for
     * a complete cache and try to parse garbage out of it.
     */
    private suspend fun ensureDownloaded(
        url: String,
        label: String,
        onProgress: (Progress) -> Unit,
        onBytes: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Downloaded {
        val zip = zipFile(url)
        if (zip.exists() && zip.length() > 0L) return Downloaded(zip, null)
        onProgress(Progress.Downloading(label))
        val tmp = File(zip.parentFile, zip.name + ".part")
        var meta: FeedMeta? = null
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code} for $label")
            val body = res.body!!
            val total = body.contentLength().takeIf { it >= 0 }
            meta = FeedMeta(res.header("ETag"), res.header("Last-Modified"), total)
            tmp.outputStream().use { out -> body.byteStream().copyToWithProgress(out, total, onBytes) }
        }
        if (!tmp.renameTo(zip)) { tmp.copyTo(zip, overwrite = true); tmp.delete() }
        return Downloaded(zip, meta)
    }

    /** Deletes the zip once it's served its purpose, if it's over the size we keep. */
    private fun releaseIfOversized(zip: File) {
        if (zip.exists() && zip.length() > MAX_KEPT_ZIP_BYTES) zip.delete()
    }

    /**
     * routes.txt only: id, code, name. Fast — no trips/stops/stop_times read
     * at all. [onMeta] fires only when this call causes a real download (the
     * zip wasn't already cached), since that's the only time fresh
     * ETag/Last-Modified/Content-Length headers exist to report.
     */
    suspend fun importRouteList(
        url: String,
        mode: Mode,
        borough: String?,
        onProgress: (Progress) -> Unit = {},
        onMeta: (FeedMeta) -> Unit = {},
        onBytes: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<List<RouteStub>> = withContext(Dispatchers.IO) {
        runCatching {
            val label = borough ?: "subway"
            val downloaded = ensureDownloaded(url, label, onProgress, onBytes)
            downloaded.meta?.let(onMeta)
            val zip = downloaded.file
            try {
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
            } finally {
                releaseIfOversized(zip)
            }
        }.onFailure { Log.w(TAG, "route list import failed for $url", it) }
    }

    /**
     * trips.txt + stop_times.txt for every route in [stubs], in one pass —
     * the background prefetch TransitViewModel fires right after a grid
     * populates, so most taps land on an already-warm store instead of
     * paying for their own scan of a potentially huge stop_times.txt.
     */
    suspend fun importAllRouteDetail(
        url: String,
        stubs: List<RouteStub>,
        mode: Mode,
        borough: String?
    ): Result<List<Route>> = withContext(Dispatchers.IO) {
        runCatching {
            val zip = ensureDownloaded(url, borough ?: "subway", onProgress = {}).file
            try {
                val routeMeta = stubs.associateBy({ it.id }, { it.code to it.name })

                val tripRoute = HashMap<String, String>()      // trip_id -> route_id
                val tripDir = HashMap<String, String>()        // trip_id -> direction_id
                val headsign = HashMap<String, String>()       // "route|dir" -> headsign
                readEntry(zip, "trips.txt") { h, row ->
                    val trip = row.at(h["trip_id"])
                    val route = row.at(h["route_id"])
                    if (trip.isEmpty() || route.isEmpty() || route !in routeMeta) return@readEntry
                    val dir = row.at(h["direction_id"]).ifEmpty { "0" }
                    tripRoute[trip] = route
                    tripDir[trip] = dir
                    val hs = row.at(h["trip_headsign"])
                    if (hs.isNotEmpty()) headsign.putIfAbsent("$route|$dir", hs)
                }

                val stopName = HashMap<String, String>()
                readEntry(zip, "stops.txt") { h, row ->
                    val id = row.at(h["stop_id"])
                    if (id.isNotEmpty()) stopName[id] = row.at(h["stop_name"])
                }

                // stop_times pass 1: trip length, every wanted route's trips.
                val tripLen = HashMap<String, Int>()
                readEntry(zip, "stop_times.txt") { h, row ->
                    val trip = row.at(h["trip_id"])
                    if (trip in tripRoute) tripLen[trip] = (tripLen[trip] ?: 0) + 1
                }

                // longest trip per route+direction is the full-length pattern riders think of.
                val best = HashMap<String, String>()            // "route|dir" -> trip_id
                for ((trip, len) in tripLen) {
                    val route = tripRoute[trip] ?: continue
                    val key = "$route|${tripDir[trip] ?: "0"}"
                    val incumbent = best[key]
                    if (incumbent == null || len > (tripLen[incumbent] ?: 0)) best[key] = trip
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
                for ((key, trip) in best) {
                    val routeId = key.substringBefore('|')
                    val dir = key.substringAfter('|')
                    val (code, name) = routeMeta[routeId] ?: continue
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
                        direction = headsign["$routeId|$dir"]?.let { "To $it" }
                            ?: "${stops.first()} → ${stops.last()}",
                        borough = borough,
                        stops = stops,
                        stopIds = stopIds
                    )
                }
                coroutineContext.ensureActive()
                out.sortedBy { it.id }
            } finally {
                releaseIfOversized(zip)
            }
        }.onFailure { Log.w(TAG, "full feed detail import failed for $url", it) }
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
            val zip = ensureDownloaded(url, borough ?: "subway", onProgress = {}).file
            try {
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
            } finally {
                releaseIfOversized(zip)
            }
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

    companion object {
        private const val TAG = "GtfsImporter"
        const val MAX_KEPT_ZIP_BYTES = 250L * 1024 * 1024
    }
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

/**
 * Like [java.io.InputStream.copyTo], but reports cumulative bytes at most
 * 4/sec — E Ink shouldn't repaint a progress bar continuously for the length
 * of a long download — plus once more at the end so the final value is exact.
 */
private fun java.io.InputStream.copyToWithProgress(
    out: java.io.OutputStream,
    total: Long?,
    onBytes: (bytesRead: Long, totalBytes: Long?) -> Unit
) {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    var lastReportAt = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        copied += n
        val now = System.currentTimeMillis()
        if (now - lastReportAt >= 250) {
            onBytes(copied, total)
            lastReportAt = now
        }
    }
    onBytes(copied, total)
}
