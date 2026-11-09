package com.breeze.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos

private val SunYellow = Color(0xFFFFD54F)

/**
 * Sunrise and sunset on one card, with the sun's path across the day drawn as a curve: solid for
 * time already passed, dotted for what's ahead. [now] is null for days that haven't started.
 */
@Composable
fun SunCard(
    sunrise: LocalDateTime?,
    sunset: LocalDateTime?,
    now: LocalDateTime?,
    nextSunrise: LocalDateTime?,
    use24h: Boolean,
    modifier: Modifier = Modifier,
) {
    GlassCard(title = "Sunrise & sunset", icon = Icons.Rounded.WbTwilight, modifier = modifier) {
        if (sunrise == null || sunset == null) {
            Text("No sunrise or sunset today", style = MaterialTheme.typography.bodyMedium, color = OnSky)
            return@GlassCard
        }
        val daylight = Duration.between(sunrise, sunset)

        // Headline: the next sun event when looking at today, otherwise both times.
        Row(verticalAlignment = Alignment.Bottom) {
            val (label, time, caption) = when {
                now == null -> Triple("Sunrise", sunrise, null)
                now.isBefore(sunrise) -> Triple("Sunrise", sunrise, "In ${formatDuration(Duration.between(now, sunrise))}")
                now.isBefore(sunset) -> Triple("Sunset", sunset, "In ${formatDuration(Duration.between(now, sunset))}")
                nextSunrise != null -> Triple("Sunrise tomorrow", nextSunrise, "In ${formatDuration(Duration.between(now, nextSunrise))}")
                else -> Triple("Sunset", sunset, null)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = OnSky.copy(alpha = 0.7f))
                Text(formatClock(time, use24h), fontSize = 28.sp, color = OnSky)
                if (caption != null) {
                    Text(caption, style = MaterialTheme.typography.bodySmall, color = OnSky.copy(alpha = 0.8f))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (now == null) {
                    Text("Sunset", style = MaterialTheme.typography.labelMedium, color = OnSky.copy(alpha = 0.7f))
                    Text(formatClock(sunset, use24h), fontSize = 28.sp, color = OnSky)
                }
                Text(
                    "Daylight ${formatDuration(daylight)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSky.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SunCurve(sunrise, sunset, now, use24h)
    }
}

private fun LocalDateTime.hourOfDay(): Float = hour + minute / 60f + second / 3600f

@Composable
private fun SunCurve(sunrise: LocalDateTime, sunset: LocalDateTime, now: LocalDateTime?, use24h: Boolean) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
    val riseText = formatClock(sunrise, use24h)
    val setText = formatClock(sunset, use24h)

    Canvas(Modifier.fillMaxWidth().height(116.dp)) {
        val rise = sunrise.hourOfDay()
        val set = sunset.hourOfDay()
        val noon = (rise + set) / 2
        val c0 = cos(2 * PI * (rise - noon) / 24).toFloat()
        fun f(t: Float) = cos(2 * PI * (t - noon) / 24).toFloat() - c0

        val samples = (0..96).map { it * 0.25f }
        val values = samples.map(::f)
        val maxF = values.max()
        val minF = values.min()
        val pad = 10.dp.toPx()
        val labelH = 18.dp.toPx()
        val top = pad
        val bottom = size.height - labelH
        fun x(t: Float) = t / 24f * size.width
        fun y(v: Float) = top + (maxF - v) / (maxF - minF) * (bottom - top)
        val horizon = y(0f)

        // Daylight glow under the arc.
        val day = Path().apply {
            moveTo(x(rise), horizon)
            samples.filter { it in rise..set }.forEach { lineTo(x(it), y(f(it))) }
            lineTo(x(set), horizon)
            close()
        }
        drawPath(day, Brush.verticalGradient(listOf(SunYellow.copy(alpha = 0.28f), Color.Transparent), startY = y(maxF), endY = horizon))

        // Horizon.
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, horizon), Offset(size.width, horizon), strokeWidth = 1.dp.toPx())

        val curve = Path().apply {
            samples.forEachIndexed { i, t -> if (i == 0) moveTo(x(t), y(values[i])) else lineTo(x(t), y(values[i])) }
        }
        val nowT: Float = when {
            now == null -> 0f
            now.toLocalDate().isAfter(sunrise.toLocalDate()) -> 24f
            now.toLocalDate().isBefore(sunrise.toLocalDate()) -> 0f
            else -> now.hourOfDay()
        }
        val nowX = x(nowT)
        // Passed: solid. Ahead: dotted.
        clipRect(right = nowX) {
            drawPath(curve, Color.White.copy(alpha = 0.9f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        clipRect(left = nowX) {
            drawPath(
                curve, Color.White.copy(alpha = 0.6f),
                style = Stroke(
                    width = 3.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 7.dp.toPx())),
                ),
            )
        }

        // Sunrise / sunset markers and labels.
        listOf(rise to riseText, set to setText).forEach { (t, text) ->
            drawCircle(Color.White.copy(alpha = 0.85f), radius = 3.dp.toPx(), center = Offset(x(t), horizon))
            val layout = measurer.measure(text, labelStyle)
            val lx = (x(t) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(lx, size.height - layout.size.height))
        }

        if (now != null && nowT in 0f..24f && nowT != 24f) drawSun(Offset(nowX, y(f(nowT))), above = f(nowT) > 0)
    }
}

private fun DrawScope.drawSun(center: Offset, above: Boolean) {
    if (above) {
        drawCircle(
            Brush.radialGradient(listOf(SunYellow.copy(alpha = 0.55f), Color.Transparent), center = center, radius = 18.dp.toPx()),
            radius = 18.dp.toPx(), center = center,
        )
        drawCircle(SunYellow, radius = 7.dp.toPx(), center = center)
    } else {
        drawCircle(Color.White.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = center)
        drawCircle(Color(0xFFE3E8F5), radius = 5.dp.toPx(), center = center)
    }
}
