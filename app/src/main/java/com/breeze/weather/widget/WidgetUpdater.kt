package com.breeze.weather.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.breeze.weather.data.LocationAccess
import com.breeze.weather.data.LocationProvider
import com.breeze.weather.data.Place
import com.breeze.weather.data.PlacesStore
import com.breeze.weather.data.WeatherRepository
import com.breeze.weather.data.distanceKm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Keeps widgets current. Widgets draw from the app's forecast cache, so anything that refreshes
 * the cache (the app in the foreground, or [WidgetRefreshWorker] in the background) just asks
 * for a redraw here.
 */
object WidgetUpdater {

    private const val TAG = "BreezeWidgets"
    private const val PERIODIC_WORK = "breeze-widget-refresh"
    private const val ONE_TIME_WORK = "breeze-widget-refresh-now"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingRedraw: Job? = null

    /** Every placed Breeze widget, with its kind. */
    fun allWidgets(context: Context): List<Pair<Int, WidgetKind>> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyList()
        return WidgetKind.entries.flatMap { kind ->
            manager.getAppWidgetIds(ComponentName(context, WidgetProviders.providerClass(kind))).map { it to kind }
        }
    }

    fun kindOf(context: Context, appWidgetId: Int): WidgetKind? =
        WidgetProviders.kindOf(AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider?.className)

    /** Redraws every widget soon, coalescing bursts (e.g. several places refreshing at once). */
    fun redrawAllSoon(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            pendingRedraw?.cancel()
            pendingRedraw = scope.launch {
                delay(400)
                runCatching { update(app, allWidgets(app)) }.onFailure { Log.w(TAG, "Redraw failed", it) }
            }
        }
    }

    /**
     * Redraws [widgets] from the cache.
     *
     * @return true if any of them has no forecast, or one older than the refresh interval.
     */
    suspend fun update(context: Context, widgets: List<Pair<Int, WidgetKind>>): Boolean {
        if (widgets.isEmpty()) return false
        val manager = AppWidgetManager.getInstance(context)
        val sources = Sources(context)
        val renderer = WidgetRenderer(context)
        val staleAfter = TimeUnit.MINUTES.toMillis(sources.widgetStore.refreshMinutes.toLong())
        var stale = false
        for ((id, kind) in widgets) {
            val config = sources.widgetStore.configOrDefault(id, kind)
            val data = sources.load(id, config)
            val weather = data.weather
            if (data.state != DataState.NO_LOCATION && (weather == null || System.currentTimeMillis() - weather.fetchedAt > staleAfter)) stale = true
            val sizes = WidgetSizes.from(manager.getAppWidgetOptions(id), kind)
            Log.d(TAG, "Drawing widget $id ($kind, ${data.state}) at $sizes")
            try {
                manager.updateAppWidget(id, renderer.build(id, kind, config, data, sizes))
            } catch (e: RuntimeException) {
                // Most likely too much bitmap data for the launcher: fall back to one layout.
                Log.w(TAG, "Update of widget $id failed; retrying with a single layout", e)
                runCatching { manager.updateAppWidget(id, renderer.build(id, kind, config, data, WidgetSizes.fixed(sizes.primary.width, sizes.primary.height))) }
            }
        }
        return stale
    }

    /** Loads what a widget needs from the app's stores. */
    class Sources(context: Context) {
        val widgetStore = WidgetStore(context)
        val places = PlacesStore(context)
        val repo = WeatherRepository(context)
        val locator = LocationProvider(context)
        val units get() = places.units.value

        /** The place a widget shows: its own, or the device's last known location. */
        fun placeFor(config: WidgetConfig): Place? =
            config.place ?: places.lastCurrentPlace?.takeIf { locator.access() != LocationAccess.NONE }

        suspend fun load(appWidgetId: Int, config: WidgetConfig): WidgetData {
            val place = placeFor(config) ?: return WidgetData(null, null, DataState.NO_LOCATION)
            val weather = repo.cached(place, units)
            val state = when {
                weather != null -> DataState.READY
                widgetStore.failed(appWidgetId) -> DataState.ERROR
                else -> DataState.LOADING
            }
            return WidgetData(place, weather, state)
        }
    }

    /**
     * Fetches fresh forecasts for every place a widget shows (and the device's location, when
     * allowed in the background), then redraws. Runs in [WidgetRefreshWorker].
     *
     * @param force fetch even if the cached forecast is recent (the user tapped refresh).
     */
    suspend fun refresh(context: Context, force: Boolean) {
        val widgets = allWidgets(context)
        if (widgets.isEmpty()) return
        val sources = Sources(context)
        val configs = widgets.map { (id, kind) -> id to sources.widgetStore.configOrDefault(id, kind) }

        if (configs.any { it.second.followsLocation } && canLocateInBackground(context)) {
            withTimeoutOrNull(45_000) { updateDeviceLocation(sources) }
        }

        val units = sources.units
        val freshFor = TimeUnit.MINUTES.toMillis((sources.widgetStore.refreshMinutes - 5L).coerceAtLeast(10L))
        val failed = mutableSetOf<String>()
        configs.mapNotNull { sources.placeFor(it.second) }.distinctBy { it.id }.forEach { place ->
            val cached = sources.repo.cached(place, units)
            val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAt < freshFor
            if (force || !fresh) {
                runCatching { sources.repo.forecast(place, units) }.onFailure {
                    Log.w(TAG, "Forecast for ${place.name} failed", it)
                    failed += place.id
                }
            }
        }
        configs.forEach { (id, config) -> sources.widgetStore.setFailed(id, sources.placeFor(config)?.id in failed) }
        update(context, widgets)
    }

    internal suspend fun updateDeviceLocation(sources: Sources) {
        runCatching {
            // Background requests are throttled to a few an hour, so don't wait long for a fresh
            // fix; fall back to the newest location any app has obtained.
            val fix = withTimeoutOrNull(10_000) { runCatching { sources.locator.currentLocation() }.getOrNull() }
                ?: sources.locator.newestLastKnown()
                ?: return
            val previous = sources.places.lastCurrentPlace
            // Keep the old name if we haven't moved far (saves a geocoder call), like the app does.
            val place = if (previous != null && previous.distanceKm(fix.latitude, fix.longitude) < 0.5) {
                previous.copy(latitude = fix.latitude, longitude = fix.longitude)
            } else {
                sources.locator.describe(fix)
            }
            sources.places.lastCurrentPlace = place
        }.onFailure { Log.i(TAG, "Background location unavailable: ${it.message}") }
    }

    /** Whether the device's location can be read while Breeze isn't on screen. */
    fun canLocateInBackground(context: Context): Boolean {
        if (LocationProvider(context).access() == LocationAccess.NONE) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Makes sure the periodic refresh is scheduled. Its interval comes from the widget settings;
     * pass [intervalChanged] after changing it.
     */
    fun schedule(context: Context, intervalChanged: Boolean = false) {
        val minutes = WidgetStore(context).refreshMinutes.toLong()
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(minutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            if (intervalChanged) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Refreshes in the background as soon as possible. */
    fun requestRefresh(context: Context, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInputData(workDataOf(WidgetRefreshWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Stops background refreshes once the last widget is gone. */
    fun cancelIfUnused(context: Context) {
        if (allWidgets(context).isEmpty()) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK)
        }
    }
}

/** Background refresh for widgets: periodic, and on demand when a widget is tapped to refresh. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.refresh(applicationContext, force = inputData.getBoolean(KEY_FORCE, false))
        return Result.success()
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}
