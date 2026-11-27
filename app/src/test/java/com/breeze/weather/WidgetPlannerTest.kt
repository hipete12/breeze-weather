package com.breeze.weather

import com.breeze.weather.widget.DayLayout
import com.breeze.weather.widget.HeaderStyle
import com.breeze.weather.widget.Metric
import com.breeze.weather.widget.StripLayout
import com.breeze.weather.widget.StripText
import com.breeze.weather.widget.TextScale
import com.breeze.weather.widget.TextMeasure
import com.breeze.weather.widget.WidgetConfig
import com.breeze.weather.widget.WidgetKind
import com.breeze.weather.widget.WidgetPlan
import com.breeze.weather.widget.WidgetPlanner
import com.breeze.weather.widget.WidgetSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sizes here are typical launcher cell spans: roughly 70–96 dp per column and 80–120 dp per row
 * (Pixel and One UI grids), so "4×2" is about 350 × 170 dp.
 */
class WidgetPlannerTest {

    private fun plan(kind: WidgetKind, w: Float, h: Float, config: WidgetConfig = kind.defaultConfig(), temp: String = "21°", days: Int = 10) =
        WidgetPlanner.plan(kind, config, w, h, temp, availableHours = 24, availableDays = days)

    private fun WidgetPlan.innerW(w: Float) = w - 2 * paddingH

    // ---------- Now ----------

    @Test fun nowIsIconOverTemperatureAtOneByOne() {
        val p = plan(WidgetKind.NOW, 80f, 90f)
        assertEquals(HeaderStyle.TINY, p.header)
        assertTrue(p.iconDp >= 14f)
        assertTrue("temperature fits the width", p.tempTextDp * WidgetPlanner.tempWidthEm("21°") <= p.innerW(80f))
    }

    @Test fun nowIsOneRowAtTwoByOne() {
        assertEquals(HeaderStyle.COMPACT, plan(WidgetKind.NOW, 170f, 80f).header)
    }

    @Test fun nowIsLargeAtTwoByTwo() {
        val p = plan(WidgetKind.NOW, 170f, 170f)
        assertEquals(HeaderStyle.BIG, p.header)
        assertEquals("Now never shows readings beside the temperature", 0, p.sideMetrics)
    }

    @Test fun tallNarrowNowShowsThePlaceUnderTheTemperature() {
        val p = plan(WidgetKind.NOW, 90f, 190f)
        assertEquals(HeaderStyle.TINY, p.header)
        assertTrue(p.tinyPlace)
    }

    // ---------- Current conditions ----------

    @Test fun wideConditionsShowsReadingsBesideTheTemperature() {
        val p = plan(WidgetKind.CONDITIONS, 350f, 170f)
        assertEquals(HeaderStyle.BIG, p.header)
        assertEquals(3, p.sideMetrics)
        assertEquals(0, plan(WidgetKind.CONDITIONS, 170f, 170f).sideMetrics)
    }

    @Test fun bigTemperatureAndIconAlwaysFitSideBySide() {
        for (w in listOf(130f, 145f, 170f, 200f, 260f, 350f)) {
            for (temp in listOf("1°", "21°", "-12°", "104°")) {
                val p = plan(WidgetKind.CONDITIONS, w, 170f, temp = temp)
                if (p.header != HeaderStyle.BIG) continue
                val used = p.tempTextDp * WidgetPlanner.tempWidthEm(temp) + 8f + p.iconDp + if (p.sideMetrics > 0) 108f else 0f
                assertTrue("$temp at ${w}dp needs ${used}dp of ${p.innerW(w)}dp", used <= p.innerW(w) + 0.5f)
            }
        }
    }

    @Test fun realFontMeasurementShrinksTheTemperature() {
        // A wider font than the estimate must give a smaller temperature, never an overflow.
        val wide = TextMeasure { text, size -> WidgetPlanner.tempWidthEm(text) * size * 1.3f }
        val p = WidgetPlanner.plan(WidgetKind.CONDITIONS, WidgetKind.CONDITIONS.defaultConfig(), 150f, 170f, "10°", measure = wide)
        val estimate = plan(WidgetKind.CONDITIONS, 150f, 170f, temp = "10°")
        assertTrue(p.tempTextDp < estimate.tempTextDp)
        assertTrue(p.tempTextDp * WidgetPlanner.tempWidthEm("10°") * 1.3f + 8f + p.iconDp <= p.innerW(150f) + 0.5f)
    }

    // ---------- Hourly ----------

    @Test fun shortHourlyWidgetKeepsTheStripAndDropsTheHeader() {
        val p = plan(WidgetKind.HOURLY, 350f, 90f)
        assertEquals(listOf(WidgetSection.HOURLY), p.sections)
        assertEquals(HeaderStyle.NONE, p.header)
        assertEquals(7, p.hours)
        assertFalse(p.hourlyCompact)
    }

    @Test fun veryShortHourlyWidgetUsesTheCompactStrip() {
        val p = plan(WidgetKind.HOURLY, 260f, 70f)
        assertEquals(listOf(WidgetSection.HOURLY), p.sections)
        assertTrue(p.hourlyCompact)
        assertFalse(p.hourlyRain)
    }

    @Test fun hourlyFourByTwoHasHeaderStripAndRain() {
        val p = plan(WidgetKind.HOURLY, 350f, 170f)
        assertEquals(listOf(WidgetSection.HEADER, WidgetSection.HOURLY), p.sections)
        assertEquals(HeaderStyle.COMPACT, p.header)
        assertTrue(p.hourlyRain)
        assertFalse(p.hourlyCompact)
    }

    @Test fun theRainLineGoesBeforeTheHeader() {
        // 300 × 150: header + strip fit only without the rain line.
        val p = plan(WidgetKind.HOURLY, 300f, 150f)
        assertEquals(listOf(WidgetSection.HEADER, WidgetSection.HOURLY), p.sections)
        assertFalse(p.hourlyRain)
    }

    @Test fun hourlyColumnsFollowWidthAndAreCapped() {
        assertEquals(5, plan(WidgetKind.HOURLY, 250f, 170f).hours)
        assertEquals(12, plan(WidgetKind.HOURLY, 800f, 170f).hours)
        val few = WidgetPlanner.plan(WidgetKind.HOURLY, WidgetKind.HOURLY.defaultConfig(), 800f, 170f, availableHours = 4)
        assertEquals(4, few.hours)
    }

    @Test fun noRainLineWhenTheUserTurnedRainOff() {
        val p = plan(WidgetKind.HOURLY, 350f, 230f, WidgetKind.HOURLY.defaultConfig().copy(showRainChance = false))
        assertFalse(p.hourlyRain)
    }

    // ---------- Daily ----------

    @Test fun dailyRowsFillTheHeight() {
        val p = plan(WidgetKind.DAILY, 350f, 260f)
        assertEquals(listOf(WidgetSection.HEADER, WidgetSection.DAILY), p.sections)
        assertEquals(DayLayout.ROWS, p.dayLayout)
        assertEquals(7, p.days)
    }

    @Test fun dailyNeverShowsMoreDaysThanTheForecastHas() {
        assertEquals(3, plan(WidgetKind.DAILY, 350f, 600f, days = 3).days)
    }

    @Test fun shortDailyWidgetShowsTwoRowsWithoutHeader() {
        val p = plan(WidgetKind.DAILY, 350f, 90f)
        assertEquals(listOf(WidgetSection.DAILY), p.sections)
        assertEquals(2, p.days)
    }

    @Test fun dailyUsesColumnsWhenRowsDontFitButColumnsDo() {
        val config = WidgetKind.DAILY.defaultConfig().copy(sections = setOf(WidgetSection.HEADER, WidgetSection.HOURLY, WidgetSection.DAILY))
        // Header + hourly leave too little height for two rows, but enough for a row of columns.
        val p = plan(WidgetKind.DAILY, 350f, 250f, config)
        assertTrue(WidgetSection.DAILY in p.sections)
        if (p.dayLayout == DayLayout.COLUMNS) assertEquals(7, p.days) else assertTrue(p.days >= 2)
    }

    // ---------- Details ----------

    @Test fun narrowDetailsAreAList() {
        val p = plan(WidgetKind.DETAILS, 170f, 170f)
        assertTrue(p.detailList)
        assertEquals(4, p.detailRows)
        assertEquals(1, p.detailColumns)
    }

    @Test fun wideDetailsAreABalancedGrid() {
        val p = plan(WidgetKind.DETAILS, 350f, 170f)
        assertFalse(p.detailList)
        // Four readings in a 3-wide space become 2 × 2, not 3 + 1.
        assertEquals(2, p.detailColumns)
        assertEquals(2, p.detailRows)
    }

    @Test fun oneRowOfDetailsInAShortWideWidget() {
        val p = plan(WidgetKind.DETAILS, 350f, 80f)
        assertEquals(1, p.detailRows)
        assertEquals(3, p.detailColumns)
    }

    @Test fun detailsSectionIsSkippedWithoutReadings() {
        val config = WidgetKind.FORECAST.defaultConfig().copy(sections = WidgetSection.entries.toSet(), metrics = emptyList())
        assertFalse(WidgetSection.DETAILS in plan(WidgetKind.FORECAST, 350f, 450f, config).sections)
    }

    // ---------- Everything together ----------

    @Test fun completeForecastShowsAllThreeAtFourByFour() {
        val p = plan(WidgetKind.FORECAST, 350f, 350f)
        assertEquals(listOf(WidgetSection.HEADER, WidgetSection.HOURLY, WidgetSection.DAILY), p.sections)
        assertTrue(p.days >= 5)
    }

    @Test fun sectionsTheUserTurnedOffNeverAppear() {
        val config = WidgetKind.FORECAST.defaultConfig().copy(sections = setOf(WidgetSection.DAILY))
        val p = plan(WidgetKind.FORECAST, 350f, 350f, config)
        assertEquals(listOf(WidgetSection.DAILY), p.sections)
        assertEquals(HeaderStyle.NONE, p.header)
    }

    @Test fun largeTextFitsLess() {
        val large = WidgetKind.FORECAST.defaultConfig().copy(textScale = TextScale.LARGE)
        assertTrue(plan(WidgetKind.FORECAST, 350f, 350f, large).days < plan(WidgetKind.FORECAST, 350f, 350f).days)
        assertTrue(plan(WidgetKind.HOURLY, 350f, 170f, large).hours < plan(WidgetKind.HOURLY, 350f, 170f).hours)
    }

    // ---------- Strips ----------

    private val hourLabels = StripText(cellLabels = listOf("10 AM", "11 AM", "12 PM", "1 PM", "2 PM", "3 PM", "4 PM", "5 PM"), cellTemps = listOf("21°"))
    private val dayLabels = StripText(cellLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri"), cellTemps = listOf("21° 12°", "9° -2°"))

    private fun strip(kind: WidgetKind, w: Float, h: Float, config: WidgetConfig = kind.defaultConfig(), text: StripText = if (kind == WidgetKind.DAILY_STRIP) dayLabels else hourLabels, temp: String = "21°") =
        WidgetPlanner.plan(kind, config, w, h, temp, availableHours = 24, availableDays = 9, stripText = text)

    @Test fun hourlyStripShowsFiveHoursAtFourByOne() {
        for ((w, h) in listOf(350f to 80f, 340f to 72f, 380f to 95f)) {
            val p = strip(WidgetKind.HOURLY_STRIP, w, h)
            val sp = p.strip!!
            assertEquals("${w}x$h", StripLayout.ROW, sp.layout)
            assertEquals("${w}x$h: $sp", 5, sp.cells)
            assertTrue(sp.highLowDp > 0f)
            assertTrue(p.sections.isEmpty())
        }
    }

    @Test fun stripCountIsAMaximum() {
        val three = WidgetKind.HOURLY_STRIP.defaultConfig().copy(stripCount = 3)
        assertEquals(3, strip(WidgetKind.HOURLY_STRIP, 350f, 80f, three).strip!!.cells)
        // Eight asked for, but only so many fit in 4×1; a 5×1 fits more.
        val eight = WidgetKind.HOURLY_STRIP.defaultConfig().copy(stripCount = 8)
        val fourWide = strip(WidgetKind.HOURLY_STRIP, 350f, 80f, eight).strip!!.cells
        val fiveWide = strip(WidgetKind.HOURLY_STRIP, 450f, 80f, eight).strip!!.cells
        assertTrue("$fourWide then $fiveWide", fourWide in 5..7 && fiveWide > fourWide)
        // And never more than the forecast has.
        assertEquals(2, WidgetPlanner.plan(WidgetKind.HOURLY_STRIP, eight, 450f, 80f, availableHours = 2, stripText = hourLabels).strip!!.cells)
    }

    @Test fun dailyStripFitsSeveralDays() {
        val sp = strip(WidgetKind.DAILY_STRIP, 350f, 80f).strip!!
        assertTrue("$sp", sp.cells >= 4)
        assertTrue(sp.cellTempDp < strip(WidgetKind.HOURLY_STRIP, 350f, 80f).strip!!.cellTempDp)
    }

    @Test fun narrowStripKeepsJustTheTemperature() {
        val sp = strip(WidgetKind.HOURLY_STRIP, 170f, 80f).strip!!
        assertEquals(0, sp.cells)
        assertEquals(StripLayout.ROW, sp.layout)
        assertTrue(sp.highLowDp > 0f)
        // Never a lone column.
        for (w in 150..450 step 10) assertTrue(strip(WidgetKind.HOURLY_STRIP, w.toFloat(), 80f).strip!!.cells != 1)
    }

    @Test fun miniStacksInOneCellAndLinesUpInTwo() {
        assertEquals(StripLayout.STACK, strip(WidgetKind.MINI, 80f, 90f).strip!!.layout)
        assertEquals(StripLayout.STACK, strip(WidgetKind.MINI, 70f, 70f).strip!!.layout)
        val wide = strip(WidgetKind.MINI, 170f, 80f).strip!!
        assertEquals(StripLayout.ROW, wide.layout)
        assertEquals(0, wide.cells)
        assertEquals(StripLayout.STACK, strip(WidgetKind.MINI, 170f, 170f).strip!!.layout)
    }

    @Test fun tallerStripsGetBiggerTypeAndRain() {
        val short = strip(WidgetKind.HOURLY_STRIP, 350f, 80f).strip!!
        val tall = strip(WidgetKind.HOURLY_STRIP, 350f, 170f).strip!!
        assertTrue(tall.tempDp >= short.tempDp && tall.iconDp > short.iconDp)
        assertTrue(tall.cellRain)
        assertFalse(short.cellRain)
        // But not at the cost of columns.
        assertTrue("$short vs $tall", tall.cells >= short.cells)
        assertTrue(strip(WidgetKind.DAILY_STRIP, 350f, 170f).strip!!.cells >= strip(WidgetKind.DAILY_STRIP, 350f, 80f).strip!!.cells)
        val noRain = WidgetKind.HOURLY_STRIP.defaultConfig().copy(showRainChance = false)
        assertFalse(strip(WidgetKind.HOURLY_STRIP, 350f, 170f, noRain).strip!!.cellRain)
    }

    @Test fun stripPlaceIsOptionalAndKeepsTheColumns() {
        // Off by default, and the Mini never shows it.
        assertEquals(0f, strip(WidgetKind.HOURLY_STRIP, 350f, 80f).strip!!.placeDp, 0f)
        val mini = WidgetKind.MINI.defaultConfig().copy(stripPlace = true)
        assertEquals(0f, strip(WidgetKind.MINI, 80f, 90f, mini).strip!!.placeDp, 0f)
        for (kind in listOf(WidgetKind.HOURLY_STRIP, WidgetKind.DAILY_STRIP)) {
            val off = strip(kind, 350f, 80f).strip!!
            val on = strip(kind, 350f, 80f, kind.defaultConfig().copy(stripPlace = true)).strip!!
            assertTrue("$on", on.placeDp >= 11f)
            // The temperature row gives up the height; the columns and high and low stay.
            assertEquals(off.cells, on.cells)
            assertTrue(on.highLowDp > 0f)
            assertTrue(on.iconDp <= off.iconDp)
        }
        // Still there in a narrow strip without columns.
        assertTrue(strip(WidgetKind.HOURLY_STRIP, 170f, 80f, WidgetKind.HOURLY_STRIP.defaultConfig().copy(stripPlace = true)).strip!!.placeDp > 0f)
    }

    @Test fun stripHighAndLowCanBeHidden() {
        val off = WidgetKind.HOURLY_STRIP.defaultConfig().copy(showHighLow = false)
        assertEquals(0f, strip(WidgetKind.HOURLY_STRIP, 350f, 80f, off).strip!!.highLowDp, 0f)
        assertEquals(0f, strip(WidgetKind.MINI, 80f, 90f, WidgetKind.MINI.defaultConfig().copy(showHighLow = false)).strip!!.highLowDp, 0f)
    }

    @Test fun stripsFitTheirSpace() {
        val dims = com.breeze.weather.widget.WidgetDimens
        val line = 1.17f
        val measure = TextMeasure.Estimate
        for (kind in listOf(WidgetKind.HOURLY_STRIP, WidgetKind.DAILY_STRIP, WidgetKind.MINI)) {
            for (scale in TextScale.entries) for (count in listOf(3, 5, 8)) for (temp in listOf("8°", "-12°", "104°")) for (place in listOf(false, true)) {
                for (w in listOf(60f, 80f, 110f, 170f, 260f, 320f, 350f, 420f)) for (h in listOf(50f, 60f, 70f, 80f, 95f, 120f, 170f, 260f)) {
                    val config = kind.defaultConfig().copy(textScale = scale, stripCount = count, stripPlace = place)
                    val p = strip(kind, w, h, config, temp = temp)
                    val sp = p.strip!!
                    val innerW = w - 2 * p.paddingH
                    val innerH = h - 2 * p.paddingV
                    val label = "$kind $scale $count $temp place=$place ${w}x$h: $sp"
                    val text = if (kind == WidgetKind.DAILY_STRIP) dayLabels else hourLabels
                    if (sp.layout == StripLayout.STACK) {
                        val height = sp.iconDp + sp.tempDp * line + (if (sp.highLowDp > 0f) sp.highLowDp * line + 2f else 0f)
                        assertTrue("$label is $height tall", height <= innerH + 2f)
                        assertTrue(label, measure.widthDp(temp, sp.tempDp) <= innerW + 0.5f)
                        continue
                    }
                    val highLow = if (sp.highLowDp > 0f) dims.STRIP_HIGH_LOW_GAP + measure.widthDp("21°", sp.highLowDp) else 0f
                    val row = sp.iconDp + dims.STRIP_ICON_GAP + measure.widthDp(temp, sp.tempDp) + highLow
                    val cellW = maxOf(text.cellLabels.maxOf { measure.widthDp(it, sp.cellLabelDp) }, text.cellTemps.maxOf { measure.widthDp(it, sp.cellTempDp) })
                    val cells = if (sp.cells > 0) dims.STRIP_CELLS_GAP + sp.cells * cellW else 0f
                    assertTrue("$label is ${row + cells} wide", row + cells <= innerW + 0.5f || sp.tempDp <= 14f)
                    val placeH = if (sp.placeDp > 0f) sp.placeDp * line + dims.STRIP_PLACE_GAP else 0f
                    assertTrue("$label: row too tall", maxOf(sp.iconDp, sp.tempDp * line, 2 * sp.highLowDp * line + 2f) + placeH <= innerH + 0.5f)
                    if (sp.cells > 0) {
                        val column = (sp.cellLabelDp + sp.cellTempDp) * line + sp.cellIconDp + 2 * dims.STRIP_CELL_GAP +
                            if (sp.cellRain) dims.TEXT_TINY * 0.9f * scale.factor * line + dims.STRIP_CELL_GAP else 0f
                        assertTrue("$label: column is $column of $innerH", column <= innerH + 1f || innerH < 40f)
                    }
                }
            }
        }
    }

    @Test fun planContentNeverExceedsTheHeight() {
        val dims = com.breeze.weather.widget.WidgetDimens
        for (kind in WidgetKind.entries) {
            for (scale in TextScale.entries) {
                for (w in listOf(80f, 120f, 170f, 260f, 350f, 420f)) {
                    for (h in listOf(60f, 80f, 110f, 170f, 260f, 350f, 460f)) {
                        val config = kind.defaultConfig().copy(textScale = scale, sections = WidgetSection.entries.toSet(), metrics = Metric.entries)
                        val p = plan(kind, w, h, if (kind == WidgetKind.NOW) kind.defaultConfig().copy(textScale = scale) else config)
                        val s = scale.factor
                        var used = 0f
                        p.sections.forEachIndexed { i, section ->
                            if (i > 0) used += dims.GAP * s
                            used += when (section) {
                                WidgetSection.HEADER -> if (p.header == HeaderStyle.COMPACT) dims.COMPACT_H * s else 0f
                                WidgetSection.HOURLY -> (if (p.hourlyCompact) dims.HOURLY_COMPACT_H else dims.HOURLY_H) * s + if (p.hourlyRain) dims.RAIN_LINE * s else 0f
                                WidgetSection.DAILY -> if (p.dayLayout == DayLayout.ROWS) p.days * dims.DAY_ROW_H * s else (dims.DAY_COLS_H + dims.RAIN_LINE) * s
                                WidgetSection.DETAILS -> if (p.detailList) p.detailRows * dims.LINE_H * s else p.detailRows * dims.TILE_H * s + (p.detailRows - 1) * dims.TILE_GAP * s
                            }
                        }
                        val inner = h - 2 * p.paddingV
                        assertTrue("$kind $scale ${w}x$h uses $used of $inner: $p", used <= inner + 0.5f || p.sections.size == 1)
                    }
                }
            }
        }
    }
}
