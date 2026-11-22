package com.breeze.weather.widget

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How the current conditions are laid out. */
enum class HeaderStyle {
    NONE,

    /** Icon over temperature, for 1×1-ish widgets. */
    TINY,

    /** One row: icon, temperature, place and condition, high and low on the right. */
    COMPACT,

    /** Place, a large temperature, condition and high/low beside a large icon. */
    BIG,
}

enum class DayLayout { ROWS, COLUMNS }

/** How a strip widget arranges the current temperature. */
enum class StripLayout {
    /** Icon, temperature, then high over low, side by side; forecast columns follow. */
    ROW,

    /** Icon over temperature over high and low, for small, square widgets. */
    STACK,
}

/** A strip widget's sizes, in dp (see [WidgetKind.isStrip]). */
data class StripPlan(
    val layout: StripLayout,
    val iconDp: Float,
    val tempDp: Float,
    /** High and low text size; 0 when they're turned off or don't fit. */
    val highLowDp: Float,
    /** Forecast columns (hours or days) after the current conditions; ROW only. */
    val cells: Int = 0,
    val cellLabelDp: Float = 0f,
    val cellIconDp: Float = 0f,
    val cellTempDp: Float = 0f,
    /** Room for a chance-of-rain line under each column. */
    val cellRain: Boolean = false,
)

/** The text a strip has to fit, so the planner can measure it. */
data class StripText(
    val high: String = "21°",
    val low: String = "12°",
    /** Every candidate column's label ("3 PM", "Mon"). */
    val cellLabels: List<String> = listOf("10 AM"),
    /** Every candidate column's temperature line ("21°", or "21° 12°" for a day). */
    val cellTemps: List<String> = listOf("21°"),
)

/**
 * What a widget shows at one size: the result of fitting the user's choices into the space.
 * Sizes are in dp; text sizes in dp too (widget text doesn't follow the system font scale, the
 * widget's own text size setting replaces it, so layouts stay predictable).
 */
data class WidgetPlan(
    val header: HeaderStyle,
    val paddingH: Float,
    val paddingV: Float,
    val scale: Float,
    /** Main temperature text size and main icon size, for the TINY and BIG headers. */
    val tempTextDp: Float = 0f,
    val iconDp: Float = 0f,
    /** BIG header: readings shown in a column beside the temperature. */
    val sideMetrics: Int = 0,
    /** COMPACT header: room for high/low (and feels like or updated) on the right. */
    val compactExtras: Boolean = false,
    /** TINY header: room for the place name under the temperature. */
    val tinyPlace: Boolean = false,
    val hours: Int = 0,
    /** Hourly strip with smaller icons and gaps, for short widgets. */
    val hourlyCompact: Boolean = false,
    /** Hourly strip has room for the chance-of-rain line. */
    val hourlyRain: Boolean = false,
    val days: Int = 0,
    val dayLayout: DayLayout = DayLayout.ROWS,
    val detailColumns: Int = 0,
    val detailRows: Int = 0,
    /** Details as a list of label–value lines instead of tiles, for narrow widgets. */
    val detailList: Boolean = false,
    /** Sections that fit, in display order. */
    val sections: List<WidgetSection> = emptyList(),
    /** Set for strip widgets, which are laid out as one row instead of sections. */
    val strip: StripPlan? = null,
) {
    val detailCount: Int get() = detailColumns * detailRows
}

/** Base dimensions at text size "Default"; everything scales with the widget's text size. */
object WidgetDimens {
    const val PAD_H = 14f
    const val PAD_V = 12f
    const val PAD_SMALL = 8f
    const val GAP = 8f

    const val COMPACT_H = 46f
    const val COMPACT_ICON = 38f
    const val COMPACT_TEMP = 28f

    const val HOUR_CELL_W = 44f
    const val HOURLY_H = 64f
    const val HOURLY_COMPACT_H = 54f
    const val RAIN_LINE = 15f

    const val DAY_ROW_H = 26f
    const val DAY_COL_W = 46f
    const val DAY_COLS_H = 82f

    const val TILE_W = 78f
    const val TILE_H = 46f
    const val TILE_GAP = 6f
    const val LINE_H = 22f

    /** The side column's fixed width plus its start margin (widget_header_big.xml); not scaled. */
    const val SIDE_COLUMN_W = 108f
    const val SIDE_LINE_H = 32f

    // Text sizes.
    const val TEXT_PLACE = 14f
    const val TEXT_BODY = 13f
    const val TEXT_SMALL = 12f
    const val TEXT_TINY = 11f
    const val TEXT_CELL_TEMP = 14f
    const val TEXT_TILE_VALUE = 16f

    /** Line heights used for the BIG header's text under and over the temperature. */
    const val LINE_PLACE = 20f
    const val LINE_DESC = 18f
    const val LINE_HIGH_LOW = 17f
    const val LINE_UPDATED = 15f

    const val MAX_HOURS = 12
    const val MAX_DAY_COLUMNS = 7

    // Strips, at a typical one-row height (STRIP_REF_H inside the padding).
    const val STRIP_REF_H = 64f
    const val STRIP_ICON = 42f
    const val STRIP_TEMP = 36f
    const val STRIP_HIGH_LOW = 14f
    /** Gaps in widget_strip.xml: icon to temperature, temperature to high/low, then to the columns. */
    const val STRIP_ICON_GAP = 2f
    const val STRIP_HIGH_LOW_GAP = 6f
    const val STRIP_CELLS_GAP = 10f
    /** Between a column's label, icon, temperature and rain line (widget_strip_*.xml). */
    const val STRIP_CELL_GAP = 2f
    /** Least space beside a column's widest text, so neighbours don't touch. */
    const val STRIP_CELL_PAD = 3f
    const val STRIP_CELL_LABEL = 11f
    const val STRIP_CELL_ICON = 24f
    const val STRIP_HOUR_TEMP = 14f
    const val STRIP_DAY_TEMP = 13f
}

/** Measures text in dp for a given text size in dp. */
fun interface TextMeasure {
    fun widthDp(text: String, sizeDp: Float): Float

    /** Height of one line of text (without font padding) per dp of text size. */
    val lineHeightEm: Float get() = 1.17f

    companion object {
        /** A rough guess from character widths, for tests; on a device, measure with the real font. */
        val Estimate = TextMeasure { text, size -> WidgetPlanner.tempWidthEm(text) * size }
    }
}

/** Fits a widget's content into its size. Pure logic, so it is unit tested without a device. */
object WidgetPlanner {

    /**
     * @param tempText the main temperature as shown (e.g. "-12°"), to size it so it fits.
     * @param availableHours / availableDays how many forecast columns or rows there's data for
     *   (for strips: hours after now, days after today).
     * @param measure measures the light temperature font; [labelMeasure] the regular and medium
     *   fonts of the smaller text (strips only).
     */
    fun plan(
        kind: WidgetKind,
        config: WidgetConfig,
        widthDp: Float,
        heightDp: Float,
        tempText: String = "21°",
        availableHours: Int = 24,
        availableDays: Int = 10,
        measure: TextMeasure = TextMeasure.Estimate,
        labelMeasure: TextMeasure = TextMeasure.Estimate,
        stripText: StripText = StripText(),
    ): WidgetPlan {
        val s = config.textScale.factor
        val padH = if (widthDp < 120f) WidgetDimens.PAD_SMALL else WidgetDimens.PAD_H
        val padV = when {
            heightDp < 70f -> WidgetDimens.PAD_SMALL * 0.75f
            heightDp < 100f -> WidgetDimens.PAD_SMALL
            else -> WidgetDimens.PAD_V
        }
        val innerW = max(0f, widthDp - 2 * padH)
        val innerH = max(0f, heightDp - 2 * padV)
        val base = WidgetPlan(HeaderStyle.NONE, padH, padV, s)

        // Width of the temperature per dp of text size; text width scales linearly with size.
        val em = measure.widthDp(tempText, 100f) / 100f
        if (kind == WidgetKind.NOW) return planNow(base, config, innerW, innerH, em)
        if (kind.isStrip) {
            val available = when (kind) {
                WidgetKind.HOURLY_STRIP -> availableHours
                WidgetKind.DAILY_STRIP -> availableDays
                else -> 0
            }
            return base.copy(strip = planStrip(kind, config, innerW, innerH, tempText, available, measure, labelMeasure, stripText))
        }

        // Sections the user wants, with the kind's primary section first, then in display order.
        val wanted = WidgetSection.entries.filter { it in config.sections }
            .filterNot { it == WidgetSection.DETAILS && config.metrics.isEmpty() }
            .filterNot { it == WidgetSection.HOURLY && availableHours < 2 }
            .filterNot { it == WidgetSection.DAILY && availableDays < 1 }
        if (wanted.isEmpty()) return planHeaderOnly(base, config, innerW, innerH, em)
        val priority = wanted.sortedBy { if (it == kind.primarySection) -1 else it.ordinal }

        val rowH = WidgetDimens.DAY_ROW_H * s
        val colsH = (WidgetDimens.DAY_COLS_H + if (config.showRainChance) WidgetDimens.RAIN_LINE else 0f) * s
        val tileH = WidgetDimens.TILE_H * s
        val lineH = WidgetDimens.LINE_H * s
        val gap = WidgetDimens.GAP * s
        val tileColumns = floor((innerW + WidgetDimens.TILE_GAP * s) / ((WidgetDimens.TILE_W + WidgetDimens.TILE_GAP) * s)).toInt()
        // Too narrow for two tiles side by side: list the readings instead.
        val detailList = tileColumns < 2
        val dayColumns = min(min(WidgetDimens.MAX_DAY_COLUMNS, availableDays), floor(innerW / (WidgetDimens.DAY_COL_W * s)).toInt())
        val minDayRows = min(2, availableDays)

        // First pass: give each section, by priority, the least space it can work with.
        var remaining = innerH
        val alloc = LinkedHashMap<WidgetSection, Float>()
        var dayLayout = DayLayout.ROWS
        for (section in priority) {
            val sep = if (alloc.isEmpty()) 0f else gap
            val need = when (section) {
                WidgetSection.HEADER -> WidgetDimens.COMPACT_H * s
                WidgetSection.HOURLY -> if (innerW >= 3 * WidgetDimens.HOUR_CELL_W * s) WidgetDimens.HOURLY_COMPACT_H * s else Float.MAX_VALUE
                WidgetSection.DAILY -> when {
                    minDayRows * rowH + sep <= remaining -> { dayLayout = DayLayout.ROWS; minDayRows * rowH }
                    dayColumns >= 3 && colsH + sep <= remaining -> { dayLayout = DayLayout.COLUMNS; colsH }
                    else -> Float.MAX_VALUE
                }
                WidgetSection.DETAILS -> if (detailList) lineH else tileH
            }
            if (need + sep <= remaining) {
                alloc[section] = need
                remaining -= need + sep
            }
        }
        if (alloc.isEmpty() || alloc.keys == setOf(WidgetSection.HEADER)) {
            return planHeaderOnly(base, config, innerW, innerH, em)
        }

        // Second pass: spread what's left over the sections that can grow. The hourly strip
        // first gets its full size (and the rain line), then daily rows, then details.
        if (WidgetSection.HOURLY in alloc) {
            val targets = listOfNotNull(WidgetDimens.HOURLY_H, if (config.showRainChance) WidgetDimens.HOURLY_H + WidgetDimens.RAIN_LINE else null)
            for (target in targets) {
                val extra = target * s - alloc.getValue(WidgetSection.HOURLY)
                if (extra in 0f..remaining) {
                    alloc[WidgetSection.HOURLY] = target * s
                    remaining -= extra
                }
            }
        }
        val hourlyH = alloc[WidgetSection.HOURLY] ?: 0f

        var days = 0
        if (WidgetSection.DAILY in alloc) {
            if (dayLayout == DayLayout.ROWS) {
                val space = alloc.getValue(WidgetSection.DAILY) + remaining
                days = min(availableDays, floor(space / rowH).toInt())
                val used = days * rowH
                remaining = space - used
                alloc[WidgetSection.DAILY] = used
                // A few rows in a short, wide widget read better as columns.
                if (days <= 2 && dayColumns >= 4 && space >= colsH) {
                    dayLayout = DayLayout.COLUMNS
                    remaining = space - colsH
                    alloc[WidgetSection.DAILY] = colsH
                }
            }
            if (dayLayout == DayLayout.COLUMNS) days = dayColumns
        }

        var detailColumns = 0
        var detailRows = 0
        if (WidgetSection.DETAILS in alloc && detailList) {
            val space = alloc.getValue(WidgetSection.DETAILS) + remaining
            detailColumns = 1
            detailRows = min(config.metrics.size, max(1, floor(space / lineH).toInt()))
        } else if (WidgetSection.DETAILS in alloc) {
            val count = config.metrics.size
            detailColumns = min(count, max(1, tileColumns)).coerceIn(1, 4)
            val space = alloc.getValue(WidgetSection.DETAILS) + remaining
            val rowsThatFit = max(1, floor((space + WidgetDimens.TILE_GAP * s) / (tileH + WidgetDimens.TILE_GAP * s)).toInt())
            val rowsNeeded = (count + detailColumns - 1) / detailColumns
            detailRows = min(rowsThatFit, rowsNeeded)
            // Rebalance so a 5-tile 3+2 grid doesn't become 4+1.
            if (detailRows > 0) detailColumns = min(detailColumns, (min(count, detailColumns * detailRows) + detailRows - 1) / detailRows)
        }

        val hours = if (WidgetSection.HOURLY in alloc) {
            floor(innerW / (WidgetDimens.HOUR_CELL_W * s)).toInt().coerceIn(3, WidgetDimens.MAX_HOURS).coerceAtMost(availableHours)
        } else 0

        val hasHeader = WidgetSection.HEADER in alloc
        return base.copy(
            header = if (hasHeader) HeaderStyle.COMPACT else HeaderStyle.NONE,
            compactExtras = hasHeader && compactHasExtras(config, innerW, s),
            hours = hours,
            hourlyCompact = hours > 0 && hourlyH < WidgetDimens.HOURLY_H * s - 0.01f,
            hourlyRain = hours > 0 && config.showRainChance && hourlyH >= (WidgetDimens.HOURLY_H + WidgetDimens.RAIN_LINE) * s - 0.01f,
            days = days,
            dayLayout = dayLayout,
            detailColumns = detailColumns,
            detailRows = detailRows,
            detailList = detailList && detailRows > 0,
            sections = WidgetSection.entries.filter { it in alloc },
        )
    }

    private fun compactHasExtras(config: WidgetConfig, innerW: Float, s: Float) =
        (config.showHighLow || config.showFeelsLike || config.showUpdated) && innerW >= 220f * s

    private fun planNow(base: WidgetPlan, config: WidgetConfig, innerW: Float, innerH: Float, em: Float): WidgetPlan {
        val s = config.textScale.factor
        return when {
            innerH >= bigMinHeight(config) && innerW >= 110f * s -> planBig(base, config, innerW, innerH, em, allowSide = false)
            innerW >= 120f * s && innerH < 100f * s -> base.copy(
                header = HeaderStyle.COMPACT,
                compactExtras = compactHasExtras(config, innerW, s),
                sections = listOf(WidgetSection.HEADER),
            )
            else -> planTiny(base, config, innerW, innerH, em)
        }
    }

    private fun planHeaderOnly(base: WidgetPlan, config: WidgetConfig, innerW: Float, innerH: Float, em: Float): WidgetPlan {
        val s = config.textScale.factor
        return when {
            innerH >= bigMinHeight(config) && innerW >= 110f * s -> planBig(base, config, innerW, innerH, em, allowSide = true)
            innerW >= 120f * s -> base.copy(
                header = HeaderStyle.COMPACT,
                compactExtras = compactHasExtras(config, innerW, s),
                sections = listOf(WidgetSection.HEADER),
            )
            else -> planTiny(base, config, innerW, innerH, em)
        }
    }

    /** Height of the BIG header's text lines other than the temperature. */
    private fun bigTextHeight(config: WidgetConfig): Float {
        val s = config.textScale.factor
        var h = 0f
        if (config.showPlace) h += WidgetDimens.LINE_PLACE
        if (config.showCondition) h += WidgetDimens.LINE_DESC
        if (config.showHighLow || config.showFeelsLike) h += WidgetDimens.LINE_HIGH_LOW
        if (config.showUpdated) h += WidgetDimens.LINE_UPDATED
        return h * s
    }

    private fun bigMinHeight(config: WidgetConfig) = bigTextHeight(config) + MIN_BIG_TEMP * TEMP_LINE

    private fun planBig(base: WidgetPlan, config: WidgetConfig, innerW: Float, innerH: Float, em: Float, allowSide: Boolean): WidgetPlan {
        val s = config.textScale.factor
        val sideMetrics = if (allowSide && innerW >= 250f * s) {
            min(min(3, config.metrics.size), floor(innerH / (WidgetDimens.SIDE_LINE_H * s)).toInt())
        } else 0
        val sideW = if (sideMetrics > 0) WidgetDimens.SIDE_COLUMN_W else 0f
        val chars = em
        var temp = ((innerH - bigTextHeight(config)) / TEMP_LINE).coerceIn(MIN_BIG_TEMP, MAX_BIG_TEMP * s)
        // Leave room for an icon of at least MIN_BIG_ICON beside the temperature.
        val maxTempForWidth = (innerW - sideW - MIN_BIG_ICON - 8f) / chars
        temp = min(temp, max(MIN_BIG_TEMP * 0.8f, maxTempForWidth))
        // The place sits on its own line above, so the icon shares only the rows below it.
        val belowPlace = innerH - if (config.showPlace) WidgetDimens.LINE_PLACE * s else 0f
        val icon = min(belowPlace * 0.8f, innerW - sideW - temp * chars - 8f).coerceIn(MIN_BIG_ICON * 0.8f, MAX_BIG_ICON * s)
        return base.copy(
            header = HeaderStyle.BIG,
            tempTextDp = temp,
            iconDp = icon,
            sideMetrics = sideMetrics,
            sections = listOf(WidgetSection.HEADER),
        )
    }

    private fun planTiny(base: WidgetPlan, config: WidgetConfig, innerW: Float, innerH: Float, em: Float): WidgetPlan {
        val s = config.textScale.factor
        val temp = min(innerW / em, innerH * 0.34f).coerceIn(12f, 40f * s)
        val placeLine = WidgetDimens.TEXT_TINY * s * 1.35f
        val withPlace = config.showPlace && innerH - temp * 1.2f - placeLine >= 28f
        val iconSpace = innerH - temp * 1.2f - if (withPlace) placeLine else 0f
        val icon = min(innerW * 0.8f, iconSpace).coerceIn(14f, 72f)
        return base.copy(
            header = HeaderStyle.TINY,
            tempTextDp = temp,
            iconDp = icon,
            tinyPlace = withPlace,
            sections = listOf(WidgetSection.HEADER),
        )
    }

    // ---------- Strips ----------

    /**
     * A strip: the current temperature (with today's high and low) sized from the height, then as
     * many forecast columns as fit beside it, up to the user's count. A strip too narrow for even
     * the temperature row stacks it instead (the 1×1 "Mini").
     */
    private fun planStrip(
        kind: WidgetKind,
        config: WidgetConfig,
        innerW: Float,
        innerH: Float,
        tempText: String,
        available: Int,
        measure: TextMeasure,
        labelMeasure: TextMeasure,
        text: StripText,
    ): StripPlan {
        val s = config.textScale.factor
        val line = max(measure.lineHeightEm, labelMeasure.lineHeightEm)
        val d = WidgetDimens
        // A taller strip gets bigger type, within limits.
        val k = (innerH / d.STRIP_REF_H).coerceIn(0.6f, 1.45f)
        val baseIcon = min(d.STRIP_ICON * s * k, innerH)
        val baseTemp = min(d.STRIP_TEMP * s * k, innerH / line)
        val baseHighLow = if (config.showHighLow) min(min(d.STRIP_HIGH_LOW * s * k, 17f * s), (innerH - 2f) / (2 * line)) else 0f

        fun highLowWidth(size: Float) = max(labelMeasure.widthDp(text.high, size), labelMeasure.widthDp(text.low, size))
        fun rowWidth(f: Float, withHighLow: Boolean): Float {
            val hl = if (withHighLow && baseHighLow > 0f) d.STRIP_HIGH_LOW_GAP + highLowWidth(baseHighLow * f) else 0f
            return baseIcon * f + d.STRIP_ICON_GAP + measure.widthDp(tempText, baseTemp * f) + hl
        }
        // The largest scale (at most 1) at which the row fits [width].
        fun fit(width: Float, withHighLow: Boolean): Float {
            var f = 1f
            while (f > 0.3f && rowWidth(f, withHighLow) > width) f -= 0.02f
            return f
        }

        // Forecast columns, sized from the height.
        val cellsWanted = if (kind == WidgetKind.MINI) 0 else min(config.stripCount, available)
        // Columns at sizes that grow with the height; if that costs a column, at one-row sizes.
        fun columns(grow: Boolean): Triple<StripPlan, Int, Float> {
            val cell = stripCell(kind, config, innerH, line, grow)
            val cellW = max(
                text.cellLabels.maxOfOrNull { labelMeasure.widthDp(it, cell.cellLabelDp) } ?: 0f,
                text.cellTemps.maxOfOrNull { labelMeasure.widthDp(it, cell.cellTempDp) } ?: 0f,
            ) + d.STRIP_CELL_PAD
            fun cellsBeside(rowW: Float) = floor((innerW - rowW - d.STRIP_CELLS_GAP) / cellW).toInt().coerceIn(0, cellsWanted)
            var f = fit(innerW, withHighLow = true)
            var cells = if (cellsWanted >= 2) cellsBeside(rowWidth(f, true)) else 0
            // Shrink the temperature a little rather than drop a column the user asked for (in
            // a taller strip, as far back as its one-row size).
            var shrink = f
            val smallest = min(f * 0.84f, f / max(1f, k))
            while (cells < cellsWanted && shrink > smallest) {
                shrink -= 0.02f
                val more = cellsBeside(rowWidth(shrink, true))
                if (more > cells) { cells = more; f = shrink }
            }
            return Triple(cell, cells, f)
        }
        var (cell, cells, f) = columns(grow = true)
        if (cells < cellsWanted && innerH > d.STRIP_REF_H) {
            val flat = columns(grow = false)
            if (flat.second > cells) { cell = flat.first; cells = flat.second; f = flat.third }
        }
        if (cells < 2) cells = 0

        if (cells > 0) {
            return cell.copy(
                layout = StripLayout.ROW,
                iconDp = baseIcon * f,
                tempDp = baseTemp * f,
                highLowDp = baseHighLow * f,
                cells = cells,
            )
        }

        // No columns: the row alone, or (if the widget is narrow) without high and low, or stacked.
        var withHighLow = baseHighLow > 0f
        f = fit(innerW, withHighLow)
        if (withHighLow && f < 0.7f) {
            val without = fit(innerW, false)
            if (without > f + 0.15f) { withHighLow = false; f = without }
        }
        val row = StripPlan(StripLayout.ROW, baseIcon * f, baseTemp * f, if (withHighLow) baseHighLow * f else 0f)
        val stack = stackStrip(config, innerW, innerH, tempText, measure, labelMeasure, text, line)
        // Stacking wins if it's bigger, or keeps the high and low the row had to drop.
        val stackKeepsHighLow = row.highLowDp == 0f && stack != null && stack.highLowDp > 0f && stack.tempDp >= row.tempDp * 0.7f
        return if (stack != null && (stack.tempDp > row.tempDp || stackKeepsHighLow)) stack else row
    }

    /** Label, icon and temperature sizes for a strip's columns at this height. */
    private fun stripCell(kind: WidgetKind, config: WidgetConfig, innerH: Float, line: Float, grow: Boolean = true): StripPlan {
        val s = config.textScale.factor
        val d = WidgetDimens
        val g = (innerH / d.STRIP_REF_H).coerceIn(0.9f, if (grow) 1.15f else 1f)
        var label = d.STRIP_CELL_LABEL * s * g
        var temp = (if (kind == WidgetKind.DAILY_STRIP) d.STRIP_DAY_TEMP else d.STRIP_HOUR_TEMP) * s * g
        val gaps = 2 * d.STRIP_CELL_GAP
        // Short strips: smaller type before the icon gets too small to read.
        val minIcon = 16f
        val textH = (label + temp) * line
        if (innerH - textH - gaps < minIcon) {
            val t = ((innerH - gaps - minIcon) / textH).coerceIn(0.75f, 1f)
            label *= t
            temp *= t
        }
        val text = (label + temp) * line + gaps
        val maxIcon = d.STRIP_CELL_ICON * s * (innerH / d.STRIP_REF_H).coerceIn(1f, if (grow) 1.3f else 1f)
        var icon = (innerH - text).coerceIn(12f, maxIcon)
        val rainH = d.TEXT_TINY * 0.9f * s * line + d.STRIP_CELL_GAP
        val rain = config.showRainChance && innerH - text - rainH >= 20f * s
        if (rain) icon = min(icon, innerH - text - rainH)
        return StripPlan(StripLayout.ROW, 0f, 0f, 0f, 0, label, icon, temp, rain)
    }

    /** Icon over temperature over high and low; null if even that doesn't fit. */
    private fun stackStrip(
        config: WidgetConfig,
        innerW: Float,
        innerH: Float,
        tempText: String,
        measure: TextMeasure,
        labelMeasure: TextMeasure,
        text: StripText,
        line: Float,
    ): StripPlan? {
        val s = config.textScale.factor
        val em = measure.widthDp(tempText, 100f) / 100f
        val temp = minOf(innerW * 0.92f / em, innerH * 0.34f, 48f * s)
        val pair = "${text.high} ${text.low}"
        var highLow = if (config.showHighLow) min(12f * s * (innerH / 70f).coerceIn(1f, 1.2f), innerH * 0.16f) else 0f
        while (highLow > 0f && labelMeasure.widthDp(pair, highLow) > innerW) highLow = if (highLow > 9.5f) highLow - 0.5f else 0f
        val highLowH = if (highLow > 0f) highLow * line + 2f else 0f
        val icon = minOf(innerH - temp * line - highLowH - 2f, innerW * 0.85f, 64f * s)
        if (icon < 14f) return null
        return StripPlan(StripLayout.STACK, icon, temp, highLow)
    }

    /** Approximate width of [text] in ems for the light temperature font. */
    fun tempWidthEm(text: String): Float =
        text.sumOf { c ->
            when {
                c.isDigit() -> 0.56
                c == '°' -> 0.34
                c == '-' || c == '−' -> 0.36
                else -> 0.5
            }
        }.toFloat().coerceAtLeast(1f)

    private const val TEMP_LINE = 1.18f
    private const val MIN_BIG_TEMP = 30f
    private const val MAX_BIG_TEMP = 64f
    private const val MIN_BIG_ICON = 42f
    private const val MAX_BIG_ICON = 84f
}
