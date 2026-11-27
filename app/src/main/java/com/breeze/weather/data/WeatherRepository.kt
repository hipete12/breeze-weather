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

    /**
     * Last cached forecast for [place], moved on to the current hour (see [Weather.rebasedTo]), or
     * null if there is none or it no longer covers the present.
     */
    suspend fun cached(place: Place, units: Units): Weather? = withContext(Dispatchers.IO) {
        val file = cacheFile(place, units)
        if (!file.exists()) return@withContext null
        runCatching {
            val parsed = parseForecast(file.readText(), units, file.lastModified())
            parsed.rebasedTo(parsed.localNow())?.let { applyCachedObservations(place, it) }
        }.getOrNull()
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
            append("&timezone=auto&past_days=").append(Observations.PAST_DAYS)
            append("&forecast_days=10")
        }
        val (body, observed) = coroutineScope {
            val forecastDeferred = async { httpGet(url) }
            val obsDeferred = async { runCatching { fetchObservations(place) }.getOrNull() }
            forecastDeferred.await() to obsDeferred.await()
        }
        val now = System.currentTimeMillis()
        val weather = parseForecast(body, units, now)
        runCatching { cacheFile(place, units).writeText(body) }
        if (observed != null) {
            Observations.merge(weather, observed.first, observed.second)
        } else {
            applyCachedObservations(place, weather)
        }
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
        obsFile(place).delete()
        stationFile(place).delete()
    }

    private fun cacheFile(place: Place, units: Units): File {
        return File(cacheDir, "${cacheKey(place)}_${units.name}.json")
    }

    private fun obsFile(place: Place): File = File(cacheDir, "obs_${cacheKey(place)}.json")

    private fun stationFile(place: Place): File = File(cacheDir, "station_${cacheKey(place)}.json")

    private fun readValidStationCache(place: Place): StationLookupCache? {
        val file = stationFile(place)
        if (!file.exists()) return null
        val cached = runCatching { json.decodeFromString<StationLookupCache>(file.readText()) }.getOrNull() ?: return null
        if (place.distanceKm(cached.placeLat, cached.placeLon) > Observations.STATION_MOVE_KM) return null
        val maxAge = if (cached.station != null) STATION_FOUND_TTL_MS else STATION_MISSING_TTL_MS
        if (System.currentTimeMillis() - cached.checkedAt > maxAge) return null
        return cached
    }

    private fun discoverStation(place: Place): ObservationStation? {
        val body = httpGet(
            Observations.bboxUrl(place.latitude, place.longitude),
            connectTimeoutMs = OBS_CONNECT_TIMEOUT_MS,
            readTimeoutMs = OBS_READ_TIMEOUT_MS,
        )
        val station = Observations.nearestStation(place, Observations.parse(body, json))
        val entry = StationLookupCache(
            station = station,
            placeLat = place.latitude,
            placeLon = place.longitude,
            checkedAt = System.currentTimeMillis(),
        )
        runCatching { stationFile(place).writeText(json.encodeToString(entry)) }
        return station
    }

    private fun fetchObservations(place: Place): Pair<List<MetarDto>, String>? {
        val cachedLookup = readValidStationCache(place)
        var station = if (cachedLookup != null) cachedLookup.station else discoverStation(place)
        if (station == null) return null

        var body = httpGet(
            Observations.stationUrl(station.icaoId),
            connectTimeoutMs = OBS_CONNECT_TIMEOUT_MS,
            readTimeoutMs = OBS_READ_TIMEOUT_MS,
        )
        var metars = Observations.parse(body, json)
        // If a cached station stopped reporting, look for another nearby station once.
        if (metars.isEmpty() && cachedLookup != null) {
            val rediscovered = discoverStation(place) ?: return null
            if (rediscovered.icaoId != station.icaoId) {
                station = rediscovered
                body = httpGet(
                    Observations.stationUrl(station.icaoId),
                    connectTimeoutMs = OBS_CONNECT_TIMEOUT_MS,
                    readTimeoutMs = OBS_READ_TIMEOUT_MS,
                )
                metars = Observations.parse(body, json)
            }
        }
        if (metars.isEmpty()) return null
        runCatching { obsFile(place).writeText(body) }
        return metars to station.label
    }

    private fun applyCachedObservations(place: Place, weather: Weather): Weather {
        val lookup = readValidStationCache(place) ?: return weather
        val station = lookup.station ?: return weather
        val file = obsFile(place)
        if (!file.exists()) return weather
        val metars = runCatching { Observations.parse(file.readText(), json) }.getOrNull().orEmpty()
        return Observations.merge(weather, metars, station.label)
    }

    private companion object {
        const val OBS_CONNECT_TIMEOUT_MS = 4_000
        const val OBS_READ_TIMEOUT_MS = 7_000
        const val STATION_FOUND_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val STATION_MISSING_TTL_MS = 24L * 60 * 60 * 1000
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
            weatherCode = c.weatherCode.toInt(),
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

        val today = currentTime.toLocalDate()
        val d = dto.daily
        val allDays = d.time.indices.mapNotNull { i ->
            val high = d.temperatureMax.getOrNull(i) ?: return@mapNotNull null
            val low = d.temperatureMin.getOrNull(i) ?: return@mapNotNull null
            val date = LocalDate.parse(d.time[i])
            val sunrise = d.sunrise.getOrNull(i)?.let(LocalDateTime::parse)
            val sunset = d.sunset.getOrNull(i)?.let(LocalDateTime::parse)
            // The icon follows the daylight hours; the service's whole-day code is the fallback.
            val code = DayWeather.code(date, sunrise, sunset, hours, fallback = d.weatherCode.getOrNull(i)?.toInt() ?: 3)
            DayForecast(
                date = date,
                condition = Condition.fromWmo(code),
                weatherCode = code,
                high = high,
                low = low,
                precipitationChance = d.precipitationProbabilityMax.getOrNull(i)?.toInt() ?: 0,
                sunrise = sunrise,
                sunset = sunset,
                uvMax = d.uvIndexMax.getOrNull(i),
            )
        }
        val pastDaily = allDays.filter { it.date.isBefore(today) }
        val daily = allDays.filterNot { it.date.isBefore(today) }

        return Weather(current, hourly, hours, daily, units, fetchedAt, dto.utcOffsetSeconds, pastDaily = pastDaily)
    }
}
