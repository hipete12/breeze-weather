package com.breeze.weather

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeze.weather.data.PlacesStore
import com.breeze.weather.data.Units
import com.breeze.weather.ui.theme.BreezeTheme
import com.breeze.weather.widget.DataState
import com.breeze.weather.widget.RemoteViewsPreview
import com.breeze.weather.widget.SampleWeather
import com.breeze.weather.widget.TextScale
import com.breeze.weather.widget.WidgetBitmaps
import com.breeze.weather.widget.WidgetConfig
import com.breeze.weather.widget.WidgetData
import com.breeze.weather.widget.WidgetKind
import com.breeze.weather.widget.WidgetRenderer
import com.breeze.weather.widget.WidgetSection
import com.breeze.weather.widget.WidgetStore
import com.breeze.weather.widget.WidgetTheme
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Debug-only: renders widgets at several sizes over the wallpaper, with sample weather, for
 * screenshots. Examples:
 *
 *   adb shell am start -n com.breeze.weather/.WidgetLabActivity --es kind HOURLY
 *   adb shell am start -n com.breeze.weather/.WidgetLabActivity --es kind DAILY --es theme LIGHT --ei opacity 80
 *   adb shell am start -n com.breeze.weather/.WidgetLabActivity --es kind NOW --es sizes 80x90,170x80
 *   adb shell am start -n com.breeze.weather/.WidgetLabActivity --es kind FORECAST --es state LOADING
 *
 * Extras: kind, theme (SKY|SYSTEM|DARK|LIGHT), opacity (0-100), scale (SMALL|DEFAULT|LARGE),
 * sizes (WxH,…), units (METRIC|IMPERIAL), sections (HEADER,HOURLY,…), state (READY|LOADING|
 * NO_LOCATION|ERROR), hour (0-23, the sample weather's time of day), config (a WidgetConfig as
 * JSON, overriding the rest).
 *
 * With --ez export true it instead writes each kind's widget-picker preview (default size, sample
 * weather at [hour]) as a transparent PNG to the app's external files dir, then finishes:
 *
 *   adb shell am start -n com.breeze.weather/.WidgetLabActivity --ez export true --ei hour 13
 *   adb pull /sdcard/Android/data/com.breeze.weather/files/previews
 */
class WidgetLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val kind = WidgetKind.fromName(intent.getStringExtra("kind")) ?: WidgetKind.HOURLY
        val units = intent.getStringExtra("units")?.let { Units.valueOf(it) } ?: PlacesStore(this).units.value
        var config = intent.getStringExtra("config")?.let { WidgetStore.json.decodeFromString<WidgetConfig>(it) } ?: kind.defaultConfig()
        intent.getStringExtra("theme")?.let { config = config.copy(theme = WidgetTheme.valueOf(it)) }
        if (intent.hasExtra("opacity")) config = config.copy(opacity = intent.getIntExtra("opacity", 100))
        intent.getStringExtra("scale")?.let { config = config.copy(textScale = TextScale.valueOf(it)) }
        intent.getStringExtra("sections")?.let { s -> config = config.copy(sections = s.split(',').map { WidgetSection.valueOf(it) }.toSet()) }
        val state = intent.getStringExtra("state")?.let { DataState.valueOf(it) } ?: DataState.READY
        val sizes = intent.getStringExtra("sizes")?.split(',')?.map { s -> s.split('x').let { it[0].toFloat() to it[1].toFloat() } }
            ?: defaultSizes(kind)

        val now = if (intent.hasExtra("hour")) LocalDate.now().atTime(intent.getIntExtra("hour", 12), 20) else LocalDateTime.now()
        val sample = SampleWeather.create(units, utcOffsetSeconds = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds, now = now)
        val data = WidgetData(SampleWeather.place, sample.takeIf { state == DataState.READY }, state)
        val renderer = WidgetRenderer(this)
        if (intent.getBooleanExtra("export", false)) {
            exportPreviews(renderer, data)
            finish()
            return
        }
        setContent {
            BreezeTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sizes.forEach { (w, h) ->
                        Text("${kind.name} ${w.toInt()}×${h.toInt()} ${config.theme} ${config.opacity}%", color = Color.White, fontSize = 10.sp)
                        val views = renderer.render(0, kind, config, data, w, h, WidgetBitmaps(resources.displayMetrics.density), interactive = false)
                        RemoteViewsPreview(views, w, h, Modifier.fillMaxWidth().padding(horizontal = 4.dp), maxWidth = 400.dp)
                    }
                }
            }
        }
    }

    /** Draws each kind at its default size into a transparent bitmap and saves it as PNG. */
    private fun exportPreviews(renderer: WidgetRenderer, data: WidgetData) {
        val dir = File(getExternalFilesDir(null), "previews").apply { mkdirs() }
        val density = resources.displayMetrics.density
        for (kind in WidgetKind.entries) {
            val w = (kind.defaultWidthDp * density).roundToInt()
            val h = (kind.defaultHeightDp * density).roundToInt()
            val views = renderer.render(0, kind, kind.defaultConfig(), data, kind.defaultWidthDp.toFloat(), kind.defaultHeightDp.toFloat(), WidgetBitmaps(density), interactive = false)
            val parent = FrameLayout(this)
            val view = views.apply(this, parent)
            parent.addView(view, FrameLayout.LayoutParams(w, h))
            parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            parent.draw(Canvas(bitmap))
            File(dir, "widget_preview_${kind.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Log.i("BreezeWidgets", "Previews written to $dir")
    }

    private fun defaultSizes(kind: WidgetKind): List<Pair<Float, Float>> = when (kind) {
        WidgetKind.NOW -> listOf(80f to 90f, 170f to 80f, 170f to 170f, 260f to 80f, 90f to 190f)
        WidgetKind.CONDITIONS -> listOf(170f to 80f, 170f to 170f, 145f to 150f, 260f to 170f, 350f to 170f)
        WidgetKind.HOURLY -> listOf(260f to 80f, 350f to 90f, 350f to 170f, 300f to 150f, 350f to 230f)
        WidgetKind.DAILY -> listOf(350f to 90f, 350f to 170f, 350f to 260f, 260f to 260f, 350f to 350f)
        WidgetKind.DETAILS -> listOf(170f to 80f, 170f to 170f, 350f to 80f, 350f to 170f, 260f to 260f)
        WidgetKind.FORECAST -> listOf(350f to 260f, 350f to 350f, 380f to 460f)
        WidgetKind.HOURLY_STRIP, WidgetKind.DAILY_STRIP -> listOf(350f to 80f, 380f to 95f, 320f to 64f, 260f to 80f, 170f to 80f, 350f to 170f)
        WidgetKind.MINI -> listOf(80f to 90f, 70f to 70f, 170f to 80f, 170f to 170f)
    }
}
