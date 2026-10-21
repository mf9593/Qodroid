/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine.guest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest does not hand out a ready shell: `podroid login:` appears first,
 * then a password prompt, and only then a shell. Sending a command to a session
 * sitting at the login prompt types it into the *username* field — which is
 * exactly what happened, leaving `ls -la '/'` as a username and a permanently
 * broken session.
 *
 * The prompt sniffing is therefore the part that has to be right, and it is
 * pure enough to test without a guest.
 */
class GuestShellLoginTest {

    /** The real banner and prompts from the bundled Alpine image. */
    private val banner = """
        Welcome to Podroid (Alpine Linux)
        Kernel 7.1.5 on aarch64 (/dev/hvc0)

          Default login:  root  /  podroid
          Change root password:    passwd

        podroid login:
    """.trimIndent()

    private val passwordPrompt = "$banner\n$banner\nroot\nPassword:"

    private val shellPrompt = "$passwordPrompt\npodroid\npodroid:~# "

    @Test
    fun `a login prompt is not mistaken for a shell`() {
        // The banner contains "#" nowhere but does contain "passwd"; a naive
        // "does the tail contain a sigil" check would pass on other banners.
        assertFalse(GuestShell.sniff(banner))
    }

    @Test
    fun `a password prompt is not mistaken for a shell`() {
        assertFalse(GuestShell.sniff(passwordPrompt))
    }

    @Test
    fun `a root shell prompt is recognised`() {
        assertTrue(GuestShell.sniff(shellPrompt))
    }

    @Test
    fun `a non-root shell prompt is recognised`() {
        assertTrue(GuestShell.sniff("podroid:~# Welcome\nuser@vm:~$ "))
    }

    @Test
    fun `a busy line after the prompt is still a shell`() {
        // The command line the user just typed ends the transcript; the session
        // is a shell even though the last line is not the prompt itself.
        assertTrue(GuestShell.sniff("$shellPrompt\nls -la /\n"))
    }

    @Test
    fun `an empty transcript is not a shell`() {
        assertFalse(GuestShell.sniff(""))
        assertFalse(GuestShell.sniff("   \n\n"))
    }

    /**
     * A '#' inside the banner must not count. The login banner's own text is
     * what broke the naive version of this check.
     */
    @Test
    fun `banner text containing sigils does not count`() {
        val tricky = """
            Welcome to Podroid (Alpine Linux)
              Change root password:    passwd
              wheel group → can run doas/sudo
            podroid login:
        """.trimIndent()
        assertFalse(GuestShell.sniff(tricky))
    }

    @Test
    fun `password prompt wins over the earlier login marker`() {
        // The tail carries both markers (banner's "login:" then "Password:").
        // Checking the login one first would re-send the username instead of the
        // password and loop forever.
        val atPassword = "$banner\nroot\nPassword:"
        assertFalse(GuestShell.sniff(atPassword))
        assertTrue(GuestShell.lastLineHas(atPassword, GuestShell.PASSWORD_MARKER_FOR_TEST))
        assertFalse(GuestShell.lastLineHas(atPassword, GuestShell.LOGIN_MARKER_FOR_TEST))
    }

    /** A shell prompt anywhere after the last auth prompt means we are in. */
    @Test
    fun `listing output after the prompt does not look logged out`() {
        val afterListing = "$shellPrompt\ntotal 24\ndrwxr-xr-x  bin\nvar\n"
        assertTrue(GuestShell.sniff(afterListing))
    }
}