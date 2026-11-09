package com.breeze.weather.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeze.weather.data.Units
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private val beaufortLimitsKmh = listOf(1, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118)
private val beaufortNames = listOf(
    "Calm", "Light air", "Light breeze", "Gentle breeze", "Moderate breeze", "Fresh breeze",
    "Strong breeze", "Near gale", "Gale", "Strong gale", "Storm", "Violent storm", "Hurricane force",
)

/** Beaufort force (0–12) for a wind speed in km/h. */
fun beaufort(kmh: Double): Int = beaufortLimitsKmh.indexOfFirst { kmh < it }.let { if (it == -1) 12 else it }

fun beaufortName(level: Int): String = beaufortNames[level.coerceIn(0, 12)]

private fun beaufortColor(level: Int): Color {
    val stops = listOf(Color(0xFF6EE7B7), Color(0xFFFDE047), Color(0xFFFDBA74), Color(0xFFF87171), Color(0xFFC084FC))
    val t = level / 12f * (stops.size - 1)
    val i = t.toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], t - i)
}

/** Full-width wind card: speed, an animated streak field that scales with the wind, a compass and the Beaufort scale. */
@Composable
fun WindCard(speed: Double, gusts: Double?, direction: Int, units: Units, modifier: Modifier = Modifier) {
    val kmh = units.toKmh(speed)
    val level = beaufort(kmh)
    GlassCard(title = "Wind", icon = Icons.Rounded.Air, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(124.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(speed.roundToInt().toString(), fontSize = 34.sp, color = OnSky)
                    Text(
                        " ${units.windLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSky.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(beaufortName(level), style = MaterialTheme.typography.titleSmall, color = OnSky)
                Spacer(Modifier.height(4.dp))
                if (gusts != null) {
                    Text(
                        "Gusts ${gusts.roundToInt()} ${units.windLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSky.copy(alpha = 0.8f),
                    )
                }
                Text(
                    "From ${windCompass(direction)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSky.copy(alpha = 0.8f),
                )
            }
            WindStreaks(
                kmh = kmh, gustKmh = gusts?.let(units::toKmh), direction = direction,
                modifier = Modifier.weight(1f).height(88.dp).padding(horizontal = 6.dp),
            )
            Compass(direction, Modifier.size(84.dp))
        }
        Spacer(Modifier.height(14.dp))
        BeaufortBar(level)
    }
}

private class Streak(val y: Float, val length: Float, val offset: Float, val speed: Int, val alpha: Float, val width: Float)

/**
 * Lines drifting in the direction the wind blows. More, longer, faster and brighter streaks as the
 * wind picks up; gusty conditions add a gentle pulse.
 */
@Composable
private fun WindStreaks(kmh: Double, gustKmh: Double?, direction: Int, modifier: Modifier) {
    val level = beaufort(kmh)
    val count = (6 + level * 1.5f).toInt().coerceIn(6, 22)
    val cycleMs = (5600 / (1 + kmh / 7)).toInt().coerceIn(420, 5600)
    val streaks = remember(count) {
        val r = Random(42)
        List(count) {
            Streak(
                y = r.nextFloat(),
                length = 0.12f + r.nextFloat() * 0.22f,
                offset = r.nextFloat(),
                speed = 1 + r.nextInt(2),
                alpha = 0.35f + r.nextFloat() * 0.5f,
                width = 1.2f + r.nextFloat() * 1.4f,
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "wind")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(cycleMs, easing = LinearEasing)), label = "phase",
    )
    val gustiness = if (gustKmh != null && kmh > 1) ((gustKmh - kmh) / (kmh + 5)).coerceIn(0.0, 1.0).toFloat() else 0f
    val calmFactor = if (kmh < 1) 0.45f else 1f

    Canvas(modifier.clipToBounds()) {
        val diag = hypot(size.width, size.height)
        val left = center.x - diag / 2
        val top = center.y - diag / 2
        // Base motion is +x (east, bearing 90°); rotate to the bearing the wind blows toward.
        rotate(degrees = direction + 180f - 90f) {
            streaks.forEachIndexed { i, s ->
                val len = diag * s.length * (0.55f + level / 14f)
                val p = (phase * s.speed + s.offset) % 1f
                val x = left - len + p * (diag + len)
                val pulse = 1f - gustiness * 0.5f * (0.5f + 0.5f * sin((phase * 2 * Math.PI + i).toFloat()))
                val y = top + s.y * diag + gustiness * 3.dp.toPx() * cos((p * 6 + i).toDouble()).toFloat()
                val a = s.alpha * pulse * calmFactor
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = a), Color.Transparent),
                        startX = x, endX = x + len,
                    ),
                    start = Offset(x, y),
                    end = Offset(x + len, y),
                    strokeWidth = s.width.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun Compass(direction: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 - 2.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.22f), radius = r, style = Stroke(1.dp.toPx()))
            for (deg in 0 until 360 step 15) {
                val major = deg % 90 == 0
                if (major) continue // letters sit there
                val len = if (deg % 45 == 0) 6.dp.toPx() else 3.dp.toPx()
                val rad = Math.toRadians(deg.toDouble())
                val sx = center.x + (r - len) * sin(rad).toFloat()
                val sy = center.y - (r - len) * cos(rad).toFloat()
                val ex = center.x + r * sin(rad).toFloat()
                val ey = center.y - r * cos(rad).toFloat()
                drawLine(Color.White.copy(alpha = 0.4f), Offset(sx, sy), Offset(ex, ey), strokeWidth = 1.dp.toPx())
            }
        }
        val label = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
        val c = OnSky.copy(alpha = 0.75f)
        Text("N", style = label, color = c, modifier = Modifier.align(Alignment.TopCenter).padding(top = 5.dp))
        Text("S", style = label, color = c, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp))
        Text("E", style = label, color = c, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp))
        Text("W", style = label, color = c, modifier = Modifier.align(Alignment.CenterStart).padding(start = 7.dp))
        Icon(
            Icons.Rounded.Navigation,
            contentDescription = "Wind from ${windCompass(direction)}",
            tint = OnSky,
            modifier = Modifier.size(28.dp).rotate(direction + 180f),
        )
    }
}

@Composable
private fun BeaufortBar(level: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(13) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (i <= level) beaufortColor(i) else Color.White.copy(alpha = 0.15f))
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.labelSmall
        Text("Calm", style = style, color = OnSky.copy(alpha = 0.55f))
        Spacer(Modifier.weight(1f))
        Text("Force $level of 12", style = style, color = OnSky.copy(alpha = 0.8f))
        Spacer(Modifier.weight(1f))
        Text("Hurricane", style = style, color = OnSky.copy(alpha = 0.55f))
    }
}
