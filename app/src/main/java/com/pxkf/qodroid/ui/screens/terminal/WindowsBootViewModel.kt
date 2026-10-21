package com.pxkf.qodroid.ui.screens.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pxkf.qodroid.data.repository.SettingsRepository
import com.pxkf.qodroid.engine.EngineHolder
import com.pxkf.qodroid.engine.VmMode
import com.pxkf.qodroid.engine.VmState
import com.pxkf.qodroid.engine.VncStatus
import com.pxkf.qodroid.engine.VncStatusMonitor
import com.pxkf.qodroid.service.PodroidService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Windows / custom-image screen of the dual terminal.
 * Mirrors the persisted VmMode + custom image path, exposes live engine state
 * so the screen can report whether the boot actually got anywhere, and
 * forwards start/stop to the same PodroidService the Linux terminal uses — the
 * two profiles are mutually exclusive (only one VM runs at a time), matching
 * the single-engine architecture.
 *
 * The mode itself is written from the Home page's boot-mode selector
 * (HomeViewModel.setVmMode); this ViewModel only reads it, so the two screens
 * can't drift apart.
 */
@HiltViewModel
class WindowsBootViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    engine: EngineHolder,
    vncStatusMonitor: VncStatusMonitor,
) : ViewModel() {

    private val _vmMode = MutableStateFlow(VmMode.LINUX)
    val vmMode: StateFlow<VmMode> = _vmMode.asStateFlow()

    private val _customImagePath = MutableStateFlow("")
    val customImagePath: StateFlow<String> = _customImagePath.asStateFlow()

    /**
     * Live engine state. Without this the screen was a blind start/stop pair:
     * a guest that fails to boot looks identical to one that booted, because a
     * TCG x86_64 Windows takes many minutes to get anywhere and says nothing
     * in the meantime.
     */
    val vmState: StateFlow<VmState> = engine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    val bootStage: StateFlow<String> = engine.bootStage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val stopping: StateFlow<Boolean> = engine.stopping
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Raw guest serial output. A Windows guest has no terminal to type into, so
     * this is the only place its boot progress is visible at all — and it is
     * what makes "still working" distinguishable from "hung", which the status
     * line alone cannot.
     */
    val consoleText: StateFlow<String> = engine.consoleText
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Whether the framebuffer server is actually up. The in-app preview needs
     * it, so a failed or switched-off state has to be visible here rather than
     * only as a black screen on the other page.
     */
    val vncStatus: StateFlow<VncStatus> = vncStatusMonitor.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, VncStatus.Off)

    /** Mirrors the Home-page switch so this pane can explain a disabled preview. */
    val vncEnabled: StateFlow<Boolean> = settingsRepository.vncEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Firmware choice, so the pane can explain a silent console: SeaBIOS writes
     * to the VGA screen and never to the serial port.
     */
    val uefiEnabled: StateFlow<Boolean> = settingsRepository.uefiEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            settingsRepository.vmMode.collect { _vmMode.value = it }
        }
        viewModelScope.launch {
            settingsRepository.customImagePath.collect { _customImagePath.value = it }
        }
    }

    /**
     * Note: image selection deliberately has no method here. It lives on the
     * Home page (HomeViewModel.setCustomImage) next to the boot-mode chips,
 * because the image, the VNC switch and the firmware switch are one decision —
 * "what does this VM boot" — and this pane only reports engine state.
 */
    fun startVm() {
        PodroidService.start(context)
    }

    fun stopVm() {
        PodroidService.stop(context)
    }
}
