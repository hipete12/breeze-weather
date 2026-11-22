package com.breeze.weather.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import com.breeze.weather.MainActivity
import com.breeze.weather.R
import com.breeze.weather.data.Condition
import com.breeze.weather.data.Place
import com.breeze.weather.data.Weather
import com.breeze.weather.ui.IconPalette
import kotlin.math.max

/** Whether a widget has a forecast to show, and if not, why. */
enum class DataState { READY, LOADING, NO_LOCATION, ERROR }

/** Everything a widget shows, apart from its settings. */
data class WidgetData(
    val place: Place?,
    val weather: Weather?,
    val state: DataState,
)

/**
 * Builds a widget's RemoteViews: the plan from [WidgetPlanner] filled in with the forecast, in
 * the colours of the widget's theme.
 *
 * Layout defaults are the "System" theme's colours, which the launcher resolves itself (so they
 * follow dark mode and Material You). Other themes set every colour from code.
 */
class WidgetRenderer(private val context: Context) {

    private val pkg = context.packageName
    private val use24h = DateFormat.is24HourFormat(context)

    /**
     * Measure text with the fonts the launcher will draw it in (the system's sans serif, which
     * follows custom system fonts such as Samsung's), with a little slack: the light temperature
     * font, and the medium font of smaller labels (the wider of regular and medium).
     */
    private val tempMeasure = fontMeasure("sans-serif-light", scale = 1.06f, slack = 2f)
    private val labelMeasure = fontMeasure("sans-serif-medium", scale = 1.03f, slack = 0.5f)

    private fun fontMeasure(family: String, scale: Float, slack: Float): TextMeasure {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(family, Typeface.NORMAL) }
        val lineEm = paint.run {
            textSize = 100f
            (fontMetrics.descent - fontMetrics.ascent) / 100f
        }
        return object : TextMeasure {
            override fun widthDp(text: String, sizeDp: Float): Float = synchronized(paint) {
                paint.textSize = sizeDp
                paint.measureText(text) * scale + slack
            }

            override val lineHeightEm: Float = lineEm
        }
    }

    /**
     * RemoteViews covering every size the widget can currently be shown at: one layout per size
     * the launcher reports on Android 12+, portrait and landscape before that.
     */
    fun build(
        appWidgetId: Int,
        kind: WidgetKind,
        config: WidgetConfig,
        data: WidgetData,
        sizes: WidgetSizes,
        bitmaps: WidgetBitmaps = WidgetBitmaps(context.resources.displayMetrics.density),
    ): RemoteViews {
        fun one(size: SizeF) = render(appWidgetId, kind, config, data, size.width, size.height, bitmaps)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sizes.exact.size > 1 ->
                RemoteViews(sizes.exact.associateWith { one(it) })
            sizes.exact.size == 1 -> one(sizes.exact.first())
            else -> RemoteViews(one(sizes.landscape), one(sizes.portrait))
        }
    }

    /**
     * One layout at [widthDp] × [heightDp].
     *
     * @param interactive false for previews inside the app, which mustn't fire the widget's taps.
     */
    fun render(
        appWidgetId: Int,
        kind: WidgetKind,
        config: WidgetConfig,
        data: WidgetData,
        widthDp: Float,
        heightDp: Float,
        bitmaps: WidgetBitmaps,
        interactive: Boolean = true,
    ): RemoteViews {
        val weather = data.weather.takeIf { data.state == DataState.READY }
        val style = Style.of(config, weather, isNightMode())
        val views = RemoteViews(pkg, R.layout.widget_root)
        applyBackground(views, config, style, weather)
        views.removeAllViews(R.id.widget_content)

        val s = config.textScale.factor
        val tempText = weather?.let { WidgetContent.temp(it.current.temperature) } ?: "–"
        val plan = if (weather == null) null else WidgetPlanner.plan(
            kind, config, widthDp, heightDp, tempText,
            availableHours = WidgetContent.availableHours(weather, config.hourStep, includeNow = !kind.isStrip),
            availableDays = if (kind.isStrip) WidgetContent.daysAfterToday(weather) else weather.daily.size,
            measure = tempMeasure,
            labelMeasure = labelMeasure,
            stripText = if (kind.isStrip) stripText(kind, config, weather) else StripText(),
        )
        val padH = plan?.paddingH ?: WidgetDimens.PAD_SMALL
        val padV = plan?.paddingV ?: WidgetDimens.PAD_SMALL
        views.setViewPadding(R.id.widget_content, bitmaps.px(padH), bitmaps.px(padV), bitmaps.px(padH), bitmaps.px(padV))

        if (weather == null || plan == null) {
            views.addView(R.id.widget_content, message(data, style, s))
            if (interactive) {
                val tap = if (data.state == DataState.ERROR) refreshIntent(appWidgetId, kind) else openAppIntent(appWidgetId, data.place, null)
                views.setOnClickPendingIntent(android.R.id.background, tap)
            }
            return views
        }

        val ctx = Ctx(appWidgetId, kind, config, data.place, weather, plan, style, bitmaps, widthDp - 2 * plan.paddingH, interactive)
        if (plan.strip != null) views.addView(R.id.widget_content, strip(ctx, plan.strip))
        val fill = when {
            WidgetSection.DAILY in plan.sections && plan.dayLayout == DayLayout.ROWS -> WidgetSection.DAILY
            WidgetSection.DETAILS in plan.sections -> WidgetSection.DETAILS
            else -> null
        }
        plan.sections.forEachIndexed { i, section ->
            if (i > 0) {
                // Without a section that stretches, push everything after the header to the bottom.
                val stretch = fill == null && i == 1 && plan.sections.first() == WidgetSection.HEADER
                views.addView(R.id.widget_content, RemoteViews(pkg, if (stretch) R.layout.widget_spacer else R.layout.widget_gap))
            }
            val section = when (section) {
                WidgetSection.HEADER -> header(ctx)
                WidgetSection.HOURLY -> hourly(ctx)
                WidgetSection.DAILY -> daily(ctx, fill == WidgetSection.DAILY)
                WidgetSection.DETAILS -> details(ctx, fill == WidgetSection.DETAILS)
            }
            views.addView(R.id.widget_content, section)
        }

        val place = data.place
        views.setContentDescription(
            android.R.id.background,
            listOfNotNull(place?.name, tempText, weather.current.description).joinToString(", "),
        )
        if (interactive) views.setOnClickPendingIntent(android.R.id.background, tapIntent(appWidgetId, kind, config, place))
        return views
    }

    // ---------- Style ----------

    /** Resolved colours for one render. [system] means "leave the layout's own colours". */
    private class Style(
        val theme: WidgetTheme,
        /** Light text on a dark background (as drawn now, for the System theme). */
        val lightText: Boolean,
        val primary: Int,
        val secondary: Int,
        val rain: Int,
        val palette: IconPalette,
        val track: Color,
        val shadow: Boolean,
    ) {
        val system get() = theme == WidgetTheme.SYSTEM

        companion object {
            fun of(config: WidgetConfig, weather: Weather?, night: Boolean): Style {
                val lightText = when (config.theme) {
                    WidgetTheme.SKY, WidgetTheme.DARK -> true
                    WidgetTheme.LIGHT -> false
                    WidgetTheme.SYSTEM -> night
                }
                // Text straight on the wallpaper gets a shadow, if it's light text.
                val shadow = lightText && config.opacity < 40
                // The launcher can switch the System theme between light and dark before Breeze
                // redraws (always, before Android 12), so its bar track is a grey that suits both.
                val neutralTrack = Color(0xFF8C96A8).copy(alpha = 0.32f)
                return if (lightText) Style(
                    config.theme,
                    lightText = true,
                    primary = 0xFFFFFFFF.toInt(),
                    secondary = 0xB8FFFFFF.toInt(),
                    rain = 0xFF9AD0FF.toInt(),
                    palette = IconPalette.Sky,
                    track = if (config.theme == WidgetTheme.SYSTEM) neutralTrack else Color.White.copy(alpha = 0.22f),
                    shadow = shadow,
                ) else Style(
                    config.theme,
                    lightText = false,
                    primary = 0xFF1A1F2B.toInt(),
                    secondary = 0xFF5B6577.toInt(),
                    rain = 0xFF1F6FD1.toInt(),
                    palette = IconPalette.OnLight,
                    track = if (config.theme == WidgetTheme.SYSTEM) neutralTrack else Color(0xFF1A1F2B).copy(alpha = 0.12f),
                    shadow = false,
                )
            }
        }
    }

    private fun isNightMode(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun applyBackground(views: RemoteViews, config: WidgetConfig, style: Style, weather: Weather?) {
        val (drawable, tint) = when (config.theme) {
            WidgetTheme.SKY -> skyBackground(weather?.current?.condition ?: Condition.PARTLY_CLOUDY, weather?.current?.isDay ?: true) to 0
            WidgetTheme.SYSTEM -> R.drawable.widget_bg_system to 0
            WidgetTheme.DARK -> R.drawable.widget_bg_solid to DARK_BG
            WidgetTheme.LIGHT -> R.drawable.widget_bg_solid to LIGHT_BG
        }
        views.setImageViewResource(R.id.widget_bg, drawable)
        // A transparent filter is a no-op; set it anyway so a reapplied view drops an old tint.
        views.setInt(R.id.widget_bg, "setColorFilter", tint)
        views.setInt(R.id.widget_bg, "setImageAlpha", config.opacity * 255 / 100)
        views.setViewVisibility(R.id.widget_bg, if (config.opacity == 0) View.GONE else View.VISIBLE)
    }

    private fun skyBackground(condition: Condition, isDay: Boolean): Int = when (condition) {
        Condition.CLEAR -> if (isDay) R.drawable.widget_bg_sky_clear_day else R.drawable.widget_bg_sky_clear_night
        Condition.PARTLY_CLOUDY -> if (isDay) R.drawable.widget_bg_sky_partly_cloudy_day else R.drawable.widget_bg_sky_partly_cloudy_night
        Condition.CLOUDY -> if (isDay) R.drawable.widget_bg_sky_cloudy_day else R.drawable.widget_bg_sky_cloudy_night
        Condition.FOG -> if (isDay) R.drawable.widget_bg_sky_fog_day else R.drawable.widget_bg_sky_fog_night
        Condition.DRIZZLE, Condition.RAIN -> if (isDay) R.drawable.widget_bg_sky_rain_day else R.drawable.widget_bg_sky_rain_night
        Condition.SNOW -> if (isDay) R.drawable.widget_bg_sky_snow_day else R.drawable.widget_bg_sky_snow_night
        Condition.STORM -> if (isDay) R.drawable.widget_bg_sky_storm_day else R.drawable.widget_bg_sky_storm_night
    }

    /** The layout itself, or its generated text-shadow twin. */
    @LayoutRes
    private fun layout(style: Style, @LayoutRes base: Int): Int {
        if (!style.shadow) return base
        return when (base) {
            R.layout.widget_header_tiny -> R.layout.widget_header_tiny_shadow
            R.layout.widget_header_compact -> R.layout.widget_header_compact_shadow
            R.layout.widget_header_big -> R.layout.widget_header_big_shadow
            R.layout.widget_hour_cell -> R.layout.widget_hour_cell_shadow
            R.layout.widget_hour_cell_compact -> R.layout.widget_hour_cell_compact_shadow
            R.layout.widget_detail_line -> R.layout.widget_detail_line_shadow
            R.layout.widget_day_row -> R.layout.widget_day_row_shadow
            R.layout.widget_day_row_plain -> R.layout.widget_day_row_plain_shadow
            R.layout.widget_day_column -> R.layout.widget_day_column_shadow
            R.layout.widget_detail_tile -> R.layout.widget_detail_tile_shadow
            R.layout.widget_message -> R.layout.widget_message_shadow
            R.layout.widget_strip -> R.layout.widget_strip_shadow
            R.layout.widget_strip_stack -> R.layout.widget_strip_stack_shadow
            R.layout.widget_strip_hour -> R.layout.widget_strip_hour_shadow
            R.layout.widget_strip_day -> R.layout.widget_strip_day_shadow
            else -> base
        }
    }

    // ---------- Sections ----------

    /** What every section builder needs for one render. */
    private class Ctx(
        val appWidgetId: Int,
        val kind: WidgetKind,
        val config: WidgetConfig,
        val place: Place?,
        val weather: Weather,
        val plan: WidgetPlan,
        val style: Style,
        val bitmaps: WidgetBitmaps,
        val innerWidthDp: Float,
        val interactive: Boolean,
    ) {
        val s get() = plan.scale
    }

    private fun header(c: Ctx): RemoteViews = when (c.plan.header) {
        HeaderStyle.TINY -> tinyHeader(c)
        HeaderStyle.BIG -> bigHeader(c)
        else -> compactHeader(c)
    }

    private fun tinyHeader(c: Ctx): RemoteViews {
        val v = RemoteViews(pkg, layout(c.style, R.layout.widget_header_tiny))
        val cur = c.weather.current
        weatherIcon(v, R.id.ht_icon, cur.weatherCode, cur.isDay, c.plan.iconDp, c, cur.description)
        text(v, R.id.ht_temp, WidgetContent.temp(cur.temperature), c.plan.tempTextDp, c.style.primary, c.style)
        if (c.plan.tinyPlace && c.place != null) {
            v.setViewVisibility(R.id.ht_place, View.VISIBLE)
            text(v, R.id.ht_place, c.place.name, WidgetDimens.TEXT_TINY * c.s, c.style.secondary, c.style)
        }
        return v
    }

    private fun compactHeader(c: Ctx): RemoteViews {
        val v = RemoteViews(pkg, layout(c.style, R.layout.widget_header_compact))
        val cur = c.weather.current
        val cfg = c.config
        weatherIcon(v, R.id.hc_icon, cur.weatherCode, cur.isDay, WidgetDimens.COMPACT_ICON * c.s, c, cur.description)
        text(v, R.id.hc_temp, WidgetContent.temp(cur.temperature), WidgetDimens.COMPACT_TEMP * c.s, c.style.primary, c.style)

        if (cfg.showPlace && c.place != null) {
            text(v, R.id.hc_place, c.place.name, WidgetDimens.TEXT_PLACE * c.s, c.style.primary, c.style)
            pin(v, R.id.hc_pin, c, 12f)
        } else {
            v.setViewVisibility(R.id.hc_place_row, View.GONE)
        }
        // The condition stands in for the place when the place is hidden.
        if (cfg.showCondition) {
            val primaryLine = !cfg.showPlace || c.place == null
            text(
                v, R.id.hc_desc, cur.description,
                (if (primaryLine) WidgetDimens.TEXT_PLACE else WidgetDimens.TEXT_SMALL) * c.s,
                if (primaryLine) c.style.primary else c.style.secondary, c.style,
            )
        } else {
            v.setViewVisibility(R.id.hc_desc, View.GONE)
        }

        val hl = if (cfg.showHighLow) WidgetContent.highLow(c.weather) else null
        val sub = when {
            cfg.showFeelsLike -> WidgetContent.feelsLike(c.weather)
            cfg.showUpdated -> WidgetContent.updated(c.weather.fetchedAt, use24h)
            else -> null
        }
        if (c.plan.compactExtras && (hl != null || sub != null)) {
            if (hl != null) text(v, R.id.hc_hl, hl, WidgetDimens.TEXT_BODY * c.s, c.style.primary, c.style)
            else v.setViewVisibility(R.id.hc_hl, View.GONE)
            if (sub != null) text(v, R.id.hc_sub, sub, WidgetDimens.TEXT_TINY * c.s, c.style.secondary, c.style)
            else v.setViewVisibility(R.id.hc_sub, View.GONE)
        } else {
            v.setViewVisibility(R.id.hc_extras, View.GONE)
        }
        return v
    }

    private fun bigHeader(c: Ctx): RemoteViews {
        val v = RemoteViews(pkg, layout(c.style, R.layout.widget_header_big))
        val cur = c.weather.current
        val cfg = c.config
        if (cfg.showPlace && c.place != null) {
            text(v, R.id.hb_place, c.place.name, WidgetDimens.TEXT_PLACE * c.s, c.style.primary, c.style)
            pin(v, R.id.hb_pin, c, 13f)
        } else {
            v.setViewVisibility(R.id.hb_place_row, View.GONE)
        }
        text(v, R.id.hb_temp, WidgetContent.temp(cur.temperature), c.plan.tempTextDp, c.style.primary, c.style)
        if (cfg.showCondition) text(v, R.id.hb_desc, cur.description, WidgetDimens.TEXT_BODY * c.s, c.style.primary, c.style)
        else v.setViewVisibility(R.id.hb_desc, View.GONE)

        val hl = listOfNotNull(
            if (cfg.showHighLow) WidgetContent.highLow(c.weather) else null,
            if (cfg.showFeelsLike) WidgetContent.feelsLike(c.weather) else null,
        ).joinToString("  ·  ")
        if (hl.isNotEmpty()) text(v, R.id.hb_hl, hl, WidgetDimens.TEXT_BODY * c.s, c.style.secondary, c.style)
        else v.setViewVisibility(R.id.hb_hl, View.GONE)

        if (cfg.showUpdated) {
            v.setViewVisibility(R.id.hb_updated, View.VISIBLE)
            text(v, R.id.hb_updated, WidgetContent.updated(c.weather.fetchedAt, use24h), WidgetDimens.TEXT_TINY * c.s, c.style.secondary, c.style)
        }
        weatherIcon(v, R.id.hb_icon, cur.weatherCode, cur.isDay, c.plan.iconDp, c, cur.description)

        if (c.plan.sideMetrics > 0) {
            v.setViewVisibility(R.id.hb_side, View.VISIBLE)
            val labels = listOf(R.id.hb_side_label_1, R.id.hb_side_label_2, R.id.hb_side_label_3)
            val values = listOf(R.id.hb_side_value_1, R.id.hb_side_value_2, R.id.hb_side_value_3)
            for (i in 0 until 3) {
                val metric = cfg.metrics.getOrNull(i)?.takeIf { i < c.plan.sideMetrics }
                if (metric == null) {
                    v.setViewVisibility(labels[i], View.GONE)
                    v.setViewVisibility(values[i], View.GONE)
                    continue
                }
                val m = WidgetContent.metric(metric, c.weather, use24h)
                text(v, labels[i], m.shortLabel, 10f * c.s, c.style.secondary, c.style)
                text(v, values[i], m.wideValue, WidgetDimens.TEXT_PLACE * c.s, c.style.primary, c.style)
            }
        }
        return v
    }

    private fun hourly(c: Ctx): RemoteViews {
        val row = RemoteViews(pkg, R.layout.widget_hourly)
        val cells = WidgetContent.hourCells(c.weather, c.config.hourStep, c.plan.hours, use24h)
        val showRain = c.plan.hourlyRain && cells.any { it.rainChance >= RAIN_MIN }
        val compact = c.plan.hourlyCompact
        for (cell in cells) {
            val v = RemoteViews(pkg, layout(c.style, if (compact) R.layout.widget_hour_cell_compact else R.layout.widget_hour_cell))
            text(v, R.id.hour_time, cell.label, WidgetDimens.TEXT_SMALL * c.s, c.style.secondary, c.style)
            weatherIcon(v, R.id.hour_icon, cell.weatherCode, cell.isDay, (if (compact) 20f else 26f) * c.s, c, null)
            text(v, R.id.hour_temp, WidgetContent.temp(cell.temperature), WidgetDimens.TEXT_CELL_TEMP * c.s, c.style.primary, c.style)
            if (showRain) {
                v.setViewVisibility(R.id.hour_rain, if (cell.rainChance >= RAIN_MIN) View.VISIBLE else View.INVISIBLE)
                text(v, R.id.hour_rain, "${cell.rainChance}%", WidgetDimens.TEXT_TINY * c.s, c.style.rain, c.style)
            }
            row.addView(R.id.hourly_row, v)
        }
        return row
    }

    private fun daily(c: Ctx, fill: Boolean): RemoteViews {
        val columns = c.plan.dayLayout == DayLayout.COLUMNS
        val list = RemoteViews(
            pkg,
            when {
                columns -> R.layout.widget_daily_columns
                fill -> R.layout.widget_daily_fill
                else -> R.layout.widget_daily_wrap
            },
        )
        val days = WidgetContent.dayCells(c.weather, c.plan.days)
        val range = WidgetContent.range(days)
        // Room left for the bar once the row's fixed-width parts are in; narrow widgets skip it.
        val barWidth = c.innerWidthDp - (48f + 2f + 22f * c.s + 36f + 36f + 16f + 30f * c.s)
        val bars = c.config.showRangeBars && !columns && barWidth >= MIN_BAR_WIDTH
        val roomForRain = columns || bars || c.innerWidthDp >= 200f * c.s
        val showRain = c.config.showRainChance && roomForRain && days.any { it.day.precipitationChance >= RAIN_MIN }
        for (cell in days) {
            val d = cell.day
            val base = when {
                columns -> R.layout.widget_day_column
                bars -> R.layout.widget_day_row
                else -> R.layout.widget_day_row_plain
            }
            val v = RemoteViews(pkg, layout(c.style, base))
            val nameColor = if (columns) c.style.secondary else c.style.primary
            text(v, R.id.day_name, cell.label, (if (columns) WidgetDimens.TEXT_SMALL else WidgetDimens.TEXT_BODY) * c.s, nameColor, c.style)
            weatherIcon(v, R.id.day_icon, d.weatherCode, true, (if (columns) 26f else 22f) * c.s, c, null)
            text(v, R.id.day_high, WidgetContent.temp(d.high), (if (columns) WidgetDimens.TEXT_CELL_TEMP else WidgetDimens.TEXT_BODY) * c.s, c.style.primary, c.style)
            text(v, R.id.day_low, WidgetContent.temp(d.low), (if (columns) WidgetDimens.TEXT_SMALL else WidgetDimens.TEXT_BODY) * c.s, c.style.secondary, c.style)
            val rain = if (showRain && d.precipitationChance >= RAIN_MIN) "${d.precipitationChance}%" else ""
            if (columns) {
                if (showRain) v.setViewVisibility(R.id.day_rain, if (rain.isEmpty()) View.INVISIBLE else View.VISIBLE)
            } else if (!showRain && !bars) {
                v.setViewVisibility(R.id.day_rain, View.GONE)
            }
            text(v, R.id.day_rain, rain, WidgetDimens.TEXT_TINY * c.s, c.style.rain, c.style)
            if (bars && range != null) {
                v.setImageViewBitmap(
                    R.id.day_bar,
                    c.bitmaps.rangeBar(barWidth, 5f * c.s, d.low, d.high, range.first, range.second, c.weather.units, c.style.track),
                )
            }
            if (c.interactive && c.config.tapAction == TapAction.OPEN_APP) {
                // A day opens that day's detail in the app.
                v.setOnClickPendingIntent(R.id.day_root, openAppIntent(c.appWidgetId, c.place, cell.index))
            }
            list.addView(R.id.daily_list, v)
        }
        return list
    }

    private fun details(c: Ctx, fill: Boolean): RemoteViews {
        val grid = RemoteViews(pkg, if (fill) R.layout.widget_details_fill else R.layout.widget_details_wrap)
        val metrics = c.config.metrics.take(c.plan.detailCount)
        if (c.plan.detailList) {
            metrics.forEach { metric ->
                val v = RemoteViews(pkg, layout(c.style, R.layout.widget_detail_line))
                val m = WidgetContent.metric(metric, c.weather, use24h)
                v.setImageViewBitmap(R.id.line_icon, c.bitmaps.glyph(glyph(metric), 14f * c.s))
                text(v, R.id.line_label, m.label, WidgetDimens.TEXT_SMALL * c.s, c.style.secondary, c.style)
                text(v, R.id.line_value, m.value, WidgetDimens.TEXT_BODY * c.s, c.style.primary, c.style)
                if (!c.style.system) v.setInt(R.id.line_icon, "setColorFilter", c.style.secondary)
                grid.addView(R.id.details_grid, v)
            }
            return grid
        }
        val columns = max(1, c.plan.detailColumns)
        val tileWidth = (c.innerWidthDp + 6f) / columns - 6f
        metrics.chunked(columns).forEach { rowMetrics ->
            val row = RemoteViews(pkg, R.layout.widget_detail_row)
            rowMetrics.forEach { metric ->
                val v = RemoteViews(pkg, layout(c.style, R.layout.widget_detail_tile))
                val m = WidgetContent.metric(metric, c.weather, use24h)
                v.setImageViewBitmap(R.id.tile_icon, c.bitmaps.glyph(glyph(metric), 13f * c.s))
                text(v, R.id.tile_label, m.shortLabel, 10f * c.s, c.style.secondary, c.style)
                text(v, R.id.tile_value, if (tileWidth >= 112f * c.s) m.wideValue else m.value, WidgetDimens.TEXT_TILE_VALUE * c.s, c.style.primary, c.style)
                if (!c.style.system) {
                    v.setInt(R.id.tile_bg, "setColorFilter", c.style.primary)
                    v.setInt(R.id.tile_icon, "setColorFilter", c.style.secondary)
                }
                row.addView(R.id.detail_row, v)
            }
            // Keep tiles the same width when the last row is short.
            repeat(columns - rowMetrics.size) {
                val filler = RemoteViews(pkg, layout(c.style, R.layout.widget_detail_tile))
                filler.setViewVisibility(R.id.tile_bg, View.INVISIBLE)
                filler.setTextViewText(R.id.tile_label, "")
                filler.setTextViewText(R.id.tile_value, "")
                row.addView(R.id.detail_row, filler)
            }
            grid.addView(R.id.details_grid, row)
        }
        return grid
    }

    // ---------- Strips ----------

    /** The text a strip may show, for the planner to measure. */
    private fun stripText(kind: WidgetKind, config: WidgetConfig, weather: Weather): StripText {
        val today = WidgetContent.today(weather)
        val high = today?.let { WidgetContent.temp(it.high) } ?: ""
        val low = today?.let { WidgetContent.temp(it.low) } ?: ""
        return when (kind) {
            WidgetKind.HOURLY_STRIP -> {
                val cells = WidgetContent.hourCells(weather, config.hourStep, config.stripCount, use24h, includeNow = false)
                StripText(high, low, cells.map { it.label }, cells.map { WidgetContent.temp(it.temperature) })
            }
            WidgetKind.DAILY_STRIP -> {
                val days = WidgetContent.dayCells(weather, config.stripCount, fromTomorrow = true)
                StripText(high, low, days.map { it.label }, days.map { "${WidgetContent.temp(it.day.high)} ${WidgetContent.temp(it.day.low)}" })
            }
            else -> StripText(high, low, emptyList(), emptyList())
        }
    }

    private fun strip(c: Ctx, sp: StripPlan): RemoteViews {
        val stack = sp.layout == StripLayout.STACK
        val v = RemoteViews(pkg, layout(c.style, if (stack) R.layout.widget_strip_stack else R.layout.widget_strip))
        val cur = c.weather.current
        weatherIcon(v, R.id.st_icon, cur.weatherCode, cur.isDay, sp.iconDp, c, cur.description)
        text(v, R.id.st_temp, WidgetContent.temp(cur.temperature), sp.tempDp, c.style.primary, c.style)
        val today = WidgetContent.today(c.weather)
        if (sp.highLowDp > 0f && today != null) {
            tempText(v, R.id.st_high, today.high, sp.highLowDp, c.style.primary, c)
            tempText(v, R.id.st_low, today.low, sp.highLowDp, c.style.secondary, c)
            v.setContentDescription(R.id.st_hl, "High ${WidgetContent.temp(today.high)}, low ${WidgetContent.temp(today.low)}")
        } else {
            v.setViewVisibility(R.id.st_hl, View.GONE)
        }
        if (stack) return v
        if (sp.placeDp > 0f && c.place != null) {
            v.setViewVisibility(R.id.st_place_row, View.VISIBLE)
            text(v, R.id.st_place, c.place.name, sp.placeDp, c.style.primary, c.style)
            pin(v, R.id.st_pin, c, sp.placeDp / c.s)
        }
        if (sp.cells == 0) {
            v.setViewVisibility(R.id.st_lead, View.VISIBLE)
            return v
        }
        when (c.kind) {
            WidgetKind.HOURLY_STRIP -> stripHours(c, sp, v)
            WidgetKind.DAILY_STRIP -> stripDays(c, sp, v)
            else -> {}
        }
        return v
    }

    private fun stripHours(c: Ctx, sp: StripPlan, strip: RemoteViews) {
        val cells = WidgetContent.hourCells(c.weather, c.config.hourStep, sp.cells, use24h, includeNow = false)
        val showRain = sp.cellRain && cells.any { it.rainChance >= RAIN_MIN }
        for (cell in cells) {
            val v = RemoteViews(pkg, layout(c.style, R.layout.widget_strip_hour))
            text(v, R.id.hour_time, cell.label, sp.cellLabelDp, c.style.secondary, c.style)
            weatherIcon(v, R.id.hour_icon, cell.weatherCode, cell.isDay, sp.cellIconDp, c, null)
            text(v, R.id.hour_temp, WidgetContent.temp(cell.temperature), sp.cellTempDp, c.style.primary, c.style)
            if (showRain) {
                v.setViewVisibility(R.id.hour_rain, if (cell.rainChance >= RAIN_MIN) View.VISIBLE else View.INVISIBLE)
                text(v, R.id.hour_rain, "${cell.rainChance}%", WidgetDimens.TEXT_TINY * 0.9f * c.s, c.style.rain, c.style)
            }
            strip.addView(R.id.st_cells, v)
        }
    }

    private fun stripDays(c: Ctx, sp: StripPlan, strip: RemoteViews) {
        val days = WidgetContent.dayCells(c.weather, sp.cells, fromTomorrow = true)
        val showRain = sp.cellRain && days.any { it.day.precipitationChance >= RAIN_MIN }
        for (cell in days) {
            val d = cell.day
            val v = RemoteViews(pkg, layout(c.style, R.layout.widget_strip_day))
            text(v, R.id.day_name, cell.label, sp.cellLabelDp, c.style.secondary, c.style)
            weatherIcon(v, R.id.day_icon, d.weatherCode, true, sp.cellIconDp, c, null)
            tempText(v, R.id.day_high, d.high, sp.cellTempDp, c.style.primary, c)
            tempText(v, R.id.day_low, d.low, sp.cellTempDp * 0.92f, c.style.secondary, c)
            if (showRain) {
                v.setViewVisibility(R.id.day_rain, if (d.precipitationChance >= RAIN_MIN) View.VISIBLE else View.INVISIBLE)
                text(v, R.id.day_rain, "${d.precipitationChance}%", WidgetDimens.TEXT_TINY * 0.9f * c.s, c.style.rain, c.style)
            }
            if (c.interactive && c.config.tapAction == TapAction.OPEN_APP) {
                v.setOnClickPendingIntent(R.id.day_root, openAppIntent(c.appWidgetId, c.place, cell.index))
            }
            strip.addView(R.id.st_cells, v)
        }
    }

    /**
     * A temperature coloured cold to hot when the widget's settings ask for it, otherwise in
     * [plainColor]. The System theme gets both shades on Android 12+, so the launcher can switch
     * them with dark mode.
     */
    private fun tempText(v: RemoteViews, id: Int, t: Double, sizeDp: Float, plainColor: Int, c: Ctx) {
        text(v, id, WidgetContent.temp(t), sizeDp, plainColor, c.style)
        if (!c.config.colorTemps) return
        val units = c.weather.units
        if (c.style.system && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setColorInt(id, "setTextColor", TempColors.onLight(t, units), TempColors.onDark(t, units))
        } else {
            v.setTextColor(id, if (c.style.lightText) TempColors.onDark(t, units) else TempColors.onLight(t, units))
        }
    }

    private fun glyph(metric: Metric): ImageVector = when (metric) {
        Metric.FEELS_LIKE -> Icons.Rounded.Thermostat
        Metric.HUMIDITY -> Icons.Rounded.WaterDrop
        Metric.WIND -> Icons.Rounded.Air
        Metric.UV -> Icons.Rounded.LightMode
        Metric.RAIN_CHANCE -> Icons.Rounded.Umbrella
        Metric.PRECIPITATION -> Icons.Rounded.Grain
        Metric.SUNRISE -> Icons.Rounded.WbSunny
        Metric.SUNSET -> Icons.Rounded.WbTwilight
        Metric.CLOUD_COVER -> Icons.Rounded.Cloud
        Metric.HIGH_LOW -> Icons.Rounded.SwapVert
    }

    private fun message(data: WidgetData, style: Style, s: Float): RemoteViews {
        val v = RemoteViews(pkg, layout(style, R.layout.widget_message))
        val (title, body) = when (data.state) {
            DataState.NO_LOCATION -> "Breeze" to "Open the app to set your location"
            DataState.ERROR -> (data.place?.name ?: "Breeze") to "Can't reach the weather service. Tap to retry."
            else -> (data.place?.name ?: "Breeze") to "Loading weather…"
        }
        text(v, R.id.msg_title, title, WidgetDimens.TEXT_PLACE * s, style.primary, style)
        text(v, R.id.msg_body, body, WidgetDimens.TEXT_SMALL * s, style.secondary, style)
        return v
    }

    // ---------- Helpers ----------

    private fun text(v: RemoteViews, id: Int, value: CharSequence, sizeDp: Float, color: Int, style: Style) {
        v.setTextViewText(id, value)
        v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, sizeDp)
        if (!style.system) v.setTextColor(id, color)
    }

    /** The location arrow beside the place name, when the widget follows the device's location. */
    private fun pin(v: RemoteViews, id: Int, c: Ctx, sizeDp: Float) {
        if (!c.config.followsLocation) return
        v.setViewVisibility(id, View.VISIBLE)
        // The same arrow, at the same angle, as the app's "My location" label.
        v.setImageViewBitmap(id, c.bitmaps.glyph(Icons.Rounded.Navigation, sizeDp * c.s, rotation = 45f))
        if (!c.style.system) v.setInt(id, "setColorFilter", c.style.primary)
    }

    private fun weatherIcon(v: RemoteViews, id: Int, code: Int, isDay: Boolean, sizeDp: Float, c: Ctx, description: String?) {
        if (c.style.system && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Let the launcher pick the variant for its light or dark mode.
            v.setIcon(
                id, "setImageIcon",
                Icon.createWithBitmap(c.bitmaps.weather(code, isDay, sizeDp, IconPalette.OnLight)),
                Icon.createWithBitmap(c.bitmaps.weather(code, isDay, sizeDp, IconPalette.Sky)),
            )
        } else {
            v.setImageViewBitmap(id, c.bitmaps.weather(code, isDay, sizeDp, c.style.palette))
        }
        v.setContentDescription(id, description)
    }

    // ---------- Taps ----------

    private fun tapIntent(appWidgetId: Int, kind: WidgetKind, config: WidgetConfig, place: Place?): PendingIntent = when (config.tapAction) {
        TapAction.OPEN_APP -> openAppIntent(appWidgetId, place, null)
        TapAction.REFRESH -> refreshIntent(appWidgetId, kind)
        TapAction.SETTINGS -> settingsIntent(appWidgetId)
    }

    private fun openAppIntent(appWidgetId: Int, place: Place?, day: Int?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // A distinct URI per widget and day keeps each PendingIntent separate.
            .setData(Uri.parse("breeze://widget/$appWidgetId" + (day?.let { "/day/$it" } ?: "")))
            .putExtra(WidgetIntents.EXTRA_PLACE_ID, place?.id)
            .putExtra(WidgetIntents.EXTRA_DAY, day ?: -1)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun refreshIntent(appWidgetId: Int, kind: WidgetKind): PendingIntent {
        val intent = Intent(context, WidgetProviders.providerClass(kind))
            .setAction(WidgetIntents.ACTION_REFRESH)
            .setData(Uri.parse("breeze://widget/$appWidgetId/refresh"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun settingsIntent(appWidgetId: Int): PendingIntent {
        val intent = WidgetConfigActivity.editIntent(context, appWidgetId)
            .setData(Uri.parse("breeze://widget/$appWidgetId/settings"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        /** Every chance of rain above 0% gets a label. */
        const val RAIN_MIN = 1
        /** Narrower than this (dp), a temperature bar says too little to be worth the space. */
        const val MIN_BAR_WIDTH = 40f
        val DARK_BG = Color(0xFF141A2A).toArgb()
        val LIGHT_BG = Color(0xFFF7F9FC).toArgb()
    }
}

object WidgetIntents {
    const val ACTION_REFRESH = "com.breeze.weather.widget.REFRESH"
    const val EXTRA_PLACE_ID = "com.breeze.weather.extra.PLACE_ID"
    const val EXTRA_DAY = "com.breeze.weather.extra.DAY"
}
