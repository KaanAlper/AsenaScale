package com.termux.terminal;

/**
 * Callbacks from {@link TerminalEmulator} to its host.
 *
 * Trimmed for Mobile Claude: the original Termux interface also carried
 * TerminalSession (local pty) callbacks, which this app does not use.
 */
public interface TerminalSessionClient {

    void onTerminalCursorStateChange(boolean state);

    Integer getTerminalCursorStyle();

    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);
}
