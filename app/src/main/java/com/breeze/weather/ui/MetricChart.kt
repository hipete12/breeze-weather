package com.breeze.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeze.weather.data.HistoryHour
import com.breeze.weather.data.HourForecast
import com.breeze.weather.data.Units
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Metric(val label: String, val icon: ImageVector) {
    TEMPERATURE("Temperature", Icons.Rounded.Thermostat),
    FEELS_LIKE("Feels like", Icons.Rounded.Thermostat),
    PRECIP_CHANCE("Rain chance", Icons.Rounded.Umbrella),
    PRECIPITATION("Precipitation", Icons.Rounded.Grain),
    HUMIDITY("Humidity", Icons.Rounded.WaterDrop),
    WIND("Wind", Icons.Rounded.Air),
    UV("UV index", Icons.Rounded.LightMode),
    CLOUD_COVER("Cloud cover", Icons.Rounded.Cloud);

    fun value(h: HourForecast): Double? = when (this) {
        TEMPERATURE -> h.temperature
        FEELS_LIKE -> h.feelsLike
        PRECIP_CHANCE -> h.precipitationChance.toDouble()
        PRECIPITATION -> h.precipitation
        HUMIDITY -> h.humidity
        WIND -> h.windSpeed
        UV -> h.uvIndex
        CLOUD_COVER -> h.cloudCover
    }

    fun historical(h: HistoryHour?): Double? = when (this) {
        TEMPERATURE -> h?.temperature
        FEELS_LIKE -> h?.feelsLike
        PRECIPITATION -> h?.precipitation
        HUMIDITY -> h?.humidity
        WIND -> h?.windSpeed
        CLOUD_COVER -> h?.cloudCover
        PRECIP_CHANCE, UV -> null
    }

    val hasHistory: Boolean get() = this != PRECIP_CHANCE && this != UV
    val isBars: Boolean get() = this == PRECIP_CHANCE || this == PRECIPITATION || this == UV

    fun format(v: Double, units: Units): String = when (this) {
        TEMPERATURE, FEELS_LIKE -> v.deg()
        PRECIP_CHANCE, HUMIDITY, CLOUD_COVER -> "${v.roundToInt()}%"
        PRECIPITATION -> formatPrecip(v, units)
        WIND -> "${v.roundToInt()} ${units.windLabel}"
        UV -> "${v.roundToInt()}"
    }

    fun axisLabel(v: Double, units: Units): String = when (this) {
        TEMPERATURE, FEELS_LIKE -> v.deg()
        PRECIP_CHANCE, HUMIDITY, CLOUD_COVER -> "${v.roundToInt()}%"
        PRECIPITATION -> if (units == Units.IMPERIAL) String.format("%.2f", v) else String.format("%.1f", v)
        WIND, UV -> "${v.roundToInt()}"
    }
}

/** Color for the legend's forecast sample. */
fun Metric.legendColor(): Color = when (this) {
    Metric.TEMPERATURE, Metric.FEELS_LIKE -> Color(0xFFFDE047)
    Metric.UV -> Color(0xFFFDBA74)
    else -> solidColor()
}

private fun Metric.solidColor(): Color = when (this) {
    Metric.HUMIDITY -> Color(0xFF7DD3FC)
    Metric.CLOUD_COVER -> Color(0xFFE2E8F0)
    Metric.WIND -> Color(0xFFA7F3D0)
    Metric.PRECIP_CHANCE -> Color(0xFF60A5FA)
    Metric.PRECIPITATION -> Color(0xFF38BDF8)
    else -> Color.White
}

/**
 * Interactive 24-hour chart for one day. Tap or drag horizontally to inspect an hour; the
 * selection is reported through [onSelect]. Past hours of today are dimmed.
 */
@Composable
fun MetricChart(
    metric: Metric,
    hours: List<HourForecast>,
    history: List<HistoryHour?>,
    units: Units,
    nowHour: Float?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    use24h: Boolean,
    modifier: Modifier = Modifier,
) {
    val axisWidth = 36.dp
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
    val n = hours.size

    Column(modifier) {
        // Icons row aligned to the plot: weather for temperature-like metrics, direction for wind.
        if (metric in setOf(Metric.TEMPERATURE, Metric.FEELS_LIKE, Metric.PRECIP_CHANCE, Metric.WIND)) {
            Row(Modifier.fillMaxWidth().padding(end = axisWidth).height(22.dp)) {
                for (i in 0 until 24) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        val h = hours.getOrNull(i)
                        if (h != null && i % 2 == 0) {
                            if (metric == Metric.WIND) {
                                Icon(
                                    Icons.Rounded.Navigation, contentDescription = null, tint = OnSky.copy(alpha = 0.8f),
                                    modifier = Modifier.requiredSize(14.dp).rotate(((h.windDirection ?: 0.0) + 180).toFloat()),
                                )
                            } else {
                                Icon(
                                    h.condition.icon(h.isDay), contentDescription = null,
                                    tint = h.condition.iconTint(h.isDay), modifier = Modifier.requiredSize(17.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .pointerInput(n) {
                    val plotW = size.width - axisWidth.toPx()
                    fun index(x: Float) = floor(x / plotW * 24).toInt().coerceIn(0, max(0, n - 1))
                    detectTapGestures { pos ->
                        val i = index(pos.x)
                        currentOnSelect(if (currentSelected == i) null else i)
                    }
                }
                .pointerInput(n) {
                    val plotW = size.width - axisWidth.toPx()
                    fun index(x: Float) = floor(x / plotW * 24).toInt().coerceIn(0, max(0, n - 1))
                    detectHorizontalDragGestures(
                        onDragStart = { currentOnSelect(index(it.x)) },
                        onDragEnd = { currentOnSelect(null) },
                        onDragCancel = { currentOnSelect(null) },
                    ) { change, _ ->
                        change.consume()
                        currentOnSelect(index(change.position.x))
                    }
                }
        ) {
            if (n == 0) return@Canvas
            val plotW = size.width - axisWidth.toPx()
            val top = 6.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            fun x(hour: Float) = (hour + 0.5f) / 24f * plotW

            val values = hours.map { metric.value(it) }
            val hist = if (metric.hasHistory) history.map { metric.historical(it) } else emptyList()
            val gusts = if (metric == Metric.WIND) hours.map { it.windGusts } else emptyList()

            // ---- Y range ----
            val all = (values + hist + gusts).filterNotNull()
            if (all.isEmpty()) return@Canvas
            val dataMin = all.min()
            val dataMax = all.max()
            val (lo, hi, step) = when (metric) {
                Metric.PRECIP_CHANCE, Metric.HUMIDITY, Metric.CLOUD_COVER -> Triple(0.0, 100.0, 25.0)
                Metric.UV -> {
                    val h = max(8.0, ceil(dataMax + 1))
                    Triple(0.0, h, if (h > 10) 4.0 else 2.0)
                }
                Metric.PRECIPITATION -> {
                    val floorMax = if (units == Units.IMPERIAL) 0.04 else 1.0
                    val h = max(floorMax, dataMax * 1.2)
                    val s = niceStep(h / 3)
                    Triple(0.0, ceil(h / s) * s, s)
                }
                Metric.WIND -> {
                    val s = niceStep(max(dataMax, 10.0) * 1.1 / 3)
                    Triple(0.0, ceil(max(dataMax, 10.0) * 1.1 / s) * s, s)
                }
                else -> {
                    val s = niceStep(max(dataMax - dataMin, 4.0) / 3)
                    val l = floor(dataMin / s) * s
                    var h = ceil(dataMax / s) * s
                    if (h - l < 2 * s) h = l + 2 * s
                    Triple(l, h, s)
                }
            }
            fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

            // ---- Grid & axes ----
            var tick = lo
            while (tick <= hi + 1e-9) {
                val ty = y(tick)
                drawLine(
                    Color.White.copy(alpha = 0.12f), Offset(0f, ty), Offset(plotW, ty), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
                val layout = measurer.measure(metric.axisLabel(tick, units), labelStyle)
                drawText(layout, topLeft = Offset(plotW + 6.dp.toPx(), ty - layout.size.height / 2f))
                tick += step
            }
            for (h in listOf(0, 6, 12, 18)) {
                val hx = x(h.toFloat())
                drawLine(Color.White.copy(alpha = 0.08f), Offset(hx, top), Offset(hx, bottom), strokeWidth = 1.dp.toPx())
                val label = formatHour(LocalDateTime.of(2000, 1, 1, h, 0), use24h)
                val layout = measurer.measure(label, labelStyle)
                drawText(layout, topLeft = Offset((hx - layout.size.width / 2f).coerceAtLeast(0f), size.height - layout.size.height))
            }

            val nowX = nowHour?.let { x(it) }
            val pastAlpha = 0.4f

            // ---- Series ----
            if (metric.isBars) {
                val slot = plotW / 24f
                values.forEachIndexed { i, v ->
                    if (v == null || v <= 0) return@forEachIndexed
                    val bx = x(i.toFloat()) - slot * 0.32f
                    val color = if (metric == Metric.UV) uvColor(v) else metric.solidColor()
                    val past = nowHour != null && i + 1 <= nowHour
                    drawRoundRect(
                        color.copy(alpha = if (past) pastAlpha else 0.9f),
                        topLeft = Offset(bx, y(v)),
                        size = Size(slot * 0.64f, bottom - y(v)),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            } else {
                val line = smoothPath(values.mapIndexed { i, v -> v?.let { Offset(x(i.toFloat()), y(it)) } })
                val brush = if (metric == Metric.TEMPERATURE || metric == Metric.FEELS_LIKE) {
                    Brush.verticalGradient(listOf(tempColor(hi, units), tempColor(lo, units)), startY = top, endY = bottom)
                } else Brush.verticalGradient(listOf(metric.solidColor(), metric.solidColor()))
                // Area fill.
                val first = values.indexOfFirst { it != null }
                val last = values.indexOfLast { it != null }
                if (first >= 0) {
                    val area = Path().apply {
                        addPath(line)
                        lineTo(x(last.toFloat()), bottom)
                        lineTo(x(first.toFloat()), bottom)
                        close()
                    }
                    drawPath(area, brush, alpha = 0.18f)
                }
                if (gusts.isNotEmpty()) {
                    val gustPath = smoothPath(gusts.mapIndexed { i, v -> v?.let { Offset(x(i.toFloat()), y(it)) } })
                    drawPath(
                        gustPath, metric.solidColor().copy(alpha = 0.45f),
                        style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))),
                    )
                }
                drawDimmedPast(nowX) { alpha ->
                    drawPath(line, brush, alpha = alpha, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
            }

            // Historical average (dashed).
            if (hist.any { it != null }) {
                val histPath = smoothPath(hist.mapIndexed { i, v -> v?.let { Offset(x(i.toFloat()), y(it)) } })
                drawPath(
                    histPath, Color.White.copy(alpha = 0.7f),
                    style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }

            // Now marker.
            if (nowX != null && nowHour in 0f..23.99f && !metric.isBars) {
                val i0 = floor(nowHour!!).toInt().coerceIn(0, n - 1)
                val i1 = (i0 + 1).coerceAtMost(n - 1)
                val v0 = values[i0]
                val v1 = values[i1]
                if (v0 != null && v1 != null) {
                    val v = v0 + (v1 - v0) * (nowHour - i0)
                    drawCircle(Color.Black.copy(alpha = 0.35f), radius = 6.dp.toPx(), center = Offset(nowX, y(v)))
                    drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(nowX, y(v)))
                }
            }

            // Selection.
            if (selected != null && selected in 0 until n) {
                val sx = x(selected.toFloat())
                drawLine(Color.White.copy(alpha = 0.7f), Offset(sx, top), Offset(sx, bottom), strokeWidth = 1.5.dp.toPx())
                values[selected]?.let { v ->
                    if (!metric.isBars) {
                        drawCircle(Color.Black.copy(alpha = 0.4f), radius = 7.dp.toPx(), center = Offset(sx, y(v)))
                        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(sx, y(v)))
                    }
                }
                hist.getOrNull(selected)?.let { v ->
                    drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(sx, y(v)), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

private inline fun DrawScope.drawDimmedPast(nowX: Float?, draw: DrawScope.(alpha: Float) -> Unit) {
    if (nowX == null) {
        draw(1f)
        return
    }
    clipRect(right = nowX) { draw(0.4f) }
    clipRect(left = nowX) { draw(1f) }
}

/** Smooth curve through the points, breaking at gaps (null). */
private fun smoothPath(points: List<Offset?>): Path {
    val path = Path()
    var prev: Offset? = null
    for (p in points) {
        if (p == null) {
            prev = null
            continue
        }
        val pr = prev
        if (pr == null) path.moveTo(p.x, p.y)
        else {
            val mx = (pr.x + p.x) / 2
            path.cubicTo(mx, pr.y, mx, p.y, p.x, p.y)
        }
        prev = p
    }
    return path
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val nice = when {
        norm <= 1 -> 1.0
        norm <= 2 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5 -> 5.0
        else -> 10.0
    }
    return nice * mag
}
