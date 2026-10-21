/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine.guest

import java.util.Locale

/**
 * Parse `ls -la` output from the guest into rows.
 *
 * The guest is Alpine, whose coreutils `ls` produces one line per entry with
 * mode, link count, owner, group, size, date and name. Parsing it is more
 * reliable than it looks — the name is the last field and may contain spaces,
 * so everything up to the 8th field is metadata and the rest is the name.
 *
 * Pure Kotlin so the parsing is unit-testable without a guest.
 */
object GuestLsParser {

    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val isLink: Boolean,
        val sizeBytes: Long,
        val mode: String,
    )

    private val LINE = Regex(
        "^([-dlbcps])([rwxstST-]{9})[.+]?\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+" +
            "(\\S+\\s+\\S+\\s+\\S+)\\s+(.+)$"
    )

    /**
     * Rows for the listing: directories first, then files, each group by name.
     *
     * Lines that do not match (a `total N` header, an error message, output from
     * a command that was not `ls`) are skipped rather than failing the whole
     * listing — a partial parse is more useful than none.
     *
     * Order is applied here rather than left to `ls`, because the guest's sort is
     * locale-dependent and alphabetical-only: a folder can land below a file and
     * files interleave with folders. The page lists whole trees, and the usual
     * way to navigate one is by descending into its subdirectories, so they group
     * first. Within a group the name is compared case-insensitively (files.txt /
     * Files.txt), falling back to the exact name so the order is total and
     * stable rather than depending on the incoming `ls` output.
     */
    fun parse(output: String): List<Entry> =
        output.lineSequence().mapNotNull { line ->
            val m = LINE.matchEntire(line.trimEnd()) ?: return@mapNotNull null
            val name = m.groupValues[8].trim()
            // `.` and `..` are the listing's self/parent pointers, not content.
            // A file manager shows neither — `..` would duplicate the page's
            // up-navigation and `.` does nothing but re-list the same folder.
            if (name == "." || name == "..") return@mapNotNull null
            val (type, mode) = m.destructured
            Entry(
                name = name,
                isDirectory = type == "d",
                isLink = type == "l",
                // A directory's "size" is its inode size, not its contents; -1
                // keeps the UI from printing a meaningless number for it.
                sizeBytes = if (type == "d") -1 else m.groupValues[6].toLongOrNull() ?: -1,
                mode = type + mode,
            )
        }.sortedWith(
            // A symlink to a directory is type 'l', not 'd', so it sorts as a
            // file; `isDirectory` here is deliberately ls's own type char.
            compareByDescending<Entry> { it.isDirectory }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.name },
        ).toList()

    /** Strip a trailing " -> target" from a symlink's name for display. */
    fun displayName(entry: Entry): String =
        if (entry.isLink) entry.name.substringBefore(" -> ") else entry.name

    /**
     * Absolute path of the entry joined to [parent], collapsing to "/" at the
     * root so the guest never sees "//".
     */
    fun childPath(parent: String, name: String): String =
        if (parent.endsWith("/")) "$parent$name" else "$parent/$name"
}