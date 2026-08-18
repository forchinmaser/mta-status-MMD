package com.example.transitkompakt.data

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One feed's persisted state: its route list, whatever stop detail has been
 * gathered for it so far, and the versioning signal from its one real
 * download. Written to filesDir as plain JSON, one file per feed, and kept
 * indefinitely — GTFS route/stop structure barely changes, so unlike alerts
 * there is no TTL here. The only ways this goes away are the ones the user
 * chose: clearing app storage or reinstalling.
 */
@Serializable
data class GtfsFeedRecord(
    val routes: List<RouteStub> = emptyList(),
    val detail: List<Route> = emptyList(),
    /** True once a full-feed pass (importAllRouteDetail) has covered every route. */
    val detailComplete: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    val contentLength: Long? = null,
    val fetchedAtMillis: Long = 0L
)

/** Reads and writes [GtfsFeedRecord]s, one JSON file per feed, in filesDir. */
class GtfsCacheStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(feedUrl: String) = File(context.filesDir, "gtfs_${GtfsSources.keyFor(feedUrl)}.json")

    fun read(feedUrl: String): GtfsFeedRecord? {
        val f = file(feedUrl)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(GtfsFeedRecord.serializer(), f.readText()) }.getOrNull()
    }

    fun write(feedUrl: String, record: GtfsFeedRecord) {
        file(feedUrl).writeText(json.encodeToString(GtfsFeedRecord.serializer(), record))
    }
}
