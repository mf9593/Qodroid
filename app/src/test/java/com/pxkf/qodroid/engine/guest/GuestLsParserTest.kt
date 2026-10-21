/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLsParserTest {

    @Test
    fun `parses a realistic alpine listing`() {
        val out = """
            total 40
            drwxr-xr-x    2 root     root          4096 Jan  1 00:00 bin
            -rw-r--r--    1 root     root             0 Jan  1 00:00 file.txt
            lrwxrwxrwx    1 root     root             7 Jan  1 00:00 link -> file.txt
        """.trimIndent()
        val entries = GuestLsParser.parse(out)
        assertEquals(3, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("bin", entries[0].name)
        assertEquals(-1L, entries[0].sizeBytes)
        assertEquals(0L, entries[1].sizeBytes)
        assertTrue(entries[2].isLink)
    }

    /** The `total N` header must not become a row. */
    @Test
    fun `skips the total header`() {
        val out = "total 40\ndrwxr-xr-x 2 root root 4096 Jan 1 00:00 bin"
        assertEquals(1, GuestLsParser.parse(out).size)
    }

    /** `.` and `..` are navigation pointers, not content — never listed. */
    @Test
    fun `hides dot and dotdot entries`() {
        val out = """
            total 0
            drwxr-xr-x 2 root root 4096 Jan 1 00:00 .
            drwxr-xr-x 2 root root 4096 Jan 1 00:00 ..
            drwxr-xr-x 2 root root 4096 Jan 1 00:00 bin
            -rw-r--r-- 1 root root 0 Jan 1 00:00 .hidden
        """.trimIndent()
        val names = GuestLsParser.parse(out).map { it.name }
        assertEquals(listOf("bin", ".hidden"), names)
    }

    /** Names with spaces are the reason the name is "field 9 onward". */
    @Test
    fun `keeps spaces in names`() {
        val out = "-rw-r--r-- 1 root root 12 Jan 1 00:00 my notes.txt"
        assertEquals("my notes.txt", GuestLsParser.parse(out).single().name)
    }

    @Test
    fun `skips lines that are not entries`() {
        val out = """
            ls: /nope: No such file or directory
            Permission denied
            drwxr-xr-x 2 root root 4096 Jan 1 00:00 ok
        """.trimIndent()
        assertEquals(listOf("ok"), GuestLsParser.parse(out).map { it.name })
    }

    @Test
    fun `parses large sizes`() {
        val out = "-rw-r--r-- 1 root root 4294967296 Jan 1 00:00 big"
        assertEquals(4294967296L, GuestLsParser.parse(out).single().sizeBytes)
    }

    @Test
    fun `empty output yields no entries`() {
        assertTrue(GuestLsParser.parse("").isEmpty())
        assertTrue(GuestLsParser.parse("total 0").isEmpty())
    }

    /** Directories group above files regardless of the order `ls` emitted. */
    @Test
    fun `sorts directories before files`() {
        val out = """
            -rw-r--r-- 1 root root 0 Jan  1 00:00 zeta.txt
            drwxr-xr-x 2 root root 4096 Jan  1 00:00 zulu
            -rw-r--r-- 1 root root 0 Jan  1 00:00 alpha.txt
            drwxr-xr-x 2 root root 4096 Jan  1 00:00 bravo
        """.trimIndent()
        val names = GuestLsParser.parse(out).map { it.name }
        assertEquals(listOf("bravo", "zulu", "alpha.txt", "zeta.txt"), names)
    }

    /** Name order inside a group ignores case, the way `ls` presents it. */
    @Test
    fun `sorts within a group case-insensitively`() {
        val out = """
            -rw-r--r-- 1 root root 0 Jan  1 00:00 Beta.txt
            -rw-r--r-- 1 root root 0 Jan  1 00:00 alpha.txt
            -rw-r--r-- 1 root root 0 Jan  1 00:00 Apple.txt
        """.trimIndent()
        val names = GuestLsParser.parse(out).map { it.name }
        assertEquals(listOf("alpha.txt", "Apple.txt", "Beta.txt"), names)
    }

    /** A symlink has type 'l', so it stays in the file group. */
    @Test
    fun `symlink sorts as a file not a directory`() {
        val out = """
            lrwxrwxrwx 1 root root 7 Jan  1 00:00 link -> /somewhere
            drwxr-xr-x 2 root root 4096 Jan  1 00:00 dir
        """.trimIndent()
        val names = GuestLsParser.parse(out).map { it.name }
        assertEquals("dir", names.first())
    }

    @Test
    fun `displayName drops the symlink target`() {
        val out = "lrwxrwxrwx 1 root root 7 Jan 1 00:00 link -> /some/where"
        val entry = GuestLsParser.parse(out).single()
        // The parser keeps the raw field; only the display form drops the
        // " -> target" suffix, so a link and a real file of the same name stay
        // distinguishable in the underlying data.
        assertEquals("link -> /some/where", entry.name)
        assertEquals("link", GuestLsParser.displayName(entry))
    }

    @Test
    fun `childPath joins without doubling slashes`() {
        assertEquals("/etc/passwd", GuestLsParser.childPath("/etc", "passwd"))
        assertEquals("/etc", GuestLsParser.childPath("/", "etc"))
    }
}