package com.breeze.weather.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0E1424)
val InkRaised = Color(0xFF182036)
val Sky = Color(0xFF7CB8FF)

private val colors = darkColorScheme(
    primary = Sky,
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF1E3A66),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFFB9C7E0),
    background = Ink,
    onBackground = Color(0xFFE6EAF2),
    surface = Ink,
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = InkRaised,
    onSurfaceVariant = Color(0xFFA9B3C7),
    surfaceContainer = InkRaised,
    surfaceContainerHigh = Color(0xFF212A42),
    surfaceContainerHighest = Color(0xFF29334D),
    outline = Color(0xFF3A4560),
    outlineVariant = Color(0xFF2A3350),
    error = Color(0xFFFF8A80),
)

private val base = Typography()
private val typography = Typography(
    displayLarge = TextStyle(fontSize = 104.sp, fontWeight = FontWeight.Thin, letterSpacing = (-4).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(letterSpacing = 0.6.sp),
)

@Composable
fun BreezeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
