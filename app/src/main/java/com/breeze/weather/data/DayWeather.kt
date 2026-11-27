package com.breeze.weather.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * A day's weather icon comes from the hours the sun is up, so rain overnight doesn't make a dry
 * day show as rainy.
 */
object DayWeather {

    /**
     * The hours from the hour of sunrise to the hour after sunset, both included: sunrise 7:45
     * and sunset 17:03 give 7:00 to 18:00. Empty without a sunrise or sunset (polar day or night).
     */
    fun daylightHours(date: LocalDate, sunrise: LocalDateTime?, sunset: LocalDateTime?, hours: List<HourForecast>): List<HourForecast> {
        if (sunrise == null || sunset == null || !sunset.isAfter(sunrise)) return emptyList()
        val start = sunrise.truncatedTo(ChronoUnit.HOURS)
        val sunsetHour = sunset.truncatedTo(ChronoUnit.HOURS)
        val end = if (sunsetHour == sunset) sunsetHour else sunsetHour.plusHours(1)
        return hours.filter { it.time.toLocalDate() == date && !it.time.isBefore(start) && !it.time.isAfter(end) }
    }

    /**
     * The day's WMO code: the most severe of its daylight hours' codes (the highest, the same
     * way the forecast service picks a whole day's code), or [fallback] (the service's code for
     * the whole day) when there are no daylight hours to go on.
     */
    fun code(date: LocalDate, sunrise: LocalDateTime?, sunset: LocalDateTime?, hours: List<HourForecast>, fallback: Int): Int =
        daylightHours(date, sunrise, sunset, hours).maxOfOrNull { it.weatherCode } ?: fallback
}
