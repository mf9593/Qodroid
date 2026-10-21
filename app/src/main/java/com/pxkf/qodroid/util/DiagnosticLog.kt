/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.util

import android.content.Context
import com.pxkf.qodroid.BuildConfig
import com.pxkf.qodroid.PodroidApplication
import com.pxkf.qodroid.engine.VmCrashLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one diagnostic dump, shared by every surface that shows or shares a log.
 *
 * "View logs" and "Export logs" used to build their content independently, and
 * the exported file was a strict subset: it had the VM exit record and the guest
 * console but none of the logcat, the native faults, or the captured Java crash —
 * i.e. exactly the parts that explain a failure. Two buttons both labelled
 * "log" that disagree is worse than either one being incomplete, so both now read
 * from here and cannot drift.
 *
 * Section order is deliberate: the durable records come before the ring-buffer
 * captures, because a crash is exactly when the logcat buffer has already lost
 * the lines that matter.
 */
object DiagnosticLog {

    private const val MAX_LOGCAT_LINES = 2000

    /** Sentinels, so captureAppLogcat() can tell "empty" from "failed to run". */
    private const val NO_LINES = "(no matching logcat lines)\n"
    private const val FAILED_PREFIX = "(failed to capture logcat: "

    /**
     * Podroid's own logcat tags. `ActivityManager` and friends are in
     * [NATIVE_FAULT_TAGS] instead: they belong to the kernel/crash machinery,
     * not to us, and mixing them into this list would flood the dump.
     */
    private val APP_LOG_TAGS = listOf(
        "AndroidRuntime",
        "AudioStreamer", "AvfDownloadsShare", "AvfEngine", "AvfReflect",
        "ConsoleFanout", "EngineHolder", "HomeViewModel", "HostRequestServer",
        "LanguageManager", "Ninep2000LServer", "PodroidApp", "PodroidService",
        "PodroidVM-err", "QemuBootMonitor", "QemuEngine", "QmpClient",
        "SettingsViewModel", "TerminalVM", "UpdateRepository", "UsbPassthrough",
        "VncStatusMonitor", "VmControlReceiver", "VsockControlChannel", "VsockPortForwarder",
        "VsockUdpForwarder", "X11Stats", "X11ViewModel",
    )

    /**
     * Tags the kernel / crash_dump use for a native fault. Needed *in addition*
     * to the Podroid tags: "Fatal signal 11 (SIGSEGV)" is emitted under `libc`
     * and the backtrace under `DEBUG`.
     */
    private val NATIVE_FAULT_TAGS = listOf(
        "DEBUG", "libc", "crash_dump64", "crash_dump32",
        "tombstoned", "ActivityManager",
    )

    fun build(context: Context, title: String? = null): String = buildString {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        if (title != null) appendLine("=== $title ===")
        appendLine("Generated: $ts")
        appendLine("Version:   v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("QEMU:     aarch64 ${BuildConfig.QEMU_VERSION_AARCH64} / x86_64 ${BuildConfig.QEMU_VERSION_X86_64}")
        appendLine()

        // Captured crash first: a crash kills the process, so the logcat buffer
        // may already have been trimmed by the time the user reopens the app —
        // this file is the only reliable record of it.
        val crashLog = runCatching { PodroidApplication.readCrashLog(context) }.getOrNull()
        if (crashLog != null) {
            appendLine("=== Captured Crash ===")
            append(crashLog.trimEnd())
            appendLine()
        }

        appendLine("=== VM Exit Record ===")
        // File-backed, so it survives the process restart a crash-induced
        // relaunch causes — exactly when the pid-filtered capture below has
        // already discarded the lines describing the failure.
        val vmCrash = VmCrashLog.read(context.filesDir)
        if (vmCrash != null) append(vmCrash) else appendLine("(no abnormal QEMU exit recorded)")
        appendLine()

        appendLine("=== App Logcat (this process) ===")
        append(captureAppLogcat())
        appendLine()
        appendLine("=== Native Faults (any pid) ===")
        append(captureNativeFaults())
        appendLine()

        appendLine("=== VM Console Log ===")
        val consoleFile = File(context.filesDir, "console.log")
        if (consoleFile.exists() && consoleFile.length() > 0) {
            append(consoleFile.readText().trimEnd())
            appendLine()
        } else {
            appendLine("(no console.log — the VM has not produced serial output this session)")
        }
    }

    /**
     * This process's own logcat, filtered to Podroid's tags (+ `*:S` to silence
     * everything else).
     *
     * Falls back to an unfiltered tail of our own pid when the filtered pass
     * comes back empty. The whitelist has to be kept in sync by hand, so a
     * component that logs under a tag nobody added silently vanishes from the
     * dump — and "no matching lines" is then indistinguishable from "the app
     * really logged nothing", which is exactly the state that made a real
     * rendering fault undiagnosable. Better to over-collect: the pid filter
     * still keeps other apps out.
     */
    private fun captureAppLogcat(): String {
        val filtered = runLogcat {
            listOf("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}") +
                APP_LOG_TAGS.map { "$it:V" } + listOf("*:S")
        }
        if (!filtered.startsWith(NO_LINES) && !filtered.startsWith(FAILED_PREFIX)) return filtered
        val unfiltered = runLogcat {
            listOf("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}")
        }
        return if (unfiltered.startsWith(NO_LINES) || unfiltered.startsWith(FAILED_PREFIX)) {
            filtered
        } else {
            unfiltered
        }
    }

    /**
     * Native-fault lines for any of our processes, WITHOUT the `--pid` filter.
     *
     * A SIGSEGV inside the QEMU child produces its tombstone under the child's
     * pid, so the pid-filtered capture above drops every line of it no matter
     * which tags are whitelisted.
     */
    private fun captureNativeFaults(): String = runLogcat {
        listOf("logcat", "-d", "-v", "time") +
            NATIVE_FAULT_TAGS.map { "$it:V" } + listOf("*:S")
    }

    private inline fun runLogcat(args: () -> List<String>): String = try {
        val proc = Runtime.getRuntime().exec(args().toTypedArray())
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        val lines = output.trimEnd().lines().filter { it.isNotBlank() }
        when {
            lines.isEmpty() -> NO_LINES
            lines.size <= MAX_LOGCAT_LINES -> lines.joinToString("\n") + "\n"
            else -> (listOf("... (${lines.size - MAX_LOGCAT_LINES} earlier lines trimmed) ...") +
                lines.takeLast(MAX_LOGCAT_LINES)).joinToString("\n") + "\n"
        }
    } catch (e: Exception) {
        FAILED_PREFIX + "${e.message})\n"
    }
}