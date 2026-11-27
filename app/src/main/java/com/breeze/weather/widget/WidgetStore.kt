package com.breeze.weather.widget

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * Per-widget settings and widget-wide preferences, in their own SharedPreferences file so the
 * app's own preferences stay untouched.
 */
class WidgetStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("breeze_widgets", Context.MODE_PRIVATE)

    /** The saved settings for [appWidgetId], or null if it has never been configured. */
    fun config(appWidgetId: Int): WidgetConfig? = prefs.getString(configKey(appWidgetId), null)?.let(::decode)

    /** The saved settings for [appWidgetId], or [kind]'s defaults. */
    fun configOrDefault(appWidgetId: Int, kind: WidgetKind): WidgetConfig = config(appWidgetId) ?: kind.defaultConfig()

    fun save(appWidgetId: Int, config: WidgetConfig) {
        prefs.edit().putString(configKey(appWidgetId), encode(config.sanitized())).apply()
    }

    fun delete(appWidgetId: Int) {
        prefs.edit().remove(configKey(appWidgetId)).remove(errorKey(appWidgetId)).apply()
    }

    /** Moves settings to new ids after a backup is restored onto a new device or launcher. */
    fun move(oldIds: IntArray, newIds: IntArray) {
        val editor = prefs.edit()
        val moved = oldIds.zip(newIds).mapNotNull { (old, new) -> prefs.getString(configKey(old), null)?.let { new to it } }
        oldIds.forEach { editor.remove(configKey(it)) }
        moved.forEach { (new, json) -> editor.putString(configKey(new), json) }
        editor.apply()
    }

    /**
     * Settings chosen in the app before asking the launcher to pin a new widget. The launcher may
     * open the configuration screen for the new widget too; it starts from these settings then.
     */
    fun setPendingPin(kind: WidgetKind, config: WidgetConfig) {
        prefs.edit()
            .putString(pendingKey(kind), encode(config.sanitized()))
            .putLong(pendingTimeKey(kind), System.currentTimeMillis())
            .apply()
    }

    /** Pending settings for a widget of [kind] being pinned right now, if any. */
    fun pendingPin(kind: WidgetKind, maxAgeMs: Long = PENDING_MAX_AGE_MS): WidgetConfig? {
        val at = prefs.getLong(pendingTimeKey(kind), 0L)
        if (System.currentTimeMillis() - at > maxAgeMs) return null
        return prefs.getString(pendingKey(kind), null)?.let(::decode)
    }

    fun clearPendingPin(kind: WidgetKind) {
        prefs.edit().remove(pendingKey(kind)).remove(pendingTimeKey(kind)).apply()
    }

    /** Minutes between background refreshes. */
    var refreshMinutes: Int
        get() = prefs.getInt(KEY_REFRESH, DEFAULT_REFRESH_MINUTES).let { if (it in REFRESH_CHOICES) it else DEFAULT_REFRESH_MINUTES }
        set(value) = prefs.edit().putInt(KEY_REFRESH, value).apply()

    /** When the last background refresh failed to reach the weather service, for [appWidgetId]'s place. */
    fun setFailed(appWidgetId: Int, failed: Boolean) {
        prefs.edit().apply { if (failed) putBoolean(errorKey(appWidgetId), true) else remove(errorKey(appWidgetId)) }.apply()
    }

    fun failed(appWidgetId: Int): Boolean = prefs.getBoolean(errorKey(appWidgetId), false)

    private fun decode(text: String): WidgetConfig? = runCatching { json.decodeFromString<WidgetConfig>(text).sanitized() }.getOrNull()

    private fun encode(config: WidgetConfig): String = json.encodeToString(config)

    companion object {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

        const val DEFAULT_REFRESH_MINUTES = 30
        val REFRESH_CHOICES = listOf(30, 60, 120, 180)
        private const val PENDING_MAX_AGE_MS = 2 * 60 * 1000L
        private const val KEY_REFRESH = "refresh_minutes"

        private fun configKey(id: Int) = "config_$id"
        private fun errorKey(id: Int) = "failed_$id"
        private fun pendingKey(kind: WidgetKind) = "pending_${kind.name}"
        private fun pendingTimeKey(kind: WidgetKind) = "pending_at_${kind.name}"
    }
}
