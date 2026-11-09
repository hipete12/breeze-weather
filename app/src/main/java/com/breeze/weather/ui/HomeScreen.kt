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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val DefaultGradient = listOf(Color(0xFF1B2440), Color(0xFF34436B))

@Composable
fun HomeScreen(
    state: UiState,
    pagerState: PagerState,
    onOpenLocations: () -> Unit,
    onSearch: () -> Unit,
    onRefresh: (Place) -> Unit,
    onRequestLocation: () -> Unit,
    onOpenDay: (Place, Int) -> Unit,
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
                    onOpenDay = { day -> onOpenDay(place, day) },
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
    onOpenDay: (Int) -> Unit,
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
                weather != null -> WeatherBody(weather, onOpenDay)
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
private fun ColumnScope.WeatherBody(weather: Weather, onOpenDay: (Int) -> Unit) {
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

    GlassCard(title = "Next 24 hours", icon = Icons.Rounded.Schedule, onClick = { onOpenDay(0) }) {
        HourlyStrip(weather, use24h)
    }

    Spacer(Modifier.height(12.dp))

    GlassCard(title = "10-day forecast", icon = Icons.Rounded.CalendarMonth) {
        val minT = weather.daily.minOfOrNull { it.low } ?: 0.0
        val maxT = weather.daily.maxOfOrNull { it.high } ?: 1.0
        weather.daily.forEachIndexed { i, day ->
            if (i > 0) HorizontalDivider(color = OnSky.copy(alpha = 0.1f))
            DayRow(
                onClick = { onOpenDay(i) },
                day = day,
                label = formatDay(day.date, c.time.toLocalDate()),
                minT = minT, maxT = maxT,
                currentTemp = if (i == 0) c.temperature else null,
                units = weather.units,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    DetailsSection(weather, use24h)

    Spacer(Modifier.height(20.dp))
    val updated = Instant.ofEpochMilli(weather.fetchedAt).atZone(ZoneId.systemDefault())
    Text(
        "Updated ${updated.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a"))} · Open-Meteo",
        style = MaterialTheme.typography.labelSmall,
        color = OnSky.copy(alpha = 0.55f),
    )
}

@Composable
private fun DayRow(
    onClick: () -> Unit,
    day: DayForecast,
    label: String,
    minT: Double,
    maxT: Double,
    currentTemp: Double?,
    units: Units,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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

@Composable
private fun HourlyStrip(weather: Weather, use24h: Boolean) {
    val hours = weather.hourly
    val showPrecip = hours.take(6).any { it.precipitationChance >= 20 }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Exactly six hours fit the card width on any screen; the rest scroll.
        val itemWidth = maxWidth / 6
        LazyRow {
            itemsIndexed(hours) { i, hour ->
                Column(Modifier.width(itemWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (i == 0) "Now" else formatHour(hour.time, use24h),
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSky.copy(alpha = if (i == 0) 1f else 0.8f),
                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    Icon(
                        hour.condition.icon(hour.isDay), contentDescription = null,
                        tint = hour.condition.iconTint(hour.isDay), modifier = Modifier.size(22.dp),
                    )
                    if (showPrecip) {
                        Text(
                            if (hour.precipitationChance >= 20) "${hour.precipitationChance}%" else "",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = RainBlue,
                            modifier = Modifier.height(14.dp),
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        if (i == 0) weather.current.temperature.deg() else hour.temperature.deg(),
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                        color = OnSky,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailsSection(weather: Weather, use24h: Boolean) {
    val c = weather.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeelsLikeTile(weather, Modifier.weight(1f))
            HumidityTile(c.humidity, Modifier.weight(1f))
        }
        WindCard(speed = c.windSpeed, gusts = c.windGusts, direction = c.windDirection, units = weather.units)
        SunCard(
            sunrise = weather.daily.firstOrNull()?.sunrise,
            sunset = weather.daily.firstOrNull()?.sunset,
            now = weather.localNow(),
            nextSunrise = weather.daily.getOrNull(1)?.sunrise,
            use24h = use24h,
        )
    }
}

@Composable
private fun FeelsLikeTile(weather: Weather, modifier: Modifier) {
    val c = weather.current
    val delta = c.feelsLike - c.temperature
    val deltaC = weather.units.deltaToC(delta)
    val color = feelsDeltaColor(delta, weather.units)
    val caption = when {
        abs(deltaC) < 1 -> "Same as actual"
        delta < 0 && c.windSpeed > 10 -> "Wind makes it feel cooler"
        delta < 0 -> "Feels cooler than actual"
        c.humidity > 60 -> "Humidity makes it feel warmer"
        else -> "Feels warmer than actual"
    }
    GlassCard(title = "Feels like", icon = Icons.Rounded.Thermostat, modifier = modifier.height(140.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.feelsLike.deg(), fontSize = 28.sp, color = OnSky)
            Spacer(Modifier.width(8.dp))
            val rounded = delta.roundToInt()
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.22f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (rounded != 0) {
                    Icon(
                        if (rounded < 0) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                        contentDescription = null, tint = color, modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    if (rounded == 0) "±0°" else "${abs(rounded)}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
        }
        Text(
            "Actual ${c.temperature.deg()}",
            style = MaterialTheme.typography.bodySmall,
            color = OnSky.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HumidityTile(humidity: Int, modifier: Modifier) {
    val caption = when {
        humidity < 30 -> "Dry"
        humidity < 60 -> "Comfortable"
        humidity < 80 -> "Humid"
        else -> "Very humid"
    }
    GlassCard(title = "Humidity", icon = Icons.Rounded.WaterDrop, modifier = modifier.height(140.dp)) {
        Text("$humidity%", fontSize = 28.sp, color = OnSky)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(humidity / 100f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFBAE6FD), Color(0xFF38BDF8))))
            )
        }
        Spacer(Modifier.weight(1f))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = OnSky.copy(alpha = 0.8f))
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
