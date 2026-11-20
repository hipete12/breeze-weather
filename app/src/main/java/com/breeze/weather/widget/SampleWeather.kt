package com.breeze.weather.widget

import com.breeze.weather.data.Condition
import com.breeze.weather.data.CurrentConditions
import com.breeze.weather.data.DayForecast
import com.breeze.weather.data.HourForecast
import com.breeze.weather.data.Place
import com.breeze.weather.data.Units
import com.breeze.weather.data.Weather
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * A plausible made-up forecast, for widget previews when there's no real forecast yet (and for
 * tests). Mild, mixed weather with a little rain, so every part of a widget has something to show.
 */
object SampleWeather {

    val place = Place(id = "sample", name = "San Francisco", region = "California", country = "United States", latitude = 37.77, longitude = -122.42)

    fun create(
        units: Units = Units.METRIC,
        utcOffsetSeconds: Int = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds,
        now: LocalDateTime = LocalDateTime.now(ZoneOffset.ofTotalSeconds(utcOffsetSeconds)),
    ): Weather {
        val start = now.toLocalDate().atStartOfDay()
        val dayCodes = listOf(2, 3, 61, 80, 1, 0, 2, 3, 63, 1)
        val hours = (0 until 240).map { i ->
            val time = start.plusHours(i.toLong())
            val day = i / 24
            val hourOfDay = i % 24
            val isDay = hourOfDay in 7..17
            // Daily swing peaking mid-afternoon, drifting a little from day to day.
            val swing = -cos((hourOfDay - 3) / 24.0 * 2 * PI)
            val tempC = 13.0 + day % 3 + 5.0 * swing
            val code = when {
                dayCodes[day] == 61 && hourOfDay in 13..19 -> 61
                dayCodes[day] == 80 && hourOfDay in 9..12 -> 80
                dayCodes[day] == 63 && hourOfDay >= 10 -> 63
                isDay -> if (hourOfDay in 9..14) dayCodes[day].coerceAtMost(2) else 2
                else -> if (dayCodes[day] >= 3) 3 else 0
            }
            HourForecast(
                time = time,
                temperature = temp(tempC, units),
                condition = Condition.fromWmo(code),
                weatherCode = code,
                isDay = isDay,
                precipitationChance = if (code >= 51) 60 + (hourOfDay % 4) * 10 else hourOfDay % 6,
                feelsLike = temp(tempC - 1.5, units),
                humidity = 72.0 - 12 * swing,
                precipitation = if (code >= 51) 0.6 else 0.0,
                windSpeed = if (units == Units.IMPERIAL) 9.0 else 14.0,
                windGusts = if (units == Units.IMPERIAL) 18.0 else 29.0,
                windDirection = 250.0,
                uvIndex = if (isDay) (4.0 * (1 - kotlin.math.abs(hourOfDay - 13) / 6.0)).coerceAtLeast(0.0) else 0.0,
                cloudCover = if (code >= 3) 90.0 else if (code == 2) 45.0 else 10.0,
            )
        }
        val daily = (0 until 10).map { d ->
            val dayHours = hours.subList(d * 24, d * 24 + 24)
            DayForecast(
                date = start.toLocalDate().plusDays(d.toLong()),
                condition = Condition.fromWmo(dayCodes[d]),
                weatherCode = dayCodes[d],
                high = dayHours.maxOf { it.temperature },
                low = dayHours.minOf { it.temperature },
                precipitationChance = dayHours.maxOf { it.precipitationChance },
                sunrise = start.plusDays(d.toLong()).plusHours(7).plusMinutes(4),
                sunset = start.plusDays(d.toLong()).plusHours(17).plusMinutes(1),
                uvMax = 4.0,
            )
        }
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        val h = hours.first { it.time == hour }
        val current = CurrentConditions(
            time = now,
            temperature = h.temperature,
            feelsLike = h.feelsLike ?: h.temperature,
            humidity = h.humidity?.roundToInt() ?: 70,
            isDay = h.isDay,
            condition = h.condition,
            weatherCode = h.weatherCode,
            description = Condition.describe(h.weatherCode, h.isDay),
            windSpeed = h.windSpeed ?: 0.0,
            windGusts = h.windGusts,
            windDirection = 250,
            uvIndex = h.uvIndex,
        )
        return Weather(
            current = current,
            hourly = hours.filterNot { it.time.isBefore(hour) }.take(25),
            hours = hours,
            daily = daily,
            units = units,
            fetchedAt = System.currentTimeMillis(),
            utcOffsetSeconds = utcOffsetSeconds,
        )
    }

    private fun temp(celsius: Double, units: Units) = if (units == Units.IMPERIAL) celsius * 9 / 5 + 32 else celsius
}
