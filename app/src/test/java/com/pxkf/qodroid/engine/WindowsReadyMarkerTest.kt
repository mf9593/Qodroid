/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Linux boot profile flips Starting → Running on "Ready!", which Podroid's
 * own bootstrap prints. A Windows guest has no such bootstrap, so waiting for
 * it pinned the VM in "starting" for the full safety-net timeout even though the
 * firmware was already running and viewable over VNC — the console log the user
 * had open showed OVMF happily starting the whole time.
 *
 * These lock the per-profile ready markers so that gap cannot reopen.
 */
class WindowsReadyMarkerTest {

    private fun detector(markers: List<String>): Triple<BootStageDetector, MutableStateFlow<VmState>, MutableStateFlow<String>> {
        val stage = MutableStateFlow("")
        val state = MutableStateFlow<VmState>(VmState.Starting)
        return Triple(BootStageDetector(stage, state, onReady = { }, readyMarkers = markers), state, stage)
    }

    private fun BootStageDetector.feed(s: String) = feed(s.toByteArray(Charsets.ISO_8859_1), s.length)

    @Test
    fun `windows profile promotes on the UEFI firmware banner`() {
        val (d, state, stage) = detector(BootStageDetector.WINDOWS_READY_MARKERS)
        d.feed("UEFI firmware (version 2025.02-8+deb13u1 built at 18:06:17)")
        assertEquals(VmState.Running, state.value)
        assertEquals("UEFI firmware", stage.value)
    }

    @Test
    fun `windows profile promotes on BdsDxe`() {
        val (d, state, _) = detector(BootStageDetector.WINDOWS_READY_MARKERS)
        d.feed("BdsDxe: loading Boot0002 \"EFI Internal Shell\"")
        assertEquals(VmState.Running, state.value)
    }

    @Test
    fun `windows profile promotes on the UEFI Shell`() {
        val (d, state, stage) = detector(BootStageDetector.WINDOWS_READY_MARKERS)
        d.feed("UEFI Interactive Shell v2.2")
        assertEquals(VmState.Running, state.value)
        assertEquals("UEFI Shell", stage.value)
    }

    /** The exact log the user reported: UEFI running, UI stuck on "starting". */
    @Test
    fun `real UEFI boot transcript reaches Running`() {
        val (d, state, _) = detector(BootStageDetector.WINDOWS_READY_MARKERS)
        d.feed("\u001b[2J\u001b[01;01H\u001b[=3hUEFI firmware (version 2025.02)\n")
        d.feed("BdsDxe: failed to load Boot0001 \"UEFI Misc Device\" from PciRoot(0x0)\n")
        assertEquals(VmState.Running, state.value)
    }

    /** A Windows guest never prints "Ready!", so Linux markers must not apply. */
    @Test
    fun `windows profile ignores the Linux-only Ready marker`() {
        val (d, state, _) = detector(BootStageDetector.WINDOWS_READY_MARKERS)
        d.feed("Ready!\n")
        assertEquals(VmState.Starting, state.value)
    }

    @Test
    fun `linux profile still needs its own marker`() {
        val (d, state, stage) = detector(BootStageDetector.LINUX_READY_MARKERS)
        d.feed("UEFI firmware (version 2025.02)\n")
        assertEquals(VmState.Starting, state.value)
        d.feed("Ready!\n")
        assertEquals(VmState.Running, state.value)
        assertEquals("Ready", stage.value)
    }

    @Test
    fun `linux profile keeps reporting stage progress before ready`() {
        val (d, state, stage) = detector(BootStageDetector.LINUX_READY_MARKERS)
        d.feed("Booting kernel...\n")
        assertEquals("Booting kernel...", stage.value)
        assertEquals(VmState.Starting, state.value)
    }

    /** One-shot: the UEFI shell keeps talking and must not re-fire onReady. */
    @Test
    fun `windows profile fires ready only once`() {
        var readyCount = 0
        val stage = MutableStateFlow("")
        val state: MutableStateFlow<VmState> = MutableStateFlow(VmState.Starting)
        val d = BootStageDetector(
            stage, state,
            onReady = { readyCount++ },
            readyMarkers = BootStageDetector.WINDOWS_READY_MARKERS,
        )
        d.feed("UEFI firmware\n")
        d.feed("BdsDxe: starting\n")
        d.feed("UEFI Interactive Shell v2.2\n")
        assertEquals(1, readyCount)
    }

    @Test
    fun `ready marker names are reported for the safety-net log line`() {
        val (d, _, _) = detector(BootStageDetector.WINDOWS_READY_MARKERS)
        assertTrue(
            d.describeReadyMarkers(),
            d.describeReadyMarkers().contains("BdsDxe"),
        )
    }
}