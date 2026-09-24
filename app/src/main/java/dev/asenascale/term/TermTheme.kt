package dev.asenascale.term

import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import java.util.Properties

/** Catppuccin, as kitty ships it: Mocha for dark mode, Latte for light mode. */
object TermTheme {
    private val mocha = mapOf(
        "background" to "#1e1e2e", "foreground" to "#cdd6f4", "cursor" to "#f5e0dc",
        "color0" to "#45475a", "color8" to "#585b70",
        "color1" to "#f38ba8", "color9" to "#f38ba8",
        "color2" to "#a6e3a1", "color10" to "#a6e3a1",
        "color3" to "#f9e2af", "color11" to "#f9e2af",
        "color4" to "#89b4fa", "color12" to "#89b4fa",
        "color5" to "#f5c2e7", "color13" to "#f5c2e7",
        "color6" to "#94e2d5", "color14" to "#94e2d5",
        "color7" to "#bac2de", "color15" to "#a6adc8",
    )

    private val latte = mapOf(
        "background" to "#eff1f5", "foreground" to "#4c4f69", "cursor" to "#dc8a78",
        "color0" to "#5c5f77", "color8" to "#6c6f85",
        "color1" to "#d20f39", "color9" to "#d20f39",
        "color2" to "#40a02b", "color10" to "#40a02b",
        "color3" to "#df8e1d", "color11" to "#df8e1d",
        "color4" to "#1e66f5", "color12" to "#1e66f5",
        "color5" to "#ea76cb", "color13" to "#ea76cb",
        "color6" to "#179299", "color14" to "#179299",
        "color7" to "#acb0be", "color15" to "#bcc0cc",
    )

    @Volatile var dark: Boolean? = null
        private set

    val background: Int get() = if (dark == false) 0xFFEFF1F5.toInt() else 0xFF1E1E2E.toInt()

    /** Switches the default colors; returns true if they changed. */
    fun set(isDark: Boolean): Boolean {
        if (dark == isDark) return false
        dark = isDark
        TerminalColors.COLOR_SCHEME.updateWith(Properties().apply { putAll(if (isDark) mocha else latte) })
        return true
    }

    /** Re-reads the defaults into an existing emulator (programs' own colors are kept per cell). */
    fun applyTo(emulator: TerminalEmulator) = emulator.mColors.reset()
}
