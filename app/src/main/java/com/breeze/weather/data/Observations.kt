package com.breeze.weather.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

/**
 * Nearby weather-station observations (METAR from the Aviation Weather Center, free and keyless)
 * used to replace the past hours of today with what actually happened. Pure parsing and merging
 * logic so everything here can be unit-tested without a network or Android framework.
 */
object Observations {

    /** Ignore stations farther than this so distant airports don't misrepresent local weather. */
    const val MAX_STATION_KM = 60.0

    /** Re-discover the nearest station when a moving place (e.g. current location) shifts past this. */
    const val STATION_MOVE_KM = 5.0

    private const val BASE_URL = "https://aviationweather.gov/api/data/metar"
    private const val KT_TO_KMH = 1.852
    private const val KT_TO_MPH = 1.15077945
    private const val INCH_TO_MM = 25.4

    /** Query for recently reporting stations within roughly [MAX_STATION_KM] of ([lat], [lon]). */
    fun bboxUrl(lat: Double, lon: Double): String {
        val dLat = 0.55
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val dLon = (dLat / cosLat).coerceAtMost(5.0)
        val minLat = (lat - dLat).coerceAtLeast(-90.0)
        val maxLat = (lat + dLat).coerceAtMost(90.0)
        val minLon = (lon - dLon).coerceAtLeast(-180.0)
        val maxLon = (lon + dLon).coerceAtMost(180.0)
        return String.format(
            Locale.US,
            "%s?bbox=%.4f,%.4f,%.4f,%.4f&hours=3&format=json",
            BASE_URL, minLat, minLon, maxLat, maxLon,
        )
    }

    /** Query for the last [hours] of METAR/SPECI observations from one station. */
    fun stationUrl(icaoId: String, hours: Int = 26): String {
        val id = URLEncoder.encode(icaoId.trim(), "UTF-8")
        return "$BASE_URL?ids=$id&hours=$hours&format=json"
    }

    /** Parses a METAR JSON array; empty/204 responses yield an empty list. */
    internal fun parse(body: String, json: Json): List<MetarDto> {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        return json.decodeFromString(trimmed)
    }

    /** Picks the closest actively reporting station with temperature data within [MAX_STATION_KM]. */
    internal fun nearestStation(place: Place, metars: List<MetarDto>): ObservationStation? {
        return metars
            .asSequence()
            .filter { !it.icaoId.isNullOrBlank() && it.lat != null && it.lon != null && it.temp != null }
            .groupBy { it.icaoId!! }
            .values
            .map { group -> group.first() }
            .map { m ->
                val lat = m.lat!!
                val lon = m.lon!!
                val label = stationLabel(m.icaoId!!, m.name)
                ObservationStation(m.icaoId, label, lat, lon) to place.distanceKm(lat, lon)
            }
            .filter { (_, dist) -> dist <= MAX_STATION_KM }
            .minByOrNull { (_, dist) -> dist }
            ?.first
    }

    internal fun stationLabel(icaoId: String, rawName: String?): String {
        val shortName = rawName?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
        return if (shortName != null && !shortName.equals(icaoId, ignoreCase = true)) {
            "$shortName ($icaoId)"
        } else {
            icaoId
        }
    }

    /**
     * Replaces today's past hours (up through the current hour) in [weather] with observed values
     * from [metars], and updates today's high, low and daylight weather code to match.
     * Returns [weather] unchanged if no observations cover today's past hours.
     */
    internal fun merge(weather: Weather, metars: List<MetarDto>, stationLabel: String?): Weather {
        if (metars.isEmpty()) return weather
        val zone = ZoneOffset.ofTotalSeconds(weather.utcOffsetSeconds)
        val today = weather.current.time.toLocalDate()
        val currentHour = weather.current.time.truncatedTo(ChronoUnit.HOURS)

        // Group each observation into the nearest top-of-hour bucket (handles US :53 reports -> next :00).
        val byHour = HashMap<LocalDateTime, MutableList<Pair<LocalDateTime, MetarDto>>>()
        for (m in metars) {
            val epoch = m.obsTime ?: continue
            val local = Instant.ofEpochSecond(epoch).atOffset(zone).toLocalDateTime()
            val bucket = local.plusMinutes(30).truncatedTo(ChronoUnit.HOURS)
            if (bucket.toLocalDate() == today && !bucket.isAfter(currentHour)) {
                byHour.getOrPut(bucket) { mutableListOf() }.add(local to m)
            }
        }
        if (byHour.isEmpty()) return weather

        var anyMerged = false
        val mergedHours = weather.hours.map { hour ->
            val group = byHour[hour.time] ?: return@map hour
            val merged = mergeHour(hour, group, weather.units)
            if (merged !== hour) anyMerged = true
            merged
        }
        if (!anyMerged) return weather

        val mergedHourly = mergedHours.filterNot { it.time.isBefore(currentHour) }.take(25)
        val mergedDaily = weather.daily.map { day ->
            if (day.date != today) day
            else {
                val todayHours = mergedHours.filter { it.time.toLocalDate() == today }
                val high = todayHours.maxOfOrNull { it.temperature } ?: day.high
                val low = todayHours.minOfOrNull { it.temperature } ?: day.low
                val code = DayWeather.code(today, day.sunrise, day.sunset, mergedHours, fallback = day.weatherCode)
                day.copy(high = high, low = low, weatherCode = code, condition = Condition.fromWmo(code))
            }
        }

        return weather.copy(
            hourly = mergedHourly,
            hours = mergedHours,
            daily = mergedDaily,
            observationStation = stationLabel,
        )
    }

    private fun mergeHour(
        hour: HourForecast,
        group: List<Pair<LocalDateTime, MetarDto>>,
        units: Units,
    ): HourForecast {
        // Order closest to the top of the hour first, breaking ties with the newer observation.
        val ordered = group.sortedWith(
            compareBy<Pair<LocalDateTime, MetarDto>> { (t, _) -> abs(Duration.between(hour.time, t).seconds) }
                .thenByDescending { (_, m) -> m.obsTime ?: 0L }
        ).map { it.second }

        val tempObs = ordered.firstOrNull { it.temp != null }
        val tempC = tempObs?.temp
        val dewpC = tempObs?.dewp ?: ordered.firstNotNullOfOrNull { it.dewp }

        val windObs = ordered.firstOrNull { it.wspd != null }
        val windKt = windObs?.wspd
        val windDir = windObs?.wdir?.let(::parseDirection) ?: ordered.firstNotNullOfOrNull { it.wdir?.let(::parseDirection) }
        val gustKt = ordered.mapNotNull { it.wgst }.maxOrNull()

        val cloudCover = ordered.firstNotNullOfOrNull { cloudCoverPercent(it.clouds) }
        val weatherCode = pickWeatherCode(ordered)
        val precipInches = ordered.mapNotNull { it.precip }.maxOrNull()

        if (tempC == null && weatherCode == null) return hour

        val humidity = if (tempC != null && dewpC != null) relativeHumidity(tempC, dewpC) else null
        val temp = tempC?.let { convertTemp(it, units) }
        val windSpeed = windKt?.let { convertWind(it, units) }
        val windGusts = gustKt?.let { convertWind(it, units) }
        val windKmhForFeels = windKt?.times(KT_TO_KMH)
            ?: hour.windSpeed?.let { units.toKmh(it) }
            ?: 0.0
        val feelsLike = if (tempC != null && humidity != null) {
            convertTemp(apparentTemperatureC(tempC, humidity, windKmhForFeels), units)
        } else null
        val precip = precipInches?.let { if (units == Units.IMPERIAL) it else it * INCH_TO_MM }

        val code = weatherCode ?: hour.weatherCode
        return hour.copy(
            temperature = temp ?: hour.temperature,
            weatherCode = code,
            condition = Condition.fromWmo(code),
            feelsLike = feelsLike ?: hour.feelsLike,
            humidity = humidity ?: hour.humidity,
            precipitation = precip ?: hour.precipitation,
            windSpeed = windSpeed ?: hour.windSpeed,
            windGusts = if (windSpeed != null) windGusts else hour.windGusts,
            windDirection = windDir ?: hour.windDirection,
            cloudCover = cloudCover ?: hour.cloudCover,
            observed = true,
            observedPrecipitation = precip != null,
        )
    }

    /**
     * Picks the weather code for an hour from its observations: any significant weather (fog,
     * precipitation, storms — WMO >= 45) wins with the most severe code; otherwise the observation
     * closest to the hour sets the sky cover (0..3).
     */
    private fun pickWeatherCode(ordered: List<MetarDto>): Int? {
        val codes = ordered.mapNotNull { wmoCode(it.wxString, it.clouds) }
        val significant = codes.filter { it >= 45 }.maxOrNull()
        return significant ?: codes.firstOrNull()
    }

    internal fun parseDirection(element: JsonElement): Double? =
        (element as? JsonPrimitive)?.doubleOrNull

    /** Magnus–Tetens relative humidity (%) from temperature and dew point in °C. */
    internal fun relativeHumidity(tempC: Double, dewpC: Double): Double {
        val es = exp(17.625 * tempC / (243.04 + tempC))
        val e = exp(17.625 * dewpC / (243.04 + dewpC))
        return (100.0 * e / es).coerceIn(0.0, 100.0)
    }

    /** Steadman shade apparent temperature (°C) from temperature (°C), humidity (%) and wind (km/h). */
    internal fun apparentTemperatureC(tempC: Double, humidity: Double, windKmh: Double): Double {
        val vaporHpa = (humidity / 100.0) * 6.105 * exp(17.27 * tempC / (237.7 + tempC))
        val windMs = windKmh / 3.6
        return tempC + 0.33 * vaporHpa - 0.70 * windMs - 4.00
    }

    internal fun convertTemp(tempC: Double, units: Units): Double =
        if (units == Units.IMPERIAL) tempC * 9.0 / 5.0 + 32.0 else tempC

    internal fun convertWind(knots: Double, units: Units): Double =
        knots * if (units == Units.IMPERIAL) KT_TO_MPH else KT_TO_KMH

    /** Highest reported cloud layer coverage as a percentage (0–100), or null if not reported. */
    internal fun cloudCoverPercent(clouds: List<MetarCloudDto>?): Double? {
        if (clouds.isNullOrEmpty()) return null
        return clouds.mapNotNull { layer ->
            when (layer.cover?.trim()?.uppercase(Locale.US)) {
                "CLR", "SKC", "NSC", "NCD", "CAVOK" -> 0.0
                "FEW" -> 20.0
                "SCT" -> 45.0
                "BKN" -> 75.0
                "OVC", "VV" -> 100.0
                else -> null
            }
        }.maxOrNull()
    }

    /** Maps METAR present weather (`wxString`) and sky cover (`clouds`) to a Breeze WMO code. */
    internal fun wmoCode(wxString: String?, clouds: List<MetarCloudDto>?): Int? {
        val wxCode = wxString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.split(Regex("\\s+"))
            ?.mapNotNull(::wxTokenToWmo)
            ?.maxOrNull()
        if (wxCode != null) return wxCode

        val cover = cloudCoverPercent(clouds) ?: return null
        return when {
            cover <= 5.0 -> 0
            cover <= 25.0 -> 1
            cover <= 55.0 -> 2
            else -> 3
        }
    }

    private fun wxTokenToWmo(raw: String): Int? {
        val token = raw.uppercase(Locale.US)
        val light = token.startsWith("-")
        val heavy = token.startsWith("+")
        val body = token.removePrefix("-").removePrefix("+").removePrefix("VC")
        return when {
            "TS" in body -> when {
                "GR" in body || "GS" in body -> if (heavy) 99 else 96
                else -> 95
            }
            "FZ" in body && "RA" in body -> if (heavy) 67 else 66
            "FZ" in body && "DZ" in body -> if (heavy) 57 else 56
            "FZ" in body && "FG" in body -> 48
            "SH" in body && "SN" in body -> if (heavy) 86 else 85
            "SH" in body && "RA" in body -> when {
                light -> 80
                heavy -> 82
                else -> 81
            }
            "SN" in body || "PL" in body -> when {
                light -> 71
                heavy -> 75
                else -> 73
            }
            "SG" in body -> 77
            "RA" in body -> when {
                light -> 61
                heavy -> 65
                else -> 63
            }
            "DZ" in body -> when {
                light -> 51
                heavy -> 55
                else -> 53
            }
            "GR" in body || "GS" in body -> 82
            "UP" in body -> 61
            "FG" in body || "BR" in body -> 45
            else -> null
        }
    }
}

@Serializable
internal data class ObservationStation(
    val icaoId: String,
    val label: String,
    val lat: Double,
    val lon: Double,
)

@Serializable
internal data class StationLookupCache(
    val station: ObservationStation? = null,
    val placeLat: Double,
    val placeLon: Double,
    val checkedAt: Long,
)

@Serializable
internal data class MetarDto(
    val icaoId: String? = null,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val obsTime: Long? = null,
    val temp: Double? = null,
    val dewp: Double? = null,
    val wdir: JsonElement? = null,
    val wspd: Double? = null,
    val wgst: Double? = null,
    val precip: Double? = null,
    val wxString: String? = null,
    val clouds: List<MetarCloudDto>? = null,
)

@Serializable
internal data class MetarCloudDto(
    val cover: String? = null,
    val base: Int? = null,
)
