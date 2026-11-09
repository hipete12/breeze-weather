package com.breeze.weather.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            append("weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index")
            append("&hourly=temperature_2m,weather_code,precipitation_probability,is_day,")
            append("apparent_temperature,relative_humidity_2m,precipitation,wind_speed_10m,")
            append("wind_gusts_10m,wind_direction_10m,uv_index,cloud_cover")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,")
            append("precipitation_probability_max,sunrise,sunset,uv_index_max")
            append("&temperature_unit=").append(units.tempParam)
            append("&wind_speed_unit=").append(units.windParam)
            append("&precipitation_unit=").append(units.precipParam)
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

    /**
     * Hour-by-hour averages for [start]..[end] over the previous [years] years, from the
     * Open-Meteo ERA5 archive. One small request per year, run in parallel; cached on disk.
     */
    suspend fun history(
        place: Place,
        units: Units,
        start: LocalDate,
        end: LocalDate,
        years: Int = 10,
    ): Map<LocalDateTime, HistoryHour> = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "hist_${cacheKey(place)}_${units.name}_${start}_$end.json")
        if (file.exists()) {
            runCatching { json.decodeFromString<List<HistoryHour>>(file.readText()) }.getOrNull()
                ?.let { cached -> return@withContext cached.associateBy { LocalDateTime.parse(it.time) } }
        }
        val perYear = coroutineScope {
            (1..years).map { k ->
                async { runCatching { k to fetchArchive(place, units, start.minusYears(k.toLong()), end.minusYears(k.toLong())) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        if (perYear.size < 3) throw IOException("Historical data unavailable")

        val acc = HashMap<LocalDateTime, Array<Accumulator>>()
        for ((k, dto) in perYear) {
            val h = dto.hourly
            h.time.forEachIndexed { i, t ->
                val target = LocalDateTime.parse(t).plusYears(k.toLong())
                val slots = acc.getOrPut(target) { Array(7) { Accumulator() } }
                slots[0].add(h.temperature.getOrNull(i))
                slots[1].add(h.apparentTemperature.getOrNull(i))
                slots[2].add(h.humidity.getOrNull(i))
                slots[3].add(h.precipitation.getOrNull(i))
                slots[4].add(h.windSpeed.getOrNull(i))
                slots[5].add(h.windGusts.getOrNull(i))
                slots[6].add(h.cloudCover.getOrNull(i))
            }
        }
        val result = acc.entries.sortedBy { it.key }.map { (time, a) ->
            HistoryHour(
                time = time.toString(),
                temperature = a[0].mean(), feelsLike = a[1].mean(), humidity = a[2].mean(),
                precipitation = a[3].mean(), windSpeed = a[4].mean(), windGusts = a[5].mean(),
                cloudCover = a[6].mean(),
            )
        }
        runCatching { file.writeText(json.encodeToString(result)) }
        result.associateBy { LocalDateTime.parse(it.time) }
    }

    private fun fetchArchive(place: Place, units: Units, start: LocalDate, end: LocalDate): ArchiveDto {
        val url = "https://archive-api.open-meteo.com/v1/archive" +
            "?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&start_date=$start&end_date=$end" +
            "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation," +
            "wind_speed_10m,wind_gusts_10m,cloud_cover" +
            "&temperature_unit=${units.tempParam}&wind_speed_unit=${units.windParam}" +
            "&precipitation_unit=${units.precipParam}&timezone=auto"
        return json.decodeFromString<ArchiveDto>(httpGet(url))
    }

    private class Accumulator {
        private var sum = 0.0
        private var n = 0
        fun add(v: Double?) { if (v != null) { sum += v; n++ } }
        fun mean(): Double? = if (n == 0) null else sum / n
    }

    private fun cacheKey(place: Place) = place.id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun clearCache(place: Place) {
        Units.entries.forEach { cacheFile(place, it).delete() }
    }

    private fun cacheFile(place: Place, units: Units): File {
        return File(cacheDir, "${cacheKey(place)}_${units.name}.json")
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
            windGusts = c.windGusts,
            windDirection = c.windDirection.toInt(),
            uvIndex = c.uvIndex,
        )

        val h = dto.hourly
        val startHour = currentTime.truncatedTo(ChronoUnit.HOURS)
        val hours = h.time.indices.mapNotNull { i ->
            val temp = h.temperature.getOrNull(i) ?: return@mapNotNull null
            val code = h.weatherCode.getOrNull(i)?.toInt() ?: 3
            HourForecast(
                time = LocalDateTime.parse(h.time[i]),
                temperature = temp,
                condition = Condition.fromWmo(code),
                weatherCode = code,
                isDay = (h.isDay.getOrNull(i)?.toInt() ?: 1) == 1,
                precipitationChance = h.precipitationProbability.getOrNull(i)?.toInt() ?: 0,
                feelsLike = h.apparentTemperature.getOrNull(i),
                humidity = h.humidity.getOrNull(i),
                precipitation = h.precipitation.getOrNull(i),
                windSpeed = h.windSpeed.getOrNull(i),
                windGusts = h.windGusts.getOrNull(i),
                windDirection = h.windDirection.getOrNull(i),
                uvIndex = h.uvIndex.getOrNull(i),
                cloudCover = h.cloudCover.getOrNull(i),
            )
        }
        val hourly = hours.filterNot { it.time.isBefore(startHour) }.take(25)

        val d = dto.daily
        val daily = d.time.indices.mapNotNull { i ->
            val high = d.temperatureMax.getOrNull(i) ?: return@mapNotNull null
            val low = d.temperatureMin.getOrNull(i) ?: return@mapNotNull null
            val code = d.weatherCode.getOrNull(i)?.toInt() ?: 3
            DayForecast(
                date = LocalDate.parse(d.time[i]),
                condition = Condition.fromWmo(code),
                weatherCode = code,
                high = high,
                low = low,
                precipitationChance = d.precipitationProbabilityMax.getOrNull(i)?.toInt() ?: 0,
                sunrise = d.sunrise.getOrNull(i)?.let(LocalDateTime::parse),
                sunset = d.sunset.getOrNull(i)?.let(LocalDateTime::parse),
                uvMax = d.uvIndexMax.getOrNull(i),
            )
        }

        return Weather(current, hourly, hours, daily, units, fetchedAt, dto.utcOffsetSeconds)
    }
}
