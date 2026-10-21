/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Durable record of abnormal QEMU exits.
 *
 * Written because the alternatives lose exactly the evidence that matters:
 *
 *  * `Log.*` goes to a ring buffer keyed by pid, so once the process is
 *    restarted the lines describing the previous run's crash are filtered out
 *    by the log viewer's `--pid` — reopening the log shows a clean sheet for a
 *    crash that just happened.
 *  * The guest `console.log` only holds serial output; a SIGSEGV writes
 *    nothing there.
 *  * `crash.log` only catches Java exceptions in this process. A native fault
 *    in the QEMU child never throws one.
 *
 * So the record goes to a file the log viewer reads directly, the same way it
 * reads crash.log. Appended, not overwritten: a crash loop leaves the whole
 * history, and the file is capped so it cannot grow without bound.
 */
object VmCrashLog {

    private const val FILE_NAME = "vm-crash.log"
    private const val MAX_BYTES = 256 * 1024

    /**
     * Append one record. [deliberate] records are kept too — "the VM stopped
     * because you stopped it" is useful context next to an unexplained exit —
     * but they are labelled so a stop is never mistaken for a fault.
     */
    fun append(
        filesDir: File,
        deliberate: Boolean,
        exitCode: Int,
        reason: String,
        detail: String,
    ) {
        runCatching {
            val f = File(filesDir, FILE_NAME)
            // Roll rather than truncate so the oldest entries survive: the last
            // crash is the interesting one but the one before it explains a
            // repeating pattern.
            if (f.length() > MAX_BYTES) {
                val kept = f.readBytes().let { it.copyOfRange(it.size - MAX_BYTES / 2, it.size) }
                val rolled = File(filesDir, "$FILE_NAME.1")
                rolled.delete()
                rolled.writeBytes(kept)
                f.writeBytes(ByteArray(0))
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText(
                buildString {
                    appendLine("---- $ts ----")
                    appendLine("kind:     ${if (deliberate) "stopped" else "exited"}")
                    appendLine("exit:     $exitCode ($reason)")
                    detail.lineSequence().forEach { appendLine("  $it") }
                    appendLine()
                }
            )
        }
    }

    fun read(context_filesDir: File): String? =
        runCatching {
            val f = File(context_filesDir, FILE_NAME)
            if (!f.exists() || f.length() == 0L) return null
            val rolled = File(context_filesDir, "$FILE_NAME.1")
            val body = buildString {
                if (rolled.exists() && rolled.length() > 0) {
                    appendLine("=== earlier ===")
                    append(rolled.readText())
                }
                append(f.readText())
            }
            body
        }.getOrNull()

    fun clear(filesDir: File) {
        runCatching {
            File(filesDir, FILE_NAME).delete()
            File(filesDir, "$FILE_NAME.1").delete()
        }
    }
}