package com.breeze.weather.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeze.weather.data.Condition
import com.breeze.weather.data.DayForecast
import com.breeze.weather.data.HistoryHour
import com.breeze.weather.data.HourForecast
import com.breeze.weather.data.Place
import com.breeze.weather.data.Units
import com.breeze.weather.data.Weather
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DayDetailScreen(
    place: Place,
    weather: Weather,
    history: HistoryState?,
    initialDay: Int,
    onBack: () -> Unit,
    onLoadHistory: () -> Unit,
) {
    val context = LocalContext.current
    val use24h = remember { DateFormat.is24HourFormat(context) }
    var dayIndex by rememberSaveable { mutableIntStateOf(initialDay.coerceIn(0, weather.daily.lastIndex)) }
    var metric by rememberSaveable { mutableStateOf(Metric.TEMPERATURE) }
    var selected by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(place.id, weather.units) { onLoadHistory() }

    val day = weather.daily[dayIndex]
    val hours = remember(weather, dayIndex) { weather.hoursOn(day.date) }
    val hist = remember(history?.data, hours) { hours.map { history?.data?.get(it.time) } }
    val now = weather.localNow()
    val isToday = day.date == now.toLocalDate()
    val nowHour = if (isToday) now.hour + now.minute / 60f else null

    val colors = day.condition.gradient(true)
    val top by animateColorAsState(colors[0], tween(500), label = "top")
    val bottom by animateColorAsState(colors[1], tween(500), label = "bottom")

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            // Top bar.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = OnSky)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium, color = OnSky, maxLines = 1)
                    Text(
                        day.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSky.copy(alpha = 0.75f),
                    )
                }
                Spacer(Modifier.width(48.dp))
            }

            Spacer(Modifier.height(12.dp))
            DaySelector(weather.daily, dayIndex, now.toLocalDate()) { dayIndex = it; selected = null }
            Spacer(Modifier.height(20.dp))

            // Day hero.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                WeatherIcon(day.weatherCode, isDay = true, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(Condition.describe(day.weatherCode, true), style = MaterialTheme.typography.titleLarge, color = OnSky)
                    Text(
                        "H:${day.high.deg()}   L:${day.low.deg()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSky.copy(alpha = 0.85f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            MetricSelector(metric) { metric = it; selected = null }
            Spacer(Modifier.height(12.dp))

            GlassCard(title = metric.label, icon = metric.icon) {
                val header = headerFor(metric, day, hours, hist, selected, weather.units, use24h)
                Text(header.label, style = MaterialTheme.typography.labelMedium, color = OnSky.copy(alpha = 0.7f))
                Text(header.value, fontSize = 30.sp, color = OnSky)
                Text(
                    header.secondary ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSky.copy(alpha = 0.8f),
                    maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                MetricChart(
                    metric = metric,
                    hours = hours,
                    history = hist,
                    units = weather.units,
                    nowHour = nowHour,
                    selected = selected,
                    onSelect = { selected = it },
                    use24h = use24h,
                )
                Spacer(Modifier.height(10.dp))
                Legend(metric, history, hasObserved = hours.any { metric.isObserved(it) })
            }

            Spacer(Modifier.height(12.dp))
            GlassCard(title = "Daily summary", icon = Icons.AutoMirrored.Rounded.Notes) {
                Text(
                    summaryFor(day, hours, hist, weather.units, use24h),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSky,
                )
            }

            Spacer(Modifier.height(12.dp))
            DayStats(hours, day, weather.units)

            Spacer(Modifier.height(12.dp))
            SunCard(
                sunrise = day.sunrise,
                sunset = day.sunset,
                now = if (isToday) now else null,
                nextSunrise = weather.daily.getOrNull(dayIndex + 1)?.sunrise,
                use24h = use24h,
            )
            if (isToday && weather.observationStation != null && hours.any { it.observed }) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Past hours observed at ${weather.observationStation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSky.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DaySelector(days: List<DayForecast>, selected: Int, today: java.time.LocalDate, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        days.forEachIndexed { i, d ->
            val isSel = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelect(i) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSky.copy(alpha = if (isSel) 1f else 0.7f),
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isSel) Color.White else Color.Transparent)
                        .then(
                            if (d.date == today && !isSel) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        d.date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSel) Color(0xFF1B2440) else OnSky,
                    )
                }
                Spacer(Modifier.height(2.dp))
                WeatherIcon(d.weatherCode, isDay = true, modifier = Modifier.size(14.dp).alpha(if (isSel) 1f else 0.8f))
            }
        }
    }
}

@Composable
private fun MetricSelector(selected: Metric, onSelect: (Metric) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 0)
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(Metric.entries) { m ->
            val isSel = m == selected
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (isSel) Color.White else Color.White.copy(alpha = 0.14f))
                    .clickable { onSelect(m) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val c = if (isSel) Color(0xFF1B2440) else OnSky
                Icon(m.icon, contentDescription = null, tint = c, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(m.label, style = MaterialTheme.typography.labelLarge, color = c)
            }
        }
    }
}

@Composable
private fun Legend(metric: Metric, history: HistoryState?, hasObserved: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hasObserved) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendLine(dashed = false, color = metric.legendColor(), alpha = 0.4f)
                Spacer(Modifier.width(6.dp))
                Text("Observed", style = MaterialTheme.typography.labelSmall, color = OnSky.copy(alpha = 0.75f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendLine(dashed = false, color = metric.legendColor())
            Spacer(Modifier.width(6.dp))
            Text("Forecast", style = MaterialTheme.typography.labelSmall, color = OnSky.copy(alpha = 0.75f))
        }
        if (metric == Metric.WIND) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendLine(dashed = true, dotted = true, color = metric.legendColor())
                Spacer(Modifier.width(6.dp))
                Text("Gusts", style = MaterialTheme.typography.labelSmall, color = OnSky.copy(alpha = 0.75f))
            }
        }
        if (metric.hasHistory) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    history?.data != null -> {
                        LegendLine(dashed = true)
                        Spacer(Modifier.width(6.dp))
                        Text("10-year average", style = MaterialTheme.typography.labelSmall, color = OnSky.copy(alpha = 0.75f))
                    }
                    history?.failed == true ->
                        Text("Historical average unavailable", style = MaterialTheme.typography.labelSmall, color = OnSky.copy(alpha = 0.55f))
                    else -> {
                        CircularProgressIndicator(Modifier.size(10.dp), color = OnSky, strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Loading historical average…", style = MaterialTheme.typography.labelSmall, color = OnSky.copy(alpha = 0.55f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendLine(
    dashed: Boolean,
    dotted: Boolean = false,
    color: Color = Color.White,
    alpha: Float = if (dashed) 0.7f else 1f,
) {
    Canvas(Modifier.width(18.dp).height(8.dp)) {
        val effect = when {
            dotted -> PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
            dashed -> PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
            else -> null
        }
        drawLine(
            color.copy(alpha = alpha),
            Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
            strokeWidth = (if (dashed) 1.6.dp else 3.dp).toPx(), pathEffect = effect,
        )
    }
}

@Composable
private fun DayStats(hours: List<HourForecast>, day: DayForecast, units: Units) {
    val precip = hours.sumOf { it.precipitation ?: 0.0 }
    val maxWind = hours.maxOfOrNull { it.windSpeed ?: 0.0 } ?: 0.0
    val maxGust = hours.maxOfOrNull { it.windGusts ?: 0.0 } ?: 0.0
    val hum = hours.mapNotNull { it.humidity }
    val cloud = hours.mapNotNull { it.cloudCover }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Precipitation", Icons.Rounded.Grain, formatPrecip(precip, units), "Chance up to ${day.precipitationChance}%", Modifier.weight(1f))
            StatTile(
                "Wind", Icons.Rounded.Air, "${maxWind.roundToInt()} ${units.windLabel}",
                "Gusts up to ${maxGust.roundToInt()} ${units.windLabel}", Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "Humidity", Icons.Rounded.WaterDrop,
                if (hum.isEmpty()) "—" else "${hum.average().roundToInt()}%",
                if (hum.isEmpty()) "" else "Range ${hum.min().roundToInt()}–${hum.max().roundToInt()}%",
                Modifier.weight(1f),
            )
            StatTile(
                "Cloud cover", Icons.Rounded.Cloud,
                if (cloud.isEmpty()) "—" else "${cloud.average().roundToInt()}%",
                if (cloud.isEmpty()) "" else cloudLabel(cloud.average()),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(title: String, icon: ImageVector, value: String, caption: String, modifier: Modifier) {
    GlassCard(title = title, icon = icon, modifier = modifier.height(108.dp)) {
        Text(value, fontSize = 24.sp, color = OnSky, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = OnSky.copy(alpha = 0.8f), maxLines = 1)
    }
}

private fun cloudLabel(avg: Double) = when {
    avg < 20 -> "Mostly clear skies"
    avg < 50 -> "Partly cloudy"
    avg < 80 -> "Mostly cloudy"
    else -> "Overcast"
}

private data class HeaderText(val label: String, val value: String, val secondary: String?)

private fun headerFor(
    metric: Metric,
    day: DayForecast,
    hours: List<HourForecast>,
    hist: List<HistoryHour?>,
    selected: Int?,
    units: Units,
    use24h: Boolean,
): HeaderText {
    if (selected != null && selected in hours.indices) {
        val h = hours[selected]
        val v = metric.value(h)
        val avg = metric.historical(hist.getOrNull(selected))
        val parts = buildList {
            when (metric) {
                Metric.TEMPERATURE, Metric.FEELS_LIKE, Metric.PRECIP_CHANCE -> add(Condition.describe(h.weatherCode, h.isDay))
                Metric.WIND -> {
                    h.windGusts?.let { add("Gusts ${it.roundToInt()} ${units.windLabel}") }
                    h.windDirection?.let { add("from ${windCompass(it.roundToInt())}") }
                }
                Metric.UV -> v?.let { add(uvLabel(it)) }
                else -> Unit
            }
            avg?.let { add("Avg ${metric.format(it, units)}") }
        }
        val clock = formatClock(h.time, use24h)
        val label = if (metric.isObserved(h)) "$clock · Observed" else clock
        return HeaderText(label, v?.let { metric.format(it, units) } ?: "—", parts.joinToString(" · ").ifBlank { null })
    }

    val vals = hours.mapNotNull(metric::value)
    val hv = hist.mapNotNull(metric::historical)
    if (vals.isEmpty()) return HeaderText(metric.label, "—", null)
    fun f(v: Double) = metric.format(v, units)
    return when (metric) {
        Metric.TEMPERATURE -> HeaderText(
            "High / Low", "${day.high.deg()} / ${day.low.deg()}",
            hv.takeIf { it.isNotEmpty() }?.let { "10-year average ${it.max().deg()} / ${it.min().deg()}" },
        )
        Metric.FEELS_LIKE -> HeaderText(
            "Feels like high / low", "${vals.max().deg()} / ${vals.min().deg()}",
            hv.takeIf { it.isNotEmpty() }?.let { "10-year average ${it.max().deg()} / ${it.min().deg()}" },
        )
        Metric.PRECIP_CHANCE -> {
            val peak = hours.maxBy { it.precipitationChance }
            HeaderText(
                "Chance of precipitation", "${day.precipitationChance}%",
                if (peak.precipitationChance >= 10) "Most likely around ${formatHour(peak.time, use24h)}" else "Little chance of rain",
            )
        }
        Metric.PRECIPITATION -> HeaderText(
            "Total for the day", f(vals.sum()),
            hv.takeIf { it.isNotEmpty() }?.let { "10-year average ${f(it.sum())}" },
        )
        Metric.HUMIDITY -> HeaderText(
            "Average", f(vals.average()),
            hv.takeIf { it.isNotEmpty() }?.let { "10-year average ${f(it.average())}" },
        )
        Metric.WIND -> HeaderText(
            "Strongest wind", f(vals.max()),
            buildList {
                hours.mapNotNull { it.windGusts }.maxOrNull()?.let { add("Gusts up to ${it.roundToInt()} ${units.windLabel}") }
                if (hv.isNotEmpty()) add("Avg ${f(hv.average())}")
            }.joinToString(" · ").ifBlank { null },
        )
        Metric.UV -> HeaderText("Highest UV index", "${vals.max().roundToInt()}", uvLabel(vals.max()))
        Metric.CLOUD_COVER -> HeaderText(
            "Average", f(vals.average()),
            hv.takeIf { it.isNotEmpty() }?.let { "10-year average ${f(it.average())}" },
        )
    }
}

/** Plain-language summary of the day, comparing against the historical average when available. */
private fun summaryFor(
    day: DayForecast,
    hours: List<HourForecast>,
    hist: List<HistoryHour?>,
    units: Units,
    use24h: Boolean,
): String = buildString {
    append("${Condition.describe(day.weatherCode, true)} with a high of ${day.high.deg()} and a low of ${day.low.deg()}.")

    val histTemps = hist.mapNotNull { it?.temperature }
    if (histTemps.isNotEmpty()) {
        val avgHigh = histTemps.max()
        val diff = day.high - avgHigh
        val diffC = units.deltaToC(diff)
        append(' ')
        append(
            when {
                abs(diffC) < 1 -> "That's about normal for this time of year (average high ${avgHigh.deg()})."
                diff > 0 -> "That's ${abs(diff).roundToInt()}° warmer than the 10-year average high of ${avgHigh.deg()}."
                else -> "That's ${abs(diff).roundToInt()}° cooler than the 10-year average high of ${avgHigh.deg()}."
            }
        )
    }

    val snowy = day.condition == Condition.SNOW
    val word = if (snowy) "Snow" else "Rain"
    // Peak = likeliest hour, ties broken by the wettest one.
    val peak = hours.maxWithOrNull(compareBy({ it.precipitationChance }, { it.precipitation ?: 0.0 }))
    val total = hours.sumOf { it.precipitation ?: 0.0 }
    // Showers can add up to a real amount even when no single hour is very likely, so the
    // total counts too (≥ 1 mm / 0.04 in).
    val meaningfulTotal = total >= if (units == Units.IMPERIAL) 0.04 else 1.0
    append(' ')
    if (peak != null && (peak.precipitationChance >= 30 || meaningfulTotal)) {
        append("$word is most likely around ${formatHour(peak.time, use24h)} (${peak.precipitationChance}%)")
        if (total >= 0.005) append(", with about ${formatPrecip(total, units)} expected")
        append('.')
    } else {
        append("No significant ${word.lowercase()} expected.")
    }

    val gust = hours.mapNotNull { it.windGusts }.maxOrNull()
    if (gust != null && units.toKmh(gust) >= 40) {
        append(" Gusts could reach ${gust.roundToInt()} ${units.windLabel}.")
    }
}
