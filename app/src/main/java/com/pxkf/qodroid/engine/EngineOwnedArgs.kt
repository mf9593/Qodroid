/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine

/**
 * Mode-specific filtering of the user's stored "extra QEMU args".
 *
 * Exists because the shipped default was written for the ARM profile and the
 * x86_64 Windows profile is the newer of the two. Pure Kotlin (no Android deps)
 * so the rule is unit-testable without a device.
 */
object EngineOwnedArgs {

    /**
     * Strip `-cpu` / `-accel` (and their value token) out of a stored extra-args
     * string.
     *
     * Those are the only flags QemuEngine already emits per boot mode, so a
     * stored string overriding them can only break the mode it was tuned for.
     * Everything else — rng, overcommit, extra `-device`s a user added — is
     * portable and passes through untouched.
     *
     * The value token is dropped with the flag (not kept as a bare word), since
     * `max,sve=off` and `qemu64` are values, not further flags. A trailing flag
     * with no value is tolerated rather than throwing.
     */
    fun drop(extras: String): String {
        val tokens = extras.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == "-cpu" || t == "-accel") {
                i += 2 // flag + value; may overshoot a trailing flag, which is fine
            } else {
                out += t
                i += 1
            }
        }
        return out.joinToString(" ")
    }
}