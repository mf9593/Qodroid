package com.pxkf.qodroid.ui.screens.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pxkf.qodroid.BuildConfig
import com.pxkf.qodroid.R
import com.pxkf.qodroid.data.repository.PortForwardRepository
import com.pxkf.qodroid.data.repository.SettingsRepository
import com.pxkf.qodroid.data.repository.UpdateCheckResult
import com.pxkf.qodroid.data.repository.UpdateInfo
import com.pxkf.qodroid.data.repository.UpdateRepository
import com.pxkf.qodroid.engine.EngineHolder
import com.pxkf.qodroid.engine.EngineSelection
import com.pxkf.qodroid.engine.VmMode
import com.pxkf.qodroid.engine.VmState
import com.pxkf.qodroid.engine.VncStatus
import com.pxkf.qodroid.engine.VncStatusMonitor
import com.pxkf.qodroid.engine.avf.AvfDiagnostics
import com.pxkf.qodroid.engine.avf.AvfFailureGuidance
import com.pxkf.qodroid.engine.avf.AvfReflect
import com.pxkf.qodroid.engine.avf.AvfReport
import com.pxkf.qodroid.engine.guest.GuestShell
import com.pxkf.qodroid.service.PodroidService
import com.pxkf.qodroid.util.NetworkUtils
import com.pxkf.qodroid.util.UptimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Aggregated Home metadata used by the data sections (resources, network,
 * last-session). `Resources` is shown in the meta row in every state; the
 * Network/LastSession sections render conditionally based on vmState.
 */
data class HomeMeta(
    val ramMb: Int,
    val cpus: Int,
    val storageGb: Int,
    val sshEnabled: Boolean,
    val portForwardCount: Int,
    val lastBootDurationMs: Long,
) {
    val resourcesLabel: String = "${formatRam(ramMb)} · $cpus CPU · ${storageGb} GB"

    private fun formatRam(mb: Int): String =
        if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
}

/**
 * Progress of the one-tap guest login started from Home.
 *
 * [Failed] carries its own message because "no session" and "login rejected"
 * have different fixes, and the Home page renders the reason rather than
 * deciding it.
 */
sealed interface LinuxLoginState {
    data object Idle : LinuxLoginState
    data object Working : LinuxLoginState
    data object Done : LinuxLoginState
    data class Failed(val reason: String) : LinuxLoginState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineHolder,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val vncStatusMonitor: VncStatusMonitor,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "HomeViewModel"

        /**
         * How long to wait for the terminal bridge after a start before giving
         * up on auto-login. Generous because it covers a cold boot from
         * Stopped, where the bridge is not expected for a while; the wait costs
         * nothing when auto-login is off.
         */
        const val AUTO_LOGIN_WAIT_MS = 60_000L
        const val AUTO_LOGIN_POLL_MS = 500L
    }

    val vmState: StateFlow<VmState> = engine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    /** True while a stop is tearing the VM down (Running/Starting -> Stopped). */
    val stopping: StateFlow<Boolean> = engine.stopping
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val bootStage: StateFlow<String> = engine.bootStage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // Pushed live by the guest over the host bridge (STATS verb), persisted via
    // SettingsRepository.setLastContainerCount and read back here as a Flow -
    // works identically on both backends, no polling needed.
    val containerCount: StateFlow<Int?> = settingsRepository.lastContainerCount
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Persisted boot profile (built-in Linux vs user-selected Windows image).
     *  Drives the Home "boot mode" selector and the terminal page's content;
     *  the engine reads the same value at VM start. */
    val vmMode: StateFlow<VmMode> = settingsRepository.vmMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmMode.LINUX)

    /** Selected Windows/custom image path — shown as a hint under the boot-mode
     *  selector so a Windows selection with no image is visible, not a boot failure. */
    val customImagePath: StateFlow<String> = settingsRepository.customImagePath
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setVmMode(mode: VmMode) {
        viewModelScope.launch { settingsRepository.setVmMode(mode) }
    }

    fun setCustomImage(path: String) {
        viewModelScope.launch { settingsRepository.setCustomImagePath(path) }
    }

    val vncStatus: StateFlow<VncStatus> = vncStatusMonitor.status

    val vncEnabled: StateFlow<Boolean> = settingsRepository.vncEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setVncEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setVncEnabled(value) }
    }

    val vncExpose: StateFlow<Boolean> = settingsRepository.vncExpose
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setVncExpose(value: Boolean) {
        viewModelScope.launch { settingsRepository.setVncExpose(value) }
    }

    val uefiEnabled: StateFlow<Boolean> = settingsRepository.uefiEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setUefiEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setUefiEnabled(value) }
    }

    /**
     * Live VM-config metadata for the Home hero and data rows. Six settings
     * feeds of differing types, grouped into two same-typed combines (the typed
     * combine overloads top out at five). WhileSubscribed so the ticking
     * resources row stops recomputing when Home is off-screen.
     */
    val meta: StateFlow<HomeMeta> = combine(
        combine(
            settingsRepository.vmRamMb,
            settingsRepository.vmCpus,
            settingsRepository.storageSizeGb,
            settingsRepository.sshEnabled,
        ) { ram, cpus, storage, ssh -> arrayOf(ram, cpus, storage, ssh) },
        combine(
            portForwardRepository.rules.map { it.size }.distinctUntilChanged(),
            settingsRepository.lastBootDurationMs,
        ) { fwd, last -> arrayOf(fwd, last) },
    ) { a, b ->
        HomeMeta(
            a[0] as Int,
            a[1] as Int,
            a[2] as Int,
            a[3] as Boolean,
            b[0] as Int,
            b[1] as Long,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeMeta(512, 2, 8, false, 0, 0))

    /**
     * 1 Hz epoch timestamp while the VM is running (0 while idle). Home uses it
     * to re-render the uptime label every second without polling; derived from
     * the state flow so a VM that exits between ticks stops the clock too.
     */
    @OptIn(FlowPreview::class)
    val uptimeTicker: StateFlow<Long> = engine.state
        .map { it is VmState.Running }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (running) {
                flow {
                    while (true) {
                        emit(System.currentTimeMillis())
                        delay(1000)
                    }
                }
            } else {
                flowOf(0L)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** "Up 1h 2m"-style label for the status row; null when not running. */
    fun uptimeLabel(tick: Long): String? {
        val runningSince = engine.runningSinceMs ?: return null
        val seconds = ((System.currentTimeMillis() - runningSince) / 1000).coerceAtLeast(0)
        return "${context.getString(R.string.up)} ${UptimeFormatter.format(context, seconds)}"
    }

    fun phoneIp(): String = NetworkUtils.localIpv4(context)

    /** Update-check state consumed by the About page's update row. */
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    /** One-shot toast for the manual update check outcome (latest / failed). */
    private val _updateNotice = MutableStateFlow<Int?>(null)
    val updateNotice: StateFlow<Int?> = _updateNotice.asStateFlow()
    fun consumeUpdateNotice() { _updateNotice.value = null }

    /** Automatic update check on launch. */
    val autoCheckUpdates: StateFlow<Boolean> = settingsRepository.autoCheckUpdates
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAutoCheckUpdates(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoCheckUpdates(value) }
    }

    /** Cached once per ViewModel lifetime; the hint and backend logic read it. */
    private val _avfProbe: AvfReport = AvfDiagnostics.probe(context)

    /** Show the AVF hint banner while the feature exists but its permission is
     *  still ungranted, and the user hasn't dismissed it. */
    val showAvfHint: StateFlow<Boolean> = settingsRepository.avfHintDismissed
        .map { dismissed ->
            _avfProbe.featureSupported && !_avfProbe.managePermissionGranted && !dismissed
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when the user asked for AVF but the engine fell back to QEMU. */
    val backendFallbackBanner: StateFlow<Boolean> = combine(
        engine.backendFallback,
        settingsRepository.engineSelection,
    ) { fallback, selection ->
        fallback != null && selection == EngineSelection.AVF
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when the last boot failed on the AVF backend (Error state). */
    val avfBootFailure: StateFlow<Boolean> = vmState
        .map { state -> state is VmState.Error && engine.backendId == "avf" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** One-core vs switch-to-QEMU guidance, based on the current core count. */
    val avfFailureAdvice: StateFlow<AvfFailureGuidance.Advice> = settingsRepository.vmCpus
        .map { cpus -> AvfFailureGuidance.advise(cpus) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AvfFailureGuidance.Advice.SWITCH_TO_QEMU)

    /** GPU-config compatibility step failed while the VM runs on AVF. */
    val avfNetWorkaroundFailed: StateFlow<Boolean> = combine(
        vmState,
        AvfReflect.lastGpuConfigOutcome,
    ) { state, outcome ->
        engine.backendId == "avf" &&
            (state is VmState.Running || state is VmState.Starting) &&
            outcome == AvfReflect.GpuConfigOutcome.FAILED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun useOneCoreAndRetry() {
        viewModelScope.launch {
            settingsRepository.setVmCpus(1)
            restartVm()
        }
    }

    fun switchToQemuAndRetry() {
        viewModelScope.launch {
            settingsRepository.setEngineSelection(EngineSelection.QEMU)
            restartVm()
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            try {
                // Automatic check bypasses the 24h cache so every launch really
                // asks GitHub (the cache gate would otherwise swallow launches
                // within a day of a manual check and never surface a release).
                // A newer release still respects the user's "don't ask again".
                when (val result = updateRepository.checkForUpdate(BuildConfig.VERSION_NAME, force = true)) {
                    is UpdateCheckResult.Available -> _updateInfo.value = result.info
                    // Automatic check: silence "already latest" and failures.
                    else -> Unit
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
            }
        }
    }

    /** Manual check for the About page: always reports a definite outcome. */
    fun refreshUpdateCheck() {
        viewModelScope.launch {
            try {
                when (val result = updateRepository.checkForUpdate(BuildConfig.VERSION_NAME, force = true)) {
                    // A manual tap means "show me the update again" — ignore any
                    // previous "later" dismissal so the dialog actually returns.
                    is UpdateCheckResult.Available -> _updateInfo.value = result.info
                    UpdateCheckResult.UpToDate -> _updateNotice.value = R.string.update_up_to_date
                    UpdateCheckResult.Failed -> _updateNotice.value = R.string.update_check_failed
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                _updateNotice.value = R.string.update_check_failed
            }
        }
    }

    fun dismissUpdate() {
        // "Later" closes this dialog only. A persistent per-version dismissal
        // would silence every future launch check — the exact opposite of the
        // "check on every start" setting — so nothing is written to storage.
        _updateInfo.value = null
    }

    fun dismissAvfHint() {
        viewModelScope.launch { settingsRepository.setAvfHintDismissed(true) }
    }

    /**
     * Boot the VM, and log the guest shell in afterwards when auto-login is on.
     *
     * The guest does not come up with a prompt: `podroid login:` waits for a
     * username and then a password, so until that exchange is answered there is
     * no shell to run anything in — which is also what leaves the Files tab and
     * export unable to do their one round-trip.
     */
    fun startPodroid() {
        PodroidService.start(context)
        if (!autoLoginOnStart.value) return
        loginJob?.cancel()
        loginJob = viewModelScope.launch { awaitShellThenLogin() }
    }

    /**
     * Wait for a shell that can actually be logged into, then log in.
     *
     * Running is not the same as usable: the terminal bridge appears a few
     * seconds after the VM reaches Running, and [GuestShell.ensureLoggedIn] types
     * into a live [TerminalSession]. Firing once on Running would report a login
     * failure that is really only "too early", so both conditions are polled.
     */
    private suspend fun awaitShellThenLogin() {
        _linuxLogin.value = LinuxLoginState.Working
        val deadline = System.currentTimeMillis() + AUTO_LOGIN_WAIT_MS
        var sawSession = false
        while (System.currentTimeMillis() < deadline) {
            val session = engine.terminalSession
            if (session != null && session.isRunning) {
                sawSession = true
                if (withContext(Dispatchers.IO) { guestShell.ensureLoggedIn() }) {
                    _linuxLogin.value = LinuxLoginState.Done
                    return
                }
                // Not a failure yet. ensureLoggedIn() answers whatever prompt is
                // on screen right now; it does not wait for one. A session object
                // appears as soon as the terminal page composes, which is well
                // before the guest has finished booting userspace and printed
                // `podroid login:` — until it has, the transcript is empty and
                // there is nothing to answer, so the call correctly reports false.
                //
                // Giving up on that first false is what produced "login failed,
                // did you change the password" while the very same code logged
                // in fine when the Files tab triggered it seconds later. Keep
                // retrying until the deadline.
            }
            delay(AUTO_LOGIN_POLL_MS)
        }
        _linuxLogin.value = LinuxLoginState.Failed(
            context.getString(
                if (sawSession) R.string.home_login_failed else R.string.home_login_no_session
            )
        )
    }

    /** Whether starting the VM also logs the guest shell in. */
    val autoLoginOnStart: StateFlow<Boolean> = settingsRepository.autoLogin
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoLoginOnStart(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoLogin(value) }
    }

    /** Clear a finished login so its status line goes back to the standing hint. */
    fun resetLinuxLogin() {
        if (_linuxLogin.value !is LinuxLoginState.Working) {
            _linuxLogin.value = LinuxLoginState.Idle
        }
    }

    private val guestShell = GuestShell(engine)

    private var loginJob: kotlinx.coroutines.Job? = null

    private val _linuxLogin = MutableStateFlow<LinuxLoginState>(LinuxLoginState.Idle)
    val linuxLogin: StateFlow<LinuxLoginState> = _linuxLogin.asStateFlow()

    fun stopVm() {
        // An explicit stop ends any boot that was waiting to log in; the status
        // reset in init handles the display, this just stops the polling.
        loginJob?.cancel()
        PodroidService.stop(context)
    }

    fun restartVm() {
        loginJob?.cancel()
        PodroidService.stop(context)
        viewModelScope.launch {
            // Only start if we observed a terminal state within the timeout window.
            // A null result means QEMU is still tearing down — starting over it would
            // race two instances on the same socket files and storage.img.
            val reached = withTimeoutOrNull(10_000) {
                engine.state.first { state ->
                    state is VmState.Stopped || state is VmState.Idle || state is VmState.Error
                }
            }
            if (reached != null) {
                // Restart reuses the same start path, so an auto-login switch
                // that is on has to fire here too or a restarted VM would sit
                // at its login form.
                startPodroid()
            }
        }
    }

    init {
        // Check for updates at launch when the user hasn't disabled it. Runs
        // after a short delay so it never races the first frame. The automatic
        // path is quiet: only an actual newer release pops the dialog.
        viewModelScope.launch {
            if (settingsRepository.autoCheckUpdates.first()) {
                kotlinx.coroutines.delay(3_000L)
                checkForUpdate()
            }
        }
        // The login belongs to the terminal session that performed it. Once that
        // session is gone — VM stopped, crashed, restarted — a lingering "Logged
        // in" would be a claim the app can no longer back, so the status is
        // cleared the moment the engine stops reporting Running.
        //
        // Status only. This must NOT cancel [loginJob]: a start passes through
        // Idle and Starting before reaching Running, so cancelling on "not
        // Running" killed the auto-login wait during the very window it exists
        // to serve — which is why turning the switch on appeared to do nothing.
        // Cancellation belongs to the operations that end a boot deliberately
        // (see stopVm / restartVm).
        viewModelScope.launch {
            engine.state.collect { s ->
                if (s !is VmState.Running) resetLinuxLogin()
            }
        }
    }
}
