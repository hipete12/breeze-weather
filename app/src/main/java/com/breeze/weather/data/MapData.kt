package com.breeze.weather.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

// ---------- Precipitation radar (RainViewer) ----------

/** One radar image of the recent past: its time (Unix seconds) and its map-tile URL template. */
data class RadarFrame(val time: Long, val tiles: String)

/**
 * RainViewer's free radar (personal use, no key): composite radar for the past two hours in
 * 10-minute steps, as map tiles up to zoom 7 in its "Universal Blue" colours.
 */
object RainViewer {
    const val URL = "https://api.rainviewer.com/public/weather-maps.json"

    /** Tiles stop at zoom 7; the map stretches them beyond that. */
    const val MAX_ZOOM = 7f
    const val TILE_SIZE = 512

    /**
     * Free use allows 100 tile requests a minute, and every frame loads its own tiles, so the
     * animation steps 20 minutes at a time: seven frames for the two hours, ending at the latest.
     */
    const val FRAME_STEP = 2

    fun parse(body: String, json: Json): List<RadarFrame> {
        val dto = json.decodeFromString<RainViewerDto>(body)
        return dto.radar.past
            .sortedByDescending { it.time }
            .filterIndexed { i, _ -> i % FRAME_STEP == 0 }
            .reversed()
            .map { RadarFrame(it.time, tileUrl(dto.host, it.path)) }
    }

    /** Smoothed (`1`), without separate snow colours (`0`), colour scheme 2 (the only free one). */
    fun tileUrl(host: String, path: String): String =
        "${host.trimEnd('/')}$path/$TILE_SIZE/{z}/{x}/{y}/2/1_0.png"
}

@Serializable
internal data class RainViewerDto(val host: String, val radar: RainViewerRadarDto = RainViewerRadarDto())

@Serializable
internal data class RainViewerRadarDto(val past: List<RainViewerFrameDto> = emptyList())

@Serializable
internal data class RainViewerFrameDto(val time: Long, val path: String)

// ---------- Forecast fields over a map area (Open-Meteo) ----------

/** Web Mercator: the map's vertical coordinate for a latitude, and back. */
object Mercator {
    /** The forecast grid stays inside this; Mercator stretches the poles out of all proportion. */
    const val MAX_LAT = 80.0

    fun y(lat: Double): Double = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
    fun lat(y: Double): Double = Math.toDegrees(atan(sinh(y)))
}

/**
 * Sample points over a map area: columns evenly spaced in longitude and rows evenly spaced in
 * Mercator y (not latitude), so an image drawn from the grid lies straight onto the map. Row 0
 * is the north edge. Longitudes may run past ±180 when the area crosses the date line.
 */
data class GridArea(
    val west: Double,
    val east: Double,
    val north: Double,
    val south: Double,
    val cols: Int,
    val rows: Int,
) {
    fun lon(col: Int): Double = west + (east - west) * col / (cols - 1)

    fun lat(row: Int): Double {
        if (row == 0) return north
        if (row == rows - 1) return south
        val yn = Mercator.y(north)
        return Mercator.lat(yn + (Mercator.y(south) - yn) * row / (rows - 1))
    }

    /** Height over width of the area as the map draws it. */
    val aspect: Double get() = (Mercator.y(north) - Mercator.y(south)) / Math.toRadians(east - west)

    /** Whether a visible map area lies wholly inside this grid. */
    fun covers(w: Double, e: Double, n: Double, s: Double): Boolean =
        w >= west && e <= east && n <= north && s >= south

    /** Every point, row by row from the north-west corner: (latitude, longitude). */
    fun points(): List<Pair<Double, Double>> =
        (0 until rows).flatMap { r -> (0 until cols).map { c -> lat(r) to lon(c) } }

    companion object {
        /**
         * A grid over the visible map area grown by [grow] of its size on every side, so small
         * pans don't need new data, with [cols] points across and rows to match the shape.
         */
        fun around(
            west: Double, east: Double, north: Double, south: Double,
            grow: Double = 0.25, cols: Int = 9, maxRows: Int = 16,
        ): GridArea {
            val lonSpan = (east - west).coerceIn(0.2, 360.0) * (1 + 2 * grow)
            val cx = (west + east) / 2
            val half = min(lonSpan, 360.0) / 2
            val yLimit = Mercator.y(Mercator.MAX_LAT)
            val yn = Mercator.y(north.coerceIn(-Mercator.MAX_LAT, Mercator.MAX_LAT))
            val ys = Mercator.y(south.coerceIn(-Mercator.MAX_LAT, Mercator.MAX_LAT))
            val ySpan = max(yn - ys, 0.002)
            val n = Mercator.lat(min(yn + ySpan * grow, yLimit))
            val s = Mercator.lat(max(ys - ySpan * grow, -yLimit))
            val aspect = (Mercator.y(n) - Mercator.y(s)) / Math.toRadians(2 * half)
            val rows = (cols * aspect).roundToInt().coerceIn(4, maxRows)
            return GridArea(cx - half, cx + half, n, s, cols, rows)
        }
    }
}

/**
 * Forecast values at every point of [area] for the coming hours. Each list holds one array per
 * hour in [times] (Unix seconds), indexed `row * cols + col`; NaN where a value is missing.
 * Temperatures are in °C and wind speeds in km/h whatever the app's units.
 */
class MapField(
    val area: GridArea,
    val times: List<Long>,
    val temperature: List<FloatArray>,
    val windSpeed: List<FloatArray>,
    val windDirection: List<FloatArray>,
    val cloudCover: List<FloatArray>,
) {
    companion object {
        /** Hours of forecast, starting with the current one. */
        const val HOURS = 13

        fun url(area: GridArea): String {
            val pts = area.points()
            fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v)
            return buildString {
                append("https://api.open-meteo.com/v1/forecast")
                append("?latitude=").append(pts.joinToString(",") { fmt(it.first) })
                // Open-Meteo wants -180..180; the grid itself may run past the date line.
                append("&longitude=").append(pts.joinToString(",") { fmt(((it.second + 540) % 360) - 180) })
                append("&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,cloud_cover")
                append("&forecast_hours=").append(HOURS)
                append("&timeformat=unixtime&timezone=GMT")
            }
        }

        /** Open-Meteo answers several points with a JSON array, in the order they were asked. */
        fun parse(body: String, area: GridArea, json: Json): MapField {
            val points: List<GridPointDto> =
                if (body.trimStart().startsWith("[")) json.decodeFromString(body)
                else listOf(json.decodeFromString(body))
            require(points.size == area.cols * area.rows) {
                "Expected ${area.cols * area.rows} points, got ${points.size}"
            }
            val times = points.first().hourly.time
            fun series(pick: (GridHourlyDto) -> List<Double?>): List<FloatArray> = times.indices.map { h ->
                FloatArray(points.size) { i -> pick(points[i].hourly).getOrNull(h)?.toFloat() ?: Float.NaN }
            }
            return MapField(
                area = area,
                times = times,
                temperature = series { it.temperature },
                windSpeed = series { it.windSpeed },
                windDirection = series { it.windDirection },
                cloudCover = series { it.cloudCover },
            )
        }
    }
}

@Serializable
internal data class GridPointDto(val hourly: GridHourlyDto)

@Serializable
internal data class GridHourlyDto(
    val time: List<Long>,
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed: List<Double?> = emptyList(),
    @SerialName("wind_direction_10m") val windDirection: List<Double?> = emptyList(),
    @SerialName("cloud_cover") val cloudCover: List<Double?> = emptyList(),
)
