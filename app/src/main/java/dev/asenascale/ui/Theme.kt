package dev.asenascale.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.asenascale.R

/** Catppuccin, like the terminal: Mocha in dark mode, Latte in light mode. */
@Immutable
data class Palette(
    val crust: Color,
    val mantle: Color,
    val base: Color,
    val surface0: Color,
    val surface1: Color,
    val overlay0: Color,
    val subtext: Color,
    val text: Color,
    val mauve: Color,
    val peach: Color,
    val green: Color,
    val yellow: Color,
    val red: Color,
    val dark: Boolean,
)

val MochaPalette = Palette(
    crust = Color(0xFF11111B), mantle = Color(0xFF181825), base = Color(0xFF1E1E2E),
    surface0 = Color(0xFF313244), surface1 = Color(0xFF45475A), overlay0 = Color(0xFF6C7086),
    subtext = Color(0xFFA6ADC8), text = Color(0xFFCDD6F4), mauve = Color(0xFFCBA6F7),
    peach = Color(0xFFFAB387), green = Color(0xFFA6E3A1), yellow = Color(0xFFF9E2AF), red = Color(0xFFF38BA8),
    dark = true,
)

val LattePalette = Palette(
    crust = Color(0xFFDCE0E8), mantle = Color(0xFFE6E9EF), base = Color(0xFFEFF1F5),
    surface0 = Color(0xFFCCD0DA), surface1 = Color(0xFFBCC0CC), overlay0 = Color(0xFF8C8FA1),
    subtext = Color(0xFF6C6F85), text = Color(0xFF4C4F69), mauve = Color(0xFF8839EF),
    peach = Color(0xFFFE640B), green = Color(0xFF40A02B), yellow = Color(0xFFDF8E1D), red = Color(0xFFD20F39),
    dark = false,
)

val LocalPalette = staticCompositionLocalOf { MochaPalette }

/** Current theme colors. */
object Pal {
    val crust: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.crust
    val mantle: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.mantle
    val base: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.base
    val surface0: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface0
    val surface1: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface1
    val overlay0: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.overlay0
    val subtext: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.subtext
    val text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val mauve: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.mauve
    val peach: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.peach
    val green: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.green
    val yellow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.yellow
    val red: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.red
}

val Mono = FontFamily(Font(R.font.jetbrains_mono))

private fun scheme(p: Palette) = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.mauve,
    onPrimary = p.crust,
    secondary = p.peach,
    onSecondary = p.crust,
    background = p.base,
    onBackground = p.text,
    surface = p.base,
    onSurface = p.text,
    surfaceVariant = p.surface0,
    onSurfaceVariant = p.subtext,
    surfaceContainerLowest = p.crust,
    surfaceContainerLow = p.mantle,
    surfaceContainer = p.mantle,
    surfaceContainerHigh = p.surface0,
    surfaceContainerHighest = p.surface1,
    outline = p.surface1,
    outlineVariant = p.surface0,
    error = p.red,
)

private val typography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        labelSmall = labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp)

@Composable
fun AsenaScaleTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) MochaPalette else LattePalette
    val colors = scheme(palette)
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = colors, typography = typography) {
            // Without this, Text outside a Surface falls back to black.
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}
