/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest shell has no framing protocol: output is carved out of the
 * terminal's scrollback by locating two marker lines. The terminal also echoes
 * every character sent to it, so the echoed command line contains the marker
 * *variables* (`$P`, `${P}end`) — and any framing that matches those words
 * instead of the expanded token returns the command as if it were its output.
 */
class GuestShellFramingTest {

    private val token = "__PODROID_1_"
    private val end = token + "end"

    @Test
    fun `frame echoes only the unexpanded variables`() {
        val (script, _, _) = GuestShell.frame("ls -la /", token)
        assertTrue(script, script.contains("\$P"))
        assertTrue(script, script.contains("\${P}end"))
        // The literal token must not appear anywhere in what we send, or the
        // terminal echo would contain the finished marker.
        assertFalse(script, script.contains("\"$token\""))
        assertFalse(script, script.contains("\"${token}end\""))
    }

    @Test
    fun `frame carries the command between the markers`() {
        val (script, _, _) = GuestShell.frame("ls -la /", token)
        assertTrue(script, script.contains("ls -la /"))
    }

    /** The realistic transcript: echo line, then output, then closing marker. */
    @Test
    fun `extracts output from a realistic transcript`() {
        val transcript = buildString {
            append("podroid@vm:~$ P=__PODROID_1_; printf '%s\\n' \"\$P\"; ls -la /; printf '%s\\n' \"\${P}end\"\r\n")
            append("$token\n")
            append("total 24\ndrwxr-xr-x  2 root root 4096 bin\n")
            append("$end\n")
            append("podroid@vm:~$ ")
        }
        assertEquals(
            "total 24\ndrwxr-xr-x  2 root root 4096 bin",
            GuestShell.extractOutput(transcript, token, end),
        )
    }

    /** No closing marker yet -> still running, not an empty result. */
    @Test
    fun `returns null while the command has not finished`() {
        val transcript = "podroid@vm:~$ P=$token; ls\r\n$token\ntotal 24\n"
        assertNull(GuestShell.extractOutput(transcript, token, end))
    }

    @Test
    fun `returns null when nothing has been sent at all`() {
        assertNull(GuestShell.extractOutput("", token, end))
        assertNull(GuestShell.extractOutput("podroid@vm:~$ ", token, end))
    }

    @Test
    fun `an empty listing yields an empty string, not null`() {
        val transcript = "echo\r\n$token\n$end\n"
        assertEquals("", GuestShell.extractOutput(transcript, token, end))
    }

    /**
     * The echo contains `${P}end` as separate words. Matching that would cut
     * the result at the echo and return the command line as the output.
     */
    @Test
    fun `does not treat the echoed variables as markers`() {
        val transcript = buildString {
            append("podroid@vm:~$ P=$token; printf '%s\\n' \"\$P\"; true; printf '%s\\n' \"\${P}end\"\r\n")
            append("$token\n")
            append("$end\n")
        }
        val out = GuestShell.extractOutput(transcript, token, end)
        assertEquals("", out)
        assertFalse(out!!.contains("printf"))
    }

    /** A listing row that happens to contain the token must not truncate it. */
    @Test
    fun `uses the last closing marker so token text in output does not truncate`() {
        val transcript = buildString {
            append("podroid@vm:~$ P=$token; ls a\r\n")
            append("$token\n")
            append("file-a\n")
            append("$end\n")
            append("podroid@vm:~$ P=$token; ls b\r\n")
            append("$token\n")               // next request starts
            append("file-b\n")
            append("$end\n")
            append("podroid@vm:~$ ")
        }
        assertEquals(
            "file-b",
            GuestShell.extractOutput(transcript, token, end),
        )
    }

    @Test
    fun `preserves interior blank lines and indentation`() {
        val transcript = "$token\nline1\n\n  indented\nline4\n$end\n"
        assertEquals(
            "line1\n\n  indented\nline4",
            GuestShell.extractOutput(transcript, token, end),
        )
    }

    /** CRLF is what a real PTY produces; a bare \n search must still work. */
    @Test
    fun `handles CRLF transcripts`() {
        val transcript = "cmd\r\n$token\r\nout\r\n$end\r\n"
        assertEquals("out", GuestShell.extractOutput(transcript, token, end))
    }

    @Test
    fun `returns null when the opening marker never arrived`() {
        // Closing marker present but no opening line — a partial transcript
        // from before the scrollback dropped the start. Must not fabricate a
        // result from whatever precedes it.
        assertNull(GuestShell.extractOutput("noise\n$end\n", token, end))
    }
}