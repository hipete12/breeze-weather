package com.breeze.weather.ui

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.breeze.weather.data.MapRepository
import com.breeze.weather.data.Place
import com.breeze.weather.data.RadarFrame
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Rendered previews by place, size and radar frame, so paging between places doesn't redraw them. */
private val previews = LruCache<String, Bitmap>(6)

/**
 * The latest radar around [place] as a still map; tapping opens the full map. A still image
 * (rendered off-screen once) keeps the home screen scrolling smoothly.
 */
@Composable
internal fun RadarCard(place: Place, zone: ZoneId, use24h: Boolean, onOpen: () -> Unit) {
    var frames by remember { mutableStateOf<List<RadarFrame>?>(null) }
    LaunchedEffect(Unit) { frames = runCatching { MapRepository.radarFrames() }.getOrDefault(emptyList()) }
    val latest = frames?.lastOrNull()
    GlassCard(
        title = "Radar",
        icon = Icons.Rounded.Radar,
        onClick = onOpen,
        trailing = latest?.let { frame ->
            {
                val pattern = if (use24h) "HH:mm" else "h:mm a"
                Text(
                    Instant.ofEpochSecond(frame.time).atZone(zone).format(DateTimeFormatter.ofPattern(pattern)),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSky.copy(alpha = 0.6f),
                )
            }
        },
    ) {
        RadarPreview(
            place = place,
            frame = latest,
            ready = frames != null,
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Radar: RainViewer · Map © OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = OnSky.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun RadarPreview(place: Place, frame: RadarFrame?, ready: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        val widthDp = maxWidth.value.roundToInt()
        val heightDp = maxHeight.value.roundToInt()
        val key = "${place.latitude},${place.longitude},$widthDp,$heightDp,${frame?.time}"
        var bitmap by remember(key) { mutableStateOf<Bitmap?>(previews.get(key)) }

        if (ready && bitmap == null && widthDp > 0 && heightDp > 0) {
            DisposableEffect(key) {
                initMapLibre(context)
                val style = Style.Builder().fromUri(MAP_STYLE_URL)
                if (frame != null) {
                    style.withSource(radarSource("breeze-radar", frame))
                        .withLayer(RasterLayer("breeze-radar", "breeze-radar").withProperties(rasterOpacity(0.78f)))
                }
                style.withSource(GeoJsonSource(PLACE_SOURCE, pointGeoJson(place.latitude, place.longitude)))
                    .withLayer(placeDotLayer())
                val options = MapSnapshotter.Options(widthDp, heightDp)
                    .withPixelRatio(density)
                    .withStyleBuilder(style)
                    .withCameraPosition(CameraPosition.Builder().target(LatLng(place.latitude, place.longitude)).zoom(5.0).build())
                    .withLogo(false)
                    .withAttribution(false)
                val snapshotter = MapSnapshotter(context, options)
                snapshotter.start(
                    object : MapSnapshotter.SnapshotReadyCallback {
                        override fun onSnapshotReady(snapshot: MapSnapshot) {
                            previews.put(key, snapshot.bitmap)
                            bitmap = snapshot.bitmap
                        }
                    },
                    object : MapSnapshotter.ErrorHandler {
                        override fun onError(error: String) {
                            Log.w("BreezeRadar", "Radar preview failed: $error")
                        }
                    },
                )
                onDispose { snapshotter.cancel() }
            }
        }

        val shown = bitmap
        if (shown != null) {
            Image(
                shown.asImageBitmap(),
                contentDescription = "Radar map around ${place.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(Modifier.size(24.dp), color = OnSky, strokeWidth = 2.dp)
        }
    }
}
