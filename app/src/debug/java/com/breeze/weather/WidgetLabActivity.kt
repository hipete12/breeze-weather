package com.breeze.weather

import android.os.Bundle
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
 * NO_LOCATION|ERROR), config (a WidgetConfig as JSON, overriding the rest).
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

        val data = WidgetData(SampleWeather.place, SampleWeather.create(units).takeIf { state == DataState.READY }, state)
        val renderer = WidgetRenderer(this)
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

    private fun defaultSizes(kind: WidgetKind): List<Pair<Float, Float>> = when (kind) {
        WidgetKind.NOW -> listOf(80f to 90f, 170f to 80f, 170f to 170f, 260f to 80f, 90f to 190f)
        WidgetKind.CONDITIONS -> listOf(170f to 80f, 170f to 170f, 145f to 150f, 260f to 170f, 350f to 170f)
        WidgetKind.HOURLY -> listOf(260f to 80f, 350f to 90f, 350f to 170f, 300f to 150f, 350f to 230f)
        WidgetKind.DAILY -> listOf(350f to 90f, 350f to 170f, 350f to 260f, 260f to 260f, 350f to 350f)
        WidgetKind.DETAILS -> listOf(170f to 80f, 170f to 170f, 350f to 80f, 350f to 170f, 260f to 260f)
        WidgetKind.FORECAST -> listOf(350f to 260f, 350f to 350f, 380f to 460f)
    }
}
