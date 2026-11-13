package com.breeze.weather.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.breeze.weather.data.HistoryHour
import com.breeze.weather.data.LocationAccess
import com.breeze.weather.data.LocationDisabledException
import com.breeze.weather.data.LocationProvider
import com.breeze.weather.data.Place
import com.breeze.weather.data.PlacesStore
import com.breeze.weather.data.Units
import com.breeze.weather.data.Weather
import com.breeze.weather.data.WeatherRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDateTime

data class PlaceWeather(
    val weather: Weather? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class HistoryState(
    val loading: Boolean = false,
    val data: Map<LocalDateTime, HistoryHour>? = null,
    val failed: Boolean = false,
)

data class LocationState(
    val access: LocationAccess = LocationAccess.NONE,
    val place: Place? = null,
    val locating: Boolean = false,
    val error: String? = null,
    val askedBefore: Boolean = false,
)

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Place> = emptyList(),
    val error: String? = null,
)

data class UiState(
    val location: LocationState = LocationState(),
    val saved: List<Place> = emptyList(),
    val units: Units = Units.METRIC,
    val weather: Map<String, PlaceWeather> = emptyMap(),
) {
    /** Pages shown on the home pager: device location first (when permitted), then saved places. */
    val pages: List<Place>
        get() = buildList {
            if (location.access != LocationAccess.NONE) {
                add(location.place ?: Place(Place.CURRENT_ID, "My location", latitude = 0.0, longitude = 0.0))
            }
            addAll(saved)
        }
}

@OptIn(FlowPreview::class)
class WeatherViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = WeatherRepository(app)
    private val store = PlacesStore(app)
    private val locator = LocationProvider(app)

    private val location = MutableStateFlow(
        LocationState(
            access = locator.access(),
            place = store.lastCurrentPlace,
            askedBefore = store.askedForLocation,
        )
    )
    private val weather = MutableStateFlow<Map<String, PlaceWeather>>(emptyMap())
    private val jobs = mutableMapOf<String, Job>()

    val state: StateFlow<UiState> = combine(location, store.saved, store.units, weather) { loc, saved, units, wx ->
        UiState(loc, saved, units, wx)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val _history = MutableStateFlow<Map<String, HistoryState>>(emptyMap())
    /** Historical hourly averages keyed by place id, loaded lazily for the day-detail chart. */
    val history: StateFlow<Map<String, HistoryState>> = _history.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    init {
        // Show cached data immediately, then refresh anything stale.
        viewModelScope.launch {
            val units = store.units.value
            val places = buildList {
                store.lastCurrentPlace?.let { if (locator.access() != LocationAccess.NONE) add(it) }
                addAll(store.saved.value)
            }
            places.forEach { place ->
                repo.cached(place, units)?.let { cached -> setWeather(place.id) { it.copy(weather = cached) } }
            }
            refreshAll(force = false)
        }

        _search.map { it.query.trim() }
            .distinctUntilChanged()
            .debounce(300)
            .onEach { runSearch(it) }
            .launchIn(viewModelScope)
    }

    // ---------- Location ----------

    /** Called after the permission dialog returns, or on resume when access may have changed. */
    fun onPermissionsChanged() {
        val newAccess = locator.access()
        val old = location.value.access
        location.update { it.copy(access = newAccess) }
        when {
            newAccess == LocationAccess.NONE -> {
                location.update { it.copy(locating = false, error = null) }
                weather.update { it - Place.CURRENT_ID }
            }
            newAccess != old || location.value.place == null -> refreshCurrentLocation()
        }
    }

    fun markAskedForLocation() {
        store.askedForLocation = true
        location.update { it.copy(askedBefore = true) }
    }

    fun refreshCurrentLocation(forceWeather: Boolean = false) {
        if (locator.access() == LocationAccess.NONE) return
        if (location.value.locating) return
        location.update { it.copy(locating = true, error = null) }
        viewModelScope.launch {
            try {
                val fix = locator.currentLocation()
                val previous = location.value.place
                // Reuse the previous name if we haven't moved meaningfully (avoids geocoder churn).
                val place = if (previous != null && distanceKm(previous, fix.latitude, fix.longitude) < 0.5) {
                    previous.copy(latitude = fix.latitude, longitude = fix.longitude)
                } else {
                    locator.describe(fix)
                }
                val moved = previous == null || distanceKm(previous, place.latitude, place.longitude) > 1.0
                store.lastCurrentPlace = place
                location.update { it.copy(place = place, locating = false, error = null) }
                loadWeather(place, force = forceWeather || moved)
            } catch (e: LocationDisabledException) {
                location.update { it.copy(locating = false, error = "Turn on location services to see local weather") }
                location.value.place?.let { loadWeather(it, force = forceWeather) }
            } catch (e: Exception) {
                location.update { it.copy(locating = false, error = e.message ?: "Couldn't find your location") }
                location.value.place?.let { loadWeather(it, force = forceWeather) }
            }
        }
    }

    // ---------- Weather ----------

    fun refreshAll(force: Boolean) {
        if (locator.access() != LocationAccess.NONE) refreshCurrentLocation(forceWeather = force)
        store.saved.value.forEach { loadWeather(it, force) }
    }

    fun refresh(place: Place) {
        if (_history.value[place.id]?.failed == true) _history.update { it - place.id }
        if (place.isCurrent) refreshCurrentLocation(forceWeather = true) else loadWeather(place, force = true)
    }

    private fun loadWeather(place: Place, force: Boolean) {
        val existing = weather.value[place.id]
        val units = store.units.value
        val fresh = existing?.weather?.let {
            it.units == units && System.currentTimeMillis() - it.fetchedAt < STALE_AFTER_MS
        } ?: false
        if (fresh && !force) return
        if (jobs[place.id]?.isActive == true) return

        // While the refresh runs, don't show hours and days that have already gone by.
        setWeather(place.id) { pw -> pw.copy(loading = true, error = null, weather = pw.weather?.let { it.rebasedTo(it.localNow()) }) }
        jobs[place.id] = viewModelScope.launch {
            try {
                val result = repo.forecast(place, units)
                setWeather(place.id) { PlaceWeather(weather = result) }
            } catch (e: Exception) {
                val message = if (e is IOException) "Can't reach the weather service" else "Something went wrong"
                setWeather(place.id) { it.copy(loading = false, error = message) }
            }
        }
    }

    private fun setWeather(id: String, transform: (PlaceWeather) -> PlaceWeather) {
        weather.update { map -> map + (id to transform(map[id] ?: PlaceWeather())) }
    }

    // ---------- Saved places ----------

    fun addPlace(place: Place) {
        store.add(place)
        loadWeather(place, force = false)
    }

    fun removePlace(place: Place) {
        store.remove(place.id)
    }

    /** Restores a removed place (undo). */
    fun restorePlace(place: Place, index: Int) {
        store.add(place, index)
        loadWeather(place, force = false)
    }

    fun setUnits(units: Units) {
        if (units == store.units.value) return
        store.setUnits(units)
        _history.value = emptyMap()
        viewModelScope.launch {
            state.value.pages.forEach { place ->
                val cached = repo.cached(place, units)
                setWeather(place.id) { it.copy(weather = cached ?: it.weather) }
                loadWeather(place, force = false)
            }
        }
    }

    // ---------- History ----------

    fun loadHistory(place: Place) {
        val existing = _history.value[place.id]
        if (existing != null && (existing.loading || existing.data != null)) return
        val days = state.value.weather[place.id]?.weather?.daily ?: return
        val start = days.firstOrNull()?.date ?: return
        val end = days.last().date
        val units = store.units.value
        _history.update { it + (place.id to HistoryState(loading = true)) }
        viewModelScope.launch {
            val result = runCatching { repo.history(place, units, start, end) }
            // Drop the result if units changed while loading.
            if (units != store.units.value) return@launch
            _history.update {
                it + (place.id to result.fold(
                    onSuccess = { data -> HistoryState(data = data) },
                    onFailure = { HistoryState(failed = true) },
                ))
            }
        }
    }

    // ---------- Search ----------

    fun onQueryChange(query: String) {
        _search.update { it.copy(query = query) }
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            _search.update { it.copy(loading = false, results = emptyList(), error = null) }
            return
        }
        _search.update { it.copy(loading = true, error = null) }
        searchJob = viewModelScope.launch {
            try {
                val results = repo.search(query)
                _search.update { it.copy(loading = false, results = results) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _search.update { it.copy(loading = false, results = emptyList(), error = "Search is unavailable right now") }
            }
        }
    }

    private fun distanceKm(place: Place, lat: Double, lon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat - place.latitude)
        val dLon = Math.toRadians(lon - place.longitude)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(place.latitude)) * Math.cos(Math.toRadians(lat)) *
            Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    private companion object {
        const val STALE_AFTER_MS = 15 * 60 * 1000L
    }
}
