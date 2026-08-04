package com.mikeos.core.location

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Single location authority (APP-ANATOMY §3).
 *
 * Apps should read location from HERE (the daemon), **not** from their own
 * Android `LocationManager`. The daemon owns ONE source of truth for the user's
 * location so apps stop each hitting the GPS chip independently — which caused
 * inconsistent/wrong fixes and drained the battery.
 *
 *   - EVERYONE reads:  [get] → `GET https://127.0.0.1:7743/api/location`.
 *   - ONE app pushes:  exactly one app (the designated *location provider*, e.g.
 *     MikeGuide, which holds the location permission) may [push] a real GNSS fix
 *     via `POST /api/location`. Every other app only reads.
 *
 * Both endpoints are auth-exempt + loopback-only on the daemon (same trust model
 * as `/api/context` and `/api/events`), so no bearer token is required. Reuse
 * the app's existing loopback-trusting `OkHttpClient` (the daemon serves a
 * self-signed cert on 127.0.0.1). This is the seed of `mikeos-android-core`'s
 * location plumbing — every app depends on it so no one reinvents GPS.
 */
data class MikeLocation(
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val city: String?,
    val region: String?,
    val country: String?,
    val fixAgeMs: Long?,
    val stale: Boolean,
)

object LocationClient {
    private const val DAEMON = "https://127.0.0.1:7743"
    private val JSON = "application/json".toMediaType()

    /**
     * Read the shared location from the daemon. Returns null on any failure
     * (daemon down, no fix yet, parse error). MUST be called off the main thread.
     *
     * This is what almost every app calls — do NOT run your own LocationManager.
     */
    fun get(daemonClient: OkHttpClient): MikeLocation? {
        val req = Request.Builder()
            .url("$DAEMON/api/location")
            .get()
            .build()
        return try {
            daemonClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val j = JSONObject(r.body?.string() ?: return null)
                MikeLocation(
                    lat = j.optDoubleOrNull("lat"),
                    lon = j.optDoubleOrNull("lon"),
                    accuracy = j.optDoubleOrNull("accuracy"),
                    speed = j.optDoubleOrNull("speed"),
                    city = j.optStringOrNull("city"),
                    region = j.optStringOrNull("region"),
                    country = j.optStringOrNull("country"),
                    fixAgeMs = if (j.isNull("fixAgeMs")) null else j.optLong("fixAgeMs"),
                    stale = j.optBoolean("stale", true),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Push a real GNSS fix into the daemon. ONLY the designated location-provider
     * app (the one holding the location permission) should call this — everyone
     * else only [get]s. Fire-and-forget; returns true if the daemon accepted it.
     * MUST be called off the main thread.
     */
    fun push(
        daemonClient: OkHttpClient,
        lat: Double,
        lon: Double,
        accuracy: Double? = null,
        speed: Double? = null,
        altitude: Double? = null,
        bearing: Double? = null,
        satellites: Int? = null,
        source: String? = null,
    ): Boolean {
        val body = JSONObject().put("lat", lat).put("lon", lon)
        accuracy?.let { body.put("accuracy", it) }
        speed?.let { body.put("speed", it) }
        altitude?.let { body.put("altitude", it) }
        bearing?.let { body.put("bearing", it) }
        satellites?.let { body.put("satellites", it) }
        source?.let { body.put("source", it) }
        val req = Request.Builder()
            .url("$DAEMON/api/location")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            daemonClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    // ── null-safe JSON helpers (the daemon sends JSON null for a missing field) ──
    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).let { if (it.isNaN()) null else it }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, null)
}
