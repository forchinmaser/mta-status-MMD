package com.example.transitkompakt.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Mode { TRAIN, BUS }

@Serializable
data class Route(
    val id: String,
    val mode: String,
    val code: String,
    val name: String,
    val direction: String,
    val borough: String? = null,
    val stops: List<String>,
    /** Same order/length as [stops]; the GTFS stop_id behind each display name. */
    val stopIds: List<String> = emptyList()
) {
    val transitMode: Mode get() = if (mode == "train") Mode.TRAIN else Mode.BUS
}

/**
 * A route's code and name only — what a route grid needs to render. Cheap to
 * build (routes.txt alone), unlike [Route], which needs the whole feed parsed.
 */
data class RouteStub(
    val id: String,
    val mode: String,
    val code: String,
    val name: String,
    val borough: String? = null
) {
    val transitMode: Mode get() = if (mode == "train") Mode.TRAIN else Mode.BUS
}

/** Bundled first-run fallback only; the live catalogue comes from GTFS. */
@Serializable
data class Catalog(
    @SerialName("subwayLines") val subwayLines: List<String>,
    @SerialName("boroughs") val boroughs: List<String>,
    @SerialName("busRoutesByBorough") val busRoutesByBorough: Map<String, List<String>>,
    @SerialName("routes") val routes: List<Route>
)

/** One rider alert, normalised out of the MTA GTFS-realtime service-alert feed. */
data class Alert(
    val type: String,
    val text: String,
    val routeCodes: Set<String>,
    /**
     * MTA's informed_entity.stop_id, verbatim — not a display name. Only
     * present when MTA gives stop-level granularity; many alerts are
     * route-wide and carry none, which is correctly "no stop affected".
     */
    val stopIds: Set<String> = emptySet()
)

data class AlertBundle(
    val alertsByRoute: Map<String, List<Alert>>,
    val fetchedAtMillis: Long
)

sealed interface Feed {
    data object Idle : Feed
    data object Loading : Feed
    data class Ready(val bundle: AlertBundle) : Feed
    data class Failed(val reason: String) : Feed
}
