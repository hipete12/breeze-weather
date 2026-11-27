package com.breeze.weather

import com.breeze.weather.data.Condition
import com.breeze.weather.data.DayWeather
import com.breeze.weather.data.HourForecast
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DayWeatherTest {

    private val date = LocalDate.of(2026, 11, 20)
    private val sunrise = date.atTime(7, 45)
    private val sunset = date.atTime(17, 3)

    /** A day of hours: [codes] maps an hour to its WMO code; the rest are partly cloudy (2). */
    private fun day(codes: Map<Int, Int>, on: LocalDate = date): List<HourForecast> = (0 until 24).map { h ->
        val code = codes[h] ?: 2
        HourForecast(
            time = on.atTime(h, 0), temperature = 10.0, condition = Condition.fromWmo(code), weatherCode = code,
            isDay = h in 8..17, precipitationChance = 0, feelsLike = null, humidity = null, precipitation = null,
            windSpeed = null, windGusts = null, windDirection = null, uvIndex = null, cloudCover = null,
        )
    }

    @Test fun windowRunsFromTheSunriseHourToTheHourAfterSunset() {
        val hours = DayWeather.daylightHours(date, sunrise, sunset, day(emptyMap()))
        assertEquals(date.atTime(7, 0), hours.first().time)
        assertEquals(date.atTime(18, 0), hours.last().time)
        assertEquals(12, hours.size)
    }

    @Test fun sunsetOnTheHourEndsThere() {
        val hours = DayWeather.daylightHours(date, sunrise, date.atTime(17, 0), day(emptyMap()))
        assertEquals(date.atTime(17, 0), hours.last().time)
    }

    @Test fun rainAtNightDoesNotMakeTheDayRainy() {
        // Rain at 3:00 and 21:00; partly cloudy all day.
        val hours = day(mapOf(3 to 63, 21 to 61))
        assertEquals(2, DayWeather.code(date, sunrise, sunset, hours, fallback = 63))
    }

    @Test fun theMostSevereDaylightHourWins() {
        // A shower mid-morning and a thunderstorm at 18:00, still inside the window.
        assertEquals(95, DayWeather.code(date, sunrise, sunset, day(mapOf(10 to 80, 18 to 95)), fallback = 3))
        // Drizzle at 7:00, the sunrise hour, counts.
        assertEquals(51, DayWeather.code(date, sunrise, sunset, day(mapOf(7 to 51)), fallback = 3))
        // Rain at 6:00 and 19:00, just outside, doesn't.
        assertEquals(2, DayWeather.code(date, sunrise, sunset, day(mapOf(6 to 61, 19 to 61)), fallback = 3))
    }

    @Test fun otherDaysHoursAreIgnored() {
        val hours = day(emptyMap()) + day(mapOf(12 to 65), on = date.plusDays(1))
        assertEquals(2, DayWeather.code(date, sunrise, sunset, hours, fallback = 3))
    }

    @Test fun withoutSunriseOrHoursItFallsBack() {
        // Polar night or day: no sunrise or sunset.
        assertEquals(71, DayWeather.code(date, null, null, day(emptyMap()), fallback = 71))
        // No hourly data for that day (the end of the forecast).
        assertEquals(61, DayWeather.code(date, sunrise, sunset, emptyList(), fallback = 61))
        assertEquals(3, DayWeather.code(date, LocalDateTime.of(2026, 11, 20, 18, 0), LocalDateTime.of(2026, 11, 20, 7, 0), day(emptyMap()), fallback = 3))
    }
}
