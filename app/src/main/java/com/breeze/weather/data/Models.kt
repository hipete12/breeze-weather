package com.breeze.weather.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

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

enum class Units(val tempParam: String, val windParam: String, val windLabel: String) {
    METRIC("celsius", "kmh", "km/h"),
    IMPERIAL("fahrenheit", "mph", "mph"),
}

enum class LocationAccess { NONE, APPROXIMATE, PRECISE }

// ---------- Domain ----------

data class Weather(
    val current: CurrentConditions,
    val hourly: List<HourForecast>,
    val daily: List<DayForecast>,
    val units: Units,
    val fetchedAt: Long,
)

data class CurrentConditions(
    val time: LocalDateTime,
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int,
    val isDay: Boolean,
    val condition: Condition,
    val description: String,
    val windSpeed: Double,
    val windDirection: Int,
    val uvIndex: Double?,
)

data class HourForecast(
    val time: LocalDateTime,
    val temperature: Double,
    val condition: Condition,
    val isDay: Boolean,
    val precipitationChance: Int,
)

data class DayForecast(
    val date: LocalDate,
    val condition: Condition,
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
    @SerialName("uv_index") val uvIndex: Double? = null,
)

@Serializable
internal data class HourlyDto(
    val time: List<String>,
    @SerialName("temperature_2m") val temperature: List<Double?>,
    @SerialName("weather_code") val weatherCode: List<Double?>,
    @SerialName("precipitation_probability") val precipitationProbability: List<Double?>,
    @SerialName("is_day") val isDay: List<Double?>,
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
