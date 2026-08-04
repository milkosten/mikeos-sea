package com.mikeos.sea

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var results by remember { mutableStateOf<List<MarineApi.Place>>(emptyList()) }
    var nearCount by remember { mutableStateOf<Int?>(null) }
    var tapped by remember { mutableStateOf<Map<String, String>?>(null) }
    var seamarks by remember { mutableStateOf(true) }
    var locating by remember { mutableStateOf(false) }
    var focusName by remember { mutableStateOf(SeaMikeAgent.focusName) }

    fun loadViewport(w: Double, s: Double, e: Double, n: Double) {
        scope.launch {
            val raw = MarineApi.rawVessels(w, s, e, n) ?: return@launch
            mapState.vesselsJson = raw
            nearCount = runCatching { JSONObject(raw).optJSONArray("features")?.length() ?: 0 }.getOrNull()
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
                dataNonce++; flyTo = LatLng(la, lo); flyNonce++
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
    LaunchedEffect(seamarks) { mapState.seamarks = seamarks; dataNonce++ }

    Box(modifier = Modifier.fillMaxSize()) {
        SeaMap(
            state = mapState, dataNonce = dataNonce, flyTo = flyTo, flyNonce = flyNonce,
            onViewport = { w, s, e, n -> loadViewport(w, s, e, n) },
            onVesselTap = { tapped = it },
            modifier = Modifier.fillMaxSize(),
        )

        // Top overlay: search + Agent Inspector
        Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shadowElevation = 6.dp,
                ) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search a harbour or coastal place…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shadowElevation = 6.dp) {
                    AgentIconButton(onClick = { AgentInspectorActivity.start(context) })
                }
            }
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column {
                        results.forEach { p ->
                            Column(Modifier.fillMaxWidth().clickable { goTo(p) }.padding(12.dp)) {
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
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = seamarks, onClick = { seamarks = !seamarks },
                    label = { Text("Seamarks") },
                    leadingIcon = { Icon(Icons.Filled.Anchor, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp)) })
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                    Text(
                        "🌊 ${nearCount ?: "–"} vessels · $focusName",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
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

        // Vessel detail sheet
        tapped?.let { v -> VesselSheet(v) { tapped = null } }
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
