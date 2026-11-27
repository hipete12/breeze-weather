package com.breeze.weather

import com.breeze.weather.data.Units
import com.breeze.weather.widget.Metric
import com.breeze.weather.widget.SampleWeather
import com.breeze.weather.widget.WidgetContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WidgetContentTest {

    private val now = LocalDateTime.of(2026, 11, 20, 14, 25)
    private val weather = SampleWeather.create(Units.METRIC, utcOffsetSeconds = 0, now = now)

    @Test fun firstHourIsNowWithTheCurrentReading() {
        val cells = WidgetContent.hourCells(weather, step = 1, count = 6, use24h = true)
        assertEquals(6, cells.size)
        assertEquals("Now", cells[0].label)
        assertEquals(weather.current.temperature, cells[0].temperature, 0.0)
        assertEquals(weather.current.weatherCode, cells[0].weatherCode)
        assertEquals(listOf("15:00", "16:00", "17:00", "18:00", "19:00"), cells.drop(1).map { it.label })
    }

    @Test fun hourStepSkipsHours() {
        val cells = WidgetContent.hourCells(weather, step = 3, count = 4, use24h = true)
        assertEquals(listOf("Now", "17:00", "20:00", "23:00"), cells.map { it.label })
    }

    @Test fun hoursStopWhereTheForecastEnds() {
        val available = WidgetContent.availableHours(weather, step = 3)
        val cells = WidgetContent.hourCells(weather, step = 3, count = 500, use24h = true)
        assertEquals(available, cells.size)
        // 10 days of hours from 14:00 on day one: 225 later hours, every third.
        assertEquals(1 + 225 / 3, available)
    }

    @Test fun twelveHourLabels() {
        val cells = WidgetContent.hourCells(weather, step = 1, count = 2, use24h = false)
        assertTrue(cells[1].label, cells[1].label.startsWith("3"))
    }

    @Test fun daysStartWithToday() {
        val days = WidgetContent.dayCells(weather, 3, now)
        assertEquals("Today", days[0].label)
        assertEquals(LocalDate.of(2026, 11, 21), days[1].day.date)
        assertEquals(listOf(0, 1, 2), days.map { it.index })
    }

    @Test fun stripHoursStartAfterNow() {
        val cells = WidgetContent.hourCells(weather, step = 1, count = 5, use24h = true, includeNow = false)
        assertEquals(listOf("15:00", "16:00", "17:00", "18:00", "19:00"), cells.map { it.label })
        assertEquals(listOf("16:00", "18:00"), WidgetContent.hourCells(weather, 2, 2, true, includeNow = false).map { it.label })
        assertEquals(WidgetContent.availableHours(weather, 3) - 1, WidgetContent.availableHours(weather, 3, includeNow = false))
    }

    @Test fun stripDaysStartTomorrow() {
        val days = WidgetContent.dayCells(weather, 4, now, fromTomorrow = true)
        assertEquals(LocalDate.of(2026, 11, 21), days[0].day.date)
        // Indexes still point into the whole forecast, for the app's day detail.
        assertEquals(listOf(1, 2, 3, 4), days.map { it.index })
        assertEquals(weather.daily.size - 1, WidgetContent.daysAfterToday(weather, now))
        assertEquals(LocalDate.of(2026, 11, 20), WidgetContent.today(weather, now)!!.date)
    }

    @Test fun stripDaysSkipPastDaysInAnOldForecast() {
        // A forecast downloaded two days ago: "tomorrow" is two entries in.
        val later = now.plusDays(2)
        val days = WidgetContent.dayCells(weather, 2, later, fromTomorrow = true)
        assertEquals(listOf(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 24)), days.map { it.day.date })
        assertEquals(LocalDate.of(2026, 11, 22), WidgetContent.today(weather, later)!!.date)
    }

    @Test fun rangeCoversLowestLowAndHighestHigh() {
        val days = WidgetContent.dayCells(weather, 5, now)
        val (lo, hi) = WidgetContent.range(days)!!
        assertEquals(days.minOf { it.day.low }, lo, 0.0)
        assertEquals(days.maxOf { it.day.high }, hi, 0.0)
        assertNull(WidgetContent.range(emptyList()))
    }

    @Test fun temperaturesRound() {
        assertEquals("21°", WidgetContent.temp(20.5))
        assertEquals("-3°", WidgetContent.temp(-2.6))
        assertEquals("0°", WidgetContent.temp(-0.4))
    }

    @Test fun highLowAndFeelsLike() {
        val today = weather.daily.first()
        assertEquals("H ${WidgetContent.temp(today.high)}  L ${WidgetContent.temp(today.low)}", WidgetContent.highLow(weather))
        assertTrue(WidgetContent.feelsLike(weather).startsWith("Feels like "))
    }

    @Test fun windHasSpeedAndDirectionWhenWide() {
        val m = WidgetContent.metric(Metric.WIND, weather, use24h = true, now = now)
        assertEquals("14 km/h", m.value)
        assertEquals("14 km/h W", m.wideValue)
        val imperial = SampleWeather.create(Units.IMPERIAL, utcOffsetSeconds = 0, now = now)
        assertEquals("9 mph", WidgetContent.metric(Metric.WIND, imperial, true, now).value)
    }

    @Test fun uvShowsItsLevelWhenWide() {
        val m = WidgetContent.metric(Metric.UV, weather, use24h = true, now = now)
        assertTrue(m.wideValue, m.wideValue.startsWith(m.value + " "))
    }

    @Test fun humidityAndRainChanceArePercentages() {
        assertEquals("${weather.current.humidity}%", WidgetContent.metric(Metric.HUMIDITY, weather, true, now).value)
        assertEquals("${weather.daily.first().precipitationChance}%", WidgetContent.metric(Metric.RAIN_CHANCE, weather, true, now).value)
    }

    @Test fun sunEventsAreTheNextOnes() {
        // At 14:25 today's sunrise (07:04) has passed, so the next one is tomorrow's; sunset is still today.
        assertEquals(LocalDateTime.of(2026, 11, 21, 7, 4), WidgetContent.nextSunEvent(weather, sunrise = true, now))
        assertEquals(LocalDateTime.of(2026, 11, 20, 17, 1), WidgetContent.nextSunEvent(weather, sunrise = false, now))
        assertEquals("07:04", WidgetContent.metric(Metric.SUNRISE, weather, true, now).value)
        assertEquals("17:01", WidgetContent.metric(Metric.SUNSET, weather, true, now).value)
    }

    @Test fun everyMetricHasAValue() {
        Metric.entries.forEach { metric ->
            val m = WidgetContent.metric(metric, weather, true, now)
            assertTrue("$metric", m.value.isNotBlank() && m.label.isNotBlank())
        }
    }
}
