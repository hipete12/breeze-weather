package com.breeze.weather.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** A location the user cares about. The device's own location uses [CURRENT_ID]. */
@Serializable
data class Place(
    val id: String,
    val name: String,
    val region: String? = null,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
) {
    val isCurrent: Boolean get() = id == CURRENT_ID

    val subtitle: String
        get() = listOfNotNull(region, country)
            .filter { it.isNotBlank() && it != name }
            .distinct()
            .joinToString(", ")

    companion object {
        const val CURRENT_ID = "current"
    }
}

/** Great-circle distance in km from this place to ([lat], [lon]). */
fun Place.distanceKm(lat: Double, lon: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat - latitude)
    val dLon = Math.toRadians(lon - longitude)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(lat)) *
        Math.sin(dLon / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(a))
}

enum class Units(
    val tempParam: String,
    val windParam: String,
    val windLabel: String,
    val precipParam: String,
    val precipLabel: String,
) {
    METRIC("celsius", "kmh", "km/h", "mm", "mm"),
    IMPERIAL("fahrenheit", "mph", "mph", "inch", "in");

    /** Converts a wind speed in these units to km/h. */
    fun toKmh(speed: Double): Double = if (this == IMPERIAL) speed * 1.609344 else speed

    /** Converts a temperature difference in these units to Celsius degrees. */
    fun deltaToC(delta: Double): Double = if (this == IMPERIAL) delta / 1.8 else delta
}

enum class LocationAccess { NONE, APPROXIMATE, PRECISE }

// ---------- Domain ----------

data class Weather(
    val current: CurrentConditions,
    /** The next ~25 hours starting at the current hour. */
    val hourly: List<HourForecast>,
    /** Every forecast hour (10 days), starting at local midnight today. */
    val hours: List<HourForecast>,
    val daily: List<DayForecast>,
    val units: Units,
    val fetchedAt: Long,
    val utcOffsetSeconds: Int,
    /** Nearest weather station whose observations cover past hours, if any. */
    val observationStation: String? = null,
    /** Past days leading up to today (oldest first), for historical browsing in day detail. */
    val pastDaily: List<DayForecast> = emptyList(),
) {
    /** All days in chronological order: historical days followed by today and the forecast. */
    val allDays: List<DayForecast> get() = pastDaily + daily

    /** Wall-clock time right now at this place. */
    fun localNow(): LocalDateTime =
        LocalDateTime.now(java.time.ZoneOffset.ofTotalSeconds(utcOffsetSeconds))

    fun hoursOn(date: LocalDate): List<HourForecast> = hours.filter { it.time.toLocalDate() == date }

    /**
     * This forecast as seen at [now] (wall-clock time at the place). A forecast fetched in an
     * earlier hour, such as one loaded from the disk cache, still starts at its fetch time: its
     * first day would read "Today" and its current conditions would be hours or days old. This
     * drops the days and hours already past and takes the current conditions from the forecast
     * for this hour. Returns null when the forecast no longer covers [now].
     */
    fun rebasedTo(now: LocalDateTime): Weather? {
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        if (!current.time.truncatedTo(ChronoUnit.HOURS).isBefore(hour)) return this
        val h = hours.firstOrNull { it.time == hour } ?: return null
        val today = now.toLocalDate()
        val past = (pastDaily + daily).filter { it.date.isBefore(today) }
        val days = daily.filterNot { it.date.isBefore(today) }
        if (days.isEmpty()) return null
        val rebasedCurrent = CurrentConditions(
            time = hour,
            temperature = h.temperature,
            feelsLike = h.feelsLike ?: h.temperature,
            humidity = h.humidity?.roundToInt() ?: current.humidity,
            isDay = h.isDay,
            condition = h.condition,
            weatherCode = h.weatherCode,
            description = Condition.describe(h.weatherCode, h.isDay),
            windSpeed = h.windSpeed ?: current.windSpeed,
            windGusts = h.windGusts,
            windDirection = h.windDirection?.roundToInt() ?: current.windDirection,
            uvIndex = h.uvIndex,
        )
        return copy(
            current = rebasedCurrent,
            hourly = hours.filterNot { it.time.isBefore(hour) }.take(25),
            pastDaily = past,
            daily = days,
        )
    }
}

data class CurrentConditions(
    val time: LocalDateTime,
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int,
    val isDay: Boolean,
    val condition: Condition,
    /** Precise WMO code behind [condition], for picking the icon. */
    val weatherCode: Int,
    val description: String,
    val windSpeed: Double,
    val windGusts: Double?,
    val windDirection: Int,
    val uvIndex: Double?,
)

data class HourForecast(
    val time: LocalDateTime,
    val temperature: Double,
    val condition: Condition,
    val weatherCode: Int,
    val isDay: Boolean,
    val precipitationChance: Int,
    val feelsLike: Double?,
    val humidity: Double?,
    val precipitation: Double?,
    val windSpeed: Double?,
    val windGusts: Double?,
    val windDirection: Double?,
    val uvIndex: Double?,
    val cloudCover: Double?,
    /** True when this hour's weather and temperature come from station observations, not a forecast. */
    val observed: Boolean = false,
    /** True when [precipitation] itself was measured by the station (not all METAR stations report it). */
    val observedPrecipitation: Boolean = false,
)

/** Multi-year average for one local hour, used as the "historical average" overlay. */
@Serializable
data class HistoryHour(
    val time: String,
    val temperature: Double? = null,
    val feelsLike: Double? = null,
    val humidity: Double? = null,
    val precipitation: Double? = null,
    val windSpeed: Double? = null,
    val windGusts: Double? = null,
    val cloudCover: Double? = null,
)

data class DayForecast(
    val date: LocalDate,
    val condition: Condition,
    val weatherCode: Int,
    val high: Double,
    val low: Double,
    val precipitationChance: Int,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val uvMax: Double?,
)

// ---------- Open-Meteo DTOs ----------

@Serializable
internal data class ForecastDto(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int,
    val current: CurrentDto,
    val hourly: HourlyDto,
    val daily: DailyDto,
)

@Serializable
internal data class CurrentDto(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double,
    @SerialName("apparent_temperature") val apparentTemperature: Double,
    @SerialName("relative_humidity_2m") val humidity: Double,
    @SerialName("is_day") val isDay: Double,
    @SerialName("weather_code") val weatherCode: Double,
    @SerialName("wind_speed_10m") val windSpeed: Double,
    @SerialName("wind_direction_10m") val windDirection: Double,
    @SerialName("wind_gusts_10m") val windGusts: Double? = null,
    @SerialName("uv_index") val uvIndex: Double? = null,
)

@Serializable
internal data class HourlyDto(
    val time: List<String>,
    @SerialName("temperature_2m") val temperature: List<Double?>,
    @SerialName("weather_code") val weatherCode: List<Double?>,
    @SerialName("precipitation_probability") val precipitationProbability: List<Double?>,
    @SerialName("is_day") val isDay: List<Double?>,
    @SerialName("apparent_temperature") val apparentTemperature: List<Double?>,
    @SerialName("relative_humidity_2m") val humidity: List<Double?>,
    val precipitation: List<Double?>,
    @SerialName("wind_speed_10m") val windSpeed: List<Double?>,
    @SerialName("wind_gusts_10m") val windGusts: List<Double?>,
    @SerialName("wind_direction_10m") val windDirection: List<Double?>,
    @SerialName("uv_index") val uvIndex: List<Double?>,
    @SerialName("cloud_cover") val cloudCover: List<Double?>,
)

@Serializable
internal data class ArchiveDto(val hourly: ArchiveHourlyDto)

@Serializable
internal data class ArchiveHourlyDto(
    val time: List<String>,
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @SerialName("apparent_temperature") val apparentTemperature: List<Double?> = emptyList(),
    @SerialName("relative_humidity_2m") val humidity: List<Double?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m") val windGusts: List<Double?> = emptyList(),
    @SerialName("cloud_cover") val cloudCover: List<Double?> = emptyList(),
)

@Serializable
internal data class DailyDto(
    val time: List<String>,
    @SerialName("weather_code") val weatherCode: List<Double?>,
    @SerialName("temperature_2m_max") val temperatureMax: List<Double?>,
    @SerialName("temperature_2m_min") val temperatureMin: List<Double?>,
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Double?>,
    val sunrise: List<String?>,
    val sunset: List<String?>,
    @SerialName("uv_index_max") val uvIndexMax: List<Double?>,
)

@Serializable
internal data class GeocodingDto(val results: List<GeoResultDto>? = null)

@Serializable
internal data class GeoResultDto(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val admin1: String? = null,
)
