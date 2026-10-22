/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Settings ViewModel for Podroid.
 */
package com.pxkf.qodroid.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pxkf.qodroid.R
import com.pxkf.qodroid.data.repository.AddRuleResult
import com.pxkf.qodroid.data.repository.LanguageManager
import com.pxkf.qodroid.data.repository.PortForwardRepository
import com.pxkf.qodroid.data.repository.PortForwardRule
import com.pxkf.qodroid.data.repository.SettingsRepository
import com.pxkf.qodroid.di.ApplicationScope
import com.pxkf.qodroid.engine.EngineHolder
import com.pxkf.qodroid.engine.EngineSelection
import com.pxkf.qodroid.engine.VmMode
import com.pxkf.qodroid.engine.VmState
import com.pxkf.qodroid.engine.VncStatus
import com.pxkf.qodroid.engine.VncStatusMonitor
import com.pxkf.qodroid.util.DeviceResourcePolicy
import com.pxkf.qodroid.util.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Snapshot of the form-style settings rows that SettingsScreen reads together.
 * Combined into one StateFlow so an emit on any one source produces a single
 * recomposition instead of one per source. Does *not* include flows with
 * different update cadences or consumers (vmState, portForwardRules, updateInfo,
 * terminal-only theme/font/size) — those stay separate.
 */
data class SettingsUiState(
    val vmRamMb: Int = 512,
    val vmCpus: Int = 1,
    val storageSizeGb: Int = 2,
    val sshEnabled: Boolean = false,
    val vncEnabled: Boolean = true,
    val storageAccessEnabled: Boolean = false,
    val qemuExtraArgs: String = SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS,
    val windowsQemuExtraArgs: String = "",
    val kernelExtraCmdline: String = SettingsRepository.DEFAULT_KERNEL_EXTRA_CMDLINE,
    val darkTheme: Boolean = false,
    val dynamicColorEnabled: Boolean = false,
    val engineSelection: EngineSelection = EngineSelection.AUTO,
    val language: String = "auto",
    val systemDefaultLanguage: String = "auto",
    val loadBalanceEnabled: Boolean = false,
    val bandwidthMbps: Int = 0,
    /** Current boot profile — selects which QEMU-extras field the UI shows. */
    val vmMode: VmMode = VmMode.LINUX,
)

// Typed intermediates for the three sub-combines below, so a reordered flow
// is a compile error instead of a silently cross-wired setting (the previous
// arrayOf<Any> packing cast back by index).
private data class VmRowsGroup(
    val vmRamMb: Int,
    val vmCpus: Int,
    val storageSizeGb: Int,
    val sshEnabled: Boolean,
    val loadBalanceEnabled: Boolean,
)

private data class DisplayAndStorageGroup(
    val vncEnabled: Boolean,
    val storageAccessEnabled: Boolean,
    val qemuExtraArgs: String,
    val windowsQemuExtraArgs: String,
    val kernelExtraCmdline: String,
)

private data class ThemeGroup(
    val darkTheme: Boolean,
    val dynamicColorEnabled: Boolean,
)

private data class LocaleRowsGroup(
    val bandwidthMbps: Int,
    val engineSelection: EngineSelection,
    val language: String,
    val systemDefaultLanguage: String,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val engine: EngineHolder,
    private val languageManager: LanguageManager,
    private val vncStatusMonitor: VncStatusMonitor,
    @ApplicationScope private val externalScope: CoroutineScope,
) : ViewModel() {

    /**
     * Single combined stream of the 8 form-style rows. SettingsScreen can collect
     * this once with collectAsStateWithLifecycle instead of subscribing 8 times.
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            settingsRepository.vmRamMb,
            settingsRepository.vmCpus,
            settingsRepository.storageSizeGb,
            settingsRepository.sshEnabled,
            settingsRepository.loadBalanceEnabled,
        ) { ram, cpus, storage, ssh, loadBal ->
            VmRowsGroup(ram, cpus, storage, ssh, loadBal)
        },
        combine(
            settingsRepository.vncEnabled,
            settingsRepository.storageAccessEnabled,
            settingsRepository.qemuExtraArgs,
            settingsRepository.windowsQemuExtraArgs,
            settingsRepository.kernelExtraCmdline,
        ) { vnc, storageAccess, qemu, winQemu, kernel ->
            DisplayAndStorageGroup(vnc, storageAccess, qemu, winQemu, kernel)
        },
        combine(
            settingsRepository.darkTheme,
            settingsRepository.dynamicColorEnabled,
        ) { dark, dyn ->
            ThemeGroup(dark, dyn)
        },
        combine(
            settingsRepository.bandwidthMbps,
            settingsRepository.engineSelection,
            settingsRepository.language,
            languageManager.language,
        ) { bandwidth, engineSel, lang, sysLang ->
            LocaleRowsGroup(bandwidth, engineSel, lang, sysLang)
        },
        settingsRepository.vmMode,
    ) { vm, display, theme, locale, mode ->
        SettingsUiState(
            vmRamMb = vm.vmRamMb,
            vmCpus = vm.vmCpus,
            storageSizeGb = vm.storageSizeGb,
            sshEnabled = vm.sshEnabled,
            vncEnabled = display.vncEnabled,
            storageAccessEnabled = display.storageAccessEnabled,
            qemuExtraArgs = display.qemuExtraArgs,
            windowsQemuExtraArgs = display.windowsQemuExtraArgs,
            kernelExtraCmdline = display.kernelExtraCmdline,
            darkTheme = theme.darkTheme,
            dynamicColorEnabled = theme.dynamicColorEnabled,
            engineSelection = locale.engineSelection,
            language = locale.language,
            systemDefaultLanguage = locale.systemDefaultLanguage,
            loadBalanceEnabled = vm.loadBalanceEnabled,
            bandwidthMbps = locale.bandwidthMbps,
            vmMode = mode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    // The Advanced VM-args fields (AdvancedTextSetting) buffer edits locally and
    // commit them on focus loss or composable disposal. Disposal coincides with
    // this ViewModel being cleared on back-navigation, which cancels viewModelScope
    // before a viewModelScope.launch could finish the DataStore write — silently
    // dropping the edit (issue #46). Persist on the application-lifetime scope so
    // the write outlives the screen teardown.
    fun setQemuExtraArgs(value: String) {
        externalScope.launch { settingsRepository.setQemuExtraArgs(value) }
    }

    fun setWindowsQemuExtraArgs(value: String) {
        externalScope.launch { settingsRepository.setWindowsQemuExtraArgs(value) }
    }

    fun setKernelExtraCmdline(value: String) {
        externalScope.launch { settingsRepository.setKernelExtraCmdline(value) }
    }

    fun resetQemuExtraArgs() {
        externalScope.launch { settingsRepository.setQemuExtraArgs(SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS) }
    }

    fun resetWindowsQemuExtraArgs() {
        externalScope.launch { settingsRepository.setWindowsQemuExtraArgs(SettingsRepository.DEFAULT_WINDOWS_QEMU_EXTRA_ARGS) }
    }

    fun resetKernelExtraCmdline() {
        externalScope.launch { settingsRepository.setKernelExtraCmdline(SettingsRepository.DEFAULT_KERNEL_EXTRA_CMDLINE) }
    }

    fun setSshEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setSshEnabled(value) }
    }

    fun setVncEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setVncEnabled(value) }
    }

    /** Live reachability of the VNC server, so the switch shows effect, not intent. */
    val vncStatus: StateFlow<VncStatus> = vncStatusMonitor.status

    fun setStorageAccessEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setStorageAccessEnabled(value) }
    }

    val portForwardRules: StateFlow<List<PortForwardRule>> = portForwardRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Eagerly (like HomeViewModel): WhileSubscribed replays the initial Idle on
    // re-subscription, flashing a "stopped" indicator for a frame when the screen
    // is reopened within the stop-timeout window.
    val vmState: StateFlow<VmState> = engine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkTheme(value) }
    }

    fun setDynamicColorEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColorEnabled(value) }
    }

    fun setEngineSelection(value: EngineSelection) {
        viewModelScope.launch { settingsRepository.setEngineSelection(value) }
    }

    val avfVerboseLogging: StateFlow<Boolean> = settingsRepository.avfVerboseLogging
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAvfVerboseLogging(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAvfVerboseLogging(value) }
    }

    val usbPassthroughEnabled: StateFlow<Boolean> = settingsRepository.usbPassthroughEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setUsbPassthroughEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setUsbPassthroughEnabled(value) }
    }

    val autostartOnBoot: StateFlow<Boolean> = settingsRepository.autostartOnBoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAutostartOnBoot(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutostartOnBoot(value) }
    }

    val autoCheckUpdates: StateFlow<Boolean> = settingsRepository.autoCheckUpdates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setAutoCheckUpdates(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoCheckUpdates(value) }
    }

    suspend fun setLanguage(value: String) {
        Log.d(TAG, "setLanguage: $value")
        languageManager.setLanguage(value)
        Log.d(TAG, "setLanguage done")
    }

    fun setVmRamMb(value: Int) {
        viewModelScope.launch {
            settingsRepository.setLoadBalanceEnabled(false)
            settingsRepository.setVmRamMb(value)
        }
    }

    fun setStorageSizeGb(value: Int) {
        viewModelScope.launch {
            // storage.img is never shrunk; the disabled chips already stop this in
            // the UI, this is defence in depth against a stale/racing value.
            val current = settingsRepository.getStorageSizeGbSnapshot()
            if (value < current) return@launch
            settingsRepository.setLoadBalanceEnabled(false)
            settingsRepository.setStorageSizeGb(value)
        }
    }

    fun setVmCpus(value: Int) {
        viewModelScope.launch {
            settingsRepository.setLoadBalanceEnabled(false)
            settingsRepository.setVmCpus(value)
        }
    }

    fun setBandwidthMbps(value: Int) {
        viewModelScope.launch {
            settingsRepository.setLoadBalanceEnabled(false)
            settingsRepository.setBandwidthMbps(value)
        }
    }

    fun setLoadBalanceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLoadBalanceEnabled(enabled)
            if (enabled) {
                val profile = DeviceResourcePolicy.balancedProfile(context)
                settingsRepository.setVmRamMb(profile.ramMb)
                settingsRepository.setVmCpus(profile.cpus)
                settingsRepository.setBandwidthMbps(profile.bandwidthMbps)
                // The overlay image is grown to this setting and never shrunk, so a
                // balanced size below the current one would only misreport the real
                // image. Floor the balanced storage at the current size.
                val current = settingsRepository.getStorageSizeGbSnapshot()
                settingsRepository.setStorageSizeGb(maxOf(profile.storageGb, current))
            }
        }
    }

    // "both" expands into separate TCP + UDP rules. Returns true if at least one
    // rule was added; false if every expansion was already present (duplicate).
    // The caller uses the return value to show feedback instead of silently
    // closing the dialog on a duplicate.
    fun addPortForward(hostPort: Int, guestPort: Int, protocol: String = "tcp"): Boolean {
        // Backstop the dialog's reserved-port check: these host ports back the
        // implicit loopback-bound X11 forwards and must never be user-bound to
        // 0.0.0.0 (the dialog rejects them with a dedicated message first).
        if (hostPort in PortForwardRepository.RESERVED_HOST_PORTS) return false
        val protos = if (protocol == "both") listOf("tcp", "udp") else listOf(protocol)
        val existing = portForwardRules.value.toSet()
        val toAdd = protos.filter { proto ->
            existing.none { it.hostPort == hostPort && it.protocol == proto }
        }
        if (toAdd.isEmpty()) return false
        viewModelScope.launch {
            // "both" can add TCP then hit the table ceiling on UDP (or vice
            // versa): the dialog already closed on the true return below, so
            // that partial outcome would otherwise vanish silently.
            val notAdded = toAdd.filter { proto ->
                portForwardRepository.addRule(PortForwardRule(hostPort, guestPort, proto)) != AddRuleResult.ADDED
            }
            if (notAdded.isNotEmpty() && notAdded.size < toAdd.size) {
                _portForwardPartialWarning.value = context.getString(
                    R.string.port_forward_partial_add,
                    hostPort,
                    notAdded.joinToString(", ") { it.uppercase() },
                )
            }
        }
        return true
    }

    /** Current LAN IP of the Android device — shown next to port forward rules. Cached for the VM lifetime. */
    val phoneIp: String by lazy { NetworkUtils.localIpv4(context) }

    /** Returns the ID string of the currently active VM backend (e.g. "qemu" or "avf"). */
    fun activeBackendId(): String = engine.backendId

    /**
     * Reactive mirror of [activeBackendId] for the UI: a backend swap (or the
     * async first pick resolving after this screen is already composed) must
     * update the AVF diagnostic dialog and the USB row without the user having
     * to leave and reopen Settings. See #66.
     */
    val activeBackendIdFlow: StateFlow<String> = engine.backendIdFlow

    /** Non-null when the current pick forced AVF but fell back to QEMU; a
     *  short localized reason for the Settings backend caption. See #66. */
    val backendFallback: StateFlow<String?> = engine.backendFallback

    private val _exportError = MutableStateFlow<String?>(null)
    /** One-shot export failure message; clear after showing with [clearExportError]. */
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    fun clearExportError() { _exportError.value = null }

    private val _portForwardPartialWarning = MutableStateFlow<String?>(null)
    /** One-shot warning when a "both" add only added one protocol (e.g. the
     *  table filled between the TCP and UDP rule); clear after showing with
     *  [clearPortForwardPartialWarning]. */
    val portForwardPartialWarning: StateFlow<String?> = _portForwardPartialWarning.asStateFlow()

    fun clearPortForwardPartialWarning() { _portForwardPartialWarning.value = null }

    fun removePortForward(rule: PortForwardRule) {
        viewModelScope.launch { portForwardRepository.removeRule(rule) }
    }

    /** Removes every persisted user rule. Implicit forwards (SSH/VNC/audio) are in-memory and unaffected. */
    fun clearPortForwards() {
        viewModelScope.launch { portForwardRepository.clearRules() }
    }

    fun resetVm() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                activityManager.clearApplicationUserData()
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
