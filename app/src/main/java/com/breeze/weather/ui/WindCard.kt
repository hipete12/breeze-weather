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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeze.weather.data.Units
import kotlin.math.PI
import kotlin.math.cos
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

/**
 * Full-width wind card: speed, an animated streak field that scales with the wind, a dial showing
 * where the wind comes from, and the Beaufort scale.
 */
@Composable
fun WindCard(speed: Double, gusts: Double?, direction: Int, units: Units, modifier: Modifier = Modifier) {
    val kmh = units.toKmh(speed)
    val level = beaufort(kmh)
    GlassCard(title = "Wind", icon = Icons.Rounded.Air, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Text column wraps its content so the streaks get the room to its right.
            Column {
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
            }
            WindStreaks(
                kmh = kmh, gustKmh = gusts?.let(units::toKmh),
                modifier = Modifier.weight(1f).height(88.dp).padding(start = 4.dp, end = 10.dp),
            )
            WindDial(direction, Modifier.size(84.dp))
        }
        Spacer(Modifier.height(14.dp))
        BeaufortBar(level)
    }
}

private const val DRIFT_MS = 9000

private class Streak(
    val y: Float, val length: Float, val offset: Float, val speed: Int, val alpha: Float, val width: Float,
    /** The air current it rides: a gentle wave (height in dp, waves across the field, start) and a slight slope. */
    val waveDp: Float, val waves: Float, val wavePhase: Float, val tilt: Float,
    /**
     * Some gusts only live for part of the crossing: they fade in at [lifeStart] (fraction of the
     * trip) and out again [lifeSpan] later, so streaks appear and vanish inside the field.
     * A span of 0 means the streak crosses the whole field.
     */
    val lifeStart: Float, val lifeSpan: Float,
)

/**
 * Lines drifting left to right (the dial beside them gives the direction), each riding its own
 * gentle current so they rise, dip and cross a little rather than run in rails. More, longer,
 * faster and brighter streaks as the wind picks up; gusty conditions add a pulse and more sway.
 */
@Composable
private fun WindStreaks(kmh: Double, gustKmh: Double?, modifier: Modifier) {
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
                waveDp = 1.5f + r.nextFloat() * 3f,
                waves = 0.4f + r.nextFloat() * 0.6f,
                wavePhase = r.nextFloat() * 6.28f,
                tilt = (r.nextFloat() - 0.5f) * 0.12f,
                lifeSpan = if (r.nextFloat() < 0.45f) 0.3f + r.nextFloat() * 0.3f else 0f,
                lifeStart = 0.15f + r.nextFloat() * 0.25f,
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "wind")
    // Every term below repeats exactly when its loop restarts, so the restart can't be seen:
    // streaks move a whole number of widths per cycle, and angles turn by whole multiples of 2π.
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(cycleMs, easing = LinearEasing)), label = "phase",
    )
    // The currents drift on their own slow loop, one full wave per loop, whatever the wind speed.
    val drift by transition.animateFloat(
        0f, 2f * PI.toFloat(), infiniteRepeatable(tween(DRIFT_MS, easing = LinearEasing)), label = "drift",
    )
    val gustiness = if (gustKmh != null && kmh > 1) ((gustKmh - kmh) / (kmh + 5)).coerceIn(0.0, 1.0).toFloat() else 0f
    val calmFactor = if (kmh < 1) 0.45f else 1f

    // Drawn offscreen so the edge masks below can fade the streaks out instead of cutting them off.
    Canvas(modifier.clipToBounds().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        streaks.forEachIndexed { i, s ->
            val len = size.width * s.length * (0.55f + level / 14f)
            val p = (phase * s.speed + s.offset) % 1f
            val x = -len + p * (size.width + len)
            val pulse = 1f - gustiness * 0.5f * (0.5f + 0.5f * sin((phase * 2 * Math.PI + i).toFloat()))
            val baseY = s.y * size.height
            val amp = (s.waveDp + gustiness * 3f).dp.toPx()
            // The current is fixed in space (drifting slowly), so a streak curves as it travels along it.
            fun yAt(px: Float): Float {
                val t = px / size.width
                return baseY + s.tilt * (px - size.width / 2) +
                    amp * sin(t * s.waves * 2f * PI.toFloat() + s.wavePhase + drift)
            }
            // Short-lived streaks swell in and die away; p wraps only while the streak is off-field,
            // so this stays seamless across the loop.
            val life = if (s.lifeSpan > 0f) {
                val t = (p - s.lifeStart) / s.lifeSpan
                if (t <= 0f || t >= 1f) 0f else sin(t * PI.toFloat())
            } else 1f
            if (life <= 0f) return@forEachIndexed
            val a = s.alpha * pulse * calmFactor * life
            val path = Path().apply {
                val steps = 10
                for (k in 0..steps) {
                    val px = x + len * k / steps
                    if (k == 0) moveTo(px, yAt(px)) else lineTo(px, yAt(px))
                }
            }
            drawPath(
                path,
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = a), Color.Transparent),
                    startX = x, endX = x + len,
                ),
                style = Stroke(width = s.width.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        // Soft edges: streaks thin out towards the sides (and a little at the top and bottom),
        // so the field has no visible box however long and fast they get.
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
}

/**
 * Where the wind comes from, like a weather vane: a needle points into the wind, with the
 * direction ("NW") at its tip. Just the needle and label, no dial around them.
 */
@Composable
private fun WindDial(direction: Int, modifier: Modifier) {
    val name = windCompass(direction)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSky)
    val label = remember(name, labelStyle) { measurer.measure(name, labelStyle) }
    Canvas(modifier.semantics { contentDescription = "Wind from $name" }) {
        val r = size.minDimension / 2 - 1.dp.toPx()
        // Unit vector toward the wind's source (bearing 0 = north, up).
        val rad = Math.toRadians(direction.toDouble())
        val ux = sin(rad).toFloat()
        val uy = -cos(rad).toFloat()
        fun at(d: Float) = Offset(center.x + ux * d, center.y + uy * d)

        // The needle: fading tail, solid shaft, arrowhead pointing into the wind.
        val tail = at(-r * 0.7f)
        val tip = at(r * 0.44f)
        val headLen = 9.dp.toPx()
        val headBase = at(r * 0.44f - headLen)
        drawLine(
            Brush.linearGradient(listOf(OnSky.copy(alpha = 0f), OnSky), start = tail, end = headBase),
            tail, headBase, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
        )
        val half = 4.5.dp.toPx()
        val head = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(headBase.x - uy * half, headBase.y + ux * half)
            lineTo(headBase.x + uy * half, headBase.y - ux * half)
            close()
        }
        drawPath(head, OnSky)
        drawCircle(OnSky, radius = 2.5.dp.toPx(), center = center)

        // The direction just past the tip, upright.
        val pos = at(r * 0.72f)
        drawText(label, topLeft = Offset(pos.x - label.size.width / 2f, pos.y - label.size.height / 2f))
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
