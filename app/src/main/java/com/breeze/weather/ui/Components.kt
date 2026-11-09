package com.breeze.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.breeze.weather.data.Units
import java.time.Duration

internal val OnSky = Color.White
internal val CardShape = RoundedCornerShape(24.dp)
internal val RainBlue = Color(0xFF9AD0FF)

/** Translucent card used for every section on the weather pages. */
@Composable
internal fun GlassCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Color.White.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = OnSky.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = OnSky.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Maps a temperature to a cool→warm color. */
internal fun tempColor(t: Double, units: Units): Color {
    val c = if (units == Units.IMPERIAL) (t - 32) * 5 / 9 else t
    val stops = listOf(
        -10.0 to Color(0xFFA5B4FC),
        0.0 to Color(0xFF7DD3FC),
        10.0 to Color(0xFF6EE7B7),
        20.0 to Color(0xFFFDE047),
        28.0 to Color(0xFFFDBA74),
        36.0 to Color(0xFFF87171),
    )
    if (c <= stops.first().first) return stops.first().second
    if (c >= stops.last().first) return stops.last().second
    val i = stops.indexOfFirst { it.first > c }
    val (t0, c0) = stops[i - 1]
    val (t1, c1) = stops[i]
    return lerp(c0, c1, ((c - t0) / (t1 - t0)).toFloat())
}

/** Color for a "feels like minus actual" difference: blue when colder, orange/red when warmer. */
internal fun feelsDeltaColor(delta: Double, units: Units): Color {
    val c = units.deltaToC(delta)
    return when {
        c <= -5 -> Color(0xFF60A5FA)
        c <= -1 -> Color(0xFF93C5FD)
        c < 1 -> Color.White.copy(alpha = 0.75f)
        c < 5 -> Color(0xFFFDBA74)
        else -> Color(0xFFF87171)
    }
}

internal fun uvColor(uv: Double): Color = when {
    uv < 3 -> Color(0xFF6EE7B7)
    uv < 6 -> Color(0xFFFDE047)
    uv < 8 -> Color(0xFFFDBA74)
    uv < 11 -> Color(0xFFF87171)
    else -> Color(0xFFC084FC)
}

internal fun formatDuration(d: Duration): String {
    val h = d.toHours()
    val m = d.toMinutes() % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

internal fun formatPrecip(v: Double, units: Units): String =
    if (v < 0.005) "0 ${units.precipLabel}"
    else if (units == Units.IMPERIAL) String.format(java.util.Locale.getDefault(), "%.2f in", v)
    else String.format(java.util.Locale.getDefault(), "%.1f mm", v)
