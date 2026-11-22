package com.breeze.weather.ui

import com.breeze.weather.data.GridArea
import kotlin.math.floor
import kotlin.math.roundToInt

/** Colour stops (value → ARGB), blended linearly between stops and held beyond the ends. */
class ColorRamp(vararg stops: Pair<Float, Long>) {
    val stops: List<Pair<Float, Int>> = stops.map { it.first to it.second.toInt() }.sortedBy { it.first }

    val min: Float get() = stops.first().first
    val max: Float get() = stops.last().first

    fun at(v: Float): Int {
        if (v.isNaN()) return 0
        if (v <= stops.first().first) return stops.first().second
        if (v >= stops.last().first) return stops.last().second
        val i = stops.indexOfLast { it.first <= v }
        val (v0, c0) = stops[i]
        val (v1, c1) = stops[i + 1]
        return blend(c0, c1, (v - v0) / (v1 - v0))
    }
}

/** Mixes two ARGB colours, channel by channel. */
internal fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(shift: Int): Int {
        val x = (a ushr shift) and 0xFF
        val y = (b ushr shift) and 0xFF
        return (x + (y - x) * t).roundToInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** [top] drawn at [opacity] over the opaque colour [under], as the map shows it. */
internal fun over(top: Int, under: Int, opacity: Float = 1f): Int {
    val alpha = ((top ushr 24) and 0xFF) / 255f * opacity
    return blend(under or (0xFF shl 24), top or (0xFF shl 24), alpha)
}

object MapRamps {
    /** °C: purple through blue at freezing, green when mild, to red and maroon in great heat. */
    val TEMPERATURE = ColorRamp(
        -40f to 0xFF5B2A86, -25f to 0xFF3F4FB0, -10f to 0xFF2E7FE0, 0f to 0xFF5EC4F2,
        8f to 0xFF55C2A8, 15f to 0xFF9BD067, 22f to 0xFFF6E35A, 30f to 0xFFF9A23B,
        38f to 0xFFE2412F, 46f to 0xFF8E1A4B,
    )

    /** km/h: pale when calm, through green and yellow to red and purple in a storm. */
    val WIND = ColorRamp(
        0f to 0xFFDCEEF7, 10f to 0xFF8ED3EE, 20f to 0xFF5CC6C0, 30f to 0xFF8CCB6E,
        45f to 0xFFF2DF5B, 60f to 0xFFF6A13D, 80f to 0xFFE45545, 105f to 0xFFB04AB8,
        130f to 0xFF6A2393,
    )

    /** Cloud cover %: clear sky leaves the map alone, overcast greys it over. */
    val CLOUDS = ColorRamp(
        0f to 0x00566078, 15f to 0x00566078, 50f to 0x60566078, 100f to 0x9E566078,
    )

    /**
     * RainViewer's "Universal Blue" scheme as it looks for light to extreme rain, for the
     * legend only (the radar tiles come already coloured).
     */
    val RADAR = ColorRamp(
        0f to 0xFF9BE1F8, 1f to 0xFF3AA6EE, 2f to 0xFF1F5FC9, 3f to 0xFFFCE94F,
        4f to 0xFFFB9B29, 5f to 0xFFE5281F, 6f to 0xFFD62DB9,
    )
}

/** An ARGB image to lay over a [GridArea] on the map. */
class FieldImage(val width: Int, val height: Int, val pixels: IntArray)

/**
 * Paints [values] (one per grid point, row by row from the north-west) over [area], [width]
 * pixels wide and as tall as the area looks on the map. Values between points are smoothed with
 * Catmull-Rom splines so the grid doesn't show; missing values leave holes.
 */
fun renderField(area: GridArea, values: FloatArray, width: Int, ramp: ColorRamp): FieldImage {
    val height = (width * area.aspect).roundToInt().coerceIn(8, 2048)
    val cols = area.cols
    val rows = area.rows
    fun v(c: Int, r: Int): Float = values[r.coerceIn(0, rows - 1) * cols + c.coerceIn(0, cols - 1)]
    fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float =
        p1 + 0.5f * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)))

    val pixels = IntArray(width * height)
    val row = FloatArray(4)
    for (py in 0 until height) {
        val gy = py.toFloat() / (height - 1) * (rows - 1)
        val r = floor(gy).toInt().coerceAtMost(rows - 2)
        val ty = gy - r
        for (px in 0 until width) {
            val gx = px.toFloat() / (width - 1) * (cols - 1)
            val c = floor(gx).toInt().coerceAtMost(cols - 2)
            val tx = gx - c
            for (k in 0..3) row[k] = cubic(v(c - 1, r - 1 + k), v(c, r - 1 + k), v(c + 1, r - 1 + k), v(c + 2, r - 1 + k), tx)
            pixels[py * width + px] = ramp.at(cubic(row[0], row[1], row[2], row[3], ty))
        }
    }
    return FieldImage(width, height, pixels)
}

/**
 * Wind arrows for the map as GeoJSON points at every grid point, each with the bearing the wind
 * blows towards (`rot`, degrees) and its speed (`spd`, km/h).
 */
fun windArrowsGeoJson(area: GridArea, speed: FloatArray, direction: FloatArray): String = buildString {
    append("""{"type":"FeatureCollection","features":[""")
    var first = true
    for (r in 0 until area.rows) for (c in 0 until area.cols) {
        val i = r * area.cols + c
        if (speed[i].isNaN() || direction[i].isNaN()) continue
        if (!first) append(',')
        first = false
        val rot = (direction[i] + 180f) % 360f
        append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
        append(area.lon(c)).append(',').append(area.lat(r))
        append("""]},"properties":{"rot":""").append(rot).append(""","spd":""").append(speed[i]).append("}}")
    }
    append("]}")
}

/**
 * Temperature labels for the map as GeoJSON points at every grid point, each with its rounded
 * value as text (`t`), such as "12°", in °F when [fahrenheit].
 */
fun temperatureLabelsGeoJson(area: GridArea, celsius: FloatArray, fahrenheit: Boolean): String = buildString {
    append("""{"type":"FeatureCollection","features":[""")
    var first = true
    for (r in 0 until area.rows) for (c in 0 until area.cols) {
        val v = celsius[r * area.cols + c]
        if (v.isNaN()) continue
        if (!first) append(',')
        first = false
        val shown = (if (fahrenheit) v * 1.8f + 32f else v).roundToInt()
        append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
        append(area.lon(c)).append(',').append(area.lat(r))
        append("""]},"properties":{"t":"""").append(shown).append("°\"}}")
    }
    append("]}")
}
