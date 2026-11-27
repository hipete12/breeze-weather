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
data class MetricText(val label: String, val value: String, val wideValue: String = value, val shortLabel: String = label)

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
     * [count] columns: "Now" (unless [includeNow] is false), then every [step] hours from the next
     * whole hour. The forecast is expected to be rebased to the present (the repository's cached
     * forecasts are).
     */
    fun hourCells(weather: Weather, step: Int, count: Int, use24h: Boolean, includeNow: Boolean = true): List<HourCell> {
        if (count <= 0) return emptyList()
        val now = weather.current
        val currentHour = now.time.truncatedTo(ChronoUnit.HOURS)
        val cells = mutableListOf<HourCell>()
        if (includeNow) {
            val first = weather.hours.firstOrNull { it.time == currentHour }
            cells += HourCell("Now", now.temperature, now.weatherCode, now.isDay, first?.precipitationChance ?: 0)
        }
        val later = weather.hours.filter { it.time.isAfter(currentHour) }
        var i = step.coerceAtLeast(1) - 1
        while (cells.size < count && i < later.size) {
            val h = later[i]
            cells += HourCell(formatHour(h.time, use24h), h.temperature, h.weatherCode, h.isDay, h.precipitationChance)
            i += step.coerceAtLeast(1)
        }
        return cells
    }

    /** How many hourly columns there's data for at this [step], including "Now" if [includeNow]. */
    fun availableHours(weather: Weather, step: Int, includeNow: Boolean = true): Int {
        val currentHour = weather.current.time.truncatedTo(ChronoUnit.HOURS)
        val later = weather.hours.count { it.time.isAfter(currentHour) }
        return (if (includeNow) 1 else 0) + later / step.coerceAtLeast(1)
    }

    /**
     * The first [count] days from today, or from tomorrow with [fromTomorrow]. [DayCell.index] is
     * the day's position in the forecast, which the app's day detail uses.
     */
    fun dayCells(weather: Weather, count: Int, now: LocalDateTime = weather.localNow(), fromTomorrow: Boolean = false): List<DayCell> {
        val today = now.toLocalDate()
        return weather.daily.withIndex()
            .filter { !fromTomorrow || it.value.date.isAfter(today) }
            .take(count)
            .map { (i, d) -> DayCell(i, formatDay(d.date, today), d) }
    }

    /** How many days after today there's a forecast for. */
    fun daysAfterToday(weather: Weather, now: LocalDateTime = weather.localNow()): Int =
        weather.daily.count { it.date.isAfter(now.toLocalDate()) }

    /** Today's forecast (the strips' high and low), if the forecast still has it. */
    fun today(weather: Weather, now: LocalDateTime = weather.localNow()): DayForecast? =
        weather.daily.firstOrNull { it.date == now.toLocalDate() } ?: weather.daily.firstOrNull()

    /** Lowest low and highest high across [days], for scaling the temperature bars. */
    fun range(days: List<DayCell>): Pair<Double, Double>? {
        if (days.isEmpty()) return null
        return days.minOf { it.day.low } to days.maxOf { it.day.high }
    }

    fun metric(metric: Metric, weather: Weather, use24h: Boolean, now: LocalDateTime = weather.localNow()): MetricText =
        metricText(metric, weather, use24h, now).copy(shortLabel = metric.shortLabel)

    private fun metricText(metric: Metric, weather: Weather, use24h: Boolean, now: LocalDateTime): MetricText {
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
                val date = now.toLocalDate()
                val total = weather.hoursOn(date).sumOf { it.precipitation ?: 0.0 }
                MetricText(metric.label, formatPrecip(total, weather.units))
            }
            Metric.SUNRISE -> MetricText(metric.label, nextSunEvent(weather, sunrise = true, now)?.let { formatClock(it, use24h) } ?: "–")
            Metric.SUNSET -> MetricText(metric.label, nextSunEvent(weather, sunrise = false, now)?.let { formatClock(it, use24h) } ?: "–")
            Metric.CLOUD_COVER -> {
                val hour = c.time.truncatedTo(ChronoUnit.HOURS)
                val cover = weather.hours.firstOrNull { it.time == hour }?.cloudCover
                MetricText(metric.label, cover?.let { "${it.roundToInt()}%" } ?: "–")
            }
            Metric.HIGH_LOW -> MetricText(metric.label, today?.let { "${temp(it.high)} / ${temp(it.low)}" } ?: "–")
        }
    }

    /** Today's sunrise or sunset if it's still ahead, otherwise tomorrow's. */
    fun nextSunEvent(weather: Weather, sunrise: Boolean, now: LocalDateTime = weather.localNow()): LocalDateTime? {
        return weather.daily.asSequence()
            .mapNotNull { if (sunrise) it.sunrise else it.sunset }
            .firstOrNull { !it.isBefore(now) }
    }
}
