package com.example.transitkompakt.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Turns a GTFS static zip into the app's Route list.
 *
 * Memory discipline matters here: the Queens bus stop_times.txt is hundreds of
 * megabytes uncompressed and the Kompakt is not a workstation. Nothing is ever
 * held whole. The zip streams from the network through ZipInputStream, each
 * CSV is read line by line, and stop_times is walked twice:
 *
 *   pass 1 — count stops per trip_id (an int per trip)
 *   pass 2 — keep the stop list for one representative trip per route+direction,
 *            namely the longest, which is the full-length pattern riders think of
 *
 * so peak memory is roughly one route's worth of stops, not one feed's worth.
 */
class GtfsImporter(private val context: Context) {

    private val http = OkHttpClient()
    private val json = Json { prettyPrint = false }

    sealed interface Progress {
        data class Downloading(val label: String) : Progress
        data class Parsing(val label: String) : Progress
        data class Done(val routes: Int) : Progress
    }

    private fun cacheFile(url: String) = File(context.cacheDir, GtfsSources.keyFor(url) + ".zip")
    private fun parsedFile(url: String) = File(context.filesDir, GtfsSources.keyFor(url) + ".json")

    fun parsedOrNull(url: String): List<Route>? {
        val f = parsedFile(url)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(ListSerializerRoute, f.readText()) }.getOrNull()
    }

    /**
     * Downloads (if not cached) and parses one feed. Safe to call repeatedly;
     * a parsed feed short-circuits without touching the network.
     */
    suspend fun import(
        url: String,
        mode: Mode,
        borough: String?,
        onProgress: (Progress) -> Unit = {}
    ): Result<List<Route>> = withContext(Dispatchers.IO) {
        parsedOrNull(url)?.let { return@withContext Result.success(it) }
        runCatching {
            val label = borough ?: "subway"
            val zip = cacheFile(url)
            if (!zip.exists() || zip.length() == 0L) {
                onProgress(Progress.Downloading(label))
                http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                    if (!res.isSuccessful) error("HTTP ${res.code} for $label")
                    zip.outputStream().use { out -> res.body!!.byteStream().copyTo(out, 64 * 1024) }
                }
            }
            onProgress(Progress.Parsing(label))
            val routes = parse(zip, mode, borough)
            parsedFile(url).writeText(json.encodeToString(ListSerializerRoute, routes))
            onProgress(Progress.Done(routes.size))
            routes
        }.onFailure { Log.w(TAG, "import failed for $url", it) }
    }

    private suspend fun parse(zip: File, mode: Mode, borough: String?): List<Route> {
        // routes.txt
        val routeName = HashMap<String, Pair<String, String>>()   // route_id -> short, long
        readEntry(zip, "routes.txt") { h, row ->
            val id = row.at(h["route_id"])
            if (id.isNotEmpty()) {
                val short = row.at(h["route_short_name"]).ifEmpty { id }
                routeName[id] = short to row.at(h["route_long_name"])
            }
        }

        // trips.txt
        val tripRoute = HashMap<String, String>()      // trip_id -> route_id
        val tripDir = HashMap<String, String>()        // trip_id -> direction_id
        val headsign = HashMap<String, String>()       // route_id|dir -> headsign
        readEntry(zip, "trips.txt") { h, row ->
            val trip = row.at(h["trip_id"])
            val route = row.at(h["route_id"])
            if (trip.isEmpty() || route.isEmpty()) return@readEntry
            val dir = row.at(h["direction_id"]).ifEmpty { "0" }
            tripRoute[trip] = route
            tripDir[trip] = dir
            val hs = row.at(h["trip_headsign"])
            if (hs.isNotEmpty()) headsign.putIfAbsent("$route|$dir", hs)
        }

        // stops.txt
        val stopName = HashMap<String, String>()
        readEntry(zip, "stops.txt") { h, row ->
            val id = row.at(h["stop_id"])
            if (id.isNotEmpty()) stopName[id] = row.at(h["stop_name"])
        }

        // stop_times pass 1: how many stops does each trip have?
        val tripLen = HashMap<String, Int>()
        readEntry(zip, "stop_times.txt") { h, row ->
            val trip = row.at(h["trip_id"])
            if (trip.isNotEmpty()) tripLen[trip] = (tripLen[trip] ?: 0) + 1
        }

        // pick the longest trip for each route+direction
        val best = HashMap<String, String>()           // route|dir -> trip_id
        for ((trip, len) in tripLen) {
            val route = tripRoute[trip] ?: continue
            val key = "$route|${tripDir[trip] ?: "0"}"
            val incumbent = best[key]
            if (incumbent == null || len > (tripLen[incumbent] ?: 0)) best[key] = trip
        }
        val wanted = best.values.toHashSet()

        // stop_times pass 2: collect ordered stops for those trips only
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
            val (short, long) = routeName[routeId] ?: continue
            val stops = seq[trip].orEmpty()
                .sortedBy { it.first }
                .map { stopName[it.second] ?: it.second }
                .dedupeAdjacent()
            if (stops.size < 2) continue
            out += Route(
                id = "${routeId}_$dir",
                mode = if (mode == Mode.TRAIN) "train" else "bus",
                code = short,
                name = long.ifEmpty { short },
                direction = headsign["$routeId|$dir"]?.let { "To $it" }
                    ?: "${stops.first()} \u2192 ${stops.last()}",
                borough = borough,
                stops = stops
            )
        }
        coroutineContext.ensureActive()
        return out.sortedWith(compareBy(RouteCodeComparator) { it.code })
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

private fun List<String>.dedupeAdjacent(): List<String> =
    filterIndexed { i, s -> i == 0 || s != this[i - 1] }

internal val ListSerializerRoute =
    kotlinx.serialization.builtins.ListSerializer(Route.serializer())
