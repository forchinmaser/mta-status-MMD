package com.example.transitkompakt.data

/**
 * MTA GTFS static feeds. These are the full published schedules — every subway
 * line and every bus route — so the app no longer ships a hand-picked subset.
 *
 * Each feed is a zip of CSV files. We only read four of them:
 *   routes.txt     route_id, route_short_name, route_long_name
 *   trips.txt      trip_id -> route_id, direction_id, trip_headsign
 *   stop_times.txt trip_id -> ordered stop_id list
 *   stops.txt      stop_id -> stop_name
 *
 * Bus feeds are split per borough, which lines up with how the app navigates:
 * tapping a borough downloads only that borough.
 */
object GtfsSources {

    const val SUBWAY = "http://web.mta.info/developers/data/nyct/subway/google_transit.zip"

    /** Borough label as shown in the UI -> feed url. */
    val BUS: Map<String, String> = linkedMapOf(
        "Manhattan" to "http://web.mta.info/developers/data/nyct/bus/google_transit_manhattan.zip",
        "Brooklyn" to "http://web.mta.info/developers/data/nyct/bus/google_transit_brooklyn.zip",
        "Queens" to "http://web.mta.info/developers/data/nyct/bus/google_transit_queens.zip",
        "Bronx" to "http://web.mta.info/developers/data/nyct/bus/google_transit_bronx.zip",
        "Staten Island" to "http://web.mta.info/developers/data/nyct/bus/google_transit_staten_island.zip",
        "MTA Bus Company" to "http://web.mta.info/developers/data/busco/google_transit.zip"
    )

    /** Stable cache key per feed. */
    fun keyFor(url: String): String = url.substringAfterLast('/').substringBeforeLast('.') +
        "_" + url.substringBeforeLast('/').substringAfterLast('/')
}
