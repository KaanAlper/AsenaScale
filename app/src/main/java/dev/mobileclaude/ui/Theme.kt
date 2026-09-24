package dev.mobileclaude.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mobileclaude.R

/** Catppuccin Mocha, to match the terminal. */
object Mocha {
    val crust = Color(0xFF11111B)
    val mantle = Color(0xFF181825)
    val base = Color(0xFF1E1E2E)
    val surface0 = Color(0xFF313244)
    val surface1 = Color(0xFF45475A)
    val overlay0 = Color(0xFF6C7086)
    val subtext = Color(0xFFA6ADC8)
    val text = Color(0xFFCDD6F4)
    val mauve = Color(0xFFCBA6F7)
    val peach = Color(0xFFFAB387)
    val green = Color(0xFFA6E3A1)
    val yellow = Color(0xFFF9E2AF)
    val red = Color(0xFFF38BA8)
}

val Mono = FontFamily(Font(R.font.jetbrains_mono))

private val colors = darkColorScheme(
    primary = Mocha.mauve,
    onPrimary = Mocha.crust,
    secondary = Mocha.peach,
    onSecondary = Mocha.crust,
    background = Mocha.base,
    onBackground = Mocha.text,
    surface = Mocha.base,
    onSurface = Mocha.text,
    surfaceVariant = Mocha.surface0,
    onSurfaceVariant = Mocha.subtext,
    surfaceContainerLowest = Mocha.crust,
    surfaceContainerLow = Mocha.mantle,
    surfaceContainer = Mocha.mantle,
    surfaceContainerHigh = Mocha.surface0,
    surfaceContainerHighest = Mocha.surface1,
    outline = Mocha.surface1,
    outlineVariant = Mocha.surface0,
    error = Mocha.red,
)

private val typography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        labelSmall = labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp)

@Composable
fun MobileClaudeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
