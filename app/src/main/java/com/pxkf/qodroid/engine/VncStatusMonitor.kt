package com.pxkf.qodroid.engine

import android.util.Log
import com.pxkf.qodroid.x11.X11Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live state of the VNC display path.
 *
 * [Listening] is deliberately not "the VM is running": the two come apart
 * constantly. QEMU opens the VNC socket the instant it is constructed, long
 * before a Windows guest has produced a frame, and with the switch off there is
 * no socket at all even though the VM is fine. Reporting one bit for both is
 * what made this unanswerable from the UI before.
 */
sealed interface VncStatus {
    /** The switch is off, or the mode in use has no VNC server to report on. */
    data object Off : VncStatus

    /** Wanted, VM starting — QEMU has not opened the socket yet. */
    data object Waiting : VncStatus

    /** Socket accepts connections. A guest may still be minutes from any pixels. */
    data class Listening(val port: Int) : VncStatus

    /** Wanted and the VM should be up, but the socket never appeared. */
    data class Failed(val reason: String) : VncStatus
}

/**
 * Watches whether a VNC server is actually reachable, so "the display is on"
 * is a fact rather than a hope.
 *
 * This is deliberately a TCP connect probe and not a full RFB handshake: the
 * VncClient already reports protocol failures, and a connect is enough to tell
 * the two states that actually confuse — server absent versus server up but
 * idle. It also keeps working for the guest-Xvnc port on the Linux path, where
 * 5900 only answers once x11vnc inside the VM has started, which is the same
 * question this class exists to answer.
 *
 * Loopback only, short timeout, one probe at a time: this is a status light,
 * not a heartbeat, and it must not keep the device awake.
 */
@Singleton
class VncStatusMonitor @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<VncStatus>(VncStatus.Off)
    val status: StateFlow<VncStatus> = _status.asStateFlow()

    private var job: Job? = null

    /**
     * Point the monitor at a port, or switch it off. Call on every VM state
     * change and on every settings change; passing null for [port] is how
     * "switched off" is expressed.
     */
    fun watch(port: Int?) {
        job?.cancel()
        job = null
        if (port == null) {
            if (_status.value != VncStatus.Off) {
                Log.i(TAG, "VNC monitor: disabled")
                _status.value = VncStatus.Off
            }
            return
        }
        _status.value = VncStatus.Waiting
        job = scope.launch {
            while (isActive) {
                val open = withContext(Dispatchers.IO) { probe(port) }
                val next = when {
                    open -> VncStatus.Listening(port)
                    // Only escalate to Failed after a grace period: the VM has
                    // to finish starting before QEMU binds the socket, and the
                    // Linux guest additionally has to boot x11vnc inside it.
                    elapsedPolls(port) >= FAILED_AFTER_POLLS -> VncStatus.Failed(
                        "no VNC server on 127.0.0.1:$port after ${FAILED_AFTER_POLLS * POLL_MS / 1000}s " +
                            "— the emulator may not have been built with VNC support"
                    )
                    else -> VncStatus.Waiting
                }
                if (next != _status.value) {
                    if (next is VncStatus.Listening) {
                        Log.i(TAG, "VNC monitor: 127.0.0.1:${next.port} is accepting connections")
                    } else if (next is VncStatus.Failed) {
                        Log.w(TAG, "VNC monitor: ${next.reason}")
                    }
                    _status.value = next
                }
                delay(POLL_MS)
            }
        }
    }

    /** Probe wall time, used only to decide when Waiting has gone on too long. */
    private var firstProbeAt = 0L
    private fun elapsedPolls(port: Int): Int {
        val now = System.currentTimeMillis()
        if (firstProbeAt == 0L || lastPort != port) {
            firstProbeAt = now
            lastPort = port
        }
        return ((now - firstProbeAt) / POLL_MS).toInt()
    }

    private var lastPort: Int = -1

    private fun probe(port: Int): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val TAG = "VncStatusMonitor"
        private const val POLL_MS = 2000L
        private const val PROBE_TIMEOUT_MS = 400
        private const val FAILED_AFTER_POLLS = 15 // ~30s of a VM that is up

        /**
         * The port worth watching for a given state, or null when nothing should
         * be reported. Windows is served by QEMU's own server; Linux has no such
         * server and the 5900 forward only answers once the guest's Xvnc is up —
         * so it is only meaningful once the guest has actually booted.
         */
        fun portFor(mode: VmMode, vncEnabled: Boolean, vmState: VmState): Int? = when {
            !vncEnabled -> null
            mode == VmMode.WINDOWS && vmState is VmState.Running -> X11Constants.QEMU_VNC_PORT
            mode == VmMode.LINUX && vmState is VmState.Running -> X11Constants.VNC_PORT
            else -> null
        }
    }
}