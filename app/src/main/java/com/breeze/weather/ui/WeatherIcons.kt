package com.breeze.weather.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Breeze's weather icons, drawn in full colour from one shared cloud shape so the set reads as a
 * family: the cloud is the same size whether it's alone or has rain, snow or a bolt under it.
 * Partly cloudy is the deliberate exception, with a smaller cloud under the sun (and the stock
 * moon-and-cloud glyph at night). Draw them with [WeatherIcon], which picks the right tint.
 */

/** What an icon shows. */
enum class IconKind { CLEAR, CLOUD, FOG, DRIZZLE, RAIN, SNOW, STORM }

/**
 * Icon recipe for a WMO weather code.
 *
 * @param peek the sun (day) or moon (night) peeks out from behind the cloud's top right: partly
 *   cloudy, rain showers and snow showers.
 * @param count drops, streaks or flakes under the cloud; grows with intensity.
 * @param hail a thunderstorm with hail adds ice pellets beside the bolt.
 * @param freezing freezing rain or drizzle: shorter rain lands on a line of ice.
 */
data class IconSpec(
    val kind: IconKind,
    val peek: Boolean = false,
    val count: Int = 0,
    val hail: Boolean = false,
    val freezing: Boolean = false,
)

fun iconSpec(code: Int): IconSpec = when (code) {
    0, 1 -> IconSpec(IconKind.CLEAR)
    2 -> IconSpec(IconKind.CLOUD, peek = true)
    3 -> IconSpec(IconKind.CLOUD)
    45, 48 -> IconSpec(IconKind.FOG)
    51, 53, 55 -> IconSpec(IconKind.DRIZZLE, count = 2)
    56, 57 -> IconSpec(IconKind.DRIZZLE, count = 2, freezing = true)
    61 -> IconSpec(IconKind.RAIN, count = 3)
    63 -> IconSpec(IconKind.RAIN, count = 4)
    66 -> IconSpec(IconKind.RAIN, count = 3, freezing = true)
    67 -> IconSpec(IconKind.RAIN, count = 4, freezing = true)
    65 -> IconSpec(IconKind.RAIN, count = 5)
    80 -> IconSpec(IconKind.RAIN, peek = true, count = 3)
    81 -> IconSpec(IconKind.RAIN, peek = true, count = 4)
    82 -> IconSpec(IconKind.RAIN, peek = true, count = 5)
    71 -> IconSpec(IconKind.SNOW, count = 2)
    73, 77 -> IconSpec(IconKind.SNOW, count = 3)
    75 -> IconSpec(IconKind.SNOW, count = 4)
    85 -> IconSpec(IconKind.SNOW, peek = true, count = 2)
    86 -> IconSpec(IconKind.SNOW, peek = true, count = 3)
    95 -> IconSpec(IconKind.STORM)
    96, 99 -> IconSpec(IconKind.STORM, hail = true)
    else -> IconSpec(IconKind.CLOUD)
}

/** Full-colour icon for a WMO weather [code]. */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, modifier: Modifier = Modifier, contentDescription: String? = null) {
    // Partly cloudy at night is the stock moon-and-cloud glyph, the one icon that takes a tint.
    val tint = if (isPartlyCloudyNight(code, isDay)) MoonWhite else Color.Unspecified
    Icon(weatherIcon(code, isDay), contentDescription = contentDescription, tint = tint, modifier = modifier)
}

private fun isPartlyCloudyNight(code: Int, isDay: Boolean) = !isDay && iconSpec(code) == IconSpec(IconKind.CLOUD, peek = true)

private val cache = HashMap<Pair<IconSpec, Boolean>, ImageVector>()

fun weatherIcon(code: Int, isDay: Boolean): ImageVector {
    if (isPartlyCloudyNight(code, isDay)) return Icons.Rounded.NightsStay
    val spec = iconSpec(code)
    // Day and night only differ where a sun or moon is drawn.
    val day = isDay || !(spec.kind == IconKind.CLEAR || spec.peek)
    return cache.getOrPut(spec to day) { buildIcon(spec, day) }
}

// ---------- Palette ----------

private val SunGold = Color(0xFFFFD54F)
private val MoonWhite = Color(0xFFE3E8F5)
private val DropBlue = Color(0xFF64B5F6)
private val BoltGold = Color(0xFFFFCA28)
private val Ice = Color(0xFFE0F2FE)
private val IceLine = Color(0xFFA5D8F3)

// ---------- Layout (24 × 24 viewport) ----------

/** Cloud width for every icon without a sun or moon: a middle ground between a lone cloud and one with rain. */
private const val CLOUD_W = 20f
/** Peeking icons share the space with the sun or moon, so their cloud is a little narrower. */
private const val PEEK_CLOUD_W = 18.4f
/** Space between the cloud's bottom and whatever falls from it. */
private const val PRECIP_GAP = 1.4f

private fun buildIcon(spec: IconSpec, day: Boolean): ImageVector =
    ImageVector.Builder("Weather_${spec.kind}_${spec.peek}_${spec.count}_${spec.hail}_${spec.freezing}_$day", 24.dp, 24.dp, 24f, 24f).apply {
        if (spec.kind == IconKind.CLEAR) {
            if (day) sun(12f, 12f, 4.8f, rayFrom = 7f, rayTo = 9.6f, rayWidth = 2f) else crescent(12f, 12f, 7.6f)
            return@apply
        }

        if (spec.kind == IconKind.CLOUD && spec.peek) {
            partlyCloudyDay()
            return@apply
        }

        val hasPrecip = spec.kind != IconKind.CLOUD
        val cloudW = if (spec.peek) PEEK_CLOUD_W else CLOUD_W
        val cloudH = cloudW * CLOUD_ASPECT
        val left = if (spec.peek) 0.6f else (24f - cloudW) / 2
        val top = when {
            spec.peek -> 4.4f // showers: sun or moon top right, precipitation below
            hasPrecip -> 2.4f
            else -> (24f - cloudH) / 2 // lone cloud, centred
        }
        val bottom = top + cloudH
        val cx = left + cloudW / 2

        // Sun or moon goes first so the cloud covers its lower left.
        if (spec.peek) {
            val (bx, by, r) = Triple(16.6f, 6.6f, 3.3f)
            if (day) sun(bx, by, r, rayFrom = r + 1.2f, rayTo = r + 2.4f, rayWidth = 1.4f)
            // Bite from the upper left so the moon's bulk shows to the right of the cloud.
            else crescent(bx + 0.4f, by - 0.2f, r + 0.6f, biteX = -0.55f)
        }

        cloud(left, top, cloudW, cloudFill(spec, top, bottom))

        val precipTop = bottom + PRECIP_GAP
        when (spec.kind) {
            IconKind.FOG -> path(stroke = SolidColor(Color.White.copy(alpha = 0.9f)), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, bottom + 2.3f); horizontalLineTo(20f)
                moveTo(6.5f, bottom + 5.6f); horizontalLineTo(17.5f)
            }
            IconKind.DRIZZLE -> {
                // Freezing drizzle tucks the drops up to leave room for the ice.
                drop(cx - 2.6f, precipTop + if (spec.freezing) 1.9f else 2.4f)
                drop(cx + 2.6f, precipTop + if (spec.freezing) 2.7f else 4.6f)
            }
            IconKind.RAIN -> rainStreaks(spec.count, cx, precipTop, scale = if (spec.freezing) 0.7f else 1f)
            IconKind.SNOW -> snowflakes(spec.count, cx, precipTop)
            IconKind.STORM -> {
                bolt(cx, bottom - 2.4f)
                if (spec.hail) {
                    dot(cx - 4.6f, precipTop + 3.4f, 1f, Ice)
                    dot(cx + 4.8f, precipTop + 2f, 1f, Ice)
                }
            }
            IconKind.CLOUD, IconKind.CLEAR -> Unit
        }
        if (spec.freezing) iceLine(cx, 22f)
    }.build()

/** Partly cloudy (day): a smaller white cloud with the sun peeking out behind its top right. */
private fun ImageVector.Builder.partlyCloudyDay() {
    val r = 3.6f
    sun(15.6f, 9.9f, r, rayFrom = r + 1.3f, rayTo = r + 2.5f, rayWidth = 1.5f)
    val k = 1.05f
    cloud(left = -1.9f + CLOUD_X0 * k, top = 6.8f + CLOUD_Y0 * k, width = CLOUD_BASE_W * k, fill = Brush.verticalGradient(listOf(Color.White, Color(0xFFE2E8F0)), startY = 9f, endY = 21f))
}

private fun cloudFill(spec: IconSpec, top: Float, bottom: Float): Brush {
    val (hi, lo) = when (spec.kind) {
        // Rain clouds darken as the rain gets heavier: 0 light … 1 heavy.
        IconKind.RAIN -> {
            val heaviness = (spec.count - 3) / 2f
            lerp(Color(0xFFF7F9FC), Color(0xFFD3DAE4), heaviness) to lerp(Color(0xFFD9E0EA), Color(0xFFA7B2C2), heaviness)
        }
        IconKind.DRIZZLE -> Color(0xFFF7F9FC) to Color(0xFFD9E0EA)
        IconKind.STORM -> Color(0xFFB9C2D0) to Color(0xFF7F8A9D)
        else -> Color.White to Color(0xFFE2E8F0)
    }
    return Brush.verticalGradient(listOf(hi, lo), startY = top, endY = bottom)
}

// ---------- Shapes ----------

// The cloud outline at scale 1 spans x 2.8–20.9, y 1.9–13.5.
private const val CLOUD_X0 = 2.8f
private const val CLOUD_Y0 = 1.9f
private const val CLOUD_BASE_W = 18.1f
private const val CLOUD_ASPECT = 11.6f / CLOUD_BASE_W

/** The app's cloud, scaled to [width] with its bounding box's top-left at ([left], [top]). */
private fun ImageVector.Builder.cloud(left: Float, top: Float, width: Float, fill: Brush) {
    val k = width / CLOUD_BASE_W
    val ox = left - CLOUD_X0 * k
    val oy = top - CLOUD_Y0 * k
    path(fill = fill) {
        moveTo(ox + 7f * k, oy + 13.5f * k)
        horizontalLineTo(ox + 17f * k)
        arcToRelative(3.5f * k, 3.5f * k, 0f, false, false, 0.4f * k, -6.98f * k)
        arcToRelative(5.6f * k, 5.6f * k, 0f, false, false, -10.7f * k, -0.9f * k)
        arcToRelative(3.95f * k, 3.95f * k, 0f, false, false, 0.3f * k, 7.88f * k)
        close()
    }
}

private fun ImageVector.Builder.dot(cx: Float, cy: Float, r: Float, color: Color) = path(fill = SolidColor(color)) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}

private fun ImageVector.Builder.sun(cx: Float, cy: Float, r: Float, rayFrom: Float, rayTo: Float, rayWidth: Float) {
    path(stroke = SolidColor(SunGold), strokeLineWidth = rayWidth, strokeLineCap = StrokeCap.Round) {
        for (a in 0 until 360 step 45) {
            val rad = Math.toRadians(a.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            moveTo(cx + dx * rayFrom, cy + dy * rayFrom)
            lineTo(cx + dx * rayTo, cy + dy * rayTo)
        }
    }
    dot(cx, cy, r, SunGold)
}

/**
 * A crescent moon: a disc of radius [r] with a bite taken from its top right, or its top left when
 * [biteX] is negative.
 */
private fun ImageVector.Builder.crescent(cx: Float, cy: Float, r: Float, biteX: Float = 0.55f) {
    val bx = cx + r * biteX
    val by = cy - r * 0.45f
    val r2 = r * 0.82f
    val dx = bx - cx
    val dy = by - cy
    val d = hypot(dx, dy)
    // Where the two circles cross.
    val l = (r * r - r2 * r2 + d * d) / (2 * d)
    val h = sqrt(r * r - l * l)
    val ux = dx / d
    val uy = dy / d
    val p1x = cx + l * ux - h * uy
    val p1y = cy + l * uy + h * ux
    val p2x = cx + l * ux + h * uy
    val p2y = cy + l * uy - h * ux
    path(fill = SolidColor(MoonWhite)) {
        moveTo(p1x, p1y)
        arcTo(r, r, 0f, true, true, p2x, p2y) // the outer edge, away from the bite
        arcTo(r2, r2, 0f, false, false, p1x, p1y) // back along the bite
        close()
    }
}

/** An upright teardrop whose round bottom is centred at ([cx], [cy]). */
private fun ImageVector.Builder.drop(cx: Float, cy: Float) {
    val r = 1.35f
    path(fill = SolidColor(DropBlue)) {
        moveTo(cx, cy - 2.7f)
        lineTo(cx + r * 0.87f, cy - r * 0.5f)
        arcTo(r, r, 0f, true, true, cx - r * 0.87f, cy - r * 0.5f)
        close()
    }
}

/**
 * Slanted rain streaks centred under the cloud at [cx]. They lie on equally spaced parallel lines;
 * each starts a little lower on its line (staggered), keeping the spacing even while looking less
 * mechanical.
 */
private fun ImageVector.Builder.rainStreaks(n: Int, cx: Float, top: Float, scale: Float = 1f) {
    val spacing = if (n >= 4) 3.0f else 3.3f
    val lengths = listOf(3.2f, 2.6f, 3.4f, 2.7f, 3.1f).map { it * scale }
    val drops = listOf(0f, 1.1f, 0.4f, 1.3f, 0.6f).map { it * scale }
    val slant = 0.38f // leftward run per unit of fall
    // Centre the streaks' midpoints (not their tops) under the cloud.
    val firstX = cx - spacing * (n - 1) / 2 + 0.6f
    path(stroke = SolidColor(DropBlue), strokeLineWidth = if (n >= 4) 1.45f else 1.65f, strokeLineCap = StrokeCap.Round) {
        repeat(n) { i ->
            val len = lengths[i]
            val y = top + drops[i]
            val x = firstX + i * spacing - drops[i] * slant
            moveTo(x, y)
            lineTo(x - len * slant, y + len)
        }
    }
}

/** The glaze freezing rain leaves: a pale ice line with a bright glint, centred at [cx]. */
private fun ImageVector.Builder.iceLine(cx: Float, y: Float) {
    path(stroke = SolidColor(IceLine), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
        moveTo(cx - 7.5f, y); lineTo(cx + 7.5f, y)
    }
    path(stroke = SolidColor(Color.White), strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round) {
        moveTo(cx - 5.6f, y - 0.25f); lineTo(cx - 3.4f, y - 0.25f)
    }
}

/** Small six-armed flakes in two staggered rows. */
private fun ImageVector.Builder.snowflakes(n: Int, cx: Float, top: Float) {
    val r = 1.6f
    val upper = top + r
    val lower = top + r + 2.4f
    val spots = when (n) {
        2 -> listOf(cx - 2.6f to upper, cx + 2.6f to lower)
        3 -> listOf(cx - 4.4f to upper, cx to lower, cx + 4.4f to upper)
        else -> listOf(cx - 5.4f to upper, cx - 1.8f to lower, cx + 1.8f to upper, cx + 5.4f to lower)
    }
    path(stroke = SolidColor(Color.White), strokeLineWidth = 1.1f, strokeLineCap = StrokeCap.Round) {
        spots.forEach { (x, y) ->
            for (a in listOf(90, 30, -30)) {
                val rad = Math.toRadians(a.toDouble())
                val dx = cos(rad).toFloat() * r
                val dy = sin(rad).toFloat() * r
                moveTo(x - dx, y - dy)
                lineTo(x + dx, y + dy)
            }
        }
    }
}

/** A lightning bolt hanging from the cloud's base, starting at [y0]. */
private fun ImageVector.Builder.bolt(cx: Float, y0: Float) = path(fill = SolidColor(BoltGold)) {
    val k = 1.2f
    moveTo(cx + 0.8f * k, y0)
    lineTo(cx - 2.4f * k, y0 + 4.6f * k)
    lineTo(cx - 0.2f * k, y0 + 4.6f * k)
    lineTo(cx - 1.4f * k, y0 + 8.4f * k)
    lineTo(cx + 2.6f * k, y0 + 3.2f * k)
    lineTo(cx + 0.4f * k, y0 + 3.2f * k)
    lineTo(cx + 2f * k, y0)
    close()
}
