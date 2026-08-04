package com.mikeos.sea.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mikeos.sea.BuildConfig
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

private const val SRC_VESSELS = "vessels"
private const val SRC_ME = "me"
private const val SRC_SEA = "seamark"
private const val IMG_ARROW = "vessel-arrow"
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

/** Data pushed from the app into the map on each change. */
class SeaMapState {
    @Volatile var vesselsJson: String = EMPTY_FC
    @Volatile var meLat: Double? = null
    @Volatile var meLon: Double? = null
    @Volatile var seamarks: Boolean = true
    @Volatile var depth: Boolean = true
}

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var didInitialCamera = false
}

private fun arrowBitmap(color: Int): Bitmap {
    val s = 36
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    val path = Path().apply {
        moveTo(s / 2f, 3f); lineTo(s - 7f, s - 6f); lineTo(s / 2f, s * 0.68f); lineTo(7f, s - 6f); close()
    }
    c.drawPath(path, p)
    return bmp
}

@Composable
fun SeaMap(
    state: SeaMapState,
    dataNonce: Int,                 // bump to push vessel/me data into the map
    flyTo: LatLng?,                 // non-null → ease camera there
    flyNonce: Int,                  // bump to trigger a fly (even to the same point)
    onViewport: (w: Double, s: Double, e: Double, n: Double) -> Unit,
    onVesselTap: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    val holder = remember { MapHolder() }
    val onViewportState by rememberUpdatedState(onViewport)
    val onVesselTapState by rememberUpdatedState(onVesselTap)
    val styleUrl = "${BuildConfig.BASEMAP_URL}/style.json"

    AndroidView(
        modifier = modifier,
        factory = { _ ->
            mapView.getMapAsync { map ->
                holder.map = map
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isTiltGesturesEnabled = false
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(state.meLat ?: 59.9, state.meLon ?: 10.72)).zoom(9.0).build()

                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    holder.style = style
                    installLayers(style)
                    pushData(holder, state)
                }

                map.addOnCameraIdleListener {
                    val b = map.projection.visibleRegion.latLngBounds
                    onViewportState(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
                }
                map.addOnMapClickListener { latLng ->
                    val pf = map.projection.toScreenLocation(latLng)
                    // Query a tolerance box, not a single pixel — the dots are tiny (3–7px), so an
                    // exact hit is impossible on a touchscreen. Pick the vessel nearest the tap.
                    val tol = 26f
                    val box = android.graphics.RectF(pf.x - tol, pf.y - tol, pf.x + tol, pf.y + tol)
                    val feats = map.queryRenderedFeatures(box, "vessels-dot")
                    val f = feats.minByOrNull { feat ->
                        val p = feat.geometry() as? org.maplibre.geojson.Point
                        if (p == null) Double.MAX_VALUE else {
                            val sp = map.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
                            val dx = (sp.x - pf.x).toDouble(); val dy = (sp.y - pf.y).toDouble()
                            dx * dx + dy * dy
                        }
                    }
                    if (f != null) {
                        val m = HashMap<String, String>()
                        f.properties()?.entrySet()?.forEach { (k, v) ->
                            if (v != null && !v.isJsonNull) m[k] = runCatching { v.asString }.getOrDefault(v.toString())
                        }
                        (f.geometry() as? org.maplibre.geojson.Point)?.let {
                            m["lat"] = it.latitude().toString(); m["lon"] = it.longitude().toString()
                        }
                        onVesselTapState(m)
                        true
                    } else false
                }
            }
            mapView
        },
        update = {
            // Push latest data + honour fly requests. Keyed by the nonces via recomposition.
            pushData(holder, state)
            if (flyTo != null) {
                holder.map?.easeCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(flyTo).zoom(12.0).build()
                    ), 700
                )
            }
        },
    )

    // Recompose the AndroidView update lambda when data/fly nonces change.
    DisposableEffect(dataNonce, flyNonce) { onDispose { } }
}

private fun installLayers(style: Style) {
    // Bathymetry — EMODnet depth-shaded chart (CC-BY). Inserted BELOW the base map's first label
    // layer, so European seas render as a real depth chart (shaded by depth + coastlines) while OSM
    // place labels stay on top. Where EMODnet has no tile (outside Europe) it draws nothing and the
    // OSM base shows through. CC-BY: "© EMODnet Bathymetry".
    val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
    val depthTiles = TileSet(
        "2.1.0", "https://tiles.emodnet-bathymetry.eu/2020/baselayer/web_mercator/{z}/{x}/{y}.png"
    ).apply { minZoom = 0f; maxZoom = 12f }
    style.addSource(RasterSource("depth", depthTiles, 256))
    val depthLayer = RasterLayer("depth-layer", "depth").withProperties(PropertyFactory.rasterOpacity(1.0f))
    if (firstSymbol != null) style.addLayerBelow(depthLayer, firstSymbol) else style.addLayer(depthLayer)

    // Kartverket Sjøkart — the OFFICIAL Norwegian nautical chart (NLOD): depth areas, soundings,
    // buoys, beacons, lights, fairways/channels, port entrances. XYZ cache (note {z}/{y}/{x} order).
    // Sits above the EMODnet depth shading; covers Norwegian waters, falls back to EMODnet/OSM elsewhere.
    val chartTiles = TileSet(
        "2.1.0", "https://cache.kartverket.no/v1/wmts/1.0.0/sjokartraster/default/webmercator/{z}/{y}/{x}.png"
    ).apply { minZoom = 0f; maxZoom = 18f }
    style.addSource(RasterSource("seachart", chartTiles, 256))
    val chartLayer = RasterLayer("seachart-layer", "seachart").withProperties(PropertyFactory.rasterOpacity(1.0f))
    if (firstSymbol != null) style.addLayerBelow(chartLayer, firstSymbol) else style.addLayer(chartLayer)

    // Nautical seamark overlay (OpenSeaMap raster).
    val tiles = TileSet("2.1.0", "https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png").apply {
        minZoom = 0f; maxZoom = 18f
    }
    style.addSource(RasterSource(SRC_SEA, tiles, 256))
    style.addLayer(RasterLayer("seamark-layer", SRC_SEA).withProperties(PropertyFactory.rasterOpacity(0.9f)))

    // Vessels.
    style.addSource(GeoJsonSource(SRC_VESSELS))
    val movingTeal = Expression.rgb(34, 211, 160)
    val stoppedGrey = Expression.rgb(140, 150, 170)
    val isMoving = Expression.gt(
        Expression.toNumber(Expression.coalesce(Expression.get("speed"), Expression.literal(0))),
        Expression.literal(0.5)
    )
    style.addLayer(
        CircleLayer("vessels-dot", SRC_VESSELS).withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(6, 3f), Expression.stop(10, 5f), Expression.stop(14, 7f))
            ),
            PropertyFactory.circleColor(Expression.switchCase(isMoving, movingTeal, stoppedGrey)),
            PropertyFactory.circleStrokeColor(Color.parseColor("#0A1119")),
            PropertyFactory.circleStrokeWidth(1.5f),
        )
    )
    style.addImage(IMG_ARROW, arrowBitmap(Color.parseColor("#22D3A0")))
    style.addLayer(
        SymbolLayer("vessels-arrow", SRC_VESSELS).withProperties(
            PropertyFactory.iconImage(IMG_ARROW),
            PropertyFactory.iconSize(0.6f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment("map"),
            PropertyFactory.iconRotate(Expression.toNumber(Expression.coalesce(Expression.get("cog"), Expression.literal(0)))),
        ).withFilter(isMoving)
    )

    // GPS puck.
    style.addSource(GeoJsonSource(SRC_ME))
    style.addLayer(
        CircleLayer("me-ring", SRC_ME).withProperties(
            PropertyFactory.circleColor(Color.parseColor("#3AA0FF")),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleRadius(22f),
        )
    )
    style.addLayer(
        CircleLayer("me-dot", SRC_ME).withProperties(
            PropertyFactory.circleColor(Color.parseColor("#3AA0FF")),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
}

private fun pushData(holder: MapHolder, state: SeaMapState) {
    val style = holder.style ?: return
    style.getSourceAs<GeoJsonSource>(SRC_VESSELS)?.setGeoJson(state.vesselsJson)
    style.getLayer("seamark-layer")?.setProperties(
        PropertyFactory.visibility(if (state.seamarks) "visible" else "none")
    )
    val depthVis = if (state.depth) "visible" else "none"
    style.getLayer("depth-layer")?.setProperties(PropertyFactory.visibility(depthVis))
    style.getLayer("seachart-layer")?.setProperties(PropertyFactory.visibility(depthVis))
    val lat = state.meLat; val lon = state.meLon
    val meJson = if (lat != null && lon != null)
        """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{}}]}"""
    else EMPTY_FC
    style.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(meJson)

    if (!holder.didInitialCamera && lat != null && lon != null) {
        holder.didInitialCamera = true
        holder.map?.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(lat, lon)).zoom(11.0).build()
            ), 600
        )
    }
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibreInit.ensure(context)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
