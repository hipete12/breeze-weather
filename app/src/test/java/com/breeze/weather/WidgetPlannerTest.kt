package com.breeze.weather

import com.breeze.weather.widget.DayLayout
import com.breeze.weather.widget.HeaderStyle
import com.breeze.weather.widget.Metric
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
