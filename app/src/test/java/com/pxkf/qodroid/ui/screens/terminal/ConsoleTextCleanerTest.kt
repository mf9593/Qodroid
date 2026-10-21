/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.ui.screens.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest console pane shows serial output directly. UEFI/EDK2 emits cursor
 * positioning and screen clears for its own renderer, which are meaningless to
 * a Compose Text — left in, they render as literal `[2J[01;01H` noise and make
 * the log unreadable.
 */
class ConsoleTextCleanerTest {

    @Test
    fun `removes EDK2 screen clears and cursor positioning`() {
        val raw = "\u001B[2J\u001B[01;01H\u001B[=3hBdsDxe: starting\n"
        assertEquals("BdsDxe: starting\n", ConsoleTextCleaner.clean(raw))
    }

    @Test
    fun `removes SGR colour sequences but keeps the text`() {
        val raw = "\u001B[1m\u001B[33mShell> \u001B[0m\u001B[37m\u001B[40m\u001B[0m"
        assertEquals("Shell> ", ConsoleTextCleaner.clean(raw))
    }

    @Test
    fun `keeps plain text untouched`() {
        val raw = "UEFI Interactive Shell v2.2\nEDK II\n"
        assertEquals(raw, ConsoleTextCleaner.clean(raw))
    }

    /**
     * ESC then 'b' is itself a two-char escape (REP — repeat previous character),
     * so both the ESC and the following byte are consumed. Stripping only the
     * ESC would leave a stray letter, which is worse than dropping the pair.
     */
    @Test
    fun `handles a bare ESC followed by a two-char escape`() {
        assertEquals("a", ConsoleTextCleaner.clean("a\u001Bb"))
    }

    /** A trailing ESC with nothing after it must still be dropped. */
    @Test
    fun `handles a trailing ESC`() {
        assertEquals("a", ConsoleTextCleaner.clean("a\u001B"))
    }

    @Test
    fun `removes OSC title sequences terminated by BEL`() {
        assertEquals("x", ConsoleTextCleaner.clean("\u001B]0;some title\u0007x"))
    }

    @Test
    fun `removes DCS sequences terminated by ST`() {
        assertEquals("y", ConsoleTextCleaner.clean("\u001BPpayload\u001B\\y"))
    }

    @Test
    fun `preserves non-ascii guest output`() {
        assertEquals("启动中", ConsoleTextCleaner.clean("\u001B[2J启动中"))
    }

    @Test
    fun `tail returns the last n lines`() {
        val raw = (1..100).joinToString("\n") { "line $it" }
        val out = ConsoleTextCleaner.tail(raw, 5)
        assertEquals("line 96\nline 97\nline 98\nline 99\nline 100", out)
    }

    @Test
    fun `tail does not truncate when output is shorter than the cap`() {
        assertEquals("a\nb", ConsoleTextCleaner.tail("a\nb", 50))
    }

    /**
     * UEFI clears the screen before each redraw, so raw output ends in a run of
     * blank lines. Keeping them would leave the pane scrolled to empty space
     * instead of at the newest message.
     */
    @Test
    fun `tail drops trailing blank lines left by screen clears`() {
        val raw = "BdsDxe: starting\n\n\n\n"
        assertEquals("BdsDxe: starting", ConsoleTextCleaner.tail(raw, 50))
    }

    @Test
    fun `tail of empty input is empty`() {
        assertEquals("", ConsoleTextCleaner.tail("", 20))
        assertEquals("", ConsoleTextCleaner.tail("\u001B[2J\n\n", 20))
    }

    @Test
    fun `cleaned output contains no escape characters`() {
        val raw = "\u001B[2J\u001B[01;01H\u001B[=3h\u001B[1m\u001B[33mShell> \u001B[0m"
        assertFalse(ConsoleTextCleaner.clean(raw).contains('\u001B'))
    }

    @Test
    fun `clean is idempotent`() {
        val raw = "\u001B[2J\u001B[=3hHello\n"
        val once = ConsoleTextCleaner.clean(raw)
        assertEquals(once, ConsoleTextCleaner.clean(once))
    }

    @Test
    fun `empty string stays empty`() {
        assertTrue(ConsoleTextCleaner.clean("").isEmpty())
    }
}