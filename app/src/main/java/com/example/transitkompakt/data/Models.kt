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
    val stops: List<String>
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
    val stopNames: Set<String> = emptySet()
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
