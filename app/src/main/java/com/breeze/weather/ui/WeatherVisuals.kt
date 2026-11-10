package com.breeze.weather.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FilterDrama
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.breeze.weather.data.Condition
import com.breeze.weather.data.Units
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Icon for a condition. Pass the precise WMO [code] so rain and drizzle can show their intensity
 * (the number of rain streaks under the cloud).
 */
fun Condition.icon(isDay: Boolean, code: Int? = null): ImageVector = when (this) {
    Condition.CLEAR -> if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.Bedtime
    Condition.PARTLY_CLOUDY -> if (isDay) Icons.Rounded.FilterDrama else Icons.Rounded.NightsStay
    Condition.CLOUDY -> Icons.Rounded.Cloud
    Condition.FOG -> FogIcon
    Condition.DRIZZLE, Condition.RAIN -> rainIcon(rainLevel(this, code))
    Condition.SNOW -> Icons.Rounded.AcUnit
    Condition.STORM -> Icons.Rounded.Thunderstorm
}

/** Rain intensity 1–4 (streak count) for a WMO code: drizzle 1–2, light rain 2, rain 3, heavy rain 4. */
fun rainLevel(condition: Condition, code: Int?): Int = when (code) {
    51, 53, 56 -> 1
    55, 57 -> 2
    61, 66, 80 -> 2
    63, 67, 81 -> 3
    65, 82 -> 4
    else -> if (condition == Condition.DRIZZLE) 1 else 3
}

private fun ImageVector.Builder.cloud() = path(fill = SolidColor(Color.White)) {
    moveTo(7f, 13.5f)
    horizontalLineTo(17f)
    arcToRelative(3.5f, 3.5f, 0f, false, false, 0.4f, -6.98f)
    arcToRelative(5.6f, 5.6f, 0f, false, false, -10.7f, -0.9f)
    arcToRelative(3.95f, 3.95f, 0f, false, false, 0.3f, 7.88f)
    close()
}

private val rainIcons = arrayOfNulls<ImageVector>(4)

/** Cloud with [level] (1–4) diagonal rain streaks under it. */
fun rainIcon(level: Int): ImageVector {
    val n = level.coerceIn(1, 4)
    rainIcons[n - 1]?.let { return it }
    val spacing = 3.6f
    val firstX = 12f - spacing * (n - 1) / 2
    return ImageVector.Builder("Rain$n", 24.dp, 24.dp, 24f, 24f).apply {
        cloud()
        path(stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            repeat(n) { i ->
                // Each streak leans down-left; centred under the cloud.
                val x = firstX + i * spacing
                moveTo(x + 1.4f, 16f)
                lineTo(x - 1.4f, 21f)
            }
        }
    }.build().also { rainIcons[n - 1] = it }
}

/** Cloud with mist lines below; the stock icon sets have no fog glyph that doesn't read as a menu. */
val FogIcon: ImageVector by lazy {
    ImageVector.Builder("Fog", 24.dp, 24.dp, 24f, 24f).apply {
        cloud()
        path(stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(4.5f, 17f); horizontalLineTo(19.5f)
            moveTo(7f, 20.5f); horizontalLineTo(17f)
        }
    }.build()
}

fun Condition.iconTint(isDay: Boolean): Color = when (this) {
    Condition.CLEAR -> if (isDay) Color(0xFFFFD54F) else Color(0xFFE3E8F5)
    Condition.PARTLY_CLOUDY -> if (isDay) Color(0xFFFFE08A) else Color(0xFFE3E8F5)
    Condition.RAIN, Condition.DRIZZLE -> Color(0xFF9AD0FF)
    Condition.STORM -> Color(0xFFFFCC66)
    else -> Color.White
}

/** Top-to-bottom background gradient for a condition. Tuned to keep white text legible. */
fun Condition.gradient(isDay: Boolean): List<Color> {
    val (top, bottom) = when (this) {
        Condition.CLEAR -> if (isDay) 0xFF1F6ED4 to 0xFF58A3EC else 0xFF0B1026 to 0xFF232D5C
        Condition.PARTLY_CLOUDY -> if (isDay) 0xFF336AB0 to 0xFF6F9BCB else 0xFF131A33 to 0xFF2C3659
        Condition.CLOUDY -> if (isDay) 0xFF4B5A70 to 0xFF77869B else 0xFF1C212B to 0xFF3A414F
        Condition.FOG -> if (isDay) 0xFF56606E to 0xFF808A97 else 0xFF23272E to 0xFF434953
        Condition.DRIZZLE, Condition.RAIN -> if (isDay) 0xFF2F4058 to 0xFF536A88 else 0xFF101823 to 0xFF243449
        Condition.SNOW -> if (isDay) 0xFF55789E to 0xFF88A4C7 else 0xFF1B2839 to 0xFF394B64
        Condition.STORM -> if (isDay) 0xFF2A2D44 to 0xFF4A4E6E else 0xFF14151E to 0xFF2B2C40
    }
    return listOf(Color(top), Color(bottom))
}

fun Double.deg(): String = "${roundToInt()}°"

fun Units.symbol(): String = if (this == Units.IMPERIAL) "°F" else "°C"

fun windCompass(degrees: Int): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[(((degrees % 360) + 360) % 360 + 22) / 45 % 8]
}

fun uvLabel(uv: Double): String = when {
    uv < 3 -> "Low"
    uv < 6 -> "Moderate"
    uv < 8 -> "High"
    uv < 11 -> "Very high"
    else -> "Extreme"
}

fun formatHour(time: LocalDateTime, use24h: Boolean): String =
    if (use24h) time.format(DateTimeFormatter.ofPattern("HH:mm"))
    else time.format(DateTimeFormatter.ofPattern("h a", Locale.getDefault()))

fun formatClock(time: LocalDateTime, use24h: Boolean): String =
    time.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()))

fun formatDay(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}
