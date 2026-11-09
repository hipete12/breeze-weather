package com.breeze.weather.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Fetches forecasts and place searches from Open-Meteo (free, no API key) and keeps a small
 * on-disk cache so the last known weather shows instantly on launch and while offline.
 */
class WeatherRepository(context: Context) {

    private val cacheDir = File(context.cacheDir, "forecasts").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Last cached forecast for [place], regardless of age, or null. */
    suspend fun cached(place: Place, units: Units): Weather? = withContext(Dispatchers.IO) {
        val file = cacheFile(place, units)
        if (!file.exists()) return@withContext null
        runCatching { parseForecast(file.readText(), units, file.lastModified()) }.getOrNull()
    }

    suspend fun forecast(place: Place, units: Units): Weather = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=").append(place.latitude)
            append("&longitude=").append(place.longitude)
            append("&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,")
            append("weather_code,wind_speed_10m,wind_direction_10m,uv_index")
            append("&hourly=temperature_2m,weather_code,precipitation_probability,is_day")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,")
            append("precipitation_probability_max,sunrise,sunset,uv_index_max")
            append("&temperature_unit=").append(units.tempParam)
            append("&wind_speed_unit=").append(units.windParam)
            append("&timezone=auto&forecast_days=10")
        }
        val body = httpGet(url)
        val now = System.currentTimeMillis()
        val weather = parseForecast(body, units, now)
        runCatching { cacheFile(place, units).writeText(body) }
        weather
    }

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val lang = Locale.getDefault().language.ifBlank { "en" }
        val body = httpGet(
            "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=$lang&format=json"
        )
        json.decodeFromString<GeocodingDto>(body).results.orEmpty().map {
            Place(
                id = "geo:${it.id}",
                name = it.name,
                region = it.admin1,
                country = it.country,
                latitude = it.latitude,
                longitude = it.longitude,
            )
        }
    }

    fun clearCache(place: Place) {
        Units.entries.forEach { cacheFile(place, it).delete() }
    }

    private fun cacheFile(place: Place, units: Units): File {
        val key = place.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(cacheDir, "${key}_${units.name}.json")
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "BreezeWeather/0.1 (Android)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Server returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseForecast(body: String, units: Units, fetchedAt: Long): Weather {
        val dto = json.decodeFromString<ForecastDto>(body)
        val c = dto.current
        val currentTime = LocalDateTime.parse(c.time)
        val current = CurrentConditions(
            time = currentTime,
            temperature = c.temperature,
            feelsLike = c.apparentTemperature,
            humidity = c.humidity.toInt(),
            isDay = c.isDay.toInt() == 1,
            condition = Condition.fromWmo(c.weatherCode.toInt()),
            description = Condition.describe(c.weatherCode.toInt(), c.isDay.toInt() == 1),
            windSpeed = c.windSpeed,
            windDirection = c.windDirection.toInt(),
            uvIndex = c.uvIndex,
        )

        val h = dto.hourly
        val startHour = currentTime.truncatedTo(ChronoUnit.HOURS)
        val hourly = h.time.indices.mapNotNull { i ->
            val time = LocalDateTime.parse(h.time[i])
            val temp = h.temperature.getOrNull(i) ?: return@mapNotNull null
            if (time.isBefore(startHour)) return@mapNotNull null
            HourForecast(
                time = time,
                temperature = temp,
                condition = Condition.fromWmo(h.weatherCode.getOrNull(i)?.toInt() ?: 3),
                isDay = (h.isDay.getOrNull(i)?.toInt() ?: 1) == 1,
                precipitationChance = h.precipitationProbability.getOrNull(i)?.toInt() ?: 0,
            )
        }.take(25)

        val d = dto.daily
        val daily = d.time.indices.mapNotNull { i ->
            val high = d.temperatureMax.getOrNull(i) ?: return@mapNotNull null
            val low = d.temperatureMin.getOrNull(i) ?: return@mapNotNull null
            DayForecast(
                date = LocalDate.parse(d.time[i]),
                condition = Condition.fromWmo(d.weatherCode.getOrNull(i)?.toInt() ?: 3),
                high = high,
                low = low,
                precipitationChance = d.precipitationProbabilityMax.getOrNull(i)?.toInt() ?: 0,
                sunrise = d.sunrise.getOrNull(i)?.let(LocalDateTime::parse),
                sunset = d.sunset.getOrNull(i)?.let(LocalDateTime::parse),
                uvMax = d.uvIndexMax.getOrNull(i),
            )
        }

        return Weather(current, hourly, daily, units, fetchedAt)
    }
}
