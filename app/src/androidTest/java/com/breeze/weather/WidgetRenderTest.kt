package com.breeze.weather

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.breeze.weather.data.Units
import com.breeze.weather.widget.DataState
import com.breeze.weather.widget.Metric
import com.breeze.weather.widget.SampleWeather
import com.breeze.weather.widget.TextScale
import com.breeze.weather.widget.WidgetBitmaps
import com.breeze.weather.widget.WidgetConfig
import com.breeze.weather.widget.WidgetData
import com.breeze.weather.widget.WidgetKind
import com.breeze.weather.widget.WidgetRenderer
import com.breeze.weather.widget.WidgetSection
import com.breeze.weather.widget.WidgetSizes
import com.breeze.weather.widget.WidgetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * Renders every widget kind in every theme and text size at many sizes, inflates the real
 * RemoteViews and lays them out at that exact size, then checks nothing spills out and no
 * temperature is cut off. This is what catches layout estimates that are wrong for real fonts,
 * and RemoteViews calls a given Android version doesn't support.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val renderer = WidgetRenderer(context)
    private val density = context.resources.displayMetrics.density

    private val sizes = listOf(
        70f to 70f, 80f to 90f, 110f to 110f, 145f to 150f, 170f to 80f, 170f to 170f, 200f to 260f,
        260f to 80f, 260f to 170f, 300f to 150f, 350f to 90f, 350f to 170f, 350f to 260f, 350f to 350f, 400f to 480f,
    )

    private val looks = listOf(
        WidgetTheme.SKY to 100, WidgetTheme.SKY to 0, WidgetTheme.SYSTEM to 100, WidgetTheme.DARK to 60, WidgetTheme.LIGHT to 100,
    )

    private fun inflate(kind: WidgetKind, config: WidgetConfig, data: WidgetData, w: Float, h: Float): View {
        val views = renderer.render(7, kind, config, data, w, h, WidgetBitmaps(density), interactive = true)
        val wPx = (w * density).roundToInt()
        val hPx = (h * density).roundToInt()
        val parent = FrameLayout(context)
        val view = views.apply(context, parent)
        parent.addView(view, FrameLayout.LayoutParams(wPx, hPx))
        parent.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, wPx, hPx)
        return view
    }

    private fun descendants(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(descendants(v.getChildAt(i)))
    }

    private fun check(label: String, root: View) {
        val content = root.findViewById<LinearLayout>(R.id.widget_content)
        val limit = content.height - content.paddingBottom
        val tolerance = (2 * density).roundToInt()
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            assertTrue("$label: section $i ends at ${child.bottom}px, past $limit px", child.bottom <= limit + tolerance)
        }
        // Temperatures are never ellipsized ("1…" instead of "10°").
        val temps = setOf(R.id.ht_temp, R.id.hc_temp, R.id.hb_temp, R.id.hour_temp, R.id.day_high, R.id.day_low, R.id.tile_value, R.id.line_value)
        descendants(root).filterIsInstance<TextView>().filter { it.id in temps && it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach { tv ->
            val layout = tv.layout ?: return@forEach
            assertEquals("$label: '${tv.text}' is cut off", 0, layout.getEllipsisCount(0))
            assertTrue("$label: '${tv.text}' is ${layout.getLineWidth(0)}px in ${tv.width}px", layout.getLineWidth(0) <= tv.width - tv.paddingLeft - tv.paddingRight + 1)
        }
    }

    private fun runAll(kinds: List<WidgetKind>, data: WidgetData) {
        val failures = mutableListOf<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (kind in kinds) for ((theme, opacity) in looks) for (scale in TextScale.entries) for ((w, h) in sizes) {
                val config = kind.defaultConfig().copy(theme = theme, opacity = opacity, textScale = scale)
                val label = "$kind $theme/$opacity $scale ${w.toInt()}x${h.toInt()}"
                try {
                    check(label, inflate(kind, config, data, w, h))
                } catch (e: Throwable) {
                    failures += "$label: ${e.message}"
                }
            }
        }
        assertTrue("${failures.size} failures:\n" + failures.take(25).joinToString("\n"), failures.isEmpty())
    }

    private fun ready(units: Units = Units.METRIC) = WidgetData(SampleWeather.place, SampleWeather.create(units), DataState.READY)

    @Test fun everyKindThemeAndSizeFits() = runAll(WidgetKind.entries, ready())

    @Test fun fahrenheitFits() = runAll(listOf(WidgetKind.NOW, WidgetKind.CONDITIONS, WidgetKind.DAILY), ready(Units.IMPERIAL))

    @Test fun everySectionTogetherFits() {
        val failures = mutableListOf<String>()
        val data = ready()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (scale in TextScale.entries) for ((w, h) in sizes) for (step in 1..3) {
                val config = WidgetKind.FORECAST.defaultConfig().copy(
                    sections = WidgetSection.entries.toSet(), metrics = Metric.entries, textScale = scale,
                    hourStep = step, showFeelsLike = true, showUpdated = true, showRangeBars = step != 2,
                )
                val label = "all-sections $scale step$step ${w.toInt()}x${h.toInt()}"
                try {
                    check(label, inflate(WidgetKind.FORECAST, config, data, w, h))
                } catch (e: Throwable) {
                    failures += "$label: ${e.message}"
                }
            }
        }
        assertTrue("${failures.size} failures:\n" + failures.take(25).joinToString("\n"), failures.isEmpty())
    }

    @Test fun messagesRenderInEveryState() {
        for (state in listOf(DataState.LOADING, DataState.NO_LOCATION, DataState.ERROR)) {
            runAll(listOf(WidgetKind.NOW, WidgetKind.FORECAST), WidgetData(SampleWeather.place.takeIf { state != DataState.NO_LOCATION }, null, state))
        }
    }

    @Test fun responsiveRemoteViewsBuildForEverySizeSet() {
        val data = ready()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (kind in WidgetKind.entries) {
                val sizes = listOf(
                    WidgetSizes.default(kind),
                    WidgetSizes(emptyList(), android.util.SizeF(170f, 260f), android.util.SizeF(350f, 120f)),
                    WidgetSizes(listOf(android.util.SizeF(170f, 170f), android.util.SizeF(260f, 120f)), android.util.SizeF(170f, 170f), android.util.SizeF(260f, 120f)),
                )
                for (s in sizes) {
                    val views = renderer.build(1, kind, kind.defaultConfig(), data, s)
                    val parent = FrameLayout(context)
                    assertTrue("$kind $s", views.apply(context, parent) != null)
                }
            }
        }
    }
}
