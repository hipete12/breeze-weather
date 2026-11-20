package com.breeze.weather.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The launcher-facing side of one widget kind. Each kind has its own subclass so it gets its own
 * entry, preview and default size in the widget picker; they all share this behaviour.
 */
abstract class BreezeWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetIntents.ACTION_REFRESH) {
            Toast.makeText(context, "Updating weather…", Toast.LENGTH_SHORT).show()
            WidgetUpdater.requestRefresh(context, force = true)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.schedule(context)
        async {
            val stale = WidgetUpdater.update(context, appWidgetIds.map { it to kind })
            // Only fetch when something is missing or old: an unconditional refresh here could
            // loop, as WorkManager toggling its components makes the system send updates again.
            if (stale) WidgetUpdater.requestRefresh(context, force = false)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // Resized, or the launcher reported its real size: lay it out again.
        async { WidgetUpdater.update(context, listOf(appWidgetId to kind)) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetStore(context)
        appWidgetIds.forEach(store::delete)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.schedule(context)
    }

    override fun onDisabled(context: Context) {
        WidgetUpdater.cancelIfUnused(context)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        WidgetStore(context).move(oldWidgetIds, newWidgetIds)
    }

    private fun async(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }
}

class NowWidgetProvider : BreezeWidgetProvider(WidgetKind.NOW)
class ConditionsWidgetProvider : BreezeWidgetProvider(WidgetKind.CONDITIONS)
class HourlyWidgetProvider : BreezeWidgetProvider(WidgetKind.HOURLY)
class DailyWidgetProvider : BreezeWidgetProvider(WidgetKind.DAILY)
class DetailsWidgetProvider : BreezeWidgetProvider(WidgetKind.DETAILS)
class ForecastWidgetProvider : BreezeWidgetProvider(WidgetKind.FORECAST)

object WidgetProviders {
    fun providerClass(kind: WidgetKind): Class<out BreezeWidgetProvider> = when (kind) {
        WidgetKind.NOW -> NowWidgetProvider::class.java
        WidgetKind.CONDITIONS -> ConditionsWidgetProvider::class.java
        WidgetKind.HOURLY -> HourlyWidgetProvider::class.java
        WidgetKind.DAILY -> DailyWidgetProvider::class.java
        WidgetKind.DETAILS -> DetailsWidgetProvider::class.java
        WidgetKind.FORECAST -> ForecastWidgetProvider::class.java
    }

    fun kindOf(className: String?): WidgetKind? = WidgetKind.entries.firstOrNull { providerClass(it).name == className }
}

/** Redraws every widget when the clock, time zone, 12/24-hour setting or language changes. */
class WidgetSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        WidgetUpdater.update(context, WidgetUpdater.allWidgets(context))
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
