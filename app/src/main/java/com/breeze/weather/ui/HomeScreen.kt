package com.breeze.weather.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeze.weather.data.Condition
import com.breeze.weather.data.DayForecast
import com.breeze.weather.data.LocationAccess
import com.breeze.weather.data.Place
import com.breeze.weather.data.Units
import com.breeze.weather.data.Weather
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val OnSky = Color.White
private val CardShape = RoundedCornerShape(24.dp)
private val DefaultGradient = listOf(Color(0xFF1B2440), Color(0xFF34436B))

@Composable
fun HomeScreen(
    state: UiState,
    pagerState: PagerState,
    onOpenLocations: () -> Unit,
    onSearch: () -> Unit,
    onRefresh: (Place) -> Unit,
    onRequestLocation: () -> Unit,
) {
    val pages = state.pages
    val settled = pages.getOrNull(pagerState.currentPage)
    val settledWeather = settled?.let { state.weather[it.id]?.weather }
    val colors = settledWeather?.let { it.current.condition.gradient(it.current.isDay) } ?: DefaultGradient
    val top by animateColorAsState(colors[0], tween(700), label = "top")
    val bottom by animateColorAsState(colors[1], tween(700), label = "bottom")

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
    ) {
        if (pages.isEmpty()) {
            Welcome(onRequestLocation = onRequestLocation, onSearch = onSearch)
        } else {
            HorizontalPager(
                state = pagerState,
                key = { pages.getOrNull(it)?.id ?: it },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                val place = pages.getOrNull(index) ?: return@HorizontalPager
                PlacePage(
                    place = place,
                    placeWeather = state.weather[place.id],
                    location = if (place.isCurrent) state.location else null,
                    onRefresh = { onRefresh(place) },
                    onRequestPrecise = onRequestLocation,
                )
            }
            // Fade content out under the status bar so scrolled text never collides with it.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(top, top.copy(alpha = 0.85f), Color.Transparent)))
                    .statusBarsPadding()
                    .height(16.dp)
            )
            BottomBar(
                scrim = bottom,
                count = pages.size,
                current = pagerState.currentPage,
                firstIsLocation = pages.firstOrNull()?.isCurrent == true,
                onOpenLocations = onOpenLocations,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacePage(
    place: Place,
    placeWeather: PlaceWeather?,
    location: com.breeze.weather.ui.LocationState?,
    onRefresh: () -> Unit,
    onRequestPrecise: () -> Unit,
) {
    val weather = placeWeather?.weather
    val refreshing = (placeWeather?.loading == true && weather != null) || (location?.locating == true && weather != null)
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            PlaceHeader(place, location, onRequestPrecise)
            when {
                weather != null -> WeatherBody(weather)
                placeWeather?.error != null -> Message(placeWeather.error, "Try again", onRefresh)
                location?.error != null && location.place == null -> Message(location.error, "Try again", onRefresh)
                else -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OnSky, strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(96.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun PlaceHeader(place: Place, location: com.breeze.weather.ui.LocationState?, onRequestPrecise: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (place.isCurrent) {
            Icon(
                Icons.Rounded.Navigation, contentDescription = "Current location",
                tint = OnSky, modifier = Modifier.size(18.dp).rotate(45f),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            place.name,
            style = MaterialTheme.typography.headlineMedium,
            color = OnSky,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (location != null) {
        when {
            location.locating && location.place == null ->
                Caption("Finding your location…")
            location.error != null && location.place != null ->
                Caption(location.error)
            location.access == LocationAccess.APPROXIMATE -> Pill(
                text = "Approximate location · Use precise",
                icon = Icons.Rounded.MyLocation,
                onClick = onRequestPrecise,
            )
        }
    }
}

@Composable
private fun ColumnScope.WeatherBody(weather: Weather) {
    val context = LocalContext.current
    val use24h = remember { DateFormat.is24HourFormat(context) }
    val today = weather.daily.firstOrNull()
    val c = weather.current

    Text(
        c.temperature.deg(),
        style = MaterialTheme.typography.displayLarge,
        color = OnSky,
        modifier = Modifier.padding(start = 28.dp),
    )
    Text(c.description, style = MaterialTheme.typography.titleLarge, color = OnSky.copy(alpha = 0.92f))
    if (today != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            "H:${today.high.deg()}   L:${today.low.deg()}",
            style = MaterialTheme.typography.titleMedium,
            color = OnSky.copy(alpha = 0.8f),
        )
    }
    Spacer(Modifier.height(36.dp))

    GlassCard(title = "Next 24 hours", icon = Icons.Rounded.Schedule) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            itemsIndexed(weather.hourly) { i, hour ->
                Column(
                    Modifier.width(62.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (i == 0) "Now" else formatHour(hour.time, use24h),
                        style = MaterialTheme.typography.labelLarge,
                        color = OnSky.copy(alpha = if (i == 0) 1f else 0.85f),
                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(10.dp))
                    Icon(
                        hour.condition.icon(hour.isDay), contentDescription = null,
                        tint = hour.condition.iconTint(hour.isDay), modifier = Modifier.size(26.dp),
                    )
                    Text(
                        if (hour.precipitationChance >= 20) "${hour.precipitationChance}%" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9AD0FF),
                        modifier = Modifier.height(16.dp),
                    )
                    Text(
                        if (i == 0) c.temperature.deg() else hour.temperature.deg(),
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSky,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    GlassCard(title = "10-day forecast", icon = Icons.Rounded.CalendarMonth) {
        val minT = weather.daily.minOfOrNull { it.low } ?: 0.0
        val maxT = weather.daily.maxOfOrNull { it.high } ?: 1.0
        weather.daily.forEachIndexed { i, day ->
            if (i > 0) HorizontalDivider(color = OnSky.copy(alpha = 0.1f))
            DayRow(
                day = day,
                label = formatDay(day.date, c.time.toLocalDate()),
                minT = minT, maxT = maxT,
                currentTemp = if (i == 0) c.temperature else null,
                units = weather.units,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    DetailsGrid(weather, use24h)

    Spacer(Modifier.height(20.dp))
    val updated = Instant.ofEpochMilli(weather.fetchedAt).atZone(ZoneId.systemDefault())
    Text(
        "Updated ${updated.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a"))} · Open-Meteo",
        style = MaterialTheme.typography.labelSmall,
        color = OnSky.copy(alpha = 0.55f),
    )
}

@Composable
private fun DayRow(day: DayForecast, label: String, minT: Double, maxT: Double, currentTemp: Double?, units: Units) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = OnSky, modifier = Modifier.width(64.dp))
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                day.condition.icon(true), contentDescription = null,
                tint = day.condition.iconTint(true), modifier = Modifier.size(24.dp),
            )
            if (day.precipitationChance >= 20) {
                Text(
                    "${day.precipitationChance}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color(0xFF9AD0FF),
                )
            }
        }
        Text(
            day.low.deg(), style = MaterialTheme.typography.titleMedium,
            color = OnSky.copy(alpha = 0.65f), textAlign = TextAlign.End, modifier = Modifier.width(44.dp),
        )
        TemperatureBar(
            low = day.low, high = day.high, minT = minT, maxT = maxT,
            current = currentTemp, units = units,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Text(
            day.high.deg(), style = MaterialTheme.typography.titleMedium,
            color = OnSky, modifier = Modifier.width(36.dp),
        )
    }
}

@Composable
private fun TemperatureBar(
    low: Double, high: Double, minT: Double, maxT: Double, current: Double?, units: Units,
    modifier: Modifier = Modifier,
) {
    val span = (maxT - minT).takeIf { it > 0 } ?: 1.0
    val start = ((low - minT) / span).toFloat()
    val end = ((high - minT) / span).toFloat()
    val lowColor = tempColor(low, units)
    val highColor = tempColor(high, units)
    Canvas(modifier.height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.16f), cornerRadius = r)
        val x0 = size.width * start
        val w = (size.width * (end - start)).coerceAtLeast(size.height)
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(lowColor, highColor), startX = x0, endX = x0 + w),
            topLeft = Offset(x0, 0f),
            size = Size(w, size.height),
            cornerRadius = r,
        )
        if (current != null) {
            val cx = (size.width * ((current - minT) / span).toFloat()).coerceIn(0f, size.width)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = size.height * 0.95f, center = Offset(cx, size.height / 2))
            drawCircle(Color.White, radius = size.height * 0.65f, center = Offset(cx, size.height / 2))
        }
    }
}

/** Maps a temperature to a cool→warm color. */
private fun tempColor(t: Double, units: Units): Color {
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

@Composable
private fun DetailsGrid(weather: Weather, use24h: Boolean) {
    val c = weather.current
    val today = weather.daily.firstOrNull()
    val tomorrow = weather.daily.getOrNull(1)

    val feelsDiff = c.feelsLike - c.temperature
    val feelsCaption = when {
        abs(feelsDiff) < 1.5 -> "Similar to the actual temperature"
        feelsDiff < 0 && c.windSpeed > 10 -> "Wind makes it feel cooler"
        feelsDiff < 0 -> "Feels cooler than actual"
        c.humidity > 60 -> "Humidity makes it feel warmer"
        else -> "Feels warmer than actual"
    }
    val humidityCaption = when {
        c.humidity < 30 -> "Dry"
        c.humidity < 60 -> "Comfortable"
        c.humidity < 80 -> "Humid"
        else -> "Very humid"
    }
    val uv = c.uvIndex ?: today?.uvMax ?: 0.0

    val sunrise = today?.sunrise
    val sunset = today?.sunset
    val daylight = if (sunrise != null && sunset != null) Duration.between(sunrise, sunset) else null
    val sunsetCaption = when {
        sunset == null -> ""
        c.time.isBefore(sunset) -> "In ${formatDuration(Duration.between(c.time, sunset))}"
        tomorrow?.sunset != null -> "Tomorrow ${formatClock(tomorrow.sunset, use24h)}"
        else -> ""
    }

    val tiles = listOf(
        Tile("Feels like", Icons.Rounded.Thermostat, c.feelsLike.deg(), feelsCaption),
        Tile("Humidity", Icons.Rounded.WaterDrop, "${c.humidity}%", humidityCaption),
        Tile(
            "Wind", Icons.Rounded.Air,
            "${c.windSpeed.roundToInt()} ${weather.units.windLabel}",
            "From ${windCompass(c.windDirection)}",
            arrowDegrees = c.windDirection + 180f,
        ),
        Tile("UV index", Icons.Rounded.LightMode, uv.roundToInt().toString(), uvLabel(uv)),
        Tile(
            "Sunrise", Icons.Rounded.WbSunny,
            sunrise?.let { formatClock(it, use24h) } ?: "—",
            daylight?.let { "Daylight ${formatDuration(it)}" } ?: "",
        ),
        Tile("Sunset", Icons.Rounded.WbTwilight, sunset?.let { formatClock(it, use24h) } ?: "—", sunsetCaption),
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { DetailTile(it, Modifier.weight(1f)) }
            }
        }
    }
}

private data class Tile(
    val title: String,
    val icon: ImageVector,
    val value: String,
    val caption: String,
    val arrowDegrees: Float? = null,
)

@Composable
private fun DetailTile(tile: Tile, modifier: Modifier) {
    GlassCard(title = tile.title, icon = tile.icon, modifier = modifier.height(132.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tile.value,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                color = OnSky,
                maxLines = 1,
            )
            if (tile.arrowDegrees != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.Navigation, contentDescription = null, tint = OnSky,
                    modifier = Modifier.size(20.dp).rotate(tile.arrowDegrees),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            tile.caption,
            style = MaterialTheme.typography.bodySmall,
            color = OnSky.copy(alpha = 0.8f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GlassCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = OnSky.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = OnSky.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = OnSky.copy(alpha = 0.75f),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Pill(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(top = 10.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = OnSky, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = OnSky)
    }
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = OnSky, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAction, border = BorderStroke(1.dp, OnSky.copy(alpha = 0.5f))) { Text(action, color = OnSky) }
    }
}

@Composable
private fun BottomBar(
    scrim: Color,
    count: Int,
    current: Int,
    firstIsLocation: Boolean,
    onOpenLocations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to scrim.copy(alpha = 0.9f), 1f to scrim))
            .padding(top = 20.dp)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (count > 1) {
                repeat(count) { i ->
                    val alpha = if (i == current) 1f else 0.4f
                    if (i == 0 && firstIsLocation) {
                        Icon(
                            Icons.Rounded.Navigation, contentDescription = null,
                            tint = OnSky.copy(alpha = alpha),
                            modifier = Modifier.size(11.dp).rotate(45f),
                        )
                    } else {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(OnSky.copy(alpha = alpha))
                        )
                    }
                }
            }
        }
        IconButton(onClick = onOpenLocations, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, contentDescription = "Locations", tint = OnSky)
        }
    }
}

@Composable
private fun Welcome(onRequestLocation: () -> Unit, onSearch: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Condition.CLEAR.icon(true), contentDescription = null,
            tint = Condition.CLEAR.iconTint(true), modifier = Modifier.size(88.dp),
        )
        Spacer(Modifier.height(28.dp))
        Text("Breeze", style = MaterialTheme.typography.headlineLarge, color = OnSky)
        Spacer(Modifier.height(8.dp))
        Text(
            "Weather for where you are and the places you love.",
            style = MaterialTheme.typography.bodyLarge,
            color = OnSky.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onRequestLocation,
            colors = ButtonDefaults.buttonColors(containerColor = OnSky, contentColor = Color(0xFF1B2440)),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Use my location")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSearch,
            border = BorderStroke(1.dp, OnSky.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = OnSky, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Search for a city", color = OnSky)
        }
    }
}

private fun formatDuration(d: Duration): String {
    val h = d.toHours()
    val m = d.toMinutes() % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
