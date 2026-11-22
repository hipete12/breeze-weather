package com.breeze.weather

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.ImageView
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
import com.breeze.weather.widget.TempColors
import com.breeze.weather.widget.WidgetContent
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
        // Short one-row widgets, as some launchers size a 4×1.
        300f to 56f, 350f to 64f, 380f to 95f,
        // The narrowest some kinds can be resized to.
        180f to 110f, 180f to 200f, 250f to 180f,
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

    /** [v]'s bounds in [root]'s coordinates, or null if it or a parent is hidden. */
    private fun visibleBounds(root: View, v: View): Rect? {
        var x = 0
        var y = 0
        var cur = v
        while (cur !== root) {
            if (cur.visibility != View.VISIBLE) return null
            x += cur.left
            y += cur.top
            cur = cur.parent as View
        }
        return Rect(x, y, x + v.width, y + v.height)
    }

    private fun check(label: String, root: View) {
        val content = root.findViewById<LinearLayout>(R.id.widget_content)
        // Every piece of text and every icon is inside the padded content area: nothing clipped.
        val inner = Rect(content.left + content.paddingLeft, content.top + content.paddingTop, content.right - content.paddingRight, content.bottom - content.paddingBottom)
        val slack = (2 * density).roundToInt()
        inner.inset(-slack, -slack)
        descendants(root).filter { (it is TextView || it is ImageView) && it.id != R.id.widget_bg }.forEach { v ->
            val bounds = visibleBounds(root, v) ?: return@forEach
            if (v.width == 0 || v.height == 0 || (v is TextView && v.text.isEmpty())) return@forEach
            val name = runCatching { context.resources.getResourceEntryName(v.id) }.getOrDefault("view")
            assertTrue("$label: $name at $bounds is outside $inner", inner.contains(bounds))
        }
        val limit = content.height - content.paddingBottom
        val tolerance = (2 * density).roundToInt()
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            assertTrue("$label: section $i ends at ${child.bottom}px, past $limit px", child.bottom <= limit + tolerance)
        }
        // Temperatures are never ellipsized ("1…" instead of "10°").
        val temps = setOf(
            R.id.ht_temp, R.id.hc_temp, R.id.hb_temp, R.id.hour_temp, R.id.day_high, R.id.day_low, R.id.tile_value, R.id.line_value,
            R.id.st_temp, R.id.st_high, R.id.st_low, R.id.hour_time, R.id.day_name,
        )
        descendants(root).filterIsInstance<TextView>().filter { it.id in temps && it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach { tv ->
            val layout = tv.layout ?: return@forEach
            assertEquals("$label: '${tv.text}' is cut off", 0, layout.getEllipsisCount(0))
            assertTrue("$label: '${tv.text}' is ${layout.getLineWidth(0)}px in ${tv.width}px", layout.getLineWidth(0) <= tv.width - tv.paddingLeft - tv.paddingRight + 1)
        }
    }

    /** The smallest size (dp) the launcher lets each kind be resized to, from its widget info. */
    private val minSizes: Map<WidgetKind, Pair<Float, Float>> by lazy {
        val providers = android.appwidget.AppWidgetManager.getInstance(context).installedProviders.filter { it.provider.packageName == context.packageName }
        WidgetKind.entries.associateWith { kind ->
            val info = providers.first { it.provider.className == com.breeze.weather.widget.WidgetProviders.providerClass(kind).name }
            info.minResizeWidth / density to info.minResizeHeight / density
        }
    }

    /** Sizes a launcher can actually give [kind] (with a dp of rounding slack). */
    private fun sizesFor(kind: WidgetKind) = sizes.filter { (w, h) ->
        val (minW, minH) = minSizes.getValue(kind)
        w >= minW - 1f && h >= minH - 1f
    }

    private fun runAll(kinds: List<WidgetKind>, data: WidgetData, tweak: (WidgetConfig) -> WidgetConfig = { it }) {
        val failures = mutableListOf<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (kind in kinds) for ((theme, opacity) in looks) for (scale in TextScale.entries) for ((w, h) in sizesFor(kind)) {
                val config = tweak(kind.defaultConfig().copy(theme = theme, opacity = opacity, textScale = scale))
                val label = "$kind $theme/$opacity $scale ${w.toInt()}x${h.toInt()}"
                try {
                    check(label, inflate(kind, config, data, w, h))
                } catch (e: Throwable) {
                    failures += "$label: ${e.message}"
                }
            }
        }
        val byKind = failures.groupingBy { it.substringBefore(' ') }.eachCount()
        // A few of each kind, so one kind's failures don't hide another's.
        val sample = failures.groupBy { it.substringBefore(' ') }.values.flatMap { it.take(8) }
        assertTrue("${failures.size} failures $byKind:\n" + sample.joinToString("\n"), failures.isEmpty())
    }

    private fun ready(units: Units = Units.METRIC) = WidgetData(SampleWeather.place, SampleWeather.create(units), DataState.READY)

    @Test fun everyKindThemeAndSizeFits() = runAll(WidgetKind.entries, ready())

    @Test fun fahrenheitFits() = runAll(
        listOf(WidgetKind.NOW, WidgetKind.CONDITIONS, WidgetKind.DAILY, WidgetKind.HOURLY_STRIP, WidgetKind.DAILY_STRIP, WidgetKind.MINI),
        ready(Units.IMPERIAL),
    )

    @Test fun stripsWithThePlaceFit() = runAll(
        listOf(WidgetKind.HOURLY_STRIP, WidgetKind.DAILY_STRIP), ready(), tweak = { it.copy(stripPlace = true) },
    )

    @Test fun fourByOneStripShowsThePlaceUnderTheTemperature() {
        val data = ready()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (kind in listOf(WidgetKind.HOURLY_STRIP, WidgetKind.DAILY_STRIP)) for ((w, h) in listOf(340f to 72f, 350f to 80f, 380f to 95f)) {
                val label = "$kind ${w}x$h"
                val off = inflate(kind, kind.defaultConfig(), data, w, h)
                val root = inflate(kind, kind.defaultConfig().copy(stripPlace = true), data, w, h)
                val place = visibleTexts(root, R.id.st_place).single()
                assertEquals(label, SampleWeather.place.name, place.text.toString())
                assertEquals("$label: place is cut off", 0, place.layout.getEllipsisCount(0))
                val temp = visibleBounds(root, root.findViewById(R.id.st_temp))!!
                val placeBounds = visibleBounds(root, place)!!
                assertTrue(label, placeBounds.top >= temp.bottom - 1)
                // Same columns as without the place.
                val cellId = if (kind == WidgetKind.HOURLY_STRIP) R.id.hour_time else R.id.day_name
                assertEquals(label, visibleTexts(off, cellId).size, visibleTexts(root, cellId).size)
            }
        }
    }

    private fun visibleTexts(root: View, id: Int) =
        descendants(root).filterIsInstance<TextView>().filter { it.id == id && visibleBounds(root, it) != null }.toList()

    @Test fun fourByOneHourlyStripShowsFiveHours() {
        val data = ready()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((w, h) in listOf(340f to 72f, 350f to 80f, 380f to 95f)) for (theme in WidgetTheme.entries) {
                val root = inflate(WidgetKind.HOURLY_STRIP, WidgetKind.HOURLY_STRIP.defaultConfig().copy(theme = theme), data, w, h)
                val hours = visibleTexts(root, R.id.hour_time)
                assertEquals("$theme ${w}x$h: ${hours.map { it.text }}", 5, hours.size)
                // The hours after now, not "Now" itself (the temperature beside them is now).
                assertTrue(hours.none { it.text == "Now" })
                assertEquals(1, visibleTexts(root, R.id.st_high).size)
                assertEquals(1, visibleTexts(root, R.id.st_low).size)
                // No place or condition text in a strip.
                val shown = descendants(root).filterIsInstance<TextView>().filter { visibleBounds(root, it) != null }.map { it.text.toString() }
                assertTrue(shown.none { it == SampleWeather.place.name || it == data.weather!!.current.description })
            }
        }
    }

    @Test fun stripHighAndLowAreColouredByTemperature() {
        val data = ready()
        val today = WidgetContent.today(data.weather!!)!!
        val units = data.weather.units
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val dark = inflate(WidgetKind.HOURLY_STRIP, WidgetKind.HOURLY_STRIP.defaultConfig().copy(theme = WidgetTheme.DARK), data, 350f, 80f)
            assertEquals(TempColors.onDark(today.high, units), dark.findViewById<TextView>(R.id.st_high).currentTextColor)
            assertEquals(TempColors.onDark(today.low, units), dark.findViewById<TextView>(R.id.st_low).currentTextColor)
            val light = inflate(WidgetKind.DAILY_STRIP, WidgetKind.DAILY_STRIP.defaultConfig().copy(theme = WidgetTheme.LIGHT), data, 350f, 80f)
            assertEquals(TempColors.onLight(today.high, units), light.findViewById<TextView>(R.id.st_high).currentTextColor)
            // Each day's high and low too.
            val firstDay = WidgetContent.dayCells(data.weather, 1, fromTomorrow = true).first().day
            assertEquals(TempColors.onLight(firstDay.high, units), light.findViewById<TextView>(R.id.day_high).currentTextColor)
            // Turned off: plain text colours.
            val plain = inflate(WidgetKind.HOURLY_STRIP, WidgetKind.HOURLY_STRIP.defaultConfig().copy(theme = WidgetTheme.DARK, colorTemps = false), data, 350f, 80f)
            assertEquals(0xFFFFFFFF.toInt(), plain.findViewById<TextView>(R.id.st_high).currentTextColor)
        }
    }

    @Test fun miniFitsOneCell() {
        val data = ready()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((w, h) in listOf(57f to 57f, 70f to 70f, 80f to 90f, 96f to 110f)) for (scale in TextScale.entries) {
                val root = inflate(WidgetKind.MINI, WidgetKind.MINI.defaultConfig().copy(textScale = scale), data, w, h)
                check("MINI $scale ${w}x$h", root)
                if (w >= 70f) assertEquals("$scale ${w}x$h shows high and low", 1, visibleTexts(root, R.id.st_low).size)
            }
        }
    }

    @Test fun everySectionTogetherFits() {
        val failures = mutableListOf<String>()
        val data = ready()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (scale in TextScale.entries) for ((w, h) in sizesFor(WidgetKind.FORECAST)) for (step in 1..3) {
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
