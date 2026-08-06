package com.mikeos.sea.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource

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
    @Volatile var soundingsJson: String = EMPTY_FC
    @Volatile var trackJson: String = EMPTY_FC
    @Volatile var navJson: String = EMPTY_FC   // waypoint + course line + MOB, one FeatureCollection (kind=…)
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
    onLongPress: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    val holder = remember { MapHolder() }
    val onViewportState by rememberUpdatedState(onViewport)
    val onVesselTapState by rememberUpdatedState(onVesselTap)
    val onLongPressState by rememberUpdatedState(onLongPress)
    val styleUrl = "${BuildConfig.BASEMAP_URL}/style.json"

    AndroidView(
        modifier = modifier,
        factory = { _ ->
            mapView.getMapAsync { map ->
                holder.map = map
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isTiltGesturesEnabled = false
                // Default over the surveyed Beaulieu bay (Litto3D coverage) at z14 — the coverage
                // patch is small (~0.06°), so a wide zoom spreads the sounding grid outside the data
                // and shows almost nothing. z14 fits the bay so the depth grid lands on real data.
                // The locate FAB still flies to the user's real GPS fix.
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(state.meLat ?: 43.695, state.meLon ?: 7.310))
                    .zoom(if (state.meLat != null) 13.0 else 14.0).build()

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
                map.addOnMapLongClickListener { latLng ->
                    onLongPressState(latLng.latitude, latLng.longitude); true
                }
            }
            mapView
        },
        update = { pushData(holder, state) },
    )

    // AndroidView's update lambda doesn't observe SeaMapState's @Volatile fields, so it won't
    // re-run when data changes. Drive the pushes explicitly from keyed effects: pushData whenever
    // the data nonce bumps (soundings/vessels/track/nav), and ease the camera on a fly request.
    LaunchedEffect(dataNonce) { pushData(holder, state) }
    LaunchedEffect(flyNonce) {
        val f = flyTo
        if (f != null && flyNonce > 0) {
            holder.map?.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(f).zoom(14.0).build()
                ), 700
            )
        }
    }
}

private fun installLayers(style: Style) {
    // Global open nautical chart — Open Waters Seascape (CC BY 4.0), the SAME sea map as
    // sea.osmike.com: depth-area fills, isobath contours + labels, and worldwide spot soundings,
    // sampled from a vector tile source. Inserted BELOW the base map's first label layer so OSM
    // place labels stay on top. This replaces the old EMODnet raster with a proper vector chart.
    val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
    fun addBelowSymbols(layer: org.maplibre.android.style.layers.Layer) {
        if (firstSymbol != null) style.addLayerBelow(layer, firstSymbol) else style.addLayer(layer)
    }
    style.addSource(VectorSource("seascape-vector", "https://tiles.openwaters.io/seascape/vector.json"))

    // Depth-area graduated fill (source-layer "depare"): green intertidal, then a shoal→deep blue ramp.
    val depthAreas = FillLayer("depth-areas", "seascape-vector").apply { sourceLayer = "depare"; minZoom = 6f }
        .withProperties(
            PropertyFactory.fillColor(
                Expression.switchCase(
                    Expression.not(Expression.has("drval1")), Expression.color(Color.parseColor("#cfe6fb")),
                    Expression.lt(Expression.get("drval1"), Expression.literal(0)), Expression.color(Color.parseColor("#a8d5ba")),
                    Expression.switchCase(
                        Expression.lt(Expression.get("drval1"), Expression.literal(1.99)), Expression.color(Color.parseColor("#3fa2e4")),
                        Expression.step(Expression.get("drval1"), Expression.color(Color.parseColor("#5db5f0")),
                            Expression.stop(1.99, Expression.color(Color.parseColor("#7fc7f8"))),
                            Expression.stop(4.99, Expression.color(Color.parseColor("#a5d9fb"))),
                            Expression.stop(9.99, Expression.color(Color.parseColor("#c9e9fd"))),
                            Expression.stop(19.99, Expression.color(Color.parseColor("#e2f2fd"))),
                            Expression.stop(49.99, Expression.color(Color.parseColor("#eef7ff"))))
                    )
                )
            ),
            PropertyFactory.fillOpacity(0.9f),
        ).withFilter(Expression.not(Expression.has("sys")))
    addBelowSymbols(depthAreas)

    // Isobath contour lines (source-layer "contours").
    val contourLines = LineLayer("contour-lines", "seascape-vector").apply { sourceLayer = "contours"; minZoom = 6f }
        .withProperties(
            PropertyFactory.lineColor(Color.parseColor("#4a7a9c")),
            PropertyFactory.lineWidth(0.6f),
            PropertyFactory.lineOpacity(0.6f),
        ).withFilter(Expression.neq(Expression.get("sys"), Expression.literal("ft")))
    addBelowSymbols(contourLines)

    // Contour depth labels ("12m") along the isobaths — dark text, no halo (per the app's no-halo rule).
    val contourLabels = SymbolLayer("contour-labels", "seascape-vector").apply { sourceLayer = "contours"; minZoom = 8f }
        .withProperties(
            PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            PropertyFactory.textField(Expression.concat(Expression.toString(Expression.get("depth_abs_m")), Expression.literal("m"))),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textMaxAngle(30f),
            PropertyFactory.textPadding(50f),
            PropertyFactory.textColor(Color.parseColor("#003366")),
            PropertyFactory.textHaloWidth(0f),
        ).withFilter(Expression.neq(Expression.get("sys"), Expression.literal("ft")))
    addBelowSymbols(contourLabels)

    // Worldwide spot soundings from the Seascape vector chart (source-layer "soundings").
    val seaSoundings = SymbolLayer("seascape-soundings", "seascape-vector").apply { sourceLayer = "soundings"; minZoom = 7f }
        .withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("depth_m"))),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textPadding(6f),
            PropertyFactory.textColor(
                Expression.switchCase(
                    Expression.lte(Expression.get("depth_m"), Expression.literal(2)), Expression.color(Color.parseColor("#000000")),
                    Expression.color(Color.parseColor("#003366"))
                )
            ),
            PropertyFactory.textHaloWidth(0f),
        )
    addBelowSymbols(seaSoundings)

    // Kartverket Sjøkart — the OFFICIAL Norwegian nautical chart (NLOD): depth areas, soundings,
    // buoys, beacons, lights, fairways/channels, port entrances. XYZ cache (note {z}/{y}/{x} order).
    // Sits above the EMODnet depth shading; covers Norwegian waters, falls back to EMODnet/OSM elsewhere.
    val chartTiles = TileSet(
        "2.1.0", "https://cache.kartverket.no/v1/wmts/1.0.0/sjokartraster/default/webmercator/{z}/{y}/{x}.png"
    ).apply { minZoom = 0f; maxZoom = 18f }
    style.addSource(RasterSource("seachart", chartTiles, 256))
    val chartLayer = RasterLayer("seachart-layer", "seachart").withProperties(PropertyFactory.rasterOpacity(1.0f))
    if (firstSymbol != null) style.addLayerBelow(chartLayer, firstSymbol) else style.addLayer(chartLayer)

    // SHOM Litto3D 1m survey depth (Licence Ouverte) — real harbour bathymetry (French coast, expanding).
    val littoTiles = TileSet("2.1.0", "https://marine.osmike.com/litto/{z}/{x}/{y}.png").apply { minZoom = 0f; maxZoom = 18f }
    style.addSource(RasterSource("litto", littoTiles, 256))
    val littoLayer = RasterLayer("litto-layer", "litto").withProperties(PropertyFactory.rasterOpacity(1.0f))
    if (firstSymbol != null) style.addLayerBelow(littoLayer, firstSymbol) else style.addLayer(littoLayer)

    // Dynamic spot-soundings (depth numbers) — ~fixed count evenly-spread per viewport, refreshed on
    // camera idle. Added at the TOP of the stack (above all raster overlays) so the numbers are never
    // occluded by the bathymetry raster. A small position dot marks each sounding (real-chart style).
    style.addSource(GeoJsonSource("soundings-dyn"))
    val dotColor = Expression.switchCase(
        Expression.lt(Expression.toNumber(Expression.get("d")), Expression.literal(5)), Expression.rgb(178, 58, 46),
        Expression.lt(Expression.toNumber(Expression.get("d")), Expression.literal(10)), Expression.rgb(10, 74, 122),
        Expression.rgb(0, 34, 64)
    )
    val dotLayer = CircleLayer("soundings-dot", "soundings-dyn").withProperties(
        PropertyFactory.circleRadius(2.2f),
        PropertyFactory.circleColor(dotColor),
        PropertyFactory.circleOpacity(0.9f),
    )
    dotLayer.minZoom = 9f
    style.addLayer(dotLayer)
    val sLayer = SymbolLayer("soundings-layer", "soundings-dyn").withProperties(
        PropertyFactory.textField(Expression.toString(Expression.get("lbl"))),
        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
        PropertyFactory.textSize(
            Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(9, 11f), Expression.stop(14, 13f), Expression.stop(18, 15f))
        ),
        PropertyFactory.textColor(dotColor),
        PropertyFactory.textHaloWidth(0f),
        PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
        PropertyFactory.textAllowOverlap(true),
        PropertyFactory.textIgnorePlacement(true),
    )
    sLayer.minZoom = 9f
    style.addLayer(sLayer)

    // Nautical seamark overlay (OpenSeaMap raster).
    val tiles = TileSet("2.1.0", "https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png").apply {
        minZoom = 0f; maxZoom = 18f
    }
    style.addSource(RasterSource(SRC_SEA, tiles, 256))
    style.addLayer(RasterLayer("seamark-layer", SRC_SEA).withProperties(PropertyFactory.rasterOpacity(0.9f)))

    // Trailing track — your own path while recording a trip.
    style.addSource(GeoJsonSource("track"))
    style.addLayer(
        LineLayer("track-layer", "track").withProperties(
            PropertyFactory.lineColor(Color.parseColor("#ff6d3a")),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
    )

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

    // Navigation: course line + waypoint ring + MOB dot (one FeatureCollection, filtered by 'kind').
    style.addSource(GeoJsonSource("nav"))
    style.addLayer(
        LineLayer("nav-line", "nav").withProperties(
            PropertyFactory.lineColor(Color.parseColor("#ffb300")),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
            PropertyFactory.lineCap("round"),
        ).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("course")))
    )
    // Waypoint pin — bold amber with a dark outline + white centre so it stands out on the blue sea
    // (the old semi-transparent blue ring was invisible over water).
    style.addLayer(
        CircleLayer("nav-wp", "nav").withProperties(
            PropertyFactory.circleRadius(11f),
            PropertyFactory.circleColor(Color.parseColor("#ffb300")),
            PropertyFactory.circleStrokeColor(Color.parseColor("#12324a")),
            PropertyFactory.circleStrokeWidth(3f),
        ).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("waypoint")))
    )
    style.addLayer(
        CircleLayer("nav-wp-core", "nav").withProperties(
            PropertyFactory.circleRadius(3.5f),
            PropertyFactory.circleColor(Color.WHITE),
        ).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("waypoint")))
    )
    // Red steer-back line from the boat to the man-overboard point (mirrors the course line).
    style.addLayer(
        LineLayer("nav-mobline", "nav").withProperties(
            PropertyFactory.lineColor(Color.parseColor("#e53935")),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("mobline")))
    )
    style.addLayer(
        CircleLayer("nav-mob", "nav").withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(Color.parseColor("#e53935")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("mob")))
    )

    lightenBase(style)
}

/** Repaint the dark basemap to a light nautical palette + readable label text (matches the web chart). */
private fun lightenBase(style: Style) {
    style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor(Color.parseColor("#eaf1f8")))
    style.getLayer("earth")?.setProperties(PropertyFactory.fillColor(Color.parseColor("#f3eddd")))
    style.getLayer("landcover")?.setProperties(PropertyFactory.fillColor(Color.parseColor("#edefe4")))
    style.getLayer("landuse_park")?.setProperties(PropertyFactory.fillColor(Color.parseColor("#e3ead7")))
    style.getLayer("water")?.setProperties(PropertyFactory.fillColor(Color.parseColor("#cfe6fb")))
    style.layers.forEach { l ->
        if (l is SymbolLayer && !l.id.contains("sound") && !l.id.contains("contour")) {
            l.setProperties(
                PropertyFactory.textColor(Color.parseColor("#1f3340")),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(0f),
            )
        } else if (l is LineLayer && l.id.contains("road")) {
            l.setProperties(PropertyFactory.lineColor(Color.parseColor("#d8d2c4")))
        }
    }
}

private fun pushData(holder: MapHolder, state: SeaMapState) {
    val style = holder.style ?: return
    style.getSourceAs<GeoJsonSource>(SRC_VESSELS)?.setGeoJson(state.vesselsJson)
    style.getLayer("seamark-layer")?.setProperties(
        PropertyFactory.visibility(if (state.seamarks) "visible" else "none")
    )
    val depthVis = if (state.depth) "visible" else "none"
    // Seascape base chart (fills/contours/labels/soundings) + Kartverket + Litto — toggled together.
    listOf("depth-areas", "contour-lines", "contour-labels", "seascape-soundings",
        "seachart-layer", "litto-layer").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.visibility(depthVis))
    }
    style.getSourceAs<GeoJsonSource>("soundings-dyn")?.setGeoJson(state.soundingsJson)
    style.getSourceAs<GeoJsonSource>("track")?.setGeoJson(state.trackJson)
    style.getSourceAs<GeoJsonSource>("nav")?.setGeoJson(state.navJson)
    val lat = state.meLat; val lon = state.meLon
    val meJson = if (lat != null && lon != null)
        """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{}}]}"""
    else EMPTY_FC
    style.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(meJson)

    if (!holder.didInitialCamera && lat != null && lon != null) {
        holder.didInitialCamera = true
        holder.map?.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                // z14: tight enough that the sounding grid lands inside the (currently small)
                // Litto3D coverage patch, so depth numbers actually appear on the first GPS fix.
                CameraPosition.Builder().target(LatLng(lat, lon)).zoom(14.0).build()
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
