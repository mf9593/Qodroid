/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine

import com.pxkf.qodroid.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored "extra QEMU args" default is ARM-tuned:
 *
 *     -cpu max,sve=off,pauth-impdef=on -accel tcg,... -object rng-random,...
 *
 * `sve` (ARM Scalable Vector Extension) does not exist on an x86_64 CPU, so
 * appending that string verbatim on the Windows path made QEMU abort with
 *
 *     can't apply global max-x86_64-cpu.sve=off: Property not found   (exit 1)
 *
 * before the guest ran a single instruction. These lock the stripping rule so
 * the ARM default cannot silently start breaking Windows again.
 */
class EngineOwnedArgsTest {

    @Test
    fun `strips cpu and accel from the shipped ARM default`() {
        val out = EngineOwnedArgs.drop(SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS)
        assertTrue("no -cpu may survive: $out", !out.contains("-cpu"))
        assertTrue("no -accel may survive: $out", !out.contains("-accel"))
        assertTrue("sve=off must be gone: $out", !out.contains("sve=off"))
        assertTrue("pauth-impdef must be gone: $out", !out.contains("pauth-impdef"))
    }

    @Test
    fun `keeps the portable flags`() {
        val out = EngineOwnedArgs.drop(SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS)
        assertTrue(out, out.contains("rng-random"))
        assertTrue(out, out.contains("virtio-rng-pci"))
        assertTrue(out, out.contains("mem-lock=off"))
    }

    @Test
    fun `leaves user additions untouched`() {
        val out = EngineOwnedArgs.drop("-cpu max -accel tcg -device usb-host,id=x")
        assertEquals("-device usb-host,id=x", out)
    }

    @Test
    fun `is a no-op when the engine flags are absent`() {
        val args = "-object rng-random,id=rng0 -overcommit mem-lock=off"
        assertEquals(args, EngineOwnedArgs.drop(args))
    }

    @Test
    fun `handles extra whitespace and empty input`() {
        assertEquals("", EngineOwnedArgs.drop("   "))
        assertEquals("", EngineOwnedArgs.drop(""))
        assertEquals("-overcommit mem-lock=off", EngineOwnedArgs.drop("  -cpu   max,sve=off   -overcommit mem-lock=off  "))
    }

    @Test
    fun `a trailing engine flag with no value does not run off the end`() {
        assertEquals("-overcommit mem-lock=off", EngineOwnedArgs.drop("-overcommit mem-lock=off -cpu"))
    }
}