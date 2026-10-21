package com.pxkf.qodroid.ui.screens.backup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pxkf.qodroid.R
import com.pxkf.qodroid.data.repository.ContainerBackupFile
import com.pxkf.qodroid.data.repository.ContainerBackupRepository
import com.pxkf.qodroid.data.repository.SettingsRepository
import com.pxkf.qodroid.engine.VmEngine
import com.pxkf.qodroid.engine.VmState
import com.pxkf.qodroid.engine.guest.GuestCommandResult
import com.pxkf.qodroid.engine.guest.GuestShell
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ContainerBackupUiState(
    val vmRunning: Boolean = false,
    val storageAccessEnabled: Boolean = false,
    val guestPath: String = "/var/backups/Qodroid",
    val backupFiles: List<ContainerBackupFile> = emptyList(),
    val backupListError: Boolean = false,
    val containerName: String = "",
    val imageRef: String = "",
)

/** Which artifact a backup run produces. */
enum class BackupKind { CONTAINER, IMAGE, ALL }


/**
 * Progress of a backup the app ran itself.
 *
 * [Failed] carries its own message: every way this can go wrong needs a
 * different action from the user (start the VM, open the terminal, log in, wait
 * longer), and one generic "backup failed" sends them to the wrong place.
 */
sealed interface BackupRunState {
    data object Idle : BackupRunState
    data class Working(val kind: BackupKind) : BackupRunState
    data class Done(val kind: BackupKind, val output: String) : BackupRunState
    data class Failed(val kind: BackupKind, val reason: String) : BackupRunState
}

/**
 * Progress of restoring an image archive into the VM.
 *
 * [Done] with [ok] true carries podman's loaded-image summary; with [ok] false
 * it carries podman's own error, which names the actual problem (file missing,
 * archive corrupt, sharing unmounted).
 */
sealed interface RestoreState {
    data object Idle : RestoreState
    data object Working : RestoreState
    data class Done(val ok: Boolean, val message: String) : RestoreState
}

/**
 * Names the guest reported, plus how the last read went.
 *
 * [scanned] separates "not asked yet" from "asked and there is nothing", which
 * otherwise both arrive as an empty list and would put a "no containers" line on
 * screen before anything had been read.
 */
data class GuestInventory(
    val containers: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val engine: String? = null,
    val working: Boolean = false,
    val scanned: Boolean = false,
    val error: String? = null,
)

/**
 * podman container names: must start with an ASCII alphanumeric and contain
 * only `[a-zA-Z0-9_.-]`. A name like 测试 (valid text, invalid as a container
 * name) passes to podman only to fail there, so it is rejected in the form
 * instead of producing a mid-pull error.
 */
private val CONTAINER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

fun isValidContainerName(name: String): Boolean = CONTAINER_NAME.matches(name)

/**
 * Mirror hosts offered as presets in the save-image section. A stored host not
 * in this set is treated as a custom mirror.
 */
val presetMirrorHosts = listOf(
    "docker.m.daocloud.io",
    "docker.mirrors.ustc.edu.cn",
    "mirror.cc.tencentyun.com",
    "hub-mirror.c.163.com",
)

@HiltViewModel
class ContainerBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ContainerBackupRepository,
    private val settingsRepository: SettingsRepository,
    private val engine: VmEngine,
) : ViewModel() {

    private val _containerName = MutableStateFlow("")
    private val _imageRef = MutableStateFlow("")
    private val _backupFiles = MutableStateFlow<List<ContainerBackupFile>>(emptyList())
    private val _backupListError = MutableStateFlow(false)

    val uiState: StateFlow<ContainerBackupUiState> = combine(
        combine(
            engine.state,
            settingsRepository.storageAccessEnabled,
            _containerName,
            _imageRef,
            _backupFiles,
        ) { vmState, storageAccess, container, image, files ->
            ContainerBackupUiState(
                vmRunning = vmState is VmState.Running,
                storageAccessEnabled = storageAccess,
                guestPath = repository.guestBackupPathLabel(storageAccess),
                backupFiles = files,
                containerName = container,
                imageRef = image,
            )
        },
        _backupListError,
    ) { state, listError ->
        state.copy(backupListError = listError)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContainerBackupUiState())

    init {
        refresh()
        viewModelScope.launch {
            val stored = settingsRepository.imageMirrorRegistry.first()
            val custom = stored.isNotEmpty() && stored !in presetMirrorHosts
            _mirror.value = MirrorState(
                host = stored,
                customEditing = custom,
                customText = if (custom) stored else "",
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.listBackupFiles() }
            }
            result.onSuccess { files ->
                _backupFiles.value = files
                _backupListError.value = false
            }.onFailure {
                _backupListError.value = true
            }
        }
    }

    private val _inventory = MutableStateFlow(GuestInventory())
    val inventory: StateFlow<GuestInventory> = _inventory.asStateFlow()

    /**
     * Which mirror to pull Docker Hub images through. The stored value is the
     * mirror host ("" = direct); a host outside [presetMirrorHosts] is a custom
     * one, remembered so the custom field opens pre-filled.
     */
    data class MirrorState(
        val host: String = "",
        val customEditing: Boolean = false,
        val customText: String = "",
    )

    private val _mirror = MutableStateFlow(MirrorState())
    val mirror: StateFlow<MirrorState> = _mirror.asStateFlow()

    fun selectMirrorPreset(host: String) {
        _mirror.value = _mirror.value.copy(host = host, customEditing = false)
        viewModelScope.launch { settingsRepository.setImageMirrorRegistry(host) }
    }

    fun editCustomMirror() {
        _mirror.value = _mirror.value.copy(customEditing = true)
    }

    fun setCustomMirror(text: String) {
        val t = text.trim()
        _mirror.value = _mirror.value.copy(customText = text, host = t)
        viewModelScope.launch { settingsRepository.setImageMirrorRegistry(t) }
    }

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()


    /**
     * Restore an image archive (a backup-list row) into the VM with `podman load`.
     *
     * Loading is additive — an image is added and nothing existing is touched —
     * so it cannot destroy anything, but it is still a VM round-trip with a real
     * result to report (the loaded image, or podman's error naming why not).
     */
    fun restoreImage(hostPath: String) {
        if (_restoreState.value is RestoreState.Working) return
        viewModelScope.launch {
            _restoreState.value = RestoreState.Working
            val result = withContext(Dispatchers.IO) {
                guestShell.run(
                    withExitStatus(repository.loadImageCommand(hostPath)),
                    timeoutMs = RESTORE_TIMEOUT_MS,
                )
            }
            _restoreState.value = when (result) {
                is GuestCommandResult.Ok -> {
                    val status = EXIT_STATUS.find(result.output)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    val text = stripNotifyIds(result.output).trim()
                    RestoreState.Done(ok = status == 0, message = text)
                }
                GuestCommandResult.NoSession -> RestoreState.Done(
                    ok = false,
                    message = context.getString(R.string.container_backup_err_no_session),
                )
                GuestCommandResult.LoginFailed -> RestoreState.Done(
                    ok = false,
                    message = context.getString(R.string.container_backup_err_login),
                )
                GuestCommandResult.Timeout -> RestoreState.Done(
                    ok = false,
                    message = context.getString(R.string.container_backup_err_timeout),
                )
            }
        }
    }

    /**
     * Put a whole-disk archive back in place: storage.img is replaced by the
     * image inside it.
     *
     * The reverse of a disk image backup, and just as fast, because it too is a
     * single file copy on the Android side. The VM must be stopped — an
     * engine still running holds the old image open and would keep writing into
     * the file being replaced.
     *
     * The one restore that replaces rather than adds: the current disk contents
     * are overwritten for good. That is why it sits behind the same confirmation
     * dialog the image restore uses.
     */

    /**
     * Ask the guest which containers and images it has.
     *
     * A hand-typed name is the one step of a backup that can be wrong without
     * this screen noticing: podman's `no container with name or ID` lands in the
     * terminal transcript, and until the exit status was read back (see
     * [completed]) the screen still called that a finished backup. Reading the
     * names out of the VM replaces the guess with a tap.
     */
    fun scanGuest() {
        if (_inventory.value.working) return
        _inventory.value = _inventory.value.copy(working = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                guestShell.run(repository.inventoryCommand(), timeoutMs = SCAN_TIMEOUT_MS)
            }
            _inventory.value = when (result) {
                is GuestCommandResult.Ok -> {
                    val (containers, images) = repository.parseInventory(result.output)
                    val engine = repository.parseEngine(result.output)
                        .takeIf { it.isNotEmpty() }
                    GuestInventory(
                        containers = containers,
                        images = images,
                        engine = engine,
                        scanned = true,
                    )
                }
                GuestCommandResult.NoSession -> scanFailed(R.string.container_backup_err_no_session)
                GuestCommandResult.LoginFailed -> scanFailed(R.string.container_backup_err_login)
                GuestCommandResult.Timeout -> scanFailed(R.string.container_backup_err_scan_timeout)
            }
        }
    }

    private fun scanFailed(res: Int) =
        GuestInventory(scanned = true, error = context.getString(res))

    fun setContainerName(value: String) {
        _containerName.value = value
    }

    fun setImageRef(value: String) {
        _imageRef.value = value
    }

    fun copyExportCommand(): Boolean {
        val name = _containerName.value.trim()
        if (name.isEmpty()) return false
        copyToClipboard(repository.exportCommand(name))
        return true
    }

    fun copySaveCommand(): Boolean {
        val ref = _imageRef.value.trim()
        if (ref.isEmpty()) return false
        copyToClipboard(repository.saveImageCommand(ref, _mirror.value.host))
        return true
    }

    fun copyListCommand() {
        copyToClipboard(repository.listCommand())
    }

    fun copyAllCommand() {
        copyToClipboard("podroid-backup all")
    }

    /** The export command as text, for the fallback display. */
    fun exportCommandText(): String =
        repository.exportCommand(_containerName.value.trim().ifEmpty { "<name>" })

    /** The save command as text, for the fallback display. */
    fun saveCommandText(): String =
        repository.saveImageCommand(_imageRef.value.trim().ifEmpty { "<image>" }, _mirror.value.host)

    fun listCommandText(): String = repository.listCommand()

    fun allCommandText(): String = "podroid-backup all"

    /**
     * Run the backup now instead of handing the user a command to paste.
     *
     * The commands this page builds are ordinary shell one-liners, and the app
     * already drives a shell of its own — [GuestShell], the same one the Files
     * tab reads the guest filesystem through. Making the user copy, switch to
     * the terminal tab and paste was never technically required; it only ever
     * worked without a running VM, and this path is gated on both the VM and a
     * logged-in shell.
     *
     * The command and its output appear in the terminal as they run. That is
     * deliberate: archiving is slow and interruptible-looking, and watching it
     * happen is what tells the user it is not stuck.
     */
    fun runBackup(kind: BackupKind) {
        val subject = when (kind) {
            BackupKind.CONTAINER -> _containerName.value.trim()
            BackupKind.IMAGE -> _imageRef.value.trim()
            BackupKind.ALL -> ""
        }
        if (subject.isEmpty()) return
        val command = when (kind) {
            BackupKind.CONTAINER -> repository.exportCommand(subject)
            BackupKind.IMAGE -> repository.saveImageCommand(subject, _mirror.value.host)
            BackupKind.ALL -> repository.allCommand()
        }
        runCommand(kind, command)
    }

    /**
     * One-tap backup of whatever the guest has: no name to look up first.
     * Exports every container; reports "nothing exported" when there is nothing
     * yet, rather than pretending a backup happened.
     */
    fun runBackupAll() = runCommand(BackupKind.ALL, repository.allCommand())


    /**
     * Whether the guest already holds a container with this name.
     *
     * Runs [ContainerBackupRepository.containerExistsCommand] and reads its
     * status with [withExitStatus] — the same device the backups use, because
     * [GuestShell] cannot see exit codes on its own.
     *
     * Anything this probe cannot answer is answered as "exists": with no
     * session, or a timed-out probe, or an unreadable status line, the honest
     * thing is to let the real export command produce the honest message, not
     * to report a container missing that may well be present.
     */
    private suspend fun containerExists(name: String): Boolean = withContext(Dispatchers.IO) {
        when (val probe = guestShell.run(
            withExitStatus(repository.containerExistsCommand(name)),
            timeoutMs = 30_000L,
        )) {
            is GuestCommandResult.Ok -> {
                val status = EXIT_STATUS.find(probe.output)?.groupValues?.get(1)?.toIntOrNull()
                if (status == null) true else status == 0
            }
            else -> true
        }
    }

    private fun runCommand(kind: BackupKind, command: String) {
        if (_backupRun.value is BackupRunState.Working) return
        val container = if (kind == BackupKind.CONTAINER) _containerName.value.trim() else ""
        viewModelScope.launch {
            _backupRun.value = BackupRunState.Working(kind)
            // Settle the name before archiving. podman refuses a container that
            // is not there, but only after the export has run — so a typo
            // arrives as a long failure whose last words are podman's terse
            // "no such container", which reads as a broken backup rather than
            // as a wrong name.
            if (container.isNotEmpty() && !containerExists(container)) {
                _backupRun.value = BackupRunState.Failed(
                    kind,
                    context.getString(R.string.container_backup_err_container_missing, container)
                )
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                guestShell.run(
                    withExitStatus(command),
                    timeoutMs = BACKUP_TIMEOUT_MS,
                )
            }
            _backupRun.value = when (result) {
                is GuestCommandResult.Ok -> completed(kind, result.output)
                GuestCommandResult.NoSession -> BackupRunState.Failed(
                    kind,
                    context.getString(R.string.container_backup_err_no_session)
                )
                GuestCommandResult.LoginFailed -> BackupRunState.Failed(
                    kind,
                    context.getString(R.string.container_backup_err_login)
                )
                GuestCommandResult.Timeout -> BackupRunState.Failed(
                    kind,
                    context.getString(R.string.container_backup_err_timeout)
                )
            }
            if (_backupRun.value is BackupRunState.Done) refresh()
        }
    }

    /** Whether a listed backup is a whole-disk archive rather than a container one. */

    fun formatSize(bytes: Long): String = repository.formatSize(bytes)

    fun formatDate(ms: Long): String = repository.formatDate(ms)

    /**
     * Append the command's exit status to its output.
     *
     * [GuestShell] cannot see that status. Its frame is
     * `...; $command; printf <end-marker>`, and the marker printf runs whether
     * the command succeeded or not — so a failed `podman export` still came back
     * as [GuestCommandResult.Ok], carrying podman's complaint as the "output",
     * and this screen reported a finished backup over a folder with no new file
     * in it. The status has to be carried out of the shell explicitly to be seen
     * at all.
     *
     * Only the commands this screen runs get the marker; the ones the user
     * copies stay clean.
     */
    private fun withExitStatus(command: String): String =
        command + "; printf 'PODROID_EXIT=%s\\n' \"\$?\""

    /**
     * Turn a finished run into [BackupRunState.Done] or [BackupRunState.Failed]
     * using the status [withExitStatus] appended.
     *
     * The command's own message is used as the failure text when there is one:
     * podman names the missing container or the unwritable path, which is more
     * use than anything this class could say in its place.
     */
    private fun completed(kind: BackupKind, output: String): BackupRunState {
        val status = EXIT_STATUS.find(output)?.groupValues?.get(1)?.toIntOrNull()
        val text = stripNotifyIds(EXIT_STATUS.replace(output, "").trim('\n', '\r'))
        return when {
            status == 0 -> BackupRunState.Done(kind, text)
            status != null -> BackupRunState.Failed(
                kind,
                when {
                    NO_ENGINE.matches(text.trim()) ->
                        context.getString(R.string.container_backup_err_no_engine)
                    kind == BackupKind.CONTAINER && NO_SUCH_CONTAINER.containsMatchIn(text) ->
                        context.getString(R.string.container_backup_err_container_missing, subjectOf(kind))
                    kind == BackupKind.IMAGE && NO_SUCH_IMAGE.containsMatchIn(text) ->
                        context.getString(R.string.container_backup_err_image_missing, subjectOf(kind))
                    text.isBlank() ->
                        context.getString(R.string.container_backup_err_exit, status)
                    else ->
                        context.getString(R.string.container_backup_err_failed, text)
                }
            )
            // No status line at all. It is written between the command and the
            // end marker, so its absence means the output was cut short and
            // nothing about this run can be trusted — which is not something to
            // call a success, the mistake being fixed here.
            else -> BackupRunState.Failed(
                kind,
                context.getString(R.string.container_backup_err_no_output)
            )
        }
    }

    private val EXIT_STATUS = Regex("(?m)^PODROID_EXIT=(\\d+)$")

    /** podman's own words for a name that resolves to nothing. */
    private val NO_SUCH_CONTAINER = Regex("no such container", RegexOption.IGNORE_CASE)
    private val NO_SUCH_IMAGE = Regex("no such image", RegexOption.IGNORE_CASE)

    /** [ContainerBackupRepository.NO_ENGINE_MSG], sent when the guest has neither. */
    private val NO_ENGINE = Regex("no_container_engine")

    /** The name or reference the run acted on, for failure messages. */
    private fun subjectOf(kind: BackupKind): String = when (kind) {
        BackupKind.CONTAINER -> _containerName.value.trim()
        BackupKind.IMAGE -> _imageRef.value.trim()
        else -> ""
    }

    /**
     * Drop trailing notification-ID lines from a backup's output.
     *
     * The guest's podroid-backup calls a podroid-notify helper on success and on
     * failure, and that helper prints a numeric notification ID to stdout —
     * which the user observed glued after podman's error, incrementing on every
     * run (…729, …730, …731). It always trails the meaningful line and is never
     * part of it, so a trailing run of digit-only lines is dropped. The helper's
     * output is silenced at the source in podroid-backup as well; this handles
     * images built before that change.
     */
    private fun stripNotifyIds(output: String): String =
        output.trim('\n', '\r').lines()
            .dropLastWhile { it.isBlank() || it.trim().all(Char::isDigit) }
            .joinToString("\n")
            .trimEnd()

    private val guestShell = GuestShell(engine)

    private val _backupRun = MutableStateFlow<BackupRunState>(BackupRunState.Idle)
    val backupRun: StateFlow<BackupRunState> = _backupRun.asStateFlow()

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("podroid-backup", text))
    }

    private companion object {
        /**
         * Far above GuestShell's 8 s default. Archiving writes every byte of the
         * container's filesystem, so the default would abort nearly every real
         * backup and report nothing about how far it got.
         */
        const val BACKUP_TIMEOUT_MS = 600_000L

        /** Long enough for `podman ps`/`podman images` on a cold daemon. */
        const val SCAN_TIMEOUT_MS = 30_000L

        /** Loading a large image archive back into the VM takes a while. */
        const val RESTORE_TIMEOUT_MS = 600_000L
    }
}
