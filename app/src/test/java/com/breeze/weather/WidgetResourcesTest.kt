package com.breeze.weather

import androidx.compose.ui.graphics.toArgb
import com.breeze.weather.data.Condition
import com.breeze.weather.ui.gradient
import com.breeze.weather.widget.WidgetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Checks the widget resources that have to agree with code or with each other. */
class WidgetResourcesTest {

    // Unit tests run in the module directory.
    private val res = File("src/main/res")
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    @Test fun skyBackgroundsMatchTheAppsGradients() {
        val names = mapOf(
            Condition.CLEAR to "clear", Condition.PARTLY_CLOUDY to "partly_cloudy", Condition.CLOUDY to "cloudy",
            Condition.FOG to "fog", Condition.RAIN to "rain", Condition.DRIZZLE to "rain", Condition.SNOW to "snow",
            Condition.STORM to "storm",
        )
        for ((condition, name) in names) {
            for (day in listOf(true, false)) {
                val file = File(res, "drawable/widget_bg_sky_${name}_${if (day) "day" else "night"}.xml")
                val gradient = parse(file).getElementsByTagName("gradient").item(0) as Element
                val (top, bottom) = condition.gradient(day).map { it.toArgb() }
                assertEquals("$file start", top, gradient.attr("startColor").removePrefix("#").toLong(16).toInt())
                assertEquals("$file end", bottom, gradient.attr("endColor").removePrefix("#").toLong(16).toInt())
                assertEquals("top to bottom", "270", gradient.attr("angle"))
            }
        }
    }

    @Test fun everyTextStyleHasAShadowTwin() {
        val styles = parse(File(res, "values/widget_styles.xml")).getElementsByTagName("style")
        val byName = (0 until styles.length).map { styles.item(it) as Element }.associateBy { it.getAttribute("name") }
        val textStyles = byName.keys.filter { it.startsWith("WidgetText.") }
        assertTrue(textStyles.isNotEmpty())
        for (name in textStyles) {
            val twin = byName[name.replace("WidgetText.", "WidgetShadow.")]
            assertTrue("$name has no WidgetShadow twin", twin != null)
            assertEquals("${twin!!.getAttribute("name")} should extend $name", name, twin.getAttribute("parent"))
        }
    }

    @Test fun layoutsOnlyUseKnownTextStyles() {
        val styles = parse(File(res, "values/widget_styles.xml")).getElementsByTagName("style")
        val known = (0 until styles.length).map { (styles.item(it) as Element).getAttribute("name") }.toSet()
        File(res, "layout").listFiles { f -> f.name.startsWith("widget_") }!!.forEach { file ->
            Regex("""@style/([\w.]+)""").findAll(file.readText()).forEach { match ->
                val style = match.groupValues[1]
                assertTrue("${file.name} uses $style", style in known && style.startsWith("WidgetText."))
            }
        }
    }

    @Test fun layoutsOnlyUseViewsWidgetsAllow() {
        // RemoteViews refuses to inflate anything else (even a plain View) on Android 8+.
        val allowed = setOf("FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout", "TextView", "ImageView", "ProgressBar", "ViewFlipper", "ListView", "GridView", "StackView", "AdapterViewFlipper", "AnalogClock", "Chronometer", "Button", "ImageButton", "TextClock", "ViewStub", "merge")
        File(res, "layout").listFiles { f -> f.name.startsWith("widget_") }!!.forEach { file ->
            Regex("""<([A-Za-z][\w.]*)[\s>/]""").findAll(file.readText()).map { it.groupValues[1] }.filter { it != "?xml" }.forEach { tag ->
                assertTrue("${file.name} uses <$tag>, which widgets can't show", tag in allowed)
            }
        }
    }

    @Test fun widgetInfoMatchesEachKind() {
        val names = mapOf(
            WidgetKind.NOW to "now", WidgetKind.CONDITIONS to "conditions", WidgetKind.HOURLY to "hourly",
            WidgetKind.DAILY to "daily", WidgetKind.DETAILS to "details", WidgetKind.FORECAST to "forecast",
            WidgetKind.HOURLY_STRIP to "hourly_strip", WidgetKind.DAILY_STRIP to "daily_strip", WidgetKind.MINI to "mini",
        )
        assertEquals(WidgetKind.entries.toSet(), names.keys)
        for ((kind, name) in names) {
            val info = parse(File(res, "xml/widget_info_$name.xml"))
            val cellsW = info.attr("targetCellWidth").toInt()
            val cellsH = info.attr("targetCellHeight").toInt()
            // Before Android 12 launchers size widgets from minWidth/minHeight: (70 × cells − 30)dp.
            assertEquals("$name minWidth", "${70 * cellsW - 30}dp", info.attr("minWidth"))
            assertEquals("$name minHeight", "${70 * cellsH - 30}dp", info.attr("minHeight"))
            assertEquals("com.breeze.weather.widget.WidgetConfigActivity", info.attr("configure"))
            assertTrue("reconfigurable", "reconfigurable" in info.attr("widgetFeatures"))
            val preview = info.attr("previewImage").removePrefix("@drawable/")
            assertTrue("$name preview exists", File(res, "drawable-nodpi/$preview.png").exists())
            // The kind's typical size should be about its default cell span.
            assertTrue(kind.defaultWidthDp >= 70 * cellsW - 30 && kind.defaultHeightDp >= 70 * cellsH - 30 - 40)
        }
    }

    @Test fun manifestDeclaresEveryProvider() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf("Now", "Conditions", "Hourly", "Daily", "Details", "Forecast", "HourlyStrip", "DailyStrip", "Mini").forEach {
            assertTrue("$it provider", ".widget.${it}WidgetProvider" in manifest)
        }
        assertTrue("android.appwidget.action.APPWIDGET_CONFIGURE" in manifest)
    }
}
