package com.breeze.weather.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Called back by the launcher once a widget requested from the app (with the settings chosen
 * there) lands on the home screen, with the new widget's id.
 */
class WidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val kind = WidgetKind.fromName(intent.getStringExtra(EXTRA_KIND)) ?: WidgetUpdater.kindOf(context, appWidgetId) ?: return
        val store = WidgetStore(context)
        val config = intent.getStringExtra(EXTRA_CONFIG)
            ?.let { runCatching { WidgetStore.json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
            ?: store.pendingPin(kind)
            ?: return
        store.save(appWidgetId, config)
        store.clearPendingPin(kind)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                WidgetUpdater.schedule(context)
                if (WidgetUpdater.update(context, listOf(appWidgetId to kind))) WidgetUpdater.requestRefresh(context, force = false)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "com.breeze.weather.extra.WIDGET_KIND"
        const val EXTRA_CONFIG = "com.breeze.weather.extra.WIDGET_CONFIG"
    }
}
