/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.ui.screens.terminal

/**
 * Strip terminal control sequences from guest console output.
 *
 * The serial console is a real terminal stream: UEFI/EDK2 writes cursor
 * positioning, screen clears and colour changes (`ESC[2J`, `ESC[=3h`,
 * `ESC[01;01H`) for its own renderer, none of which means anything in a
 * Compose Text and which otherwise litter the log with unreadable escapes.
 *
 * Kept as a pure function with a single pass so it can be unit-tested — the
 * sequence grammar here is small (CSI, OSC, two-char escapes), and being
 * wrong shows up as garbage on screen rather than a crash.
 */
object ConsoleTextCleaner {

    private val PATTERN = Regex(
        // ESC ] ... BEL | ST   -> OSC (window title, clipboard, etc.)
        "\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +
            // ESC [ ... final byte in @-~   -> CSI
            "|\\u001B\\[[0-?]*[ -/]*[@-~]" +
            // ESC ] (unterminated OSC)
            "|\\u001B\\][^\u0007]*\u0007" +
            // ESC P/X/^/_ ... ST        -> DCS/SOS/PM/APC
            "|\\u001B[P^_X][^\u001B]*\u001B\\\\" +
            // Two-char escapes: ESC ( B, ESC = , ESC 7, ESC M, ...
            "|\\u001B[ -/]*[0-~]" +
            // Lone / doubled ESC
            "|\u001B",
    )

    /** Remove control sequences and collapse the padding they leave behind. */
    fun clean(raw: String): String = PATTERN.replace(raw, "")

    /**
     * Last [maxLines] lines of [raw], cleaned, for a fixed-height log pane.
     *
     * Trailing blank lines are dropped because UEFI clears the screen before
     * each redraw, so a naive split leaves the pane scrolled to empty space
     * instead of at the newest output.
     */
    fun tail(raw: String, maxLines: Int): String {
        val lines = clean(raw).lines().toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        return if (lines.size <= maxLines) {
            lines.joinToString("\n")
        } else {
            lines.subList(lines.size - maxLines, lines.size).joinToString("\n")
        }
    }
}