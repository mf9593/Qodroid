/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Engine-agnostic boot-progress detector. Both engines feed it raw guest
 * console bytes; it sets [bootStage] flow markers it observes and flips
 * [state] to Running when [readyMarkers] appears. Each feed scans the
 * newly-appended region plus a short overlap carried from the previous feed, so
 * a marker that straddles a read boundary — or sits early in a single oversized
 * chunk — is still caught (see the history of detectBootStage in PodroidQemu
 * pre-refactor). One-shot: stops scanning after the first ready marker to keep
 * [onReady] idempotent.
 *
 * [readyMarkers] is per-profile because "Ready!" is emitted by Podroid's own
 * Linux bootstrap and therefore never appears in a Windows guest. Waiting for it
 * there left the VM pinned in Starting for the full safety-net timeout, even
 * though the guest was alive and its screen already viewable over VNC. Windows
 * passes UEFI firmware markers instead: the firmware reaching the serial
 * console is exactly "the guest is executing and you can interact with it".
 */
class BootStageDetector(
    private val bootStage: MutableStateFlow<String>,
    private val state: MutableStateFlow<VmState>,
    private val onReady: () -> Unit,
    private val readyMarkers: List<String> = LINUX_READY_MARKERS,
) {
    private val buf = StringBuilder()
    private val maxKeep = 4096

    /**
     * Overlap carried across feeds so a marker split between two reads is still
     * matched. (len(longest marker) - 1) chars of the previous feed are
     * re-scanned alongside the new bytes — enough to reconstruct any marker
     * that begins in the prior feed and ends in this one.
     */
    private val overlap = MARKERS.maxOf { it.first.length } - 1

    /** Length of [buf] before the current feed appended — start of "new" text. */
    private var scannedLen = 0
    private var ready = false

    /**
     * Human-readable form of [readyMarkers], for the log line the safety-net
     * timeout emits. Names the marker that was actually waited for, so
     * "promoting to Running" says what it failed to see rather than a generic
     * "boot detection may have missed the marker".
     */
    fun describeReadyMarkers(): String = readyMarkers.joinToString("/")

    fun feed(bytes: ByteArray, len: Int) {
        if (ready) return
        // Latin-1 decode is byte-safe (1 byte → 1 char) and the ASCII subset
        // matches UTF-8 exactly, so our pure-ASCII markers still match.
        buf.append(String(bytes, 0, len, Charsets.ISO_8859_1))
        if (buf.length > maxKeep) {
            val dropped = buf.length - maxKeep
            buf.delete(0, dropped)
            scannedLen = (scannedLen - dropped).coerceAtLeast(0)
        }
        // Scan the new region plus an overlap into the previously-scanned text,
        // so a marker spanning the boundary is reconstructed. Scanning the
        // whole appended chunk (not a fixed 1024 tail) means a marker buried
        // early in one oversized read is no longer missed.
        val from = (scannedLen - overlap).coerceAtLeast(0)
        val tail = buf.substring(from)
        scannedLen = buf.length
        // First matching marker wins, in MARKERS order (same priority as the
        // old `when` chain). A ready marker keeps its one-shot + state + onReady
        // side effects; every other marker only updates bootStage.
        val marker = MARKERS.firstOrNull { tail.contains(it.first) } ?: return
        if (marker.first in readyMarkers) {
            ready = true
            bootStage.value = marker.second
            state.value = VmState.Running
            onReady()
        } else {
            bootStage.value = marker.second
        }
    }

    companion object {
        /** Podroid's own bootstrap prints this once userspace is usable. */
        val LINUX_READY_MARKERS = listOf("Ready!")

        /**
         * UEFI reaching the serial console. OVMF writes its version banner and
         * BdsDxe/Shell lines for every x86 guest that boots firmware, so any of
         * them proves the guest is executing — which is the state Running means
         * here. "UEFI Interactive Shell" is checked last because it is the
         * fallback OVMF reaches when nothing bootable was found, which is
         * still a live guest the user can watch and drive over VNC.
         */
        val WINDOWS_READY_MARKERS = listOf(
            "BdsDxe",
            "UEFI Interactive Shell",
            "UEFI firmware",
        )

        /**
         * The exact substrings [feed] scans for, in priority order (first
         * match wins) and used to size the cross-feed [overlap] (the longest
         * marker drives how much prior text is re-scanned).
         */
        private val MARKERS = listOf(
            "Ready!" to "Ready",
            "Almost ready" to "Almost ready...",
            "Starting SSH" to "Starting SSH...",
            "Configuring containers" to "Configuring containers...",
            "Network found" to "Network found",
            "Loading kernel modules" to "Loading kernel modules...",
            "Mounting storage" to "Mounting storage...",
            "Booting kernel" to "Booting kernel...",
            // Windows / UEFI path. Stage labels read as firmware progress.
            "UEFI firmware" to "UEFI firmware",
            "BdsDxe" to "UEFI boot",
            "UEFI Interactive Shell" to "UEFI Shell",
        )
    }
}
