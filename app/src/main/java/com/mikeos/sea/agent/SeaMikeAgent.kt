package com.mikeos.sea.agent

import android.content.Context
import android.util.Log
import com.mikeos.core.MikeAgentConfig
import com.mikeos.core.agent.MikeAgent
import com.mikeos.core.agent.Skill
import com.mikeos.core.agent.Soul
import com.mikeos.core.runtime.HeartbeatService
import com.mikeos.sea.BuildConfig
import com.mikeos.sea.net.MarineApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * MikeSea's embedding of the shared **MikeAgent** runtime (mikeos-android-core).
 *
 * The soul makes the agent MikeOS's eyes on the water: it watches live vessel traffic
 * around Mike (via `marine-api.osmike.com`, the same backend as the MikeSea web viewer)
 * and can answer "what's on the water near me / near <place>". The four universal skills
 * (hive_send / remember / recall / notify / location / ask_siblings) come from the runtime;
 * MikeSea adds two marine skills over the live AIS feed.
 */
object SeaMikeAgent {

    private const val TAG = "SeaMikeAgent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The map/list focus the UI is looking at; skills + perception report vessels around it.
    // Defaults to the outer Oslofjord until the user searches somewhere else.
    @Volatile var focusLat: Double = 59.9
    @Volatile var focusLon: Double = 10.72
    @Volatile var focusName: String = "the Oslofjord"

    @Volatile private var installed = false

    private val soul = Soul(
        agentName = "Sea",
        appName = "MikeSea",
        persona = "I'm MikeSea's agent — MikeOS's eyes on the water. I watch live ship " +
            "traffic (AIS) around Mike and know the sea near him: vessels, harbours, coasts.",
        goals = listOf(
            "Know what vessels are on the water near Mike right now",
            "Answer where a harbour or coastal place is and what's moving there",
            "Flag anything notable on the water to the user and siblings",
        ),
    )

    private fun seaSkills(): List<Skill> = listOf(
        Skill(
            name = "nearby_vessels",
            description = "List the live AIS vessels currently near Mike's map focus " +
                "(name, type, speed, distance). Use to answer what's on the water right now.",
            paramsSchema = """{"limit":"optional max vessels to list (default 8)"}""",
            run = { args ->
                val limit = args.optString("limit").toIntOrNull() ?: 8
                val vs = MarineApi.nearbyVessels(focusLat, focusLon)
                if (vs.isEmpty()) "No live vessels near $focusName right now."
                else "${vs.size} vessels near $focusName. Nearest: " +
                    vs.take(limit).joinToString("; ") { v ->
                        "${v.name} (${v.speedKn ?: 0.0} kn, ${"%.1f".format(v.distanceKm)} km)"
                    }
            },
        ),
        Skill(
            name = "find_place",
            description = "Find a harbour/coastal place by name (view-biased to Mike's focus) " +
                "and report where it is. Also moves the focus there.",
            paramsSchema = """{"query":"place or harbour name"}""",
            run = { args ->
                val q = args.optString("query")
                if (q.isBlank()) return@Skill "find_place needs a 'query'."
                val hits = MarineApi.search(q, focusLat, focusLon)
                val top = hits.firstOrNull() ?: return@Skill "No place found for '$q'."
                focusLat = top.lat; focusLon = top.lon; focusName = top.name
                "Found ${top.name} — ${top.label}. Focus moved there."
            },
        ),
    )

    private suspend fun perceptionSnapshot(): String {
        val n = runCatching { MarineApi.nearbyVessels(focusLat, focusLon).size }.getOrNull()
        val total = runCatching { MarineApi.liveCount() }.getOrNull()
        return buildString {
            append("Marine focus: $focusName (${"%.3f".format(focusLat)}, ${"%.3f".format(focusLon)}). ")
            if (n != null) append("$n live vessels within ~60 km. ")
            if (total != null) append("$total vessels in the whole live feed.")
        }
    }

    /** Install the runtime (§0 self-register), start the heartbeat, open the hive. Idempotent. */
    fun boot(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val agent = MikeAgent.install(
                    app,
                    MikeAgentConfig(
                        daemonToken = BuildConfig.DAEMON_TOKEN,
                        userName = "Mike",
                        siblings = listOf("MikeSpace", "MikeGuide", "MikeBody"),
                    ),
                    soul,
                    seaSkills(),
                )
                HeartbeatService.perceptionProvider = { perceptionSnapshot() }
                HeartbeatService.start(app)
                agent.connectHive()
                Log.i(TAG, "MikeAgent installed; hive=${agent.cred?.name ?: "(offline)"}")
            }.onFailure { Log.w(TAG, "MikeAgent boot failed", it) }
        }
    }
}
