package com.breeze.weather.widget

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF

/**
 * The sizes, in dp, a widget can be shown at right now.
 *
 * @param exact sizes the launcher reported on Android 12+ (typically portrait and landscape), or
 *   a single known size; empty when only the min/max box is known.
 */
data class WidgetSizes(val exact: List<SizeF>, val portrait: SizeF, val landscape: SizeF) {

    /** The size the widget is most likely shown at, for previews: portrait. */
    val primary: SizeF get() = exact.firstOrNull() ?: portrait

    companion object {
        /** At most this many layouts per update, to bound the bitmaps sent to the launcher. */
        private const val MAX_SIZES = 4

        fun fixed(widthDp: Float, heightDp: Float): WidgetSizes {
            val size = SizeF(widthDp, heightDp)
            return WidgetSizes(listOf(size), size, size)
        }

        fun default(kind: WidgetKind) = fixed(kind.defaultWidthDp.toFloat(), kind.defaultHeightDp.toFloat())

        /** Reads the launcher's size options for a placed widget. */
        fun from(options: Bundle?, kind: WidgetKind): WidgetSizes {
            if (options == null) return default(kind)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    ?.filter { it.width > 0 && it.height > 0 }
                    ?.distinct()
                    ?.take(MAX_SIZES)
                if (!sizes.isNullOrEmpty()) return WidgetSizes(sizes, sizes.first(), sizes.last())
            }
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (minW <= 0 || minH <= 0) return default(kind)
            // The launcher's convention: portrait is narrow and tall, landscape wide and short.
            val portrait = SizeF(minW.toFloat(), maxOf(minH, maxH).toFloat())
            val landscape = SizeF(maxOf(minW, maxW).toFloat(), minH.toFloat())
            return if (portrait == landscape) WidgetSizes(listOf(portrait), portrait, landscape)
            else WidgetSizes(emptyList(), portrait, landscape)
        }
    }
}
