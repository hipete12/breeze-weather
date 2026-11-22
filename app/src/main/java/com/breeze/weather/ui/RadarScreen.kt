package com.breeze.weather.ui

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.breeze.weather.data.GridArea
import com.breeze.weather.data.MapField
import com.breeze.weather.data.MapRepository
import com.breeze.weather.data.Place
import com.breeze.weather.data.RadarFrame
import com.breeze.weather.data.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.rasterResampling
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textPadding
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** What the map can show over the place. */
enum class MapLayer(val label: String, val icon: ImageVector) {
    RADAR("Radar", Icons.Rounded.Radar),
    TEMPERATURE("Temperature", Icons.Rounded.Thermostat),
    WIND("Wind", Icons.Rounded.Air),
    CLOUDS("Clouds", Icons.Rounded.Cloud);

    /** Forecast layers show the coming hours; the radar shows the past two. */
    val isForecast: Boolean get() = this != RADAR
}

private val PanelColor = Color(0xFF1B2440)
private val Accent = Color(0xFFFFA726)

private const val TAG = "BreezeRadar"
private const val RADAR_PREFIX = "breeze-radar-"
private const val FIELD_SOURCE = "breeze-field"
private const val FIELD_LAYER = "breeze-field-layer"
private const val ARROW_SOURCE = "breeze-arrows"
private const val ARROW_LAYER = "breeze-arrows-layer"
private const val LABEL_SOURCE = "breeze-labels"
private const val LABEL_LAYER = "breeze-labels-layer"

/** Radar opacity, and the near-invisible opacity that keeps other frames' tiles loaded for playback. */
private const val RADAR_OPACITY = 0.78f
private const val RADAR_HIDDEN = 0.01f

/** Width of the forecast overlay image; the map smooths it further when drawing. */
private const val FIELD_PX = 320

/** The visible map area and zoom. */
private data class Viewport(val west: Double, val east: Double, val north: Double, val south: Double, val zoom: Double)

private fun MapLibreMap.viewport(): Viewport {
    val b = projection.visibleRegion.latLngBounds
    return Viewport(b.longitudeWest, b.longitudeEast, b.latitudeNorth, b.latitudeSouth, cameraPosition.zoom)
}

/** A forecast overlay image, tagged with what it shows so a stale one is never shown under a new label. */
private class FieldOverlay(val layer: MapLayer, val field: MapField, val hour: Int, val bitmap: Bitmap)

/**
 * Full-screen map around [place]: animated precipitation radar for the past two hours, or the
 * forecast temperature, wind or cloud cover for the coming hours, with a timeline and legend.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(place: Place, units: Units, zone: ZoneId, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val use24h = remember { DateFormat.is24HourFormat(context) }
    val statusTop = WindowInsets.statusBars.getTop(density)
    val placeLatLng = remember(place) { LatLng(place.latitude, place.longitude) }

    val mapView = rememberMapView { options ->
        options.logoEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .attributionGravity(Gravity.TOP or Gravity.START)
            .attributionTintColor(PanelColor.toArgb())
            .minZoomPreference(2.0)
            .maxZoomPreference(10.5)
            .camera(CameraPosition.Builder().target(placeLatLng).zoom(5.5).build())
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var layer by rememberSaveable { mutableStateOf(MapLayer.RADAR) }
    var frames by remember { mutableStateOf<List<RadarFrame>?>(null) }
    var framesFailed by remember { mutableStateOf(false) }
    /** Radar frame shown, or -1 to follow the latest. */
    var radarIndex by remember { mutableIntStateOf(-1) }
    var hourIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    var field by remember { mutableStateOf<MapField?>(null) }
    var fieldZoom by remember { mutableDoubleStateOf(0.0) }
    var fieldLoading by remember { mutableStateOf(false) }
    var fieldFailed by remember { mutableStateOf(false) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            val px = { dp: Int -> (dp * density.density).roundToInt() }
            m.uiSettings.setAttributionMargins(px(14), statusTop + px(64), 0, 0)
            m.addOnCameraIdleListener { viewport = m.viewport() }
            m.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { s ->
                s.addSource(GeoJsonSource(PLACE_SOURCE, pointGeoJson(place.latitude, place.longitude)))
                s.addLayer(placeDotLayer())
                s.addImage(ARROW_IMAGE, arrowBitmap(density.density))
                style = s
                viewport = m.viewport()
            }
            map = m
        }
    }

    // Radar frames, refreshed while the map stays open (and retried soon after a failure).
    LaunchedEffect(Unit) {
        while (true) {
            val ok = runCatching { MapRepository.radarFrames() }
                .onSuccess { frames = it; framesFailed = false }
                .onFailure {
                    Log.w(TAG, "Radar frames failed", it)
                    if (frames == null) framesFailed = true
                }
                .isSuccess
            delay(if (ok) 5 * 60_000L else 20_000L)
        }
    }

    // Forecast values for the visible area, fetched again once the map moves or zooms well past them.
    LaunchedEffect(layer, viewport) {
        val vp = viewport ?: return@LaunchedEffect
        if (!layer.isForecast) return@LaunchedEffect
        val current = field
        if (current != null && current.area.covers(vp.west, vp.east, vp.north, vp.south) && vp.zoom - fieldZoom < 1.5) {
            return@LaunchedEffect
        }
        delay(250)
        fieldLoading = true
        try {
            runCatching { MapRepository.field(GridArea.around(vp.west, vp.east, vp.north, vp.south)) }
                .onSuccess { field = it; fieldZoom = vp.zoom; fieldFailed = false }
                .onFailure {
                    Log.w(TAG, "Forecast map failed", it)
                    fieldFailed = true
                }
        } finally {
            fieldLoading = false
        }
    }

    val overlayCache = remember(field) { HashMap<Pair<MapLayer, Int>, Bitmap>() }
    val overlay by produceState<FieldOverlay?>(null, field, layer, hourIndex) {
        val f = field ?: return@produceState
        if (!layer.isForecast || f.times.isEmpty()) return@produceState
        val hour = hourIndex.coerceIn(0, f.times.lastIndex)
        val key = layer to hour
        val bitmap = overlayCache[key] ?: withContext(Dispatchers.Default) {
            val (values, ramp) = when (layer) {
                MapLayer.TEMPERATURE -> f.temperature[hour] to MapRamps.TEMPERATURE
                MapLayer.WIND -> f.windSpeed[hour] to MapRamps.WIND
                else -> f.cloudCover[hour] to MapRamps.CLOUDS
            }
            val image = renderField(f.area, values, FIELD_PX, ramp)
            Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        }.also { overlayCache[key] = it }
        value = FieldOverlay(layer, f, hour, bitmap)
    }

    val radarFrames = frames.orEmpty()
    val shownRadar = if (radarIndex < 0) radarFrames.lastIndex else radarIndex.coerceAtMost(radarFrames.lastIndex)
    val hours = field?.times.orEmpty()
    val shownHour = hourIndex.coerceIn(0, (hours.size - 1).coerceAtLeast(0))

    LaunchedEffect(style, layer, radarFrames, shownRadar) {
        style?.syncRadar(radarFrames, shownRadar, visible = layer == MapLayer.RADAR)
    }
    LaunchedEffect(style, layer, overlay) {
        val s = style ?: return@LaunchedEffect
        val o = overlay?.takeIf { it.layer == layer }
        s.syncField(o, layer, units)
    }

    val count = if (layer.isForecast) hours.size else radarFrames.size
    val index = if (layer.isForecast) shownHour else shownRadar
    // Picking the newest radar frame goes back to following the latest as new ones arrive.
    fun select(i: Int) {
        if (layer.isForecast) hourIndex = i else radarIndex = if (i >= radarFrames.lastIndex) -1 else i
    }

    LaunchedEffect(playing, layer, count) {
        if (!playing || count < 2) return@LaunchedEffect
        var i = index
        while (true) {
            delay(if (i == count - 1) 1400L else 650L)
            i = (i + 1) % count
            select(i)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(MAP_LAND))) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Keeps the (white) status bar icons readable over the light map.
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent)))
        )

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSky,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(PanelColor.copy(alpha = 0.85f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (fieldLoading && layer.isForecast || frames == null && !framesFailed) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(PanelColor.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = OnSky, strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(8.dp))
            }
            RoundButton(Icons.Rounded.MyLocation, "Centre on ${place.name}") {
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(placeLatLng, 5.5))
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(PanelColor.copy(alpha = 0.96f))
                .navigationBarsPadding()
                .padding(top = 14.dp, bottom = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MapLayer.entries.forEach { l ->
                    LayerTab(l, selected = l == layer, Modifier.weight(1f)) {
                        if (l != layer) {
                            layer = l
                            playing = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            val status = when {
                !layer.isForecast && framesFailed -> "Radar unavailable. Check your connection."
                !layer.isForecast && frames?.isEmpty() == true -> "No radar images right now."
                layer.isForecast && fieldFailed && field == null -> "Forecast map unavailable. Check your connection."
                count == 0 -> "Loading…"
                else -> null
            }
            Row(Modifier.padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing, enabled = count >= 2) { playing = !playing }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Bottom) {
                        if (status != null) {
                            Text(status, style = MaterialTheme.typography.titleSmall, color = OnSky.copy(alpha = 0.8f))
                        } else {
                            val time = if (layer.isForecast) hours[index] else radarFrames[index].time
                            Text(timeLabel(time, zone, use24h, minutes = !layer.isForecast), style = MaterialTheme.typography.titleSmall, color = OnSky)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (layer.isForecast) forecastOffset(index) else radarAge(time),
                                style = MaterialTheme.typography.labelMedium,
                                color = OnSky.copy(alpha = 0.7f),
                            )
                        }
                    }
                    Slider(
                        value = index.coerceAtLeast(0).toFloat(),
                        onValueChange = { select(it.roundToInt()); playing = false },
                        valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                        steps = (count - 2).coerceAtLeast(0),
                        enabled = count >= 2,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = OnSky.copy(alpha = 0.22f),
                            activeTickColor = PanelColor.copy(alpha = 0.5f),
                            inactiveTickColor = OnSky.copy(alpha = 0.45f),
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = if (layer.isForecast) "Forecast hour" else "Radar time"
                        },
                    )
                }
            }
            Legend(layer, units, Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                if (layer.isForecast) "Forecast: Open-Meteo · Map © OpenStreetMap contributors"
                else "Radar: RainViewer · Map © OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = OnSky.copy(alpha = 0.45f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/** Adds a layer per radar frame (once) and shows just the selected one. */
private fun Style.syncRadar(frames: List<RadarFrame>, shown: Int, visible: Boolean) {
    val wanted = frames.map { RADAR_PREFIX + it.time }.toSet()
    layers.filter { it.id.startsWith(RADAR_PREFIX) && it.id !in wanted }.forEach {
        removeLayer(it)
        removeSource(it.id + "-src")
    }
    val below = firstSymbolLayerId()
    frames.forEachIndexed { i, frame ->
        val id = RADAR_PREFIX + frame.time
        val layer = getLayer(id) ?: RasterLayer(id, "$id-src").also { l ->
            addSource(radarSource("$id-src", frame))
            l.setProperties(rasterFadeDuration(0f), rasterOpacity(RADAR_HIDDEN))
            l.rasterOpacityTransition = TransitionOptions(120, 0)
            if (below != null) addLayerBelow(l, below) else addLayerBelow(l, PLACE_LAYER)
        }
        layer.setProperties(
            visibility(if (visible) Property.VISIBLE else Property.NONE),
            rasterOpacity(if (i == shown) RADAR_OPACITY else RADAR_HIDDEN),
        )
    }
}

/** Lays the forecast overlay (and wind arrows) over its area, or hides them. */
private fun Style.syncField(overlay: FieldOverlay?, layer: MapLayer, units: Units) {
    val image = getLayer(FIELD_LAYER)
    val arrows = getLayer(ARROW_LAYER)
    val labels = getLayer(LABEL_LAYER)
    if (overlay == null) {
        image?.setProperties(visibility(Property.NONE))
        arrows?.setProperties(visibility(Property.NONE))
        labels?.setProperties(visibility(Property.NONE))
        return
    }
    val a = overlay.field.area
    val quad = LatLngQuad(LatLng(a.north, a.west), LatLng(a.north, a.east), LatLng(a.south, a.east), LatLng(a.south, a.west))
    val source = getSource(FIELD_SOURCE) as? ImageSource
    if (source == null) {
        addSource(ImageSource(FIELD_SOURCE, quad, overlay.bitmap))
        val l = RasterLayer(FIELD_LAYER, FIELD_SOURCE).withProperties(rasterFadeDuration(0f), rasterResampling(Property.RASTER_RESAMPLING_LINEAR))
        val below = firstSymbolLayerId()
        if (below != null) addLayerBelow(l, below) else addLayerBelow(l, PLACE_LAYER)
    } else {
        source.setCoordinates(quad)
        source.setImage(overlay.bitmap)
    }
    getLayer(FIELD_LAYER)?.setProperties(
        visibility(Property.VISIBLE),
        rasterOpacity(if (layer == MapLayer.CLOUDS) 1f else 0.6f),
    )

    val hour = overlay.hour
    if (layer == MapLayer.TEMPERATURE) {
        val json = temperatureLabelsGeoJson(a, overlay.field.temperature[hour], units == Units.IMPERIAL)
        val labelSource = getSource(LABEL_SOURCE) as? GeoJsonSource
        if (labelSource == null) {
            addSource(GeoJsonSource(LABEL_SOURCE, json))
            // Under the map's own labels, which win any collision, so place names stay readable.
            val l = SymbolLayer(LABEL_LAYER, LABEL_SOURCE).withProperties(
                textField(Expression.get("t")),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(13f),
                textColor(PanelColor.toArgb()),
                textHaloColor(android.graphics.Color.WHITE),
                textHaloWidth(1.4f),
                textPadding(6f),
            )
            val below = firstSymbolLayerId()
            if (below != null) addLayerBelow(l, below) else addLayerBelow(l, PLACE_LAYER)
        } else {
            labelSource.setGeoJson(json)
        }
        getLayer(LABEL_LAYER)?.setProperties(visibility(Property.VISIBLE))
    } else {
        labels?.setProperties(visibility(Property.NONE))
    }

    if (layer != MapLayer.WIND) {
        arrows?.setProperties(visibility(Property.NONE))
        return
    }
    val json = windArrowsGeoJson(a, overlay.field.windSpeed[hour], overlay.field.windDirection[hour])
    val arrowSource = getSource(ARROW_SOURCE) as? GeoJsonSource
    if (arrowSource == null) {
        addSource(GeoJsonSource(ARROW_SOURCE, json))
        addLayerBelow(
            SymbolLayer(ARROW_LAYER, ARROW_SOURCE).withProperties(
                iconImage(ARROW_IMAGE),
                iconRotate(Expression.get("rot")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconOpacity(0.9f),
                iconSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.get("spd"),
                        Expression.stop(0, 0.55f), Expression.stop(30, 0.85f), Expression.stop(90, 1.25f),
                    )
                ),
            ),
            PLACE_LAYER,
        )
    } else {
        arrowSource.setGeoJson(json)
    }
    getLayer(ARROW_LAYER)?.setProperties(visibility(Property.VISIBLE))
}

private fun timeLabel(epochSeconds: Long, zone: ZoneId, use24h: Boolean, minutes: Boolean): String {
    val pattern = when {
        use24h -> "EEE HH:mm"
        minutes -> "EEE h:mm a"
        else -> "EEE h a"
    }
    return Instant.ofEpochSecond(epochSeconds).atZone(zone).format(DateTimeFormatter.ofPattern(pattern))
}

private fun radarAge(epochSeconds: Long): String {
    val minutes = ((System.currentTimeMillis() / 1000 - epochSeconds) / 60).toInt()
    return when {
        minutes < 15 -> "Latest"
        minutes < 60 -> "$minutes min ago"
        minutes % 60 < 5 -> "${minutes / 60} h ago"
        else -> "${minutes / 60} h ${minutes % 60} min ago"
    }
}

private fun forecastOffset(hour: Int): String = if (hour == 0) "Now" else "In $hour h"

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(PanelColor.copy(alpha = 0.85f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = OnSky, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = if (enabled) OnSky else OnSky.copy(alpha = 0.35f),
            modifier = Modifier.size(32.dp),
        )
    }
}

/** One of four equal tabs: icon over label, filled white when selected. */
@Composable
private fun LayerTab(layer: MapLayer, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val content = if (selected) PanelColor else OnSky
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) OnSky else OnSky.copy(alpha = 0.08f))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(layer.icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(layer.label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** The colour scale for [layer] as it looks over the map, with labels in the app's units. */
@Composable
private fun Legend(layer: MapLayer, units: Units, modifier: Modifier = Modifier) {
    val (ramp, opacity) = when (layer) {
        MapLayer.RADAR -> MapRamps.RADAR to 1f
        MapLayer.TEMPERATURE -> MapRamps.TEMPERATURE to 0.6f
        MapLayer.WIND -> MapRamps.WIND to 0.6f
        MapLayer.CLOUDS -> MapRamps.CLOUDS to 1f
    }
    val span = ramp.max - ramp.min
    fun at(v: Float) = ((v - ramp.min) / span).coerceIn(0f, 1f)
    val labels: List<Pair<Float, String>> = when (layer) {
        MapLayer.RADAR -> listOf(0f to "Light", 0.33f to "Moderate", 0.6f to "Heavy", 1f to "Intense")
        MapLayer.TEMPERATURE -> if (units == Units.IMPERIAL) {
            listOf(-40, 0, 32, 70, 100).map { f -> at((f - 32) / 1.8f) to "$f°" }
        } else {
            listOf(-40, -20, 0, 20, 40).map { c -> at(c.toFloat()) to "$c°" }
        }
        MapLayer.WIND -> if (units == Units.IMPERIAL) {
            listOf(0, 20, 40, 60, 80).map { mph -> at(mph * 1.609344f) to "$mph" } + (1f to "mph")
        } else {
            listOf(0, 30, 60, 90).map { k -> at(k.toFloat()) to "$k" } + (1f to "km/h")
        }
        MapLayer.CLOUDS -> listOf(0f to "Clear", 0.5f to "50%", 1f to "Overcast")
    }
    val brush = Brush.horizontalGradient(
        *ramp.stops.map { (v, c) -> at(v) to Color(over(c, MAP_LAND, opacity)) }.toTypedArray()
    )
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(brush))
        Spacer(Modifier.height(4.dp))
        Layout(
            content = {
                labels.forEach { (_, text) ->
                    Text(text, style = MaterialTheme.typography.labelSmall, color = OnSky.copy(alpha = 0.75f))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
            val width = constraints.maxWidth
            layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
                placeables.forEachIndexed { i, p ->
                    val x = (labels[i].first * width - p.width / 2f).roundToInt().coerceIn(0, width - p.width)
                    p.place(x, 0)
                }
            }
        }
    }
}
