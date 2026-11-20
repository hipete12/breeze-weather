package com.breeze.weather.widget

import com.breeze.weather.data.DayForecast
import com.breeze.weather.data.Weather
import com.breeze.weather.ui.formatClock
import com.breeze.weather.ui.formatDay
import com.breeze.weather.ui.formatHour
import com.breeze.weather.ui.formatPrecip
import com.breeze.weather.ui.uvLabel
import com.breeze.weather.ui.windCompass
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** One column of the hourly forecast. The first is "Now", with the current reading. */
data class HourCell(
    val label: String,
    val temperature: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val rainChance: Int,
)

/** One day of the daily forecast. */
data class DayCell(
    val index: Int,
    val label: String,
    val day: DayForecast,
)

/**
 * A reading for the details section.
 *
 * @param value the short form that fits a narrow tile; [wideValue] adds context when there's room.
 */
data class MetricText(val label: String, val value: String, val wideValue: String = value)

/** Turns a forecast into the text and values widgets show. Pure, so it's unit tested. */
object WidgetContent {

    fun temp(t: Double): String = "${t.roundToInt()}°"

    fun highLow(weather: Weather): String? =
        weather.daily.firstOrNull()?.let { "H ${temp(it.high)}  L ${temp(it.low)}" }

    fun feelsLike(weather: Weather): String = "Feels like ${temp(weather.current.feelsLike)}"

    fun updated(fetchedAt: Long, use24h: Boolean): String {
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(fetchedAt), ZoneId.systemDefault())
        return "Updated ${formatClock(time, use24h)}"
    }

    /**
     * [count] columns: "Now", then every [step] hours from the next whole hour. The forecast is
     * expected to be rebased to the present (the repository's cached forecasts are).
     */
    fun hourCells(weather: Weather, step: Int, count: Int, use24h: Boolean): List<HourCell> {
        if (count <= 0) return emptyList()
        val now = weather.current
        val currentHour = now.time.truncatedTo(ChronoUnit.HOURS)
        val first = weather.hours.firstOrNull { it.time == currentHour }
        val cells = mutableListOf(
            HourCell("Now", now.temperature, now.weatherCode, now.isDay, first?.precipitationChance ?: 0)
        )
        val later = weather.hours.filter { it.time.isAfter(currentHour) }
        var i = step.coerceAtLeast(1) - 1
        while (cells.size < count && i < later.size) {
            val h = later[i]
            cells += HourCell(formatHour(h.time, use24h), h.temperature, h.weatherCode, h.isDay, h.precipitationChance)
            i += step.coerceAtLeast(1)
        }
        return cells
    }

    /** How many hourly columns there's data for at this [step], including "Now". */
    fun availableHours(weather: Weather, step: Int): Int {
        val currentHour = weather.current.time.truncatedTo(ChronoUnit.HOURS)
        val later = weather.hours.count { it.time.isAfter(currentHour) }
        return 1 + later / step.coerceAtLeast(1)
    }

    fun dayCells(weather: Weather, count: Int): List<DayCell> {
        val today = weather.localNow().toLocalDate()
        return weather.daily.take(count).mapIndexed { i, d -> DayCell(i, formatDay(d.date, today), d) }
    }

    /** Lowest low and highest high across [days], for scaling the temperature bars. */
    fun range(days: List<DayCell>): Pair<Double, Double>? {
        if (days.isEmpty()) return null
        return days.minOf { it.day.low } to days.maxOf { it.day.high }
    }

    fun metric(metric: Metric, weather: Weather, use24h: Boolean): MetricText {
        val c = weather.current
        val today = weather.daily.firstOrNull()
        return when (metric) {
            Metric.FEELS_LIKE -> MetricText(metric.label, temp(c.feelsLike))
            Metric.HUMIDITY -> MetricText(metric.label, "${c.humidity}%")
            Metric.WIND -> {
                val speed = "${c.windSpeed.roundToInt()} ${weather.units.windLabel}"
                MetricText(metric.label, speed, "$speed ${windCompass(c.windDirection)}")
            }
            Metric.UV -> {
                val uv = c.uvIndex ?: today?.uvMax
                if (uv == null) MetricText(metric.label, "–")
                else MetricText(metric.label, "${uv.roundToInt()}", "${uv.roundToInt()} ${uvLabel(uv)}")
            }
            Metric.RAIN_CHANCE -> MetricText(metric.label, today?.let { "${it.precipitationChance}%" } ?: "–")
            Metric.PRECIPITATION -> {
                val date = weather.localNow().toLocalDate()
                val total = weather.hoursOn(date).sumOf { it.precipitation ?: 0.0 }
                MetricText("Rain today", formatPrecip(total, weather.units))
            }
            Metric.SUNRISE -> MetricText(metric.label, nextSunEvent(weather, sunrise = true)?.let { formatClock(it, use24h) } ?: "–")
            Metric.SUNSET -> MetricText(metric.label, nextSunEvent(weather, sunrise = false)?.let { formatClock(it, use24h) } ?: "–")
            Metric.CLOUD_COVER -> {
                val hour = c.time.truncatedTo(ChronoUnit.HOURS)
                val cover = weather.hours.firstOrNull { it.time == hour }?.cloudCover
                MetricText(metric.label, cover?.let { "${it.roundToInt()}%" } ?: "–")
            }
            Metric.HIGH_LOW -> MetricText(metric.label, today?.let { "${temp(it.high)} / ${temp(it.low)}" } ?: "–")
        }
    }

    /** Today's sunrise or sunset if it's still ahead, otherwise tomorrow's. */
    fun nextSunEvent(weather: Weather, sunrise: Boolean): LocalDateTime? {
        val now = weather.localNow()
        return weather.daily.asSequence()
            .mapNotNull { if (sunrise) it.sunrise else it.sunset }
            .firstOrNull { !it.isBefore(now) }
    }
}
