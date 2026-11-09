package com.breeze.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeze.weather.data.LocationAccess
import com.breeze.weather.data.Place
import com.breeze.weather.data.Units
import com.breeze.weather.ui.theme.InkRaised
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    state: UiState,
    search: SearchState,
    focusSearch: Boolean,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (Place) -> Unit,
    onAdd: (Place) -> Unit,
    onRemove: (Place) -> Unit,
    onRestore: (Place, Int) -> Unit,
    onUnits: (Units) -> Unit,
    onRequestLocation: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var restoreCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusSearch) { if (focusSearch) focusRequester.requestFocus() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Locations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { UnitToggle(state.units, onUnits) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            OutlinedTextField(
                value = search.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search for a city") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange(""); focus.clearFocus() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = InkRaised,
                    focusedContainerColor = InkRaised,
                    unfocusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
            )

            if (search.query.isNotBlank()) {
                SearchResults(
                    search = search,
                    savedIds = state.saved.map { it.id }.toSet(),
                    onPick = { place ->
                        focus.clearFocus()
                        if (state.saved.none { it.id == place.id }) onAdd(place)
                        onQueryChange("")
                        onSelect(place)
                    },
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.location.access == LocationAccess.NONE) {
                        item(key = "enable") { EnableLocationCard(onRequestLocation) }
                    } else {
                        val current = state.pages.first()
                        item(key = current.id) {
                            PlaceCard(current, state.weather[current.id], onClick = { onSelect(current) })
                        }
                    }
                    items(state.saved, key = { it.id }) { place ->
                        // A restored item revives its old composition (still mid exit-animation), whose
                        // swipe state is "dismissed". Keying on restoreCount gives it a fresh state.
                        val dismiss = key(restoreCount) { rememberSwipeToDismissBoxState() }
                        SwipeToDismissBox(
                            state = dismiss,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = { DeleteBackground() },
                            onDismiss = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    val index = state.saved.indexOfFirst { it.id == place.id }
                                    onRemove(place)
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        val result = snackbar.showSnackbar(
                                            "Removed ${place.name}", actionLabel = "Undo",
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            restoreCount++
                                            onRestore(place, index)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.animateItem(),
                        ) {
                            PlaceCard(place, state.weather[place.id], onClick = { onSelect(place) })
                        }
                    }
                    if (state.saved.isEmpty()) {
                        item(key = "hint") {
                            Text(
                                "Search above to save the places you care about. Swipe left on a saved place to remove it.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitToggle(units: Units, onUnits: (Units) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.padding(end = 12.dp).width(116.dp)) {
        Units.entries.forEachIndexed { i, u ->
            SegmentedButton(
                selected = units == u,
                onClick = { onUnits(u) },
                shape = SegmentedButtonDefaults.itemShape(i, Units.entries.size),
                icon = {},
                label = { Text(u.symbol()) },
            )
        }
    }
}

@Composable
private fun PlaceCard(place: Place, placeWeather: PlaceWeather?, onClick: () -> Unit) {
    val weather = placeWeather?.weather
    val gradient = weather?.let { it.current.condition.gradient(it.current.isDay) }
        ?: listOf(InkRaised, InkRaised)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(gradient))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (place.isCurrent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Navigation, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(12.dp).rotate(45f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("My location", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                }
            } else if (place.subtitle.isNotBlank()) {
                Text(
                    place.subtitle, style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                place.name, style = MaterialTheme.typography.titleLarge, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                weather?.current?.description ?: placeWeather?.error ?: "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        if (weather != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    weather.current.temperature.deg(),
                    fontSize = 44.sp, fontWeight = FontWeight.Light, color = Color.White,
                )
                weather.daily.firstOrNull()?.let {
                    Text(
                        "H:${it.high.deg()}  L:${it.low.deg()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        } else if (placeWeather?.loading != false) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun DeleteBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFFB3261E))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Color.White)
    }
}

@Composable
private fun EnableLocationCard(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Use current location", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Get precise weather wherever you are",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun SearchResults(search: SearchState, savedIds: Set<String>, onPick: (Place) -> Unit) {
    when {
        search.loading && search.results.isEmpty() -> Box(
            Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }

        search.error != null -> Hint(search.error)
        search.query.trim().length < 2 -> Hint("Keep typing…")
        search.results.isEmpty() -> Hint("No places match “${search.query.trim()}”")

        else -> LazyColumn(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            items(search.results, key = { it.id }) { place ->
                val saved = place.id in savedIds
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onPick(place) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = MaterialTheme.typography.titleMedium)
                        if (place.subtitle.isNotBlank()) {
                            Text(
                                place.subtitle, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(
                        if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.Add,
                        contentDescription = if (saved) "Saved" else "Add",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
    )
}
