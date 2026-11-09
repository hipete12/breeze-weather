package com.breeze.weather.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.Locale

/** Persists saved places, the last resolved device location, and the unit preference. */
class PlacesStore(context: Context) {

    private val prefs = context.getSharedPreferences("breeze", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _saved = MutableStateFlow(readSaved())
    val saved: StateFlow<List<Place>> = _saved.asStateFlow()

    private val _units = MutableStateFlow(readUnits())
    val units: StateFlow<Units> = _units.asStateFlow()

    var lastCurrentPlace: Place?
        get() = prefs.getString(KEY_CURRENT, null)
            ?.let { runCatching { json.decodeFromString<Place>(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_CURRENT)
                else putString(KEY_CURRENT, json.encodeToString(value))
            }.apply()
        }

    /** True once we've asked for location permission at least once. */
    var askedForLocation: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED, value).apply()

    fun add(place: Place, index: Int = _saved.value.size) {
        if (_saved.value.any { it.id == place.id }) return
        val list = _saved.value.toMutableList()
        list.add(index.coerceIn(0, list.size), place)
        writeSaved(list)
    }

    fun remove(id: String) = writeSaved(_saved.value.filterNot { it.id == id })

    fun setUnits(units: Units) {
        prefs.edit().putString(KEY_UNITS, units.name).apply()
        _units.value = units
    }

    private fun writeSaved(list: List<Place>) {
        prefs.edit().putString(KEY_SAVED, json.encodeToString(list)).apply()
        _saved.value = list
    }

    private fun readSaved(): List<Place> = prefs.getString(KEY_SAVED, null)
        ?.let { runCatching { json.decodeFromString<List<Place>>(it) }.getOrNull() }
        .orEmpty()

    private fun readUnits(): Units {
        prefs.getString(KEY_UNITS, null)?.let { name ->
            Units.entries.firstOrNull { it.name == name }?.let { return it }
        }
        // Default to Fahrenheit only where it is the customary unit.
        val country = Locale.getDefault().country
        return if (country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) Units.IMPERIAL else Units.METRIC
    }

    private companion object {
        const val KEY_SAVED = "saved_places"
        const val KEY_CURRENT = "current_place"
        const val KEY_UNITS = "units"
        const val KEY_ASKED = "asked_location"
    }
}
