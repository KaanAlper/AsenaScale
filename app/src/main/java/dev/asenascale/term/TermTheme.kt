package dev.asenascale.term

import com.termux.terminal.TerminalColors
import java.util.Properties

/** Catppuccin Mocha — the same palette kitty ships as a theme. */
object TermTheme {
    const val BACKGROUND = 0xFF1E1E2E.toInt()

    private val colors = mapOf(
        "background" to "#1e1e2e",
        "foreground" to "#cdd6f4",
        "cursor" to "#f5e0dc",
        "color0" to "#45475a", "color8" to "#585b70",
        "color1" to "#f38ba8", "color9" to "#f38ba8",
        "color2" to "#a6e3a1", "color10" to "#a6e3a1",
        "color3" to "#f9e2af", "color11" to "#f9e2af",
        "color4" to "#89b4fa", "color12" to "#89b4fa",
        "color5" to "#f5c2e7", "color13" to "#f5c2e7",
        "color6" to "#94e2d5", "color14" to "#94e2d5",
        "color7" to "#bac2de", "color15" to "#a6adc8",
    )

    @Volatile private var applied = false

    fun apply() {
        if (applied) return
        applied = true
        TerminalColors.COLOR_SCHEME.updateWith(Properties().apply { putAll(colors) })
    }
}
