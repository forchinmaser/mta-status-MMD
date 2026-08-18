package com.example.transitkompakt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads MTA service alerts (GTFS-realtime, JSON flavour). These endpoints are
 * open — no API key. If MTA moves them, only these two constants change.
 *
 * Bus Time real-time vehicle positions (SIRI) DO need a key; this app does not
 * use them, since the screens only show alerts + static stop order.
 */
object MtaEndpoints {
    const val SUBWAY_ALERTS =
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json"
    const val BUS_ALERTS =
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fbus-alerts.json"
}

class MtaAlertsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(mode: Mode): AlertBundle = withContext(Dispatchers.IO) {
        val url = if (mode == Mode.TRAIN) MtaEndpoints.SUBWAY_ALERTS else MtaEndpoints.BUS_ALERTS
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            res.body?.string() ?: error("empty body")
        }
        AlertBundle(parse(body), System.currentTimeMillis())
    }

    private fun parse(body: String): Map<String, List<Alert>> {
        val root = json.parseToJsonElement(body).jsonObject
        val entities = root["entity"] as? JsonArray ?: return emptyMap()
        val out = mutableMapOf<String, MutableList<Alert>>()

        for (entity in entities) {
            val alert = entity.jsonObject["alert"]?.jsonObject ?: continue
            val header = translated(alert["header_text"]?.jsonObject)
            val bodyText = translated(alert["description_text"]?.jsonObject)
            val routes = mutableSetOf<String>()
            val stops = mutableSetOf<String>()
            (alert["informed_entity"] as? JsonArray)?.forEach { ie ->
                val o = ie.jsonObject
                o["route_id"]?.jsonPrimitive?.contentOrNullSafe()?.let { routes += it }
                o["stop_id"]?.jsonPrimitive?.contentOrNullSafe()?.let { stops += it }
            }
            if (routes.isEmpty()) continue
            val a = Alert(
                type = mtaAlertType(alert),
                text = bodyText.ifBlank { header }.trim(),
                routeCodes = routes,
                stopIds = stops
            )
            if (a.text.isBlank()) continue
            routes.forEach { out.getOrPut(it) { mutableListOf() } += a }
        }
        return out
    }

    private fun translated(node: JsonObject?): String {
        val list = node?.get("translation") as? JsonArray ?: return ""
        val en = list.firstOrNull { it.jsonObject["language"]?.jsonPrimitive?.contentOrNullSafe() == "en" }
            ?: list.firstOrNull()
        return normalise(en?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNullSafe().orEmpty())
    }

    /**
     * MTA's mercury alert text is not clean prose. It carries "[accessibility icon]"
     * and "[shuttle bus icon]" placeholders meant to be swapped for a glyph, "[N]"
     * route-bullet placeholders, and pipe-delimited station runs. Rendered verbatim
     * that reads as punctuation soup, so normalise at the feed boundary — the view
     * then only has to lay out lines.
     */
    private fun normalise(raw: String): String {
        var t = raw.replace("\r", "")
        t = ICON_TOKEN.replace(t, "")
        t = TAG.replace(t, "")
        // "[1][2][N]" -> "1 2 N", so route bullets read as a list
        t = BULLET_RUN.replace(t) { m ->
            BULLET.findAll(m.value).map { it.groupValues[1] }.joinToString(" ")
        }
        // pipe-delimited station runs become their own lines
        t = t.split("|")
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return t.replace(BLANK_LINES, "\n").trim()
    }

    private companion object {
        val ICON_TOKEN = Regex("""\[[^\]]*\b(icon|accessibility|ada)\b[^\]]*]""", RegexOption.IGNORE_CASE)
        val TAG = Regex("<[^>]+>")
        val BULLET_RUN = Regex("""(?:\[[A-Z0-9]{1,3}])+""")
        val BULLET = Regex("""\[([A-Z0-9]{1,3})]""")
        val WHITESPACE = Regex("[ \t]+")
        val BLANK_LINES = Regex("\n{2,}")
    }

    /** MTA puts a rider-facing category in the mercury extension; fall back to effect. */
    private fun mtaAlertType(alert: JsonObject): String {
        val mercury = alert["transit_realtime.mercury_alert"]?.jsonObject
        mercury?.get("alert_type")?.jsonPrimitive?.contentOrNullSafe()?.let { if (it.isNotBlank()) return it }
        return when (alert["effect"]?.jsonPrimitive?.contentOrNullSafe()) {
            "SIGNIFICANT_DELAYS", "REDUCED_SERVICE" -> "Delay"
            "DETOUR" -> "Planned reroute"
            "MODIFIED_SERVICE" -> "Planned change"
            "STOP_MOVED", "NO_SERVICE" -> "Service change"
            else -> "Service notice"
        }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it != "null" }
