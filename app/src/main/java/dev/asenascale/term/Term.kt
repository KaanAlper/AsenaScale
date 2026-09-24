package dev.asenascale.term

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import dev.asenascale.App
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A terminal screen (emulator + scrollback) that outlives connections: when
 * the link drops and comes back, the new connection takes it over, so the
 * history stays and the PC only repaints the current screen.
 */
class Term(initialTitle: String) {
    /** Where keystrokes go: the current connection. */
    @Volatile var sink: ((ByteArray) -> Unit)? = null

    /** Called on the main thread whenever the screen changed. */
    var onScreenUpdate: (() -> Unit)? = null
    var onBell: (() -> Unit)? = null

    val title = MutableStateFlow(initialTitle)
    private val fallbackTitle = initialTitle

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            sink?.invoke(data.copyOfRange(offset, offset + count))
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            title.value = newTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle
        }
        override fun onCopyTextToClipboard(text: String?) {
            if (text != null) App.instance.copyToClipboard(text)
        }
        override fun onPasteTextFromClipboard() {
            App.instance.clipboardText()?.let { emulator.paste(it) }
        }
        override fun onBell() {
            onBell?.invoke()
        }
        override fun onColorsChanged() {
            onScreenUpdate?.invoke()
        }
    }

    private val client = object : TerminalSessionClient {
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    val emulator: TerminalEmulator = run {
        if (TermTheme.dark == null) TermTheme.set(true)
        TerminalEmulator(output, 80, 24, 5000, client)
    }
}
