package com.breeze.weather.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.breeze.weather.data.Units
import com.breeze.weather.ui.tempColor

/** Temperature colours for widget text, from cold blue through green and yellow to hot red. */
object TempColors {

    /** For light text on a dark or sky background: the app's own temperature colours. */
    fun onDark(t: Double, units: Units): Int = tempColor(t, units).toArgb()

    /** Deeper shades of the same colours, readable on a light background. */
    fun onLight(t: Double, units: Units): Int {
        val c = if (units == Units.IMPERIAL) (t - 32) * 5 / 9 else t
        if (c <= ON_LIGHT.first().first) return ON_LIGHT.first().second.toArgb()
        if (c >= ON_LIGHT.last().first) return ON_LIGHT.last().second.toArgb()
        val i = ON_LIGHT.indexOfFirst { it.first > c }
        val (t0, c0) = ON_LIGHT[i - 1]
        val (t1, c1) = ON_LIGHT[i]
        return lerp(c0, c1, ((c - t0) / (t1 - t0)).toFloat()).toArgb()
    }

    /** Same temperatures (°C) as the app's scale; each at least 4.5:1 against the Light theme. */
    private val ON_LIGHT = listOf(
        -10.0 to Color(0xFF4F46E5),
        0.0 to Color(0xFF0369A1),
        10.0 to Color(0xFF047857),
        20.0 to Color(0xFFA16207),
        28.0 to Color(0xFFC2410C),
        36.0 to Color(0xFFB91C1C),
    )
}
