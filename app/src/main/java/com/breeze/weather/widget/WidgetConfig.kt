package com.breeze.weather.widget

import com.breeze.weather.data.Place
import kotlinx.serialization.Serializable

/**
 * The widgets Breeze offers. Each is its own entry in the launcher's widget picker (with its own
 * provider class, preview and default size), but they share one renderer: a kind only sets the
 * defaults and which section matters most when space is short.
 *
 * @param defaultWidthDp / defaultHeightDp a typical size at the default cell span, used for
 *   previews and before the launcher reports the real size.
 */
enum class WidgetKind(
    val title: String,
    val description: String,
    val defaultWidthDp: Int,
    val defaultHeightDp: Int,
) {
    NOW("Now", "Temperature and conditions at a glance", 170, 80),
    CONDITIONS("Current conditions", "Today's temperature, conditions and high and low", 170, 170),
    HOURLY("Hourly forecast", "Current conditions and the next hours", 350, 170),
    DAILY("Daily forecast", "Current conditions and the days ahead", 350, 260),
    DETAILS("Weather details", "Feels like, humidity, wind, UV and more", 170, 170),
    FORECAST("Complete forecast", "Now, hourly and daily in one large widget", 350, 350);

    /** Sections shown by default, before the user changes anything. */
    val defaultSections: Set<WidgetSection>
        get() = when (this) {
            NOW, CONDITIONS -> setOf(WidgetSection.HEADER)
            HOURLY -> setOf(WidgetSection.HEADER, WidgetSection.HOURLY)
            DAILY -> setOf(WidgetSection.HEADER, WidgetSection.DAILY)
            DETAILS -> setOf(WidgetSection.DETAILS)
            FORECAST -> setOf(WidgetSection.HEADER, WidgetSection.HOURLY, WidgetSection.DAILY)
        }

    /** The section kept first when the widget is too small for everything the user turned on. */
    val primarySection: WidgetSection
        get() = when (this) {
            NOW, CONDITIONS -> WidgetSection.HEADER
            HOURLY, FORECAST -> WidgetSection.HOURLY
            DAILY -> WidgetSection.DAILY
            DETAILS -> WidgetSection.DETAILS
        }

    /** Whether the user can choose sections; "Now" is always just the current conditions. */
    val hasSections: Boolean get() = this != NOW

    fun defaultConfig(): WidgetConfig = WidgetConfig(
        sections = defaultSections,
        showHighLow = this != NOW,
        metrics = if (this == DETAILS) Metric.DETAILS_DEFAULT else Metric.DEFAULT,
    )

    companion object {
        fun fromName(name: String?): WidgetKind? = entries.firstOrNull { it.name == name }
    }
}

/** Parts of a widget, top to bottom. */
@Serializable
enum class WidgetSection(val label: String) {
    HEADER("Current conditions"),
    HOURLY("Hourly forecast"),
    DAILY("Daily forecast"),
    DETAILS("Details"),
}

/** Background and text colours. */
@Serializable
enum class WidgetTheme {
    /** The app's look: a gradient that follows the current conditions and time of day. */
    SKY,

    /** Follows the system: Material You colours on Android 12+, otherwise light or dark mode. */
    SYSTEM,
    DARK,
    LIGHT,
}

@Serializable
enum class TextScale(val factor: Float, val label: String) {
    SMALL(0.88f, "Small"),
    DEFAULT(1f, "Default"),
    LARGE(1.15f, "Large"),
}

@Serializable
enum class TapAction(val label: String) {
    OPEN_APP("Open Breeze"),
    REFRESH("Refresh"),
    SETTINGS("Widget settings"),
}

/** Readings the details section (and the side of a wide current-conditions widget) can show. */
@Serializable
enum class Metric(val label: String) {
    FEELS_LIKE("Feels like"),
    HUMIDITY("Humidity"),
    WIND("Wind"),
    UV("UV index"),
    RAIN_CHANCE("Chance of rain"),
    PRECIPITATION("Precipitation"),
    SUNRISE("Sunrise"),
    SUNSET("Sunset"),
    CLOUD_COVER("Cloud cover"),
    HIGH_LOW("High / low");

    companion object {
        val DEFAULT = listOf(FEELS_LIKE, HUMIDITY, WIND, RAIN_CHANCE)
        val DETAILS_DEFAULT = listOf(FEELS_LIKE, HUMIDITY, WIND, UV)
    }
}

/**
 * One widget's settings. Stored as JSON per widget id, so new fields need defaults; unknown
 * fields from newer versions are ignored.
 *
 * @param place the place to show, or null to follow the device's location.
 * @param opacity background opacity, 0 (none: text straight on the wallpaper) to 100.
 * @param hourStep hours between columns in the hourly forecast (1, 2 or 3).
 * @param metrics readings for the details section, in the order the user picked them.
 */
@Serializable
data class WidgetConfig(
    val place: Place? = null,
    val theme: WidgetTheme = WidgetTheme.SKY,
    val opacity: Int = 100,
    val textScale: TextScale = TextScale.DEFAULT,
    val sections: Set<WidgetSection> = setOf(WidgetSection.HEADER),
    val showPlace: Boolean = true,
    val showCondition: Boolean = true,
    val showHighLow: Boolean = true,
    val showFeelsLike: Boolean = false,
    val showUpdated: Boolean = false,
    val hourStep: Int = 1,
    val showRainChance: Boolean = true,
    val showRangeBars: Boolean = true,
    val metrics: List<Metric> = Metric.DEFAULT,
    val tapAction: TapAction = TapAction.OPEN_APP,
) {
    val followsLocation: Boolean get() = place == null

    /** Drops out-of-range values (from hand-edited or future data) back into range. */
    fun sanitized(): WidgetConfig = copy(
        opacity = opacity.coerceIn(0, 100),
        hourStep = hourStep.coerceIn(1, 3),
        metrics = metrics.distinct(),
        sections = sections.ifEmpty { setOf(WidgetSection.HEADER) },
    )
}
