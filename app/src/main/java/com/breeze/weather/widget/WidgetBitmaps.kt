package com.breeze.weather.widget

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.breeze.weather.data.Units
import com.breeze.weather.ui.IconPalette
import com.breeze.weather.ui.tempColor
import com.breeze.weather.ui.weatherIcon
import com.breeze.weather.ui.weatherIconTint
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the app's Compose vector icons and the daily temperature bars into bitmaps for
 * RemoteViews, which can't run Compose. One instance serves one widget update: it hands back the
 * same Bitmap object for the same icon, so RemoteViews sends each distinct image only once.
 */
class WidgetBitmaps(private val density: Float) {

    private val cache = HashMap<Any, Bitmap>()

    fun px(dp: Float): Int = max(1, (dp * density).roundToInt())

    /** A full-colour weather icon [sizeDp] square. */
    fun weather(code: Int, isDay: Boolean, sizeDp: Float, palette: IconPalette): Bitmap {
        val size = px(sizeDp).coerceAtMost(MAX_ICON_PX)
        return cache.getOrPut(listOf("wx", code, isDay, size, palette)) {
            val tint = weatherIconTint(code, isDay, palette)
            vector(weatherIcon(code, isDay, palette), size, if (tint == Color.Unspecified) null else tint)
        }
    }

    /** A single-colour glyph (white, tinted by the ImageView) [sizeDp] square, turned [rotation] degrees. */
    fun glyph(icon: ImageVector, sizeDp: Float, rotation: Float = 0f): Bitmap {
        val size = px(sizeDp).coerceAtMost(MAX_ICON_PX)
        return cache.getOrPut(listOf("glyph", icon.name, size, rotation)) { vector(icon, size, Color.White, rotation) }
    }

    /**
     * A day's temperature bar: a faint track for the whole range on show, with the day's low to
     * high in a cool-to-warm gradient, like the app's 10-day forecast.
     */
    fun rangeBar(
        widthDp: Float,
        heightDp: Float,
        low: Double,
        high: Double,
        min: Double,
        max: Double,
        units: Units,
        track: Color,
    ): Bitmap {
        val w = px(widthDp).coerceAtMost(MAX_BAR_PX)
        val h = px(heightDp)
        val key = listOf("bar", w, h, low, high, min, max, units, track)
        return cache.getOrPut(key) {
            draw(w, h) {
                val r = CornerRadius(h / 2f)
                drawRoundRect(track, cornerRadius = r)
                val span = (max - min).takeIf { it > 0 } ?: 1.0
                val x0 = ((low - min) / span * w).toFloat()
                val x1 = ((high - min) / span * w).toFloat().coerceAtLeast(x0 + h)
                val left = x0.coerceIn(0f, w - h.toFloat())
                val right = x1.coerceIn(left + h, w.toFloat())
                drawRoundRect(
                    Brush.horizontalGradient(listOf(tempColor(low, units), tempColor(high, units)), startX = left, endX = right),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, h.toFloat()),
                    cornerRadius = r,
                )
            }
        }
    }

    private fun vector(icon: ImageVector, sizePx: Int, tint: Color?, rotation: Float = 0f): Bitmap = draw(sizePx, sizePx) {
        val filter = tint?.let { ColorFilter.tint(it) }
        withTransform({
            rotate(rotation)
            scale(sizePx / icon.viewportWidth, sizePx / icon.viewportHeight, pivot = Offset.Zero)
        }) {
            drawGroup(icon.root, filter)
        }
    }

    private fun DrawScope.drawGroup(group: VectorGroup, filter: ColorFilter?) {
        withTransform({
            translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
            rotate(group.rotation, pivot = Offset.Zero)
            scale(group.scaleX, group.scaleY, pivot = Offset.Zero)
            translate(-group.pivotX, -group.pivotY)
        }) {
            val clip = group.clipPathData
            if (clip.isNotEmpty()) clipPath(clip.toPath()) { drawChildren(group, filter) } else drawChildren(group, filter)
        }
    }

    private fun DrawScope.drawChildren(group: VectorGroup, filter: ColorFilter?) {
        for (node in group) {
            when (node) {
                is VectorGroup -> drawGroup(node, filter)
                is VectorPath -> {
                    val path = node.pathData.toPath().apply { fillType = node.pathFillType }
                    node.fill?.let { drawPath(path, it, alpha = node.fillAlpha, colorFilter = filter) }
                    node.stroke?.let {
                        drawPath(
                            path, it, alpha = node.strokeAlpha, colorFilter = filter,
                            style = Stroke(node.strokeLineWidth, node.strokeLineMiter, node.strokeLineCap, node.strokeLineJoin),
                        )
                    }
                }
            }
        }
    }

    private fun draw(width: Int, height: Int, block: DrawScope.() -> Unit): Bitmap {
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), block)
        return image.asAndroidBitmap()
    }

    private companion object {
        /** Caps keep a widget's images well inside the launcher's bitmap memory budget. */
        const val MAX_ICON_PX = 288
        const val MAX_BAR_PX = 900
    }
}
