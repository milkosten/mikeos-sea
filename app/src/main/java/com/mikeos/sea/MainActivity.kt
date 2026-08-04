package com.mikeos.sea

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikeos.core.ui.AgentIconButton
import com.mikeos.core.ui.AgentInspectorActivity
import com.mikeos.sea.agent.SeaMikeAgent
import com.mikeos.sea.net.MarineApi
import com.mikeos.sea.ui.theme.MikeSeaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Embed the shared MikeAgent runtime: §0 self-registration, heartbeat, live hive.
        SeaMikeAgent.boot(this)

        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        runCatching { permissionLauncher.launch(perms) }

        setContent {
            MikeSeaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SeaApp()
                }
            }
        }
    }
}

private fun shipTypeLabel(code: String?): String {
    val n = code?.toDoubleOrNull()?.toInt() ?: return "Vessel"
    return when (n) {
        in 30..30, in 1030..1039 -> "Fishing"
        in 31..32, 52 -> "Tug"
        36, 37 -> "Pleasure craft"
        in 40..49 -> "High-speed craft"
        in 50..59 -> "Special craft"
        in 60..69 -> "Passenger"
        in 70..79 -> "Cargo"
        in 80..89 -> "Tanker"
        else -> "Vessel"
    }
}

@Composable
private fun SeaApp() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var focusName by remember { mutableStateOf(SeaMikeAgent.focusName) }
    var vessels by remember { mutableStateOf<List<MarineApi.Vessel>>(emptyList()) }
    var results by remember { mutableStateOf<List<MarineApi.Place>>(emptyList()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MarineApi.Vessel?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            vessels = MarineApi.nearbyVessels(SeaMikeAgent.focusLat, SeaMikeAgent.focusLon)
            total = MarineApi.liveCount()
            loading = false
        }
    }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            loading = true
            results = MarineApi.search(q, SeaMikeAgent.focusLat, SeaMikeAgent.focusLon)
            loading = false
        }
    }

    fun goTo(p: MarineApi.Place) {
        SeaMikeAgent.focusLat = p.lat
        SeaMikeAgent.focusLon = p.lon
        SeaMikeAgent.focusName = p.name
        focusName = p.name
        results = emptyList()
        query = ""
        reload()
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header + mandatory Agent Inspector icon
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("🌊 MikeSea", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(8.dp))
            Text("live on the water", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            AgentIconButton(onClick = { AgentInspectorActivity.start(context) })
        }

        Spacer(Modifier.height(12.dp))

        // Search
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search a harbour or coastal place…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
        )

        if (results.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    results.forEach { p ->
                        Column(modifier = Modifier.fillMaxWidth().clickable { goTo(p) }.padding(12.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            if (p.label.isNotBlank()) {
                                Text(p.label, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Status line
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Watching ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(focusName, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp),
                    strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("${vessels.size} near · ${total ?: "–"} live",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (vessels.isEmpty() && !loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text("No live vessels near $focusName.\nTry searching a harbour.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vessels) { v -> VesselCard(v) { selected = v } }
        }
    }

    selected?.let { v ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
            title = { Text(v.name) },
            text = {
                Column {
                    detailRow("Type", shipTypeLabel(v.shipType))
                    detailRow("Speed", "${v.speedKn ?: 0.0} kn")
                    detailRow("Course", v.course?.let { "${it}°" } ?: "–")
                    detailRow("Distance", "%.1f km".format(v.distanceKm))
                    detailRow("Destination", v.destination ?: "–")
                    detailRow("MMSI", v.mmsi ?: "–")
                    detailRow("Callsign", v.callsign ?: "–")
                    detailRow("Position", "%.4f, %.4f".format(v.lat, v.lon))
                }
            },
        )
    }
}

@Composable
private fun VesselCard(v: MarineApi.Vessel, onClick: () -> Unit) {
    val moving = (v.speedKn ?: 0.0) > 0.5
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (moving) "🚢" else "⚓", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(v.name, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    buildString {
                        append(shipTypeLabel(v.shipType))
                        append(" · ")
                        append(if (moving) "${v.speedKn} kn" else "moored")
                        v.destination?.let { append(" · → $it") }
                    },
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("%.1f km".format(v.distanceKm), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun detailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.width(96.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}
