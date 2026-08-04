package com.mikeos.sea.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Client for the MikeOS marine backend (`marine-api.osmike.com`) — the same service
 * that powers the MikeSea web viewer. Live vessels come from the Kystverket AIS feed
 * cached on the 242 box; geocoding is Photon-proxied. Standard HTTPS (system CAs).
 */
object MarineApi {
    private const val BASE = "https://marine-api.osmike.com"

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Vessel(
        val name: String,
        val shipType: String?,
        val speedKn: Double?,
        val course: Double?,
        val destination: String?,
        val mmsi: String?,
        val callsign: String?,
        val lat: Double,
        val lon: Double,
        val distanceKm: Double,
    )

    data class Place(
        val name: String,
        val label: String,
        val lat: Double,
        val lon: Double,
        val kind: String?,
        val country: String?,
    )

    private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "MikeSea/1.0").build())
                .execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) JSONObject(body) else null
                }
        }.getOrNull()
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p = PI / 180
        val a = 0.5 - cos((lat2 - lat1) * p) / 2 +
            cos(lat1 * p) * cos(lat2 * p) * (1 - cos((lon2 - lon1) * p)) / 2
        return 12742 * asin(min(1.0, sqrt(a)))
    }

    private fun str(o: JSONObject, k: String): String? =
        if (o.has(k) && !o.isNull(k)) o.get(k).toString().takeIf { it.isNotBlank() && it != "null" } else null

    private fun dbl(o: JSONObject, k: String): Double? =
        if (o.has(k) && !o.isNull(k)) o.get(k).toString().toDoubleOrNull() else null

    /** Live vessels within [radiusDeg] of (lat, lon), nearest first. */
    suspend fun nearbyVessels(lat: Double, lon: Double, radiusDeg: Double = 0.6): List<Vessel> {
        val bbox = "${lon - radiusDeg},${lat - radiusDeg},${lon + radiusDeg},${lat + radiusDeg}"
        val gj = getJson("$BASE/live/vessels?bbox=$bbox&points=true") ?: return emptyList()
        val feats = gj.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Vessel>(feats.length())
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val vlon = coords.optDouble(0, Double.NaN)
            val vlat = coords.optDouble(1, Double.NaN)
            if (vlon.isNaN() || vlat.isNaN()) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val name = str(p, "ship_name") ?: str(p, "name") ?: str(p, "callsign")
                ?: ("MMSI " + (str(p, "mmsi") ?: "?"))
            out.add(
                Vessel(
                    name = name,
                    shipType = str(p, "ship_type"),
                    speedKn = dbl(p, "speed"),
                    course = dbl(p, "cog"),
                    destination = str(p, "destination"),
                    mmsi = str(p, "mmsi"),
                    callsign = str(p, "callsign"),
                    lat = vlat, lon = vlon,
                    distanceKm = haversineKm(lat, lon, vlat, vlon),
                )
            )
        }
        return out.sortedBy { it.distanceKm }
    }

    /** Forward geocode, view-biased to (lat, lon). */
    suspend fun search(q: String, lat: Double, lon: Double): List<Place> {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val gj = getJson("$BASE/search?q=$enc&limit=8&lat=$lat&lon=$lon") ?: return emptyList()
        val arr = gj.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val la = dbl(o, "lat"); val lo = dbl(o, "lon") ?: continue
            if (la == null) continue
            out.add(
                Place(
                    name = str(o, "name") ?: "Unnamed",
                    label = str(o, "label") ?: "",
                    lat = la, lon = lo,
                    kind = str(o, "kind"),
                    country = str(o, "country"),
                )
            )
        }
        return out
    }

    /** Total vessels currently in the live feed, or null if unreachable. */
    suspend fun liveCount(): Int? {
        val gj = getJson("$BASE/health") ?: return null
        return gj.optJSONObject("live")?.optInt("count")
    }
}
