package com.breeze.weather.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.breeze.weather.data.RadarFrame
import com.breeze.weather.data.RainViewer
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * OpenFreeMap's "Liberty" style: free OpenStreetMap vector tiles, no key or account. Its
 * attribution (OpenFreeMap, OpenMapTiles, OpenStreetMap) comes with the style.
 */
internal const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** The style's land colour, so legends can show an overlay as it looks on the map. */
internal const val MAP_LAND = 0xFFF8F4F0.toInt()

internal const val PLACE_SOURCE = "breeze-place"
internal const val PLACE_LAYER = "breeze-place-dot"
internal const val ARROW_IMAGE = "breeze-arrow"

internal fun initMapLibre(context: Context) {
    MapLibre.getInstance(context.applicationContext)
}

/**
 * A MapLibre [MapView] that follows the screen's lifecycle and is destroyed when it leaves the
 * composition.
 */
@Composable
internal fun rememberMapView(options: (MapLibreMapOptions) -> MapLibreMapOptions): MapView {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        initMapLibre(context)
        MapView(context, options(MapLibreMapOptions.createFromAttributes(context))).also { it.onCreate(null) }
    }
    DisposableEffect(lifecycleOwner, mapView) {
        var started = false
        var resumed = false
        fun moveTo(state: Lifecycle.State) {
            if (state.isAtLeast(Lifecycle.State.STARTED) && !started) { mapView.onStart(); started = true }
            if (state.isAtLeast(Lifecycle.State.RESUMED) && !resumed) { mapView.onResume(); resumed = true }
            if (!state.isAtLeast(Lifecycle.State.RESUMED) && resumed) { mapView.onPause(); resumed = false }
            if (!state.isAtLeast(Lifecycle.State.STARTED) && started) { mapView.onStop(); started = false }
        }
        val observer = LifecycleEventObserver { _, event -> moveTo(event.targetState) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            moveTo(Lifecycle.State.CREATED)
            mapView.onDestroy()
        }
    }
    return mapView
}

internal fun pointGeoJson(lat: Double, lon: Double): String = """{"type":"Point","coordinates":[$lon,$lat]}"""

/** The place as an orange dot ringed in white, like a map pin seen from above. */
internal fun placeDotLayer(): CircleLayer = CircleLayer(PLACE_LAYER, PLACE_SOURCE).withProperties(
    circleRadius(7f),
    circleColor(0xFFFFA726.toInt()),
    circleStrokeColor(android.graphics.Color.WHITE),
    circleStrokeWidth(2.5f),
)

internal fun radarSource(id: String, frame: RadarFrame): RasterSource =
    RasterSource(id, TileSet("2.2.0", frame.tiles).apply { maxZoom = RainViewer.MAX_ZOOM }, RainViewer.TILE_SIZE)

/** Overlays go under the first label layer so place names stay readable on top. */
internal fun Style.firstSymbolLayerId(): String? = layers.firstOrNull { it is SymbolLayer }?.id

/** A wind arrow pointing up (north): white with a dark edge so it shows on any colour. */
internal fun arrowBitmap(density: Float): Bitmap {
    val size = (22 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val s = size.toFloat()
    val path = Path().apply {
        moveTo(s * 0.5f, s * 0.08f)
        lineTo(s * 0.8f, s * 0.46f)
        lineTo(s * 0.58f, s * 0.46f)
        lineTo(s * 0.58f, s * 0.92f)
        lineTo(s * 0.42f, s * 0.92f)
        lineTo(s * 0.42f, s * 0.46f)
        lineTo(s * 0.2f, s * 0.46f)
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.6f * density
    paint.color = 0xCC1B2440.toInt()
    canvas.drawPath(path, paint)
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    canvas.drawPath(path, paint)
    return bitmap
}
