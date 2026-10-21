/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.ui.screens.home

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pxkf.qodroid.R
import com.pxkf.qodroid.data.repository.SettingsRepository
import com.pxkf.qodroid.engine.EngineHolder
import com.pxkf.qodroid.engine.VmMode
import com.pxkf.qodroid.engine.guest.GuestCommandResult
import com.pxkf.qodroid.engine.guest.GuestLsParser
import com.pxkf.qodroid.engine.guest.GuestShell
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the guest-filesystem pane is showing. */
sealed interface GuestFilesState {
    data object Idle : GuestFilesState
    data object Loading : GuestFilesState
    data object Empty : GuestFilesState
    data class Loaded(val entries: List<GuestLsParser.Entry>) : GuestFilesState
    data class Failed(val reason: String) : GuestFilesState
}

/**
 * Backs the guest-filesystem half of the file manager page.
 *
 * Reads go through the guest shell because there is no filesystem channel in
 * (see [GuestShell]) — so every listing is a command round-trip, which is why
 * each directory is loaded on demand rather than walked.
 *
 * Linux guests only. A Windows guest has no shell to run these in, so the page
 * says so rather than showing an empty list that looks like a bug.
 */
@HiltViewModel
class GuestFilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineHolder,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val shell = GuestShell(engine)

    private val _state = MutableStateFlow<GuestFilesState>(GuestFilesState.Idle)
    val state: StateFlow<GuestFilesState> = _state.asStateFlow()

    private val _path = MutableStateFlow("/")
    val path: StateFlow<String> = _path.asStateFlow()

    private val _stack = MutableStateFlow(listOf<String>())
    val stack: StateFlow<List<String>> = _stack.asStateFlow()

    private val _supported = MutableStateFlow<Boolean?>(null)
    val supported: StateFlow<Boolean?> = _supported.asStateFlow()

    /** State of the host Downloads → /mnt/downloads share, for the Mounts tab. */
    enum class DownloadsShareState {
        Checking, NoSession, NotEnabled, NotMounted, Mounted,
    }

    private val _mountState = MutableStateFlow(DownloadsShareState.Checking)
    val mountState: StateFlow<DownloadsShareState> = _mountState.asStateFlow()

    /** Host side of the share — the real Android Downloads folder. */
    val hostDownloadsPath: String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    /** Where that folder appears inside the Linux guest (see podroid-bootstrap). */
    val guestDownloadsPath: String = GUEST_DOWNLOADS_PATH

    /**
     * State of the host Downloads tree, shown under the mount status on the
     * Mounts tab.
     *
     * This is the phone's own directory — no guest round-trip — so it lists
     * even while the VM is stopped, which is exactly when organising what the
     * next boot will share is most useful. Rows map into [GuestLsParser.Entry]
     * so the pane reuses the guest listing's rendering and folder-first order.
     */
    sealed interface HostTreeState {
        data object Idle : HostTreeState
        data object Loading : HostTreeState
        data object Empty : HostTreeState
        data class Loaded(val entries: List<GuestLsParser.Entry>) : HostTreeState
        data class Failed(val reason: String) : HostTreeState
    }

    private val _hostState = MutableStateFlow<HostTreeState>(HostTreeState.Idle)
    val hostState: StateFlow<HostTreeState> = _hostState.asStateFlow()

    private val _hostPath = MutableStateFlow(hostDownloadsPath)
    val hostPath: StateFlow<String> = _hostPath.asStateFlow()

    private val _hostStack = MutableStateFlow(listOf<String>())
    val hostStack: StateFlow<List<String>> = _hostStack.asStateFlow()

    /** Re-list the directory the Mounts tab is currently showing. */
    fun refreshHost() = loadHost(_hostPath.value)

    /**
     * List [dir] on the host with the same folder-first ordering as the guest
     * parser, so both tabs of the page navigate identically. `listFiles()`
     * returns null without the all-files grant, which surfaces as Failed.
     */
    fun loadHost(dir: String) {
        viewModelScope.launch {
            _hostState.value = HostTreeState.Loading
            val entries = withContext(Dispatchers.IO) {
                val file = File(dir)
                if (!file.isDirectory) return@withContext null
                file.listFiles()?.map { f ->
                    GuestLsParser.Entry(
                        name = f.name,
                        isDirectory = f.isDirectory,
                        isLink = false,
                        // A directory's size is its inode size, not its contents —
                        // same convention as the guest parser, so -1 hides it.
                        sizeBytes = if (f.isDirectory) -1 else f.length(),
                        mode = if (f.isDirectory) "d" else "-",
                    )
                }
            }
            if (entries == null) {
                _hostState.value = HostTreeState.Failed(
                    context.getString(R.string.mounts_host_unreadable)
                )
            } else {
                val sorted = entries.sortedWith(
                    compareByDescending<GuestLsParser.Entry> { it.isDirectory }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                        .thenBy { it.name },
                )
                _hostState.value = if (sorted.isEmpty()) HostTreeState.Empty
                else HostTreeState.Loaded(sorted)
            }
        }
    }

    fun enterHost(dir: String) {
        _hostStack.value = _hostStack.value + _hostPath.value
        _hostPath.value = dir
        loadHost(dir)
    }

    /** Up one level; at the Downloads root there is nowhere higher to go. */
    fun goUpHost() {
        val s = _hostStack.value
        if (s.isEmpty()) return
        _hostPath.value = s.last()
        _hostStack.value = s.dropLast(1)
        loadHost(_hostPath.value)
    }

    /**
     * Export outcome, split by cause so the message can name the fix.
     *
     * A bare "export failed" would be useless here: the overwhelmingly common
     * reason is that Downloads sharing is off, and the guest then writes happily
     * into its own rootfs directory where nobody on Android can ever see it.
     */
    sealed interface ExportState {
        data object Idle : ExportState
        data object Working : ExportState
        data class Done(val name: String, val ok: Boolean, val reason: Reason) : ExportState

        enum class Reason { Exported, NotShared, Failed, NoSession }
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    /**
     * Copy [guestPath] out to the host's Downloads under `Qodroid/`.
     *
     * Goes through the 9p share rather than streaming bytes back over the shell:
     * the share is already mounted at [guestDownloadsPath] and points at the very
     * directory the user expects the file to land in, so a `cp` there is a copy
     * onto the phone — no base64 round-trip through a 200-row terminal scrollback,
     * and no size limit to work around.
     *
     * The mount is verified *before* copying. Without that check the copy still
     * succeeds when sharing is off (bootstrapping does `mkdir -p /mnt/downloads`
     * unconditionally), landing the file on the guest's own rootfs where it looks
     * exported and is not.
     */
    fun exportToDownloads(guestPath: String, isDirectory: Boolean) {
        val name = guestPath.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return
        viewModelScope.launch {
            _exportState.value = ExportState.Working
            val target = "$HOST_EXPORT_DIR/$name"

            // Same probe as checkMount(): grep rather than mountpoint(1), which
            // BusyBox builds do not all provide.
            val mounted = withContext(Dispatchers.IO) {
                shell.run("grep ' $guestDownloadsPath ' /proc/mounts 2>&1")
            }
            if (mounted !is GuestCommandResult.Ok) {
                _exportState.value = ExportState.Done(
                    name, false,
                    if (mounted is GuestCommandResult.NoSession) ExportState.Reason.NoSession
                    else ExportState.Reason.NotShared,
                )
                return@launch
            }
            if (mounted.output.isBlank()) {
                _exportState.value =
                    ExportState.Done(name, false, ExportState.Reason.NotShared)
                return@launch
            }

            // A stale file of the same name would make `cp -r` nest inside it
            // rather than replace it, so the destination is cleared first.
            val copy = if (isDirectory) {
                "mkdir -p ${quote(HOST_EXPORT_DIR)} && rm -rf ${quote(target)} && " +
                    "cp -r ${quote(guestPath)} ${quote(target)} && chmod -R a+rX ${quote(target)}"
            } else {
                "mkdir -p ${quote(HOST_EXPORT_DIR)} && cp -f ${quote(guestPath)} ${quote(target)} && " +
                    "chmod a+r ${quote(target)}"
            }
            withContext(Dispatchers.IO) { shell.run(copy) }

            // Verify by size/count rather than exit status: the marker framing
            // swallows the command's exit code, and a byte count is enough to
            // catch a truncated or never-written copy.
            val verify = if (isDirectory) {
                "find ${quote(guestPath)} | wc -l; find ${quote(target)} | wc -l"
            } else {
                "wc -c < ${quote(guestPath)}; wc -c < ${quote(target)}"
            }
            val result = withContext(Dispatchers.IO) { shell.run(verify) }
            val ok = result is GuestCommandResult.Ok &&
                result.output.trim().lines().mapNotNull { it.trim().toLongOrNull() }.let {
                    it.size >= 2 && it[it.size - 2] == it[it.size - 1]
                }
            _exportState.value = ExportState.Done(
                name, ok,
                if (ok) ExportState.Reason.Exported else ExportState.Reason.Failed,
            )
        }
    }

    fun clearExportState() { _exportState.value = ExportState.Idle }

    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    /**
     * Delete a file (or folder) on the host Downloads tree, from the Mounts tab.
     *
     * The host side is the phone's own folder, so no guest round-trip is needed
     * — but the all-files grant is what makes [File.delete] land, and a delete
     * that silently did nothing would be worse than one that reported it.
     */
    fun deleteHost(path: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val f = File(path)
                when {
                    !f.exists() -> false
                    f.isDirectory -> f.deleteRecursively()
                    else -> f.delete()
                }
            }
            _deleteError.value = if (ok) null
            else context.getString(R.string.mounts_delete_failed, path)
            refreshHost()
        }
    }

    fun clearDeleteError() { _deleteError.value = null }

    /**
     * Probe whether the Downloads share is actually mounted in the guest.
     *
     * Two gates decide the answer before the guest is even asked: the Settings
     * toggle and the Android all-files grant (the engine adds the virtio-9p
     * device only when both hold). With both open, the mount line is looked up
     * in /proc/mounts — `grep` exits non-zero on no match, but the framing only
     * needs the empty output, so NotMounted is just "no line appeared".
     */
    fun checkMount() {
        viewModelScope.launch {
            val enabled = settingsRepository.storageAccessEnabled.first()
            val hasGrant = hasDownloadsPermission()
            if (!enabled || !hasGrant) {
                _mountState.value = DownloadsShareState.NotEnabled
                return@launch
            }
            _mountState.value = DownloadsShareState.Checking
            val result = withContext(Dispatchers.IO) {
                shell.run("grep ' $guestDownloadsPath ' /proc/mounts 2>&1")
            }
            _mountState.value = when (result) {
                is GuestCommandResult.Ok ->
                    if (result.output.isNotBlank()) DownloadsShareState.Mounted
                    else DownloadsShareState.NotMounted
                GuestCommandResult.NoSession -> DownloadsShareState.NoSession
                else -> DownloadsShareState.NotMounted
            }
        }
    }

    /** Jump the Files tab straight to the shared Downloads folder. */
    fun openDownloads() {
        _stack.value = listOf()
        _path.value = guestDownloadsPath
        load(guestDownloadsPath)
    }

    private fun hasDownloadsPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Resolve whether guest browsing applies, so the page can explain itself.
     *
     * Deliberately does not load anything: opening the tab must not fire a
     * command into the guest. Listing is on demand — tap a folder, tap refresh,
     * or tap the Idle/Failed hint. [GuestShell.run] completes the login
     * handshake before sending anything, so an explicit load that races a
     * mid-sequence login is retried rather than shown as a failure.
     */
    fun resolveSupport() {
        viewModelScope.launch {
            val mode = settingsRepository.vmMode.first()
            _supported.value = mode == VmMode.LINUX
            if (_supported.value == false) {
                _state.value = GuestFilesState.Idle
                _stack.value = listOf()
                _path.value = "/"
            }
        }
    }

    /** User-tapped retry / refresh. */
    fun refresh() {
        if (_supported.value == null) resolveSupport()
        load(_path.value)
    }

    fun load(dir: String) {
        viewModelScope.launch { loadInternal(dir, 0) }
    }

    /**
     * [GuestShell] answers whatever prompt is on screen now; it does not wait for
     * one. On a session whose login is mid-sequence (the banner visible, or the
     * username just typed) it reports [GuestCommandResult.LoginFailed] immediately
     * — even though the guest keeps logging in behind it. Retrying a moment later
     * finds the completed shell and succeeds, so LoginFailed is retried up to a
     * bound instead of being shown as permanent.
     */
    private suspend fun loadInternal(dir: String, attempt: Int) {
        _state.value = GuestFilesState.Loading
        val result = withContext(Dispatchers.IO) {
            shell.run("ls -la ${quote(dir)} 2>&1")
        }
        when (result) {
            is GuestCommandResult.Ok -> {
                val entries = GuestLsParser.parse(result.output)
                val lines = result.output.trim().lines().filter { it.isNotBlank() }
                _state.value = when {
                    entries.isNotEmpty() -> GuestFilesState.Loaded(entries)
                    // `ls` prints nothing but a `total N` header for a folder with
                    // no entries; that alone counts as genuinely empty.
                    lines.isEmpty() || (lines.size <= 1 && lines.first().startsWith("total")) ->
                        GuestFilesState.Empty
                    // Any other output that fails to parse means the shell answered
                    // with something that is not an `ls` listing — reporting that
                    // as "empty folder" would be a lie, so the raw output is shown.
                    else -> GuestFilesState.Failed(
                        context.getString(
                            R.string.guest_files_unparseable,
                            lines.take(3).joinToString("\n"),
                        )
                    )
                }
            }
            GuestCommandResult.NoSession -> _state.value = GuestFilesState.Failed(
                context.getString(R.string.guest_files_no_session)
            )
            GuestCommandResult.LoginFailed -> if (attempt < MAX_LOGIN_RETRIES) {
                delay(LOGIN_RETRY_DELAY_MS)
                loadInternal(dir, attempt + 1)
            } else {
                _state.value = GuestFilesState.Failed(
                    context.getString(R.string.guest_files_login_failed)
                )
            }
            GuestCommandResult.Timeout -> _state.value = GuestFilesState.Failed(
                context.getString(R.string.guest_files_timeout)
            )
        }
    }

    fun enter(dir: String) {
        _stack.value = _stack.value + _path.value
        _path.value = dir
        load(dir)
    }

    /** Up one level; at "/" there is nowhere higher to go. */
    fun goUp() {
        val s = _stack.value
        if (s.isEmpty()) return
        _path.value = s.last()
        _stack.value = s.dropLast(1)
        load(_path.value)
    }

    fun canGoUp(): Boolean = _stack.value.isNotEmpty()

    /**
     * Single-quote a guest path.
     *
     * Guest paths come from `ls` output rather than user input, but quoting is
     * still the difference between a listing that works and a shell that
     * misinterprets a directory whose name contains a space or a glob.
     */
    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    companion object {
        /** Kept in sync with build-rootfs podroid-bootstrap / podroid-downloads. */
        private const val GUEST_DOWNLOADS_PATH = "/mnt/downloads"

        /**
         * Where exports land inside the share: the host's Download/Qodroid/,
         * i.e. /storage/emulated/0/Download/Qodroid/.
         *
         * A subdirectory rather than the Downloads root so repeated exports
         * cannot bury the user's own files, and so it is obvious later which
         * folder came out of the VM. Capitalised to match the folder the user
         * sees in a file manager — the share is case-preserving, so the mixed
         * case is what actually shows up on the host.
         */
        private const val HOST_EXPORT_DIR = GUEST_DOWNLOADS_PATH + "/Qodroid"

        /**
         * How long to keep retrying a listing whose only obstacle is a login
         * still in progress. The shell appears long before the guest is fully
         * booted, and the login itself takes under a second once its prompt is
         * on screen, so these bounds are generous but not open-ended.
         */
        private const val MAX_LOGIN_RETRIES = 8
        private const val LOGIN_RETRY_DELAY_MS = 1_000L
    }
}