/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * QEMU engine for Podroid. Manages the VM lifecycle and exposes three Unix
 * sockets for the terminal layer:
 *
 *   serial.sock   — QEMU -serial (ttyAMA0 in the VM). Boot-log sink only:
 *                   QemuBootMonitor connects here for the lifetime of the VM,
 *                   streaming kernel messages and init-podroid boot stages
 *                   into console.log + the in-memory ring buffer used by
 *                   BootStageDetector.
 *
 *   terminal.sock — QEMU virtio-console (/dev/hvc0 in the VM). Primary
 *                   terminal I/O. getty runs on hvc0; the podroid-bridge
 *                   binary connects here for bidirectional shell I/O. Fully
 *                   independent of serial.sock, so no socket hand-off.
 *
 *   ctrl.sock     — QEMU virtio-console (/dev/hvc1 in the VM). Resize signal
 *                   channel only. Bridge writes "RESIZE rows cols\n" on
 *                   SIGWINCH (debounced by RESIZE_DEBOUNCE_MS); the resize
 *                   daemon in init-podroid stty's hvc0 to deliver SIGWINCH
 *                   to the foreground TUI inside the VM.
 */
package com.pxkf.qodroid.engine

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.pxkf.qodroid.data.repository.PortForwardRule
import com.pxkf.qodroid.util.HostMetrics
import com.pxkf.qodroid.x11.X11Constants
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("StaticFieldLeak") // ApplicationContext — lives as long as the process, no leak
@Singleton
class QemuEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: com.pxkf.qodroid.data.repository.SettingsRepository,
) : VmEngine {
    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    override val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    override val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val _bootStage = MutableStateFlow("")

    // @Volatile: written on the main thread (autoStartBridge/createTerminalSession)
    // and on Dispatchers.IO (cleanup), read from both — like the lifecycle fields below.
    @Volatile private var _terminalSession: TerminalSession? = null

    override val terminalSession: TerminalSession? get() = _terminalSession
    override val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    override val backendId: String = "qemu"

    /**
     * Wall-clock millis at which this VM reached Running, or null when not
     * running. Set on every →Running transition (BootStageDetector's onReady
     * and the 60s boot-timeout fallback) and cleared in cleanup(); read by the
     * UI uptime readout via the [VmEngine] override.
     */
    @Volatile
    private var _runningSinceMs: Long? = null
    override val runningSinceMs: Long? get() = _runningSinceMs

    override fun emulatorRssMb(): Long? {
        val proc = process?.takeIf { it.isAlive } ?: return null
        val pid = HostMetrics.processPid(proc) ?: return null
        return HostMetrics.processVmRssMb(pid)
    }

    override fun emulatorPid(): Int? {
        val proc = process?.takeIf { it.isAlive } ?: return null
        return HostMetrics.processPid(proc)
    }

    @Volatile
    var process: Process? = null
        private set

    /** Unix socket paths exposed to TerminalViewModel for the bridge binary. */
    val serialSockPath: String get() = "${context.filesDir.absolutePath}/serial.sock"
    val terminalSockPath: String get() = "${context.filesDir.absolutePath}/terminal.sock"
    val ctrlSockPath: String get() = "${context.filesDir.absolutePath}/ctrl.sock"
    val hostSockPath: String get() = "${context.filesDir.absolutePath}/host.sock"

    /**
     * Last QEMU process exit code (null until it exits) + a bounded tail of
     * QEMU's own stderr. Surfaced in the diagnostic export so a crash leaves a
     * forensic trail. Written from start()/the stderr drain, read from the
     * diagnostic thread — the deque is guarded by its own monitor.
     */
    @Volatile
    private var lastExitCode: Int? = null
    private val stderrTail = ArrayDeque<String>()

    private val qmpSocketPath: String get() = "${context.filesDir.absolutePath}/qmp.sock"

    override val qmpClient: QmpClient? by lazy { QmpClient(qmpSocketPath) }

    private var ioScope: CoroutineScope? = null

    /**
     * Single dedicated thread that BOTH fork/exec's QEMU and blocks in
     * waitFor(). libpodroid-launcher sets PR_SET_PDEATHSIG(SIGKILL), which is
     * THREAD-scoped: the kernel SIGKILLs QEMU when the thread that spawned it
     * dies — not when the app process dies. Forking from a Dispatchers.IO pool
     * thread let that thread be recycled (~60s idle keep-alive) once the start()
     * coroutine migrated off it at a delay(), which SIGKILL'd a healthy VM and
     * surfaced as "QEMU crashed (SIGKILL)". A private single-thread executor's
     * thread is never reaped on idle, so it lives exactly as long as QEMU.
     * Shut down in cleanup(), after QEMU has already exited.
     */
    private var qemuDispatcher: ExecutorCoroutineDispatcher? = null

    private var bootStartTime: Long = 0L

    /** Per-run serial monitor; created in start(), released in cleanup(). */
    private var bootMonitor: QemuBootMonitor? = null

    /**
     * Fresh detector per run (see start()) rather than one reused instance.
     * The previous run's boot monitor may still be draining when start() runs
     * (cleanup cancels but does not join it); a shared instance let that stale
     * feed race the new run's scan offsets / one-shot guard. A new instance per
     * run isolates each boot. Mirrors the AVF backend, which already does this.
     *
     * @param vmMode decides which console marker counts as "the guest is up".
     * Linux waits for the bootstrap's "Ready!"; Windows waits for UEFI, because
     * that string is Linux-only and would never arrive.
     */
    private fun newBootStageDetector(vmMode: VmMode) =
        BootStageDetector(
            bootStage = _bootStage,
            state = _state,
            readyMarkers = if (vmMode == VmMode.WINDOWS) {
                BootStageDetector.WINDOWS_READY_MARKERS
            } else {
                BootStageDetector.LINUX_READY_MARKERS
            },
            onReady = {
                // BootStageDetector has already flipped _state to Running by the
                // time this onReady fires; stamp the uptime origin to match.
                _runningSinceMs = System.currentTimeMillis()
                persistBootDuration()
                autoStartBridge()
            },
        )

    /** Set once cleanup() has run for the current VM lifetime; reset by start(). */
    private val cleanedUp = AtomicBoolean(true)

    /**
     * Serializes the start() re-entrancy guard with the Starting state write so
     * two near-simultaneous ACTION_STARTs can't both pass the check and launch a
     * second QEMU (orphaning the first child + leaking its executor). Held only
     * across the guard + state flip — never across proc.waitFor() — so it can't
     * deadlock with cleanup()/stop().
     */
    private val startMutex = Mutex()

    /**
     * Proxy TerminalSessionClient — delegates to whatever real client is set.
     * Lets us create the bridge session at boot-complete time (before the
     * terminal UI exists) and plug in the real ViewModel client later.
     */
    private val proxySessionClient = ProxySessionClient(TAG)

    override var sessionClientDelegate: TerminalSessionClient?
        get() = proxySessionClient.delegate
        set(value) { proxySessionClient.delegate = value }

    private fun persistBootDuration() {
        if (bootStartTime == 0L) return
        val duration = System.currentTimeMillis() - bootStartTime
        bootStartTime = 0L
        // ioScope is the same scope launched in start(); always non-null here
        // because we're called from inside it.
        ioScope?.launch { settingsRepository.setLastBootDurationMs(duration) }
    }

    private fun autoStartBridge() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (_terminalSession != null) return@post
            if (_state.value !is VmState.Running && _state.value !is VmState.Starting) return@post
            // A late onReady could land after cleanup() already deleted the
            // sockets; don't arm a bridge against a torn-down VM lifetime.
            if (cleanedUp.get()) return@post

            // Bridge connects to terminal.sock (virtio-console, separate from serial).
            // No handoff with the boot monitor needed — they use different sockets.
            val bridgeExe = TerminalBridge.executable(context)
            if (!bridgeExe.exists()) return@post

            val sess = TerminalBridge.newSession(
                context, terminalSockPath, ctrlSockPath, proxySessionClient, onResize = null,
            )
            _terminalSession = sess
            Log.d(TAG, "Bridge auto-started on terminal.sock")
        }
    }

    override fun createTerminalSession(client: TerminalSessionClient): TerminalSession {
        sessionClientDelegate = client

        // Reuse the boot-time session ONLY if it's still alive. A finished
        // session (bridge died while the VM kept running) must not be handed
        // back — re-entering the terminal screen would otherwise show the dead
        // "[Process completed]" buffer forever. Drop it and fall through to
        // spawn a fresh bridge against the still-running VM.
        val cached = _terminalSession
        if (cached != null && cached.isRunning) {
            Log.d(TAG, "Returning pre-started terminal session")
            return cached
        }
        if (cached != null) {
            Log.d(TAG, "Cached terminal session is dead — recreating against the running VM")
            _terminalSession = null
        }

        // Fallback: create session now
        val bridgeExe = TerminalBridge.executable(context)
        if (!bridgeExe.exists()) {
            throw IllegalStateException("podroid-bridge not found at ${bridgeExe.absolutePath}")
        }

        val sess = TerminalBridge.newSession(
            context, terminalSockPath, ctrlSockPath, proxySessionClient, onResize = null,
        )

        _terminalSession = sess
        Log.d(TAG, "Terminal session created in Qemu singleton")
        return sess
    }

    /**
     * Common early-error path in start(), before a process or scope exists:
     * restores the "cleanedUp=false ⟺ a VM lifetime is in progress" invariant
     * that the startMutex block already broke by setting cleanedUp=false and
     * bootStartTime, matching the other error paths (which run cleanup()).
     * Callers still `return` right after calling this.
     */
    private fun failEarlyStart(message: String) {
        cleanedUp.set(true)
        bootStartTime = 0L
        _state.value = VmState.Error(message)
    }

    override suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) {
        // Atomically check the re-entrancy guard AND claim Starting before any
        // I/O, so two concurrent ACTION_STARTs can't both pass the guard and
        // launch a second QEMU. Held only across the guard + state flip.
        startMutex.withLock {
            if (_state.value is VmState.Starting || _state.value is VmState.Running) {
                Log.w(TAG, "start() called while VM is ${_state.value}, ignoring")
                return
            }
            cleanedUp.set(false)
            bootStartTime = System.currentTimeMillis()
            _stopping.value = false
            _state.value = VmState.Starting
        }

        val qemuExe = qemuExecutableFor(config) ?: run {
            failEarlyStart(
                if (config.vmMode == VmMode.WINDOWS)
                    "$QEMU_X64_NAME is missing — this APK has no x86_64 emulator, " +
                        "so an x86_64 guest cannot run."
                else
                    "QEMU binary not found."
            )
            return
        }

        // Payload preflight. Everything the chosen mode boots from is unpacked
        // into filesDir at first run; when a piece is missing the build simply
        // omits the matching -drive/-kernel and QEMU is left booting nothing:
        // the virt machine dies within a second with an opaque SIGSEGV
        // ("QEMU exited with code 139"). Name the real cause instead.
        if (config.vmMode == VmMode.WINDOWS) {
            if (!File(context.filesDir, UEFI_CODE).exists()) {
                failEarlyStart(
                    "UEFI firmware missing ($UEFI_CODE) — this APK was built " +
                        "without it, so a UEFI guest cannot boot."
                )
                return
            }
            val image = config.customImagePath.trim()
            if (image.isEmpty() || !File(image).exists()) {
                failEarlyStart(
                    "No boot image selected for Windows mode — pick a qcow2/raw/ISO " +
                        "image on the terminal page first."
                )
                return
            }
            // -vga std aborts q35 during realize with "failed to find romfile
            // vgabios-stdvga.bin", before the guest gets a single instruction.
            // Surface the actual missing file instead.
            if (!File(context.filesDir, VGA_BIOS).exists()) {
                failEarlyStart(
                    "VGA firmware missing ($VGA_BIOS) — this APK was built without " +
                        "it, so the Windows guest cannot get a display."
                )
                return
            }
            // SeaBIOS is a different blob from OVMF and used to be absent
            // entirely, so BIOS mode failed with QEMU's bare
            // "could not load PC BIOS 'bios-256k.bin'" and no hint that the
            // setting — not the image — was at fault.
            if (!config.uefiEnabled && !File(context.filesDir, SEA_BIOS).exists()) {
                failEarlyStart(
                    "Legacy BIOS firmware missing ($SEA_BIOS) — this APK was built " +
                        "without it, so BIOS boot is unavailable. Switch firmware " +
                        "back to UEFI."
                )
                return
            }
            // Worse than aborting mid-realize: QEMU treats a missing keymap as
            // fatal and refuses to start at all — "-vnc 127.0.0.1:1: could not
            // find keymap file for language 'en-us'". It is resolved out of
            // filesDir via -L, same as the blobs above.
            // NOTE: a QEMU keymap is a single plain file (keymaps/en-us), not a
            // directory of per-key files. Checking isDirectory here reported
            // every correctly-unpacked install as missing and blocked Windows
            // boot outright.
            if (config.vncEnabled && !File(context.filesDir, KEYMAP_EN_US).isFile) {
                failEarlyStart(
                    "VNC keymap missing ($KEYMAP_EN_US) — the asset unpack did not " +
                        "finish, so QEMU refuses to start with -vnc enabled."
                )
                return
            }
        } else {
            VM_PAYLOAD_FILES.forEach { (fileName, label) ->
                if (!File(context.filesDir, fileName).exists()) {
                    failEarlyStart(
                        "VM payload missing: $label ($fileName) was not installed. " +
                            "This APK was built without the VM images — see build-all.sh."
                    )
                    return
                }
            }
        }

        try {
            ensureStorageImage(config.storageSizeGb)
        } catch (e: java.io.IOException) {
            failEarlyStart(e.message ?: "Could not prepare the VM disk image.")
            return
        }

        _consoleText.value = ""
        _bootStage.value = "Starting QEMU..."
        // Fresh per-run detector so a previous run's still-draining monitor can't
        // feed (or race the one-shot guard / scan offsets of) this run's detector.
        val detector = newBootStageDetector(config.vmMode)

        // Clean up stale sockets from a previous run. qmp.sock must be
        // included — a leftover file from a crashed QEMU prevents the new
        // process from binding its QMP server socket.
        File(serialSockPath).delete()
        File(terminalSockPath).delete()
        File(ctrlSockPath).delete()
        File(qmpSocketPath).delete()
        File(hostSockPath).delete()

        try {
            val cmd = buildCommand(qemuExe, portForwards, config)
            Log.d(TAG, "Launching QEMU with ${cmd.size} args: $cmd")

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(cmd).directory(context.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:${context.filesDir.absolutePath}"
            // Discard QEMU's stdout. Nothing routes there today (serial/QMP use
            // sockets), but user extra args like `-monitor stdio` could, and an
            // unread OS pipe would fill its buffer and deadlock the VM. Redirect
            // to /dev/null rather than merging into stderr, which feeds error
            // formatting (recordStderr below). Redirect.DISCARD would need API 33.
            pb.redirectOutput(File("/dev/null"))

            // Fork QEMU on a private, long-lived thread (see qemuDispatcher).
            // PR_SET_PDEATHSIG (set by libpodroid-launcher) is thread-scoped, so
            // the spawning thread must outlive QEMU — a Dispatchers.IO pool
            // thread does not. waitFor() below runs on this same thread.
            val dispatcher = Executors.newSingleThreadExecutor { r ->
                Thread(r, "podroid-qemu").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            qemuDispatcher = dispatcher

            val proc = withContext(dispatcher) { pb.start() }
            process = proc
            _bootStage.value = "Booting kernel..."

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ioScope = scope

            // Drain QEMU's own stderr (not serial — just QEMU startup messages)
            scope.launch {
                try {
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val n = proc.errorStream.read(buf)
                        if (n < 0) break
                        val chunk = String(buf, 0, n).trimEnd()
                        Log.d("PodroidVM-err", chunk.take(300))
                        recordStderr(chunk)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Stderr drain ended: ${e.message}")
                }
            }

            // Boot monitor — connects to serial.sock once QEMU creates it and
            // streams the guest console into console.log + the boot-stage detector.
            val monitor = QemuBootMonitor(
                serialSockPath, File(context.filesDir, "console.log"),
                detector, _consoleText, SOCKET_READY_TIMEOUT_MS,
            )
            bootMonitor = monitor
            monitor.connectAndRun(scope) { proc.isAlive }

            val startMs = System.currentTimeMillis()
            var socketsReady = false
            while (System.currentTimeMillis() - startMs < SOCKET_READY_TIMEOUT_MS) {
                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    lastExitCode = exitCode
                    Log.e(TAG, "QEMU START FAILED — died during startup, exit code: $exitCode " +
                        "(${formatExitError(exitCode, config.storageAccessEnabled)})")
                    VmCrashLog.append(
                        filesDir = context.filesDir,
                        deliberate = false,
                        exitCode = exitCode,
                        reason = formatExitError(exitCode, config.storageAccessEnabled),
                        detail = buildString {
                            appendLine("failed during startup (before Running)")
                            appendLine("mode:     ${config.vmMode}  ram=${config.ramMb}MB cpus=${config.cpus}")
                            appendLine("firmware: ${if (config.uefiEnabled) "UEFI" else "BIOS"}  vnc=${config.vncEnabled}")
                            appendLine("image:    ${config.customImagePath.ifBlank { "<none>" }}")
                        },
                    )
                    // Same decoder as the normal exit path: a bare "code 139"
                    // tells the user nothing, "QEMU crashed (SIGSEGV)" does.
                    _state.value = VmState.Error(formatExitError(exitCode, config.storageAccessEnabled))
                    cleanup()
                    return
                }
                if (File(serialSockPath).exists() && File(qmpSocketPath).exists()) {
                    val readyMs = System.currentTimeMillis() - startMs
                    // Unambiguous single line: "did the QEMU process start" is the
                    // question the log gets asked most, and it was previously only
                    // inferrable from three differently-phrased messages. Also say
                    // what it does NOT mean: the guest is still booting, which for
                    // x86_64 under TCG is many minutes of silence on the console.
                    val vncNote = if (config.vncEnabled && config.vmMode == VmMode.WINDOWS) {
                        val bind = if (config.vncExpose) "0.0.0.0" else "127.0.0.1"
                        "VNC listening on $bind:${X11Constants.QEMU_VNC_PORT}; "
                    } else {
                        ""
                    }
                    Log.i(TAG, "QEMU START OK in ${readyMs}ms — ${vncNote}sockets ready, guest still booting")
                    socketsReady = true
                    break
                }
                delay(200)
            }
            if (!socketsReady) {
                // Don't destroyForcibly()+throw here: that kills QEMU from this
                // IO thread and skips the dedicated-thread waitFor() reap. Set
                // the error, signal a graceful stop, and fall through to the
                // same waitFor() teardown the happy path uses (it reaps on the
                // podroid-qemu thread). The guard below preserves this message.
                Log.e(TAG, "QEMU START FAILED — sockets not ready after ${SOCKET_READY_TIMEOUT_MS}ms")
                _state.value =
                    VmState.Error("QEMU failed to create sockets within ${SOCKET_READY_TIMEOUT_MS / 1000}s")
                proc.destroy()
            } else if (config.vmMode == VmMode.WINDOWS && !config.uefiEnabled) {
                // SeaBIOS writes to the VGA screen, never to the serial console,
                // so no console marker can ever arrive on this path — waiting for
                // one would hold "starting" for the full fallback on every boot.
                // QEMU holding its sockets IS the signal here: the firmware has
                // initialised by the time it does, and the screen is viewable.
                Log.i(TAG, "SeaBIOS mode — no serial console expected; promoting to Running on socket readiness")
                _bootStage.value = "BIOS"
                persistBootDuration()
                _runningSinceMs = System.currentTimeMillis()
                _state.value = VmState.Running
                autoStartBridge()
            } else {
                // Primary readiness is the console marker the detector is
                // configured for in this mode ("Ready!" on Linux, the first UEFI
                // line on Windows). This is only a safety net so the UI never
                // strands in Starting, and it logs loudly rather than silently
                // rubber-stamping Running.
                val budgetMs = if (config.vmMode == VmMode.WINDOWS) {
                    BOOT_READY_SAFETY_MS_WINDOWS
                } else {
                    BOOT_READY_SAFETY_MS
                }
                val markerName = detector.describeReadyMarkers()
                scope.launch {
                    delay(budgetMs)
                    if (_state.value is VmState.Starting &&
                        process?.isAlive == true && !cleanedUp.get()) {
                        Log.w(TAG, "Boot marker ($markerName) not seen within ${budgetMs / 1000}s - " +
                            "promoting to Running (the guest may boot without console output)")
                        _bootStage.value = "Ready"
                        persistBootDuration()
                        _runningSinceMs = System.currentTimeMillis()
                        _state.value = VmState.Running
                        autoStartBridge()
                    }
                }
            }

            // Block until QEMU exits, ON THE SAME dedicated thread that fork'd
            // it, so PR_SET_PDEATHSIG never fires while QEMU is healthy.
            val exitCode = withContext(dispatcher) { proc.waitFor() }
            lastExitCode = exitCode
            // Was this us? _stopping is set by stop() before SIGTERM, so it
            // distinguishes "stopped on request" from "died". Persisted because
            // the logcat lines below belong to a pid the log viewer filters out
            // once the process restarts — see VmCrashLog.
            val deliberate = _stopping.value
            val reason = formatExitError(exitCode, config.storageAccessEnabled)
            Log.d(TAG, "QEMU exited: $exitCode (deliberate=$deliberate)")
            VmCrashLog.append(
                filesDir = context.filesDir,
                deliberate = deliberate,
                exitCode = exitCode,
                reason = reason,
                detail = buildString {
                    appendLine("mode:     ${config.vmMode}  ram=${config.ramMb}MB cpus=${config.cpus}")
                    appendLine("firmware: ${if (config.uefiEnabled) "UEFI" else "BIOS"}  vnc=${config.vncEnabled}")
                    appendLine("image:    ${config.customImagePath.ifBlank { "<none>" }}")
                    appendLine("storage:  ${if (config.storageAccessEnabled) "shared" else "off"}")
                    appendLine("uptime:   ${_runningSinceMs?.let { "${(System.currentTimeMillis() - it) / 1000}s" } ?: "never reached Running"}")
                    if (!deliberate && exitCode == 137) {
                        appendLine("note:     137 = SIGKILL — the Android system killed QEMU,")
                        appendLine("          most likely low memory or the app being swiped from recents")
                    }
                    if (!deliberate && exitCode == 143) {
                        appendLine("note:     143 = SIGTERM sent from outside this app")
                    }
                },
            )
            val priorError = _state.value as? VmState.Error
            cleanup()
            // If the socket-timeout branch already set a specific Error, keep it
            // rather than overwriting with the generic signal-exit message.
            _state.value = when {
                priorError != null -> priorError
                exitCode == 0 -> VmState.Stopped
                else -> VmState.Error(formatExitError(exitCode, config.storageAccessEnabled))
            }
        } catch (e: CancellationException) {
            // The start() coroutine lives in PodroidService.serviceScope, which
            // onDestroy() cancels on every stop/teardown. A stop destroys the
            // process first (engine.stop() → proc.destroy()), so this cancellation
            // IS the stop completing — finalize as a clean Stopped instead of
            // mislabeling it Error("Job was cancelled"). A sticky Error here also
            // poisoned the next start: its replayed value tore the new service
            // down before the VM could boot. cleanup() is idempotent.
            Log.d(TAG, "start() cancelled — finalizing as Stopped")
            cleanup()
            _state.value = VmState.Stopped
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU", e)
            _state.value = VmState.Error(e.message ?: "Unknown error")
            cleanup()
        }
    }


    /**
     * Signal the VM to stop. Destroys the QEMU process; the start() coroutine's
     * proc.waitFor() will fall through and run cleanup() + set the final state.
     * Avoids the historical race where stop() and the start() exit-path both
     * called cleanup() concurrently and fought over _state.value.
     */
    override fun stop() {
        val proc = process ?: return
        // Signal "shutting down" immediately so the UI reflects the stop while the
        // process tears down (state stays Running until cleanup() runs). Cleared in
        // cleanup() on the →Stopped/Error transition.
        _stopping.value = true
        // Issue the graceful SIGTERM immediately (non-blocking), then run the
        // graceful-wait → forceful-escalation off the caller's thread. stop() is
        // called from PodroidService on the main thread, so blocking up to ~5s
        // in waitFor() here risks an ANR under TCG load. The dedicated
        // qemuDispatcher thread already performs the final waitFor() reap in
        // start(), which drives cleanup() and the →Stopped transition.
        proc.destroy()
        Thread({
            try {
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(2, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
        }, "podroid-qemu-stop").apply { isDaemon = true }.start()
    }

    override fun openHostTransport(): com.pxkf.qodroid.engine.hostbridge.HostTransport? =
        com.pxkf.qodroid.engine.hostbridge.QemuHostTransport.open(hostSockPath)

    override suspend fun addPortForward(rule: PortForwardRule) {
        if (_state.value !is VmState.Running) return
        val qmp = qmpClient ?: return
        // QmpClient returns Result and never throws, so the failure lives INSIDE
        // the returned Result — unwrap it. getOrThrow rethrows so EngineHolder
        // records this rule as not-applied and retries on the next diff (a
        // host-port-already-bound failure would otherwise be permanently dropped
        // and silently recorded as live).
        qmp.addPortForward(rule.hostPort, rule.guestPort, rule.protocol, rule.loopbackOnly)
            .onFailure { Log.w(TAG, "QMP addPortForward failed for $rule", it) }
            .getOrThrow()
    }

    override suspend fun removePortForward(rule: PortForwardRule) {
        if (_state.value !is VmState.Running) return
        val qmp = qmpClient ?: return
        qmp.removePortForward(rule.hostPort, rule.protocol, rule.loopbackOnly)
            .onFailure { Log.w(TAG, "QMP removePortForward failed for $rule", it) }
            .getOrThrow()
    }

    @Synchronized
    private fun cleanup() {
        if (cleanedUp.getAndSet(true)) return
        _stopping.value = false
        bootMonitor?.release()
        bootMonitor = null
        ioScope?.cancel()
        ioScope = null
        // QEMU has already exited by the time cleanup() runs, so retiring its
        // spawning thread here cannot trip PR_SET_PDEATHSIG against a live VM.
        qemuDispatcher?.close()
        qemuDispatcher = null
        process = null
        _terminalSession?.finishIfRunning()
        _terminalSession = null
        sessionClientDelegate = null
        _consoleText.value = ""
        _runningSinceMs = null
        File(serialSockPath).delete()
        File(terminalSockPath).delete()
        File(ctrlSockPath).delete()
        _bootStage.value = ""
    }

    private fun buildCommand(
        qemuExe: File,
        portForwards: List<PortForwardRule>,
        config: VmConfig,
    ): List<String> {
        val args = mutableListOf<String>()
        // Each mode has its own extras — no cross-mode fallback: the Linux set
        // is ARM-tuned (-cpu max,...) and would break an x86_64 guest, so a
        // cleared Windows field means "no extras", not "reuse the Linux ones".
        val isWindowsMode = config.vmMode == VmMode.WINDOWS
        val userQemuExtras = if (isWindowsMode) {
            config.windowsQemuExtraArgs.trim()
        } else {
            config.qemuExtraArgs.trim()
        }
        val userKernelExtras = config.kernelExtraCmdline.trim()
        val customImage = config.customImagePath.trim().let { if (it.isEmpty()) null else File(it) }

        if (isWindowsMode) {
            // q35, not virt: virt is the ARM board. pauth-impdef is arm64-only, and
            // so is -cpu max.
            args += "-M"; args += "q35"
            args += "-cpu"; args += "qemu64"
            // The x86 board needs QEMU's pc-bios blobs, which the APK ships only
            // partially (efi-virtio.rom is the arm64 board's). Without -L QEMU
            // looks next to the binary — the app's nativeLibraryDir, which has no
            // pc-bios at all — and aborts with "failed to find romfile
            // kvmvapic.bin" before the guest ever runs. -L points it at the
            // unpacked assets dir, which is also where the OVMF blobs live.
            args += "-L"; args += context.filesDir.absolutePath
            // VGA + QEMU's own VNC server. This used to be `-vga none`, which
            // left a Windows guest with no video device at all — there is no
            // x11vnc inside a Windows guest to forward to, so the only way to
            // see the session is QEMU's framebuffer server.
            //
            // -vga std needs vgabios-stdvga.bin, which -L resolves out of the
            // unpacked assets dir; the build script extracts it from QEMU's
            // release tarball. Loopback-only, no password: the in-app viewer
            // dials 127.0.0.1 and supports RFB security type None only, while a
            // 0.0.0.0 bind would hand any device on the Wi-Fi a no-auth desktop.
            args += "-vga"; args += "std"
            if (config.vncEnabled) {
                // Loopback-only unless the user opts into exposing it: the
                // server has no password, so a 0.0.0.0 bind would hand any
                // device on the Wi-Fi a no-auth desktop. The expose switch is
                // the explicit override for that default.
                val bind = if (config.vncExpose) "0.0.0.0" else "127.0.0.1"
                args += "-vnc"; args += "$bind:${X11Constants.VNC_DISPLAY}"
            }
            // Absolute pointing. Without a USB tablet the guest uses the PS/2
            // mouse, which reports relative motion only. VNC and touchscreens
            // deliver absolute coordinates, so the cursor is always drifting:
            // the pointer lags, overshoots, and resists fine positioning.
            // usb-tablet is an absolute-position HID device, so the guest gets
            // exact coordinates and the drift disappears.
            args += "-usb"; args += "-device"; args += "usb-tablet"
        } else {
            args += "-M"; args += "virt,gic-version=3"
            // pauth-impdef swaps QEMU's slow QARMA5 PAuth for a fast non-crypto impl. The 50%
            // figure quoted upstream does not show up here: measured against a QARMA5 control
            // it is worth a few percent on xz and bc and nothing on boot or sha256, because
            // the Alpine musl userspace is not built with PAC branch protection. Still free.
            args += "-cpu"; args += "max,pauth-impdef=on"
        }
        val tbSizeMb = if (config.ramMb >= 2048) 512 else 256
        // thread=multi: one host thread per vCPU; larger tb-size reduces re-translation for JIT-heavy guests.
        args += "-accel"; args += "tcg,thread=multi,tb-size=$tbSizeMb"
        args += "-smp"; args += "${config.cpus}"
        // Windows needs far more RAM than the Alpine VM; TCG also has no page
        // sharing to save us, so floor it here rather than letting a 512 MB
        // setting produce a guest that dies during install.
        args += "-m"; args += guestRamMb(config, isWindowsMode).toString()

        if (isWindowsMode) {
            // ── Windows / any user-provided disk-ISO: UEFI boot ──
            // The custom image is attached as a virtio-blk disk (qcow2/raw) or
            // an virtio-scsi CD-ROM (ISO). UEFI firmware is required: x86_64
            // Windows has no BIOS fallback (see the UEFI block below).
            val imageFile = customImage
            if (imageFile == null || !imageFile.exists()) {
                Log.w(TAG, "Windows mode: custom image not found at ${imageFile?.absolutePath ?: "<empty>"}")
            } else {
                args += "-object"; args += "iothread,id=iothread0"
                args += "-device"; args += "virtio-blk-pci,drive=drive1,num-queues=${config.cpus},iothread=iothread0"
                args += "-drive";  args += "file=${imageFile.absolutePath},if=none,id=drive1,format=${diskFormatFor(imageFile)},cache=writeback,aio=threads"
            }

            // ISO installer / second image: attach as CD-ROM.
            val isoPath = config.customImagePath.trim()
            if (isoPath.isNotEmpty() && isoPath.endsWith(".iso", ignoreCase = true) && File(isoPath).exists()) {
                args += "-drive"; args += "file=$isoPath,if=none,id=drive2,format=raw,readonly=on"
                args += "-device"; args += "virtio-scsi-pci"
                args += "-device"; args += "scsi-cd,drive=drive2"
            }

            // Firmware: OVMF when UEFI is on (the x86 build, for q35's pflash
            // slots), SeaBIOS when it is off. The BIOS path is not a fallback for
            // broken UEFI — it exists because a disk installed without an EFI
            // System Partition only boots under BIOS, and the alternative was a
            // guest that silently dropped to the UEFI Shell with no hint why.
            val uefiCode = File(context.filesDir, UEFI_CODE)
            val uefiVars = File(context.filesDir, UEFI_VARS)
            if (!config.uefiEnabled) {
                Log.i(TAG, "Windows mode: UEFI off — booting via SeaBIOS (legacy BIOS)")
            } else if (uefiCode.exists()) {
                args += "-drive"; args += "if=pflash,format=raw,unit=0,file=${uefiCode.absolutePath},readonly=on"
                if (uefiVars.exists()) {
                    args += "-drive"; args += "if=pflash,format=raw,unit=1,file=${uefiVars.absolutePath}"
                } else {
                    // start() preflighted the code file, so a miss here means only
                    // VARS is absent: boot with a throwaway NVRAM copy rather than
                    // leaving unit=1 unbound and aborting.
                    Log.w(TAG, "Windows mode: ${uefiVars.name} not found — booting with NVRAM changes discarded.")
                    val fallback = File(context.cacheDir, uefiVars.name)
                    runCatching { uefiVars.copyTo(fallback, overwrite = true) }
                    args += "-drive"; args += "if=pflash,format=raw,unit=1,file=${fallback.absolutePath},snapshot=on"
                }
            } else {
                Log.w(TAG, "Windows mode: ${uefiCode.name} not found — UEFI guests will not boot.")
            }
        } else {
            // ── Built-in Alpine Linux ─────────────────────────────────────
            val kernelPath = File(context.filesDir, "vmlinuz-virt")
            val initrdPath = File(context.filesDir, "initrd.img")

            if (kernelPath.exists()) {
                args += "-kernel"; args += kernelPath.absolutePath
                val cmdline = buildString {
                    // mitigations=off: speculative-exec attacks don't cross the TCG ISA boundary; 5–15% gain.
                    append("console=ttyAMA0 mitigations=off")
                    if (userKernelExtras.isNotEmpty()) append(" ").append(userKernelExtras)
                    append(" androidip=").append(config.androidIp)
                    if (config.sshEnabled) append(" ssh=1")
                    append(" podroid.x11.dpi=").append(config.x11Dpi)
                    if (config.bandwidthMbps > 0) append(" podroid.bandwidth=").append(config.bandwidthMbps)
                    if (config.dnsServers.isNotEmpty()) append(" podroid.dns=").append(config.dnsServers.joinToString(","))
                }
                args += "-append"; args += cmdline
            } else {
                Log.w(TAG, "Kernel not found!")
            }

            if (initrdPath.exists()) {
                args += "-initrd"; args += initrdPath.absolutePath
            } else {
                Log.w(TAG, "Initrd not found!")
            }

            val storagePath = File(context.filesDir, "storage.img")
            if (storagePath.exists()) {
                // Single dedicated iothread for the writable disk. Multi-iothread
                // fan-out via `iothread-vq-mapping` requires `-device <full-json>`
                // form (the keyval parser cannot supply array-typed properties),
                // and on TCG-emulated guests the perf win is marginal vs the
                // refactor cost. Stick with one iothread, num-queues==vCPUs.
                args += "-object"; args += "iothread,id=iothread0"
                args += "-device"; args += "virtio-blk-pci,drive=drive1,num-queues=${config.cpus},iothread=iothread0"
                // discard=unmap + detect-zeroes=unmap: as the guest fstrim's the
                // overlay, hand the punched holes back to the host filesystem so
                // storage.img stops growing unbounded after long-term container
                // churn. detect-zeroes converts all-zero writes (e.g. mkfs.ext4's
                // erasure pass) into discards too.
                args += "-drive";  args += "file=${storagePath.absolutePath},if=none,id=drive1,format=raw,cache=writeback,aio=threads,discard=unmap,detect-zeroes=unmap"
            }

            val rootfsImg = File(context.filesDir, "alpine-rootfs.squashfs")
            if (rootfsImg.exists()) {
                // Dedicated iothread for the read-only squashfs so its decompression
                // reads don't queue behind storage.img writes on iothread0.
                args += "-object"; args += "iothread,id=iothread1"
                args += "-device"; args += "virtio-blk-pci,drive=drive2,num-queues=${config.cpus},iothread=iothread1"
                args += "-drive";  args += "file=${rootfsImg.absolutePath},if=none,id=drive2,format=raw,readonly=on,cache=writeback,aio=threads"
            }
        }

        // Downloads folder sharing via virtio-9p
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val hasStorageAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager()
        else
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (config.storageAccessEnabled &&
            hasStorageAccess &&
            downloadsDir.exists()) {
            // security_model=none passes file ownership/mode straight through
            // with no xattr mapping. mapped-xattr routed through QEMU's 9p
            // chmod/chown syscall path, which triggered SIGILL on Tensor /
            // ARMv9.2 PAC devices (Pixel 10), so it was reverted to none.
            args += "-fsdev"
            args += "local,id=fsdev0,path=${downloadsDir.absolutePath},security_model=none"
            args += "-device"
            args += "virtio-9p-pci,fsdev=fsdev0,mount_tag=downloads"
        }

        val netdevArg = buildString {
            append("user,id=net0,ipv6=off")
            // DNS deliberately left untouched: -netdev user,dns= does NOT hand
            // the guest a real public resolver. It renames slirp's virtual
            // nameserver (vdns_addr, default 10.0.2.3) to the given address,
            // so queries to 223.5.5.5/8.8.8.8 are still intercepted and
            // forwarded via the Android host resolver (which has no usable
            // /etc/resolv.conf) — the "locked / high latency DNS" symptom.
            // The path that actually works is the guest's adapter using a
            // public DNS directly (manual 8.8.8.8 / 223.5.5.5), which differs
            // from vdns_addr and is therefore NAT'd straight out.
            // Only the implicit forwards go here; user rules arrive over QMP once
            // the VM is up. See inlineLaunchRules for why that distinction is
            // load-bearing rather than cosmetic.
            for (rule in inlineLaunchRules(portForwards)) {
                // hostfwd hostaddr: empty = 0.0.0.0 (LAN-reachable, user rules);
                // 127.0.0.1 for loopbackOnly rules (implicit VNC/audio) so they
                // aren't exposed to the network.
                val hostAddr = if (rule.loopbackOnly) "127.0.0.1" else ""
                append(",hostfwd=${rule.protocol}:$hostAddr:${rule.hostPort}-:${rule.guestPort}")
            }
        }
        args += "-netdev"; args += netdevArg
        args += "-device"; args += "virtio-net-pci,netdev=net0,romfile="

        // USB host controller for hot-plugged passthrough devices. Only emitted
        // when the feature is on — the XHCI model adds emulation overhead that
        // nothing else needs. UsbPassthroughManager streams Android
        // UsbDeviceConnection fds onto this bus at runtime via QMP add-fd +
        // device_add usb-host (needs a libusb-enabled QEMU build).
        if (config.usbPassthroughEnabled) {
            args += "-device"; args += "qemu-xhci,id=usbhc0"
        }

        // ── Serial (ttyAMA0) → boot log sink only; kernel msgs + init boot stages ─
        args += "-serial"; args += "unix:$serialSockPath,server,nowait"

        // ── virtio-console bus ────────────────────────────────────────────────
        // hvc0 = primary terminal (getty runs here; bridge connects to terminal.sock)
        // hvc1 = control channel (init daemon reads RESIZE messages from ctrl.sock)
        args += "-device";  args += "virtio-serial-pci"
        args += "-chardev"; args += "socket,id=term0,path=$terminalSockPath,server=on,wait=off"
        args += "-device";  args += "virtconsole,chardev=term0,name=org.podroid.term"
        args += "-chardev"; args += "socket,id=ctrl0,path=$ctrlSockPath,server=on,wait=off"
        args += "-device";  args += "virtconsole,chardev=ctrl0,name=org.podroid.ctrl"

        // hvc2 = host bridge (guest podroid-hostd <-> Android host.sock)
        args += "-chardev"; args += "socket,id=host0,path=$hostSockPath,server=on,wait=off"
        args += "-device";  args += "virtconsole,chardev=host0,name=org.podroid.host"

        args += "-display"; args += "none"
        args += "-qmp";     args += "unix:$qmpSocketPath,server,nowait"

        // User extras appended last so later -cpu / -accel overrides earlier ones.
        if (userQemuExtras.isNotEmpty()) {
            // ...but only on the Linux path. The stored default is ARM-tuned
            // ("-cpu max,sve=off,pauth-impdef=on") and SVE does not exist on an
            // x86_64 CPU, so appending it verbatim made QEMU die with
            // "can't apply global max-x86_64-cpu.sve=off: Property not found",
            // exit code 1, before the guest got a single instruction. Harmless
            // on Linux precisely because both -cpu strings are identical there.
            val extras = if (isWindowsMode) EngineOwnedArgs.drop(userQemuExtras) else userQemuExtras
            if (extras != userQemuExtras) {
                Log.i(TAG, "x86_64 mode: dropped -cpu/-accel from stored extra args — " +
                    "they are ARM-tuned (sve/pauth-impdef) and would override this " +
                    "mode's qemu64, killing QEMU at startup")
            }
            if (extras.isNotEmpty()) args += extras.split(Regex("\\s+"))
        }

        // Wrap QEMU in podroid-launcher when available — it sets PR_SET_PDEATHSIG
        // so QEMU dies with the app on uninstall/OOM/force-stop instead of leaking
        // as an orphan under PPID=1. If the launcher is missing (older deploys),
        // fall back to spawning QEMU directly.
        val launcher = File(context.applicationInfo.nativeLibraryDir, "libpodroid-launcher.so")
        return if (launcher.exists()) {
            listOf(launcher.absolutePath, qemuExe.absolutePath) + args
        } else {
            listOf(qemuExe.absolutePath) + args
        }
    }

    private fun ensureStorageImage(storageSizeGb: Int) {
        val storageFile = File(context.filesDir, "storage.img")
        val desiredBytes = storageSizeGb.toLong() * 1024L * 1024L * 1024L

        if (storageFile.exists()) {
            val current = storageFile.length()
            when {
                current == desiredBytes -> return
                // NEVER delete: storage.img is the persistent ext4 overlay (every
                // apk add, container, user file). Deleting on mismatch could wipe
                // all guest data if the size ever changes (a corrupted DataStore
                // falling back to the 2GB default, or a future resize UI). Grow in
                // place when larger — the guest's first-boot resize2fs claims the
                // new space; truncating to shrink would corrupt the filesystem.
                desiredBytes > current -> {
                    runCatching {
                        java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
                    }.onSuccess {
                        Log.i(TAG, "storage.img grown ${current / (1024 * 1024)}MB → ${storageSizeGb}GB (guest resize2fs on next boot)")
                    }.onFailure { Log.e(TAG, "Failed to grow storage.img", it) }
                    return
                }
                else -> {
                    Log.w(TAG, "storage.img (${current / (1024 * 1024)}MB) larger than requested ${storageSizeGb}GB; keeping existing image (shrink would corrupt the filesystem)")
                    return
                }
            }
        }

        try {
            java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
            Log.d(TAG, "Created storage.img (${storageSizeGb}GB)")
        } catch (e: Exception) {
            // Don't swallow: a 0-byte / missing storage.img would otherwise boot
            // into an opaque early-boot stop. Surface it so start() can report a
            // clear storage error (mirrors the AVF disk-create path).
            Log.e(TAG, "Failed to create storage.img", e)
            runCatching { storageFile.delete() }
            throw java.io.IOException("Couldn't create the ${storageSizeGb} GB VM disk image: ${e.message}", e)
        }
    }

    /**
     * Locates an emulator binary by its packaged name. Each guest architecture is
     * a separate ELF with its own softmmu target compiled in, so a missing
     * x86_64 build is a real (and user-visible) gap rather than a detail to
     * paper over — [qemuExecutableFor] returning null is what lets start()
     * report it.
     */
    private fun qemuExecutable(name: String = QEMU_AARCH64_NAME): File? {
        val exe = File(context.applicationInfo.nativeLibraryDir, name)
        return if (exe.exists()) exe else null
    }

    private fun qemuExecutableFor(config: VmConfig): File? =
        qemuExecutable(
            if (config.vmMode == VmMode.WINDOWS) QEMU_X64_NAME else QEMU_AARCH64_NAME
        )

    override fun diagnosticsReport(): String = buildString {
        appendLine("last process exit code: ${lastExitCode?.toString() ?: "(still running / not yet exited)"}")
        val tail = synchronized(stderrTail) { stderrTail.toList() }
        if (tail.isEmpty()) {
            appendLine("qemu stderr: (none captured)")
        } else {
            appendLine("qemu stderr (last ${tail.size} line(s)):")
            tail.forEach { appendLine("  $it") }
        }
    }

    /** Keep the most recent [STDERR_TAIL_LINES] non-blank stderr lines. */
    private fun recordStderr(chunk: String) {
        if (chunk.isBlank()) return
        synchronized(stderrTail) {
            for (line in chunk.lineSequence()) {
                if (line.isBlank()) continue
                stderrTail.addLast(line)
                while (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
            }
        }
    }

    /**
     * Decode a QEMU process exit code into a user-facing error string. Process
     * exit codes ≥128 are POSIX-encoded signals (128 + signum). On some devices
     * (notably Tensor / ARMv9.2 PAC) virtio-9p crashes the QEMU worker with
     * SIGILL — surface that as a Downloads-sharing hint rather than "Exit 132".
     */
    private fun formatExitError(exitCode: Int, storageSharingEnabled: Boolean): String {
        if (exitCode < 128) return "QEMU exited with code $exitCode"
        val sig = exitCode - 128
        val name = when (sig) {
            4  -> "SIGILL"
            6  -> "SIGABRT"
            7  -> "SIGBUS"
            8  -> "SIGFPE"
            9  -> "SIGKILL"
            11 -> "SIGSEGV"
            13 -> "SIGPIPE"
            15 -> "SIGTERM"
            31 -> "SIGSYS"
            else -> "signal $sig"
        }
        // SIGILL/SIGBUS/SIGSEGV with Downloads sharing on points at virtio-9p
        // on PAC-enforcing kernels — by far the most common crash path here.
        val crashSignals = setOf(4, 7, 11)
        return if (storageSharingEnabled && sig in crashSignals) {
            "QEMU crashed ($name). Downloads sharing is unstable on this device — disable it in Settings and try again."
        } else {
            "QEMU crashed ($name)"
        }
    }

    /**
     * QEMU applies only the implicit forwards while launching; the rest have to
     * be pushed in over QMP once the VM is Running.
     */
    override fun rulesAppliedAtLaunch(all: Collection<PortForwardRule>): Set<PortForwardRule> =
        inlineLaunchRules(all).toSet()

    companion object {
        private const val TAG = "QemuEngine"
        private const val STDERR_TAIL_LINES = 40

        /**
         * Built-in-Alpine payload files, checked before launch (asset name to
         * the label used in the error). Without all three QEMU has no kernel,
         * no initramfs and no root disk, and dies instantly with a signal the
         * user cannot act on.
         */
        private val VM_PAYLOAD_FILES = listOf(
            "vmlinuz-virt" to "kernel",
            "initrd.img" to "initramfs",
            "alpine-rootfs.squashfs" to "root filesystem",
        )

        /** x86 UEFI firmware in assets/qemu, unpacked flat into filesDir. */
        private const val UEFI_CODE = "OVMF_CODE.fd"
        private const val UEFI_VARS = "OVMF_VARS.fd"
        /** VGA option ROM for `-vga std`; without it q35 aborts during realize. */
        private const val VGA_BIOS = "vgabios-stdvga.bin"
        /** QEMU's en-us keymap, required before `-vnc` will start at all. */
        private const val KEYMAP_EN_US = "keymaps/en-us"

        /**
         * SeaBIOS, the legacy-BIOS firmware for the q35 machine. Distinct from
         * OVMF_CODE.fd: QEMU refuses to create the machine at all without it,
         * so this gates BIOS mode rather than being a boot-time detail.
         */
        private const val SEA_BIOS = "bios-256k.bin"

        /**
         * `-drive format=` for a picked disk image.
         *
         * This was hardcoded to `qcow2`, but the image picker accepts qcow2 /
         * qcow / img / raw — so picking a raw `.img` made QEMU abort with
         * "Image is not in qcow2 format" before the guest ran. Unknown
         * extensions fall back to raw rather than qcow2: raw is the format that
         * cannot be *wrong* about content, whereas guessing qcow2 turns a
         * readable disk into an unopenable one.
         */
        fun diskFormatFor(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
            "qcow2" -> "qcow2"
            "qcow" -> "qcow"
            else -> "raw"
        }

        /** Packaged emulator names. QEMU is renamed to lib*.so for the APK, and
         *  each guest arch is a separate binary with its own softmmu targets. */
        private const val QEMU_AARCH64_NAME = "libqemu-system-aarch64.so"
        private const val QEMU_X64_NAME = "libqemu-system-x86_64.so"

        /**
         * Guest RAM. Windows is unforgiving: under ~3 GB the installer and
         * Explorer both die, and TCG has no KSM to make a big guest cheap, so
         * raise the floor instead of letting a leftover 512 MB Alpine setting
         * produce an unbootable guest. The device can always take a smaller
         * value afterwards by editing the setting.
         */
        private fun guestRamMb(config: VmConfig, windowsMode: Boolean): Int {
            if (!windowsMode) return config.ramMb
            val floor = 4096
            return maxOf(config.ramMb, floor)
        }

        /**
         * The port forwards allowed onto QEMU's launch command line.
         *
         * SLIRP treats a hostfwd it cannot set up as fatal: QEMU exits 1 before
         * the machine starts. Rules are persisted, so anything inlined here can
         * make the VM permanently unstartable, with the app offering no way to
         * edit the rules that are blocking it. One user hit exactly that by
         * scripting 1005 forwards, most of them inside Android's ephemeral port
         * range where something is always already bound; the VM never booted
         * again and they wiped it believing the data was gone.
         *
         * So the command line carries only what the app injects itself and needs
         * from the first instant: the loopback-bound VNC and audio pair behind
         * the in-app viewer, and SSH (guest port 22), which is the way back in
         * when everything else is broken. Those are three fixed ports the app
         * controls, all below the ephemeral range.
         *
         * User rules go in through [addPortForward] on the →Running edge, where
         * a rule that cannot bind fails alone and is retried on the next diff.
         * This also un-bricks anyone already stuck: their rules simply stop being
         * a launch argument.
         */
        fun inlineLaunchRules(all: Collection<PortForwardRule>): List<PortForwardRule> =
            all.filter { it.loopbackOnly || it.guestPort == SSH_GUEST_PORT }

        private const val SSH_GUEST_PORT = 22

        /** Shared deadline for start()'s socket-readiness loop and the boot monitor's connect wait. */
        private const val SOCKET_READY_TIMEOUT_MS = 10_000L

        /**
         * Safety-net cap: if the guest's "Ready!" marker is never seen, promote
         * to Running anyway so the UI never strands. Sized above a worst-case
         * first boot (~56s, dropbear hostkey gen). On a healthy boot the detector
         * fires first and this never triggers.
         */
        /**
     * Fallback deadline for promoting Starting → Running when no console
     * marker is ever seen. Only a backstop: Linux normally trips on the
     * bootstrap's "Ready!", Windows on the first UEFI line.
     */
private const val BOOT_READY_SAFETY_MS = 120_000L

        /**
         * Same backstop for the Windows path, in seconds.
         *
         * Much shorter than the Linux one because the thing it is covering is
         * different: there, 120 s clears a worst-case first Linux boot. Here
         * every console line means firmware already reached the guest — either
         * it arrived and matched a marker, or the guest is silent (e.g. it
         * booted straight to a blank screen with no serial output). Neither
         * resolves by waiting longer, so holding "Starting" for two minutes
         * only misreported a live VM as stalled.
         */
        private const val BOOT_READY_SAFETY_MS_WINDOWS = 20_000L
    }
}
