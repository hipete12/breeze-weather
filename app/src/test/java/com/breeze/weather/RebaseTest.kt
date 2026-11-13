package com.breeze.weather

import com.breeze.weather.data.Condition
import com.breeze.weather.data.CurrentConditions
import com.breeze.weather.data.DayForecast
import com.breeze.weather.data.HourForecast
import com.breeze.weather.data.Units
import com.breeze.weather.data.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RebaseTest {

    private val start: LocalDate = LocalDate.of(2026, 11, 9)

    /** Three days of hourly data, fetched at 14:15 on the first day; temperature = hour index. */
    private fun weather(): Weather {
        val hours = (0 until 72).map { i ->
            val code = if (i == 50) 61 else 3
            HourForecast(
                time = start.atStartOfDay().plusHours(i.toLong()),
                temperature = i.toDouble(),
                condition = Condition.fromWmo(code),
                weatherCode = code,
                isDay = true,
                precipitationChance = 0,
                feelsLike = i - 2.0,
                humidity = 60.0,
                precipitation = 0.0,
                windSpeed = 10.0,
                windGusts = 20.0,
                windDirection = 90.0,
                uvIndex = 1.0,
                cloudCover = 100.0,
            )
        }
        val days = (0 until 3).map { d ->
            DayForecast(start.plusDays(d.toLong()), Condition.CLOUDY, 3, 20.0, 10.0, 0, null, null, null)
        }
        val fetched = start.atTime(14, 15)
        val current = CurrentConditions(
            fetched, 99.0, 99.0, 50, true, Condition.CLEAR, 0, "Sunny", 5.0, null, 0, null,
        )
        return Weather(
            current = current,
            hourly = hours.filterNot { it.time.isBefore(start.atTime(14, 0)) }.take(25),
            hours = hours,
            daily = days,
            units = Units.METRIC,
            fetchedAt = 0L,
            utcOffsetSeconds = 0,
        )
    }

    @Test fun sameHourIsUnchanged() {
        val w = weather()
        assertSame(w, w.rebasedTo(start.atTime(14, 50)))
    }

    @Test fun laterDayDropsPastDaysAndHours() {
        val r = weather().rebasedTo(start.plusDays(2).atTime(2, 30))!!
        // Today is now the third day, not the day the forecast was fetched.
        assertEquals(start.plusDays(2), r.daily.first().date)
        assertEquals(1, r.daily.size)
        assertEquals(LocalDateTime.of(start.plusDays(2), java.time.LocalTime.of(2, 0)), r.hourly.first().time)
        // Current conditions come from the 02:00 forecast hour (index 50), not the stale fetch.
        assertEquals(50.0, r.current.temperature, 0.0)
        assertEquals(48.0, r.current.feelsLike, 0.0)
        assertEquals(Condition.RAIN, r.current.condition)
        assertEquals(61, r.current.weatherCode)
        assertEquals("Light rain", r.current.description)
        assertEquals(60, r.current.humidity)
        assertEquals(90, r.current.windDirection)
    }

    @Test fun nullOnceTheForecastIsUsedUp() {
        assertNull(weather().rebasedTo(start.plusDays(3).atTime(1, 0)))
    }
}
