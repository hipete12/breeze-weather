package com.breeze.weather.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.FilterDrama
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.breeze.weather.data.Condition
import com.breeze.weather.data.Units
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

fun Condition.icon(isDay: Boolean): ImageVector = when (this) {
    Condition.CLEAR -> if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.Bedtime
    Condition.PARTLY_CLOUDY -> if (isDay) Icons.Rounded.FilterDrama else Icons.Rounded.NightsStay
    Condition.CLOUDY -> Icons.Rounded.Cloud
    Condition.FOG -> Icons.Rounded.Dehaze
    Condition.DRIZZLE -> Icons.Rounded.Grain
    Condition.RAIN -> Icons.Rounded.WaterDrop
    Condition.SNOW -> Icons.Rounded.AcUnit
    Condition.STORM -> Icons.Rounded.Thunderstorm
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
