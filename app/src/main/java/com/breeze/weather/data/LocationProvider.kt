package com.breeze.weather.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class LocationDisabledException : Exception("Location services are turned off")
class LocationUnavailableException : Exception("Couldn't determine your location")

/**
 * Resolves the device's current location using the platform LocationManager (no Play Services
 * dependency). With fine permission it requests a fresh high-accuracy fix rather than relying on
 * a stale cached location.
 */
class LocationProvider(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    fun access(): LocationAccess = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationAccess.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.APPROXIMATE
        else -> LocationAccess.NONE
    }

    fun isEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(manager)

    /** Returns a fresh location fix, falling back to the best recent cached fix. */
    suspend fun currentLocation(): Location {
        val access = access()
        check(access != LocationAccess.NONE) { "Location permission not granted" }
        if (!isEnabled()) throw LocationDisabledException()

        val precise = access == LocationAccess.PRECISE
        for ((provider, timeoutMs) in providerPlan(precise)) {
            val fix = withTimeoutOrNull(timeoutMs) { requestFix(provider, precise) }
            if (fix != null) return fix
        }
        return bestLastKnown() ?: throw LocationUnavailableException()
    }

    /** Best-effort reverse geocode into a [Place] named after the locality. */
    suspend fun describe(location: Location): Place {
        val address = withTimeoutOrNull(6_000) { reverseGeocode(location) }
        val name = address?.locality
            ?: address?.subAdminArea
            ?: address?.adminArea
            ?: "My location"
        return Place(
            id = Place.CURRENT_ID,
            name = name,
            region = address?.adminArea,
            country = address?.countryName,
            latitude = location.latitude,
            longitude = location.longitude,
        )
    }

    private fun providerPlan(precise: Boolean): List<Pair<String, Long>> {
        val enabled = manager.getProviders(true).toSet()
        val fused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null
        val ordered = if (precise) {
            listOfNotNull(fused, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOfNotNull(LocationManager.NETWORK_PROVIDER, fused)
        }
        return ordered.filter { it in enabled }.mapIndexed { i, p -> p to if (i == 0) 12_000L else 8_000L }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFix(provider: String, precise: Boolean): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        val request = LocationRequest.Builder(0L)
                            .setQuality(
                                if (precise) LocationRequest.QUALITY_HIGH_ACCURACY
                                else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                            )
                            .setDurationMillis(15_000)
                            .build()
                        manager.getCurrentLocation(provider, request, signal, executor) {
                            if (cont.isActive) cont.resume(it)
                        }
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        manager.getCurrentLocation(provider, signal, executor) {
                            if (cont.isActive) cont.resume(it)
                        }
                    }
                    else -> {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                manager.removeUpdates(this)
                                if (cont.isActive) cont.resume(location)
                            }
                            @Deprecated("Deprecated in API")
                            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
                            override fun onProviderDisabled(provider: String) {
                                manager.removeUpdates(this)
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                        signal.setOnCancelListener { manager.removeUpdates(listener) }
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * The newest location any app on the device has obtained, however old, or null. For
     * background use: Android throttles background location requests to a few per hour, but this
     * cached location is at least as recent as the last fix this app got itself.
     */
    @SuppressLint("MissingPermission")
    fun newestLastKnown(): Location? {
        if (access() == LocationAccess.NONE) return null
        return (manager.allProviders + LocationManager.PASSIVE_PROVIDER).distinct()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        val maxAgeMs = 30 * 60 * 1000L
        val now = System.currentTimeMillis()
        return manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time < maxAgeMs }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
    }

    private suspend fun reverseGeocode(location: Location): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(
                    location.latitude, location.longitude, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }
                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }
                    .getOrNull()
            }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
