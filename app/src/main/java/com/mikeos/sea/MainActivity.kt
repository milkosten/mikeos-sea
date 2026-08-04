package com.mikeos.sea

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikeos.core.location.LocationClient
import com.mikeos.core.net.loopbackTrustingClientPublic
import com.mikeos.core.ui.AgentIconButton
import com.mikeos.core.ui.AgentInspectorActivity
import com.mikeos.sea.agent.SeaMikeAgent
import com.mikeos.sea.net.MarineApi
import com.mikeos.sea.ui.SeaMap
import com.mikeos.sea.ui.SeaMapState
import com.mikeos.sea.ui.theme.MikeSeaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // draw the chart edge-to-edge behind transparent system bars
        SeaMikeAgent.boot(this) // §0 self-registration + heartbeat + hive

        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        if (perms.isNotEmpty()) runCatching { permissionLauncher.launch(perms) }

        setContent {
            MikeSeaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SeaMapScreen()
                }
            }
        }
    }
}

@Composable
private fun MapIconButton(icon: ImageVector, desc: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(shape = CircleShape, shadowElevation = 4.dp,
        color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = desc,
                tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun shipTypeLabel(code: String?): String {
    val n = code?.toDoubleOrNull()?.toInt() ?: return "Vessel"
    return when (n) {
        30 -> "Fishing"; 31, 32, 52 -> "Tug"; 36, 37 -> "Pleasure craft"
        in 40..49 -> "High-speed craft"; in 50..59 -> "Special craft"
        in 60..69 -> "Passenger"; in 70..79 -> "Cargo"; in 80..89 -> "Tanker"
        else -> "Vessel"
    }
}

@Composable
private fun SeaMapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val daemonClient = remember { loopbackTrustingClientPublic(BuildConfig.DAEMON_BASE_URL) }
    val mapState = remember { SeaMapState() }

    var dataNonce by remember { mutableIntStateOf(0) }
    var flyTo by remember { mutableStateOf<LatLng?>(null) }
    var flyNonce by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MarineApi.Place>>(emptyList()) }
    var nearCount by remember { mutableStateOf<Int?>(null) }
    var tapped by remember { mutableStateOf<Map<String, String>?>(null) }
    var seamarks by remember { mutableStateOf(true) }
    var depth by remember { mutableStateOf(true) }
    var locating by remember { mutableStateOf(false) }
    var focusName by remember { mutableStateOf(SeaMikeAgent.focusName) }
    var depthUnderMe by remember { mutableStateOf<Double?>(null) }
    var sog by remember { mutableStateOf<Double?>(null) }
    var wx by remember { mutableStateOf<MarineApi.Weather?>(null) }
    var showWx by remember { mutableStateOf(false) }
    val track = remember { mutableStateListOf<Pair<Double, Double>>() }
    var recording by remember { mutableStateOf(false) }
    var tripDistKm by remember { mutableStateOf(0.0) }
    var tripMaxSog by remember { mutableStateOf(0.0) }
    var waypoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var mob by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    fun rebuildNav() {
        val me = mapState.meLat?.let { la -> mapState.meLon?.let { lo -> la to lo } }
        val parts = ArrayList<String>()
        waypoint?.let { wp ->
            parts.add("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${wp.second},${wp.first}]},"properties":{"kind":"waypoint"}}""")
            if (me != null) parts.add("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[[${me.second},${me.first}],[${wp.second},${wp.first}]]},"properties":{"kind":"course"}}""")
        }
        mob?.let { m ->
            parts.add("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${m.second},${m.first}]},"properties":{"kind":"mob"}}""")
        }
        mapState.navJson = """{"type":"FeatureCollection","features":[${parts.joinToString(",")}]}"""
        dataNonce++
    }

    fun loadViewport(w: Double, s: Double, e: Double, n: Double) {
        scope.launch {
            MarineApi.rawVessels(w, s, e, n)?.let {
                mapState.vesselsJson = it
                nearCount = runCatching { JSONObject(it).optJSONArray("features")?.length() ?: 0 }.getOrNull()
            }
            MarineApi.rawSoundings(w, s, e, n, 10, 7)?.let { mapState.soundingsJson = it }
            dataNonce++
        }
    }

    fun locate() {
        scope.launch {
            locating = true
            val loc = withContext(Dispatchers.IO) { LocationClient.get(daemonClient) }
            locating = false
            val la = loc?.lat; val lo = loc?.lon
            if (la != null && lo != null) {
                mapState.meLat = la; mapState.meLon = lo
                SeaMikeAgent.focusLat = la; SeaMikeAgent.focusLon = lo
                loc.city?.let { SeaMikeAgent.focusName = it; focusName = it }
                sog = loc.speed?.let { it * 1.94384 }   // m/s -> knots
                dataNonce++; flyTo = LatLng(la, lo); flyNonce++
                depthUnderMe = MarineApi.depthAt(la, lo)
                wx = MarineApi.weather(la, lo)
            }
        }
    }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            results = MarineApi.search(q, mapState.meLat ?: SeaMikeAgent.focusLat,
                mapState.meLon ?: SeaMikeAgent.focusLon)
        }
    }

    fun goTo(p: MarineApi.Place) {
        SeaMikeAgent.focusLat = p.lat; SeaMikeAgent.focusLon = p.lon; SeaMikeAgent.focusName = p.name
        focusName = p.name; results = emptyList(); query = ""
        flyTo = LatLng(p.lat, p.lon); flyNonce++
    }

    LaunchedEffect(Unit) { locate() }
    // Live instruments: poll the shared GPS every 6s for position / speed / depth-under-me.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(6000)
            val loc = withContext(Dispatchers.IO) { LocationClient.get(daemonClient) }
            val la = loc?.lat; val lo = loc?.lon
            if (la != null && lo != null) {
                mapState.meLat = la; mapState.meLon = lo
                sog = loc.speed?.let { it * 1.94384 }
                if (recording) {
                    val last = track.lastOrNull()
                    val d = if (last != null) haversineKm(last.first, last.second, la, lo) else 0.0
                    if (last == null || d > 0.006) {   // moved > ~6 m
                        if (last != null) tripDistKm += d
                        track.add(la to lo)
                        sog?.let { if (it > tripMaxSog) tripMaxSog = it }
                        mapState.trackJson = trackToGeoJson(track)
                    }
                }
                dataNonce++
                depthUnderMe = MarineApi.depthAt(la, lo)
            }
        }
    }
    LaunchedEffect(seamarks) { mapState.seamarks = seamarks; dataNonce++ }
    LaunchedEffect(depth) { mapState.depth = depth; dataNonce++ }

    Box(modifier = Modifier.fillMaxSize()) {
        SeaMap(
            state = mapState, dataNonce = dataNonce, flyTo = flyTo, flyNonce = flyNonce,
            onViewport = { w, s, e, n -> loadViewport(w, s, e, n) },
            onVesselTap = { tapped = it },
            onLongPress = { la, lo -> waypoint = la to lo; rebuildNav() },
            modifier = Modifier.fillMaxSize(),
        )

        // Top overlay: icon-first toolbar; search expands only on tap.
        Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (searchOpen) {
                Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { searchOpen = false; query = ""; results = emptyList() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Harbour or place…") },
                            trailingIcon = {
                                if (query.isNotBlank()) IconButton(onClick = { query = ""; results = emptyList() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        )
                    }
                }
                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column {
                            results.forEach { p ->
                                Column(Modifier.fillMaxWidth()
                                    .clickable { goTo(p); searchOpen = false }.padding(12.dp)) {
                                    Text(p.name, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    if (p.label.isNotBlank()) Text(p.label, fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MapIconButton(Icons.Filled.Search, "Search") { searchOpen = true }
                    Spacer(Modifier.width(8.dp))
                    MapIconButton(Icons.Filled.Layers, "Sea chart", active = depth) { depth = !depth }
                    Spacer(Modifier.width(8.dp))
                    MapIconButton(Icons.Filled.Anchor, "Seamarks", active = seamarks) { seamarks = !seamarks }
                    Spacer(Modifier.weight(1f))
                    // Compact live-vessel count
                    Surface(shape = CircleShape, shadowElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Icon(Icons.Filled.DirectionsBoat, contentDescription = "Vessels nearby",
                                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("${nearCount ?: "–"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = CircleShape, shadowElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
                        AgentIconButton(onClick = { AgentInspectorActivity.start(context) })
                    }
                }
            }
        }

        // MOB (man-overboard) FAB — marks / clears current position
        FloatingActionButton(
            onClick = {
                if (mob != null) mob = null
                else { val la = mapState.meLat; val lo = mapState.meLon; if (la != null && lo != null) mob = la to lo }
                rebuildNav()
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 152.dp),
            containerColor = if (mob != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface,
        ) {
            Text("MOB", color = if (mob != null) Color.White else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        // Record-track FAB (above the locate FAB)
        FloatingActionButton(
            onClick = {
                if (recording) recording = false
                else {
                    recording = true; track.clear(); tripDistKm = 0.0; tripMaxSog = 0.0
                    mapState.trackJson = """{"type":"FeatureCollection","features":[]}"""; dataNonce++
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 84.dp),
            containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface,
        ) {
            Icon(
                if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = "Record track",
                tint = if (recording) Color.White else MaterialTheme.colorScheme.error,
            )
        }

        // Locate-me FAB
        FloatingActionButton(
            onClick = { locate() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            if (locating) CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp),
                strokeWidth = 2.dp, color = Color.Black)
            else Icon(Icons.Filled.MyLocation, contentDescription = "My location", tint = Color.Black)
        }

        // Instruments bar: SOG · depth-under-me · wind (tap wind → full weather)
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), shadowElevation = 8.dp,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                InstrumentCell("SOG", sog?.let { "%.1f".format(it) } ?: "–", "kn", MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(20.dp))
                InstrumentCell("DEPTH", depthUnderMe?.let { "%.1f".format(it) } ?: "–", "m",
                    if (depthUnderMe != null && depthUnderMe!! < 3.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { showWx = true }) {
                    Text("WIND", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(wx?.windKn?.let { "%.0f".format(it) } ?: "–", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text(wx?.windDir?.let { degToCompass(it) } ?: "kn", fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recording) {
                    Spacer(Modifier.width(20.dp))
                    InstrumentCell("DIST", "%.1f".format(tripDistKm * 0.539957), "NM", MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(20.dp))
                    InstrumentCell("MAX", "%.1f".format(tripMaxSog), "kn", MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // Attribution (CC-BY / ODbL / NLOD / Licence Ouverte require credit).
        Text(
            "Charts © SHOM Litto3D / Kartverket / EMODnet · © OSM · seamarks © OpenSeaMap · AIS © Kystverket · wx: Open-Meteo",
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 6.dp, end = 90.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp,
        )

        // Waypoint readout (distance / bearing / ETA) — tap to clear
        waypoint?.let { wp ->
            val mLat = mapState.meLat; val mLon = mapState.meLon
            val nm = if (mLat != null && mLon != null) haversineKm(mLat, mLon, wp.first, wp.second) * 0.539957 else null
            val brg = if (mLat != null && mLon != null) bearingDeg(mLat, mLon, wp.first, wp.second) else null
            val etaMin = if (nm != null && (sog ?: 0.0) > 0.3) nm / sog!! * 60.0 else null
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 82.dp)
                    .clickable { waypoint = null; rebuildNav() },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f), shadowElevation = 6.dp,
            ) {
                Text(
                    "→ WPT   ${nm?.let { "%.2f NM".format(it) } ?: "–"}   BRG ${brg?.let { "%03.0f°".format(it) } ?: "–"}   ETA ${etaMin?.let { "%.0f min".format(it) } ?: "–"}   ✕",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Shallow-water alarm
        if (depthUnderMe != null && depthUnderMe!! < 3.0) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 150.dp),
                shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.error,
            ) {
                Text("⚠  SHALLOW  ${"%.1f".format(depthUnderMe)} m",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        // Vessel detail sheet
        tapped?.let { v -> VesselSheet(v) { tapped = null } }
        // Weather sheet
        if (showWx) wx?.let { w -> WeatherSheet(w, focusName) { showWx = false } }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.VesselSheet(v: Map<String, String>, onClose: () -> Unit) {
    val name = v["ship_name"]?.takeIf { it.isNotBlank() } ?: v["name"]?.takeIf { it.isNotBlank() }
        ?: v["callsign"]?.takeIf { it.isNotBlank() } ?: "MMSI ${v["mmsi"] ?: "?"}"
    val speed = v["speed"]?.toDoubleOrNull() ?: 0.0
    Card(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (speed > 0.5) "🚢" else "⚓", fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Spacer(Modifier.height(6.dp))
            detailRow("Type", shipTypeLabel(v["ship_type"]))
            detailRow("Status", if (speed > 0.5) "Under way" else "Moored / stopped")
            detailRow("Speed", "$speed kn")
            detailRow("Course", v["cog"]?.let { "${it}°" } ?: "–")
            detailRow("Destination", v["destination"]?.takeIf { it.isNotBlank() } ?: "–")
            detailRow("MMSI", v["mmsi"] ?: "–")
            detailRow("Callsign", v["callsign"]?.takeIf { it.isNotBlank() } ?: "–")
        }
    }
}

@Composable
private fun detailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.width(104.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

@Composable
private fun InstrumentCell(label: String, value: String, unit: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(unit, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun degToCompass(deg: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((((deg % 360) + 360) % 360) / 45.0).roundToInt() % 8]
}

private fun wxDesc(code: Int?): String = when (code) {
    null -> "–"; 0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Overcast"
    45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"
    in 71..77 -> "Snow"; in 80..82 -> "Showers"; 95, 96, 99 -> "Thunderstorm"
    else -> "Code $code"
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.WeatherSheet(w: MarineApi.Weather, place: String, onClose: () -> Unit) {
    Card(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🌬  Weather · $place", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Spacer(Modifier.height(6.dp))
            detailRow("Wind", (w.windKn?.let { "%.0f".format(it) } ?: "–") + " kn " + (w.windDir?.let { degToCompass(it) } ?: ""))
            detailRow("Gusts", w.gustKn?.let { "%.0f kn".format(it) } ?: "–")
            detailRow("Waves", (w.waveM?.let { "%.1f m".format(it) } ?: "–") + (w.wavePeriodS?.let { " @ %.0f s".format(it) } ?: ""))
            detailRow("Swell", (w.swellM?.let { "%.1f m".format(it) } ?: "–") + (w.swellDir?.let { " " + degToCompass(it) } ?: ""))
            detailRow("Sky", wxDesc(w.weatherCode))
            detailRow("Air", w.tempC?.let { "%.0f °C".format(it) } ?: "–")
            detailRow("Pressure", w.pressureHpa?.let { "%.0f hPa".format(it) } ?: "–")
            detailRow("Visibility", w.visibilityM?.let { "%.1f km".format(it / 1000) } ?: "–")
        }
    }
}

private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p = Math.PI / 180
    val a = 0.5 - Math.cos((lat2 - lat1) * p) / 2 +
        Math.cos(lat1 * p) * Math.cos(lat2 * p) * (1 - Math.cos((lon2 - lon1) * p)) / 2
    return 12742 * Math.asin(Math.min(1.0, Math.sqrt(a)))
}

private fun trackToGeoJson(track: List<Pair<Double, Double>>): String {
    if (track.size < 2) return """{"type":"FeatureCollection","features":[]}"""
    val coords = track.joinToString(",") { "[${it.second},${it.first}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}"""
}

private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p = Math.PI / 180
    val y = Math.sin((lon2 - lon1) * p) * Math.cos(lat2 * p)
    val x = Math.cos(lat1 * p) * Math.sin(lat2 * p) - Math.sin(lat1 * p) * Math.cos(lat2 * p) * Math.cos((lon2 - lon1) * p)
    return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
}
