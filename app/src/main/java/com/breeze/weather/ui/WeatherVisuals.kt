package com.breeze.weather.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FilterDrama
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
    Condition.DRIZZLE, Condition.RAIN -> rainIcon(rainStyle(this, code))
    Condition.SNOW -> Icons.Rounded.AcUnit
    Condition.STORM -> Icons.Rounded.Thunderstorm
}

/** How rain is drawn under the cloud: drizzle as small drops, rain as streaks; [count] grows with intensity. */
data class RainStyle(val drizzle: Boolean, val count: Int)

/** Drizzle 2 drops; light rain 3 streaks, rain 4, heavy rain 5. */
fun rainStyle(condition: Condition, code: Int?): RainStyle = when (code) {
    51, 53, 55, 56, 57 -> RainStyle(drizzle = true, count = 2)
    61, 66, 80 -> RainStyle(drizzle = false, count = 3)
    63, 67, 81 -> RainStyle(drizzle = false, count = 4)
    65, 82 -> RainStyle(drizzle = false, count = 5)
    else -> if (condition == Condition.DRIZZLE) RainStyle(true, 2) else RainStyle(false, 4)
}

private fun ImageVector.Builder.cloud(fill: Brush = SolidColor(Color.White)) = path(fill = fill) {
    moveTo(7f, 13.5f)
    horizontalLineTo(17f)
    arcToRelative(3.5f, 3.5f, 0f, false, false, 0.4f, -6.98f)
    arcToRelative(5.6f, 5.6f, 0f, false, false, -10.7f, -0.9f)
    arcToRelative(3.95f, 3.95f, 0f, false, false, 0.3f, 7.88f)
    close()
}

private val RainDrop = Color(0xFF64B5F6)
private val rainIcons = HashMap<RainStyle, ImageVector>()

/**
 * Full-colour rain glyph (draw it with `tint = Color.Unspecified`): a soft grey-white cloud that
 * darkens as the rain gets heavier, with blue teardrops for drizzle or staggered, slanted streaks
 * of varying length for rain.
 */
fun rainIcon(style: RainStyle): ImageVector = rainIcons.getOrPut(style) {
    val heaviness = if (style.drizzle) 0f else (style.count - 3) / 2f // 0 light … 1 heavy
    val top = lerp(Color(0xFFF7F9FC), Color(0xFFD3DAE4), heaviness)
    val bottom = lerp(Color(0xFFD9E0EA), Color(0xFFA7B2C2), heaviness)
    ImageVector.Builder("Rain${style.count}${if (style.drizzle) "d" else ""}", 24.dp, 24.dp, 24f, 24f).apply {
        cloud(Brush.verticalGradient(listOf(top, bottom), startY = 2.5f, endY = 13.5f))
        if (style.drizzle) {
            // Two small upright teardrops, staggered.
            listOf(9.4f to 17.4f, 14.6f to 19.9f).take(style.count).forEach { (cx, cy) ->
                path(fill = SolidColor(RainDrop)) {
                    moveTo(cx, cy - 3f)
                    lineTo(cx + 1.3f, cy - 0.75f)
                    arcTo(1.5f, 1.5f, 0f, true, true, cx - 1.3f, cy - 0.75f)
                    close()
                }
            }
        } else {
            val n = style.count
            val spacing = if (n >= 5) 2.9f else 3.4f
            val lengths = listOf(4.6f, 3.4f, 5.0f, 3.8f, 4.4f)
            val drops = listOf(0f, 1.6f, 0.4f, 1.8f, 0.8f)
            val firstX = 12f - spacing * (n - 1) / 2
            path(
                stroke = SolidColor(RainDrop),
                strokeLineWidth = if (n >= 4) 1.45f else 1.65f,
                strokeLineCap = StrokeCap.Round,
            ) {
                repeat(n) { i ->
                    val x = firstX + i * spacing
                    val len = lengths[i]
                    val y = 15.4f + drops[i]
                    // Tops sit centred under the cloud; the streak leans down-left from there.
                    moveTo(x, y)
                    lineTo(x - len * 0.38f, y + len)
                }
            }
        }
    }.build()
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
    Condition.RAIN, Condition.DRIZZLE -> Color.Unspecified // rain icons carry their own colours
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
