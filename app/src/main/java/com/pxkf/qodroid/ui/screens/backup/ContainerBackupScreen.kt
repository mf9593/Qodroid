package com.pxkf.qodroid.ui.screens.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pxkf.qodroid.R
import com.pxkf.qodroid.ui.components.AdaptiveContainer
import com.pxkf.qodroid.ui.components.PodroidGhostButton
import com.pxkf.qodroid.ui.components.PodroidListRow
import com.pxkf.qodroid.ui.components.PodroidPrimaryButton
import com.pxkf.qodroid.ui.components.PodroidSectionLabel
import com.pxkf.qodroid.ui.components.PodroidTopBar
import com.pxkf.qodroid.ui.theme.PodroidTokens
import kotlinx.coroutines.launch

/** Which artifact a run is about, or null when nothing is running. */
private fun BackupRunState.kindOrNull(): BackupKind? = when (this) {
    is BackupRunState.Working -> kind
    is BackupRunState.Done -> kind
    is BackupRunState.Failed -> kind
    else -> null
}

/**
 * Outcome of an app-run backup.
 *
 * Grey for success, matching this page's treatment of the rest of its status
 * text; red is kept for the failure that needs a user action to clear.
 */
@Composable
private fun BackupRunStatus(run: BackupRunState) {
    when (run) {
        is BackupRunState.Idle -> Unit
        is BackupRunState.Working -> {
            Text(
                text = stringResource(R.string.container_backup_working),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is BackupRunState.Done -> {
            val summary = run.output.lineSequence().lastOrNull { it.isNotBlank() } ?: ""
            // podroid-backup all always exits 0, so "backup finished" alone would
            // read as success over a guest with nothing in it. A summary line of
            // `done: 0 ok` means the run completed and backed up nothing.
            val text = if (run.kind == BackupKind.ALL && summary.startsWith("done: 0 ok")) {
                stringResource(R.string.container_backup_none_all)
            } else {
                stringResource(R.string.container_backup_done, summary)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is BackupRunState.Failed -> Text(
            text = run.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** The "what does this actually do" note under each action. */

@Composable
private fun BackupHint(res: Int) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The usage notes: what each backup type produces, and how to bring it back.
 * Kept on the page rather than behind a dialog, because this is the one thing a
 * first-time user needs to read and there is no other place on the page that
 * says it.
 */
@Composable
private fun BackupExplList() {
    listOf(
        R.string.container_backup_use_expl_1,
        R.string.container_backup_use_expl_2,
        R.string.container_backup_use_expl_3,
    ).forEach { res ->
        Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PodroidTokens.Spacing.XS),
        )
    }
}

/** Outcome of restoring an image archive into the VM. */
@Composable
private fun RestoreStatus(state: RestoreState) {
    when (state) {
        is RestoreState.Idle -> Unit
        is RestoreState.Working -> Text(
            text = stringResource(R.string.container_backup_restore_working),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is RestoreState.Done -> {
            if (state.ok) {
                Text(
                    text = stringResource(R.string.container_backup_restore_ok, state.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.container_backup_restore_failed, state.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerBackupScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: ContainerBackupViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val run by viewModel.backupRun.collectAsStateWithLifecycle()
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()
    val mirror by viewModel.mirror.collectAsStateWithLifecycle()
    var restoreTarget by remember { mutableStateOf<String?>(null) }
    val mirrorPresets = listOf(
        "" to R.string.container_backup_mirror_direct,
        "docker.m.daocloud.io" to R.string.container_backup_mirror_daocloud,
        "docker.mirrors.ustc.edu.cn" to R.string.container_backup_mirror_ustc,
        "mirror.cc.tencentyun.com" to R.string.container_backup_mirror_tencent,
        "hub-mirror.c.163.com" to R.string.container_backup_mirror_netease,
    )
    // The selected dropdown row: remembered per composition, and closed again
    // by choosing a row.
    var mirrorExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.container_backup_copied)
    val working = run is BackupRunState.Working
    // A backup needs a live shell; the copy path does not, so it stays available
    // when the VM is down rather than greyed out with everything else.
    val canRun = ui.vmRunning && !working

    Scaffold(
        topBar = {
            PodroidTopBar(
                title = stringResource(R.string.container_backup_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PodroidTokens.Spacing.XL, vertical = PodroidTokens.Spacing.LG),
                verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
            ) {
                Text(
                    text = stringResource(R.string.container_backup_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PodroidSectionLabel(stringResource(R.string.container_backup_location))
                PodroidListRow(
                    label = stringResource(R.string.container_backup_guest_path),
                    value = ui.guestPath,
                    mono = true,
                )
                if (ui.storageAccessEnabled) {
                    PodroidListRow(
                        label = stringResource(R.string.container_backup_phone_path),
                        value = stringResource(R.string.container_backup_phone_path_value),
                        mono = true,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.container_backup_downloads_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = PodroidTokens.Amber,
                    )
                }

                // Read before writing: the names a backup needs are already in the
                // VM, and asking for them here is what stops a typo from reaching
                // podman — where the only complaint goes to the terminal.
                PodroidSectionLabel(stringResource(R.string.container_backup_scan_title))
                Text(
                    text = stringResource(R.string.container_backup_scan_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_scan),
                    onClick = viewModel::scanGuest,
                    enabled = ui.vmRunning && !inventory.working,
                    modifier = Modifier.fillMaxWidth(),
                )
                val scanError = inventory.error
                if (scanError != null) {
                    Text(
                        text = scanError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (inventory.working) {
                    Text(
                        text = stringResource(R.string.container_backup_scan_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (inventory.scanned &&
                    inventory.containers.isEmpty() &&
                    inventory.images.isEmpty()
                ) {
                    Text(
                        text = stringResource(R.string.container_backup_scan_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val engine = inventory.engine
                if (engine != null) {
                    // Which daemon the VM reported. A VM can hold podman and
                    // docker together and only one holds the user's containers,
                    // so naming it here is what answers whether docker is in use.
                    Text(
                        text = stringResource(R.string.container_backup_engine, engine),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (inventory.containers.isNotEmpty()) {
                    PodroidSectionLabel(stringResource(R.string.container_backup_scan_containers))
                    inventory.containers.forEach { name ->
                        PodroidListRow(
                            label = name,
                            mono = true,
                            divider = false,
                            onClick = { viewModel.setContainerName(name) },
                        )
                    }
                }
                if (inventory.images.isNotEmpty()) {
                    PodroidSectionLabel(stringResource(R.string.container_backup_scan_images))
                    inventory.images.forEach { ref ->
                        PodroidListRow(
                            label = ref,
                            mono = true,
                            divider = false,
                            onClick = { viewModel.setImageRef(ref) },
                        )
                    }
                }
                if (inventory.scanned && !inventory.working &&
                    inventory.containers.isEmpty() && inventory.images.isNotEmpty()
                ) {
                    // Images but no containers: nothing to tap, so the name field
                    // stays empty and the export has nothing to act on.
                    BackupHint(R.string.container_backup_scan_no_containers)
                }

                PodroidSectionLabel(stringResource(R.string.container_backup_export))
                // Each section shows only its own run's outcome: a failed save is
                // a save failure, and it is rendered under 保存镜像 — not here,
                // which is where a single page-wide status line used to drop it.
                // Back-up-all lands here too: it is a container export.
                if (run.kindOrNull() == BackupKind.CONTAINER || run.kindOrNull() == BackupKind.ALL) {
                    BackupRunStatus(run)
                }
                if (!ui.vmRunning) {
                    Text(
                        text = stringResource(R.string.container_backup_vm_stopped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = ui.containerName,
                    onValueChange = viewModel::setContainerName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.container_backup_container_name)) },
                    singleLine = true,
                )
                if (ui.containerName.isNotBlank() && !isValidContainerName(ui.containerName)) {
                    Text(
                        text = stringResource(R.string.container_backup_invalid_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                BackupHint(R.string.container_backup_export_hint)
                PodroidPrimaryButton(
                    text = if (working && run.kindOrNull() == BackupKind.CONTAINER) {
                        stringResource(R.string.container_backup_working)
                    } else {
                        stringResource(R.string.container_backup_run_export)
                    },
                    onClick = { viewModel.runBackup(BackupKind.CONTAINER) },
                    enabled = canRun && isValidContainerName(ui.containerName),
                )
                // The closest thing to a one-tap backup: no name to look up. It
                // is a ghost button so the page still leads with the explicit
                // named export, but it needs no field filled in.
                PodroidGhostButton(
                    text = if (working && run.kindOrNull() == BackupKind.ALL) {
                        stringResource(R.string.container_backup_working)
                    } else {
                        stringResource(R.string.container_backup_run_all)
                    },
                    onClick = viewModel::runBackupAll,
                    enabled = canRun,
                    modifier = Modifier.fillMaxWidth(),
                )
                BackupHint(R.string.container_backup_all_hint)
                PodroidSectionLabel(stringResource(R.string.container_backup_save_image))
                if (run.kindOrNull() == BackupKind.IMAGE) BackupRunStatus(run)
                OutlinedTextField(
                    value = ui.imageRef,
                    onValueChange = viewModel::setImageRef,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.container_backup_image_ref)) },
                    placeholder = { Text(stringResource(R.string.container_backup_image_placeholder)) },
                    singleLine = true,
                )
                // Mirror source: pull Docker Hub images through a mirror. The
                // selected host is persisted and applied when the save command
                // pulls the image.
                Text(
                    text = stringResource(R.string.container_backup_mirror),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Dropdown: pick the mirror. The selection is persisted and
                // applied when the save command pulls the image. A dropdown
                // rather than a list of rows — five always-visible options
                // turned this into the longest section on the page.
                ExposedDropdownMenuBox(
                    expanded = mirrorExpanded,
                    onExpandedChange = { mirrorExpanded = it },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    ) {
                        OutlinedTextField(
                            value = if (mirror.host.isEmpty()) {
                                stringResource(R.string.container_backup_mirror_none)
                            } else {
                                mirror.host
                            },
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.container_backup_mirror)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = mirrorExpanded,
                                )
                            },
                            singleLine = true,
                        )
                        ExposedDropdownMenu(
                            expanded = mirrorExpanded,
                            onDismissRequest = { mirrorExpanded = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            mirrorPresets.forEach { (host, labelRes) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(labelRes)) },
                                    trailingIcon = {
                                        if (mirror.host == host) Text("✓") else {}
                                    },
                                    onClick = {
                                        viewModel.selectMirrorPreset(host)
                                        mirrorExpanded = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.container_backup_mirror_custom))
                                },
                                trailingIcon = {
                                    if (mirror.customEditing) Text("✓") else {}
                                },
                                onClick = {
                                    viewModel.editCustomMirror()
                                    mirrorExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PodroidTokens.Spacing.XS))
                // The dropdown shows the picked value, but repeat the applied
                // host here so "which mirror is this actually using" is
                // answerable without opening the menu again.
                Text(
                    text = if (mirror.host.isEmpty()) {
                        stringResource(R.string.container_backup_mirror_none)
                    } else {
                        stringResource(R.string.container_backup_mirror_using, mirror.host)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (mirror.customEditing) {
                    OutlinedTextField(
                        value = mirror.customText,
                        onValueChange = viewModel::setCustomMirror,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.container_backup_mirror_custom)) },
                        placeholder = {
                            Text(stringResource(R.string.container_backup_mirror_custom_placeholder))
                        },
                        singleLine = true,
                    )
                }
                BackupHint(R.string.container_backup_mirror_hint)
                BackupHint(R.string.container_backup_save_image_hint)
                PodroidPrimaryButton(
                    text = if (working && run.kindOrNull() == BackupKind.IMAGE) {
                        stringResource(R.string.container_backup_working)
                    } else {
                        stringResource(R.string.container_backup_run_save)
                    },
                    onClick = { viewModel.runBackup(BackupKind.IMAGE) },
                    enabled = canRun && ui.imageRef.isNotBlank(),
                )
                // Fallback, not the main path. Every backup above runs itself;
                // these exist for the cases this page cannot cover on its own —
                // a command worth reading before running, or one to paste into a
                // shell outside the app. Labelled as secondary so the page's
                // primary action stays the obvious one.
                PodroidSectionLabel(stringResource(R.string.container_backup_fallback))
                Text(
                    text = stringResource(R.string.container_backup_fallback_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Buttons, not text blocks: the command itself is what gets
                // copied, so the page stays about what you are about to run
                // rather than dumping every one-liner on screen. Tapping copies;
                // the command itself is still readable in the terminal once a
                // backup runs.
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_copy_export),
                    onClick = {
                        if (viewModel.copyExportCommand()) {
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        }
                    },
                    enabled = ui.containerName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_copy_save),
                    onClick = {
                        if (viewModel.copySaveCommand()) {
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        }
                    },
                    enabled = ui.imageRef.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_copy_list),
                    onClick = {
                        viewModel.copyListCommand()
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_copy_all),
                    onClick = {
                        viewModel.copyAllCommand()
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.container_backup_terminal_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )

                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                PodroidSectionLabel(stringResource(R.string.container_backup_on_phone))
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_refresh),
                    onClick = viewModel::refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ui.backupListError) {
                    Text(
                        text = stringResource(R.string.container_backup_list_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (ui.backupFiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.container_backup_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ui.backupFiles.forEach { file ->
                        PodroidListRow(
                            label = file.name,
                            mono = true,
                            rightSlot = {
                                // rightSlot displaces `value` in PodroidListRow, so
                                // the size and date have to come along in it; put
                                // them in `value` and they are not drawn at all.
                                Column(horizontalAlignment = Alignment.End) {
                                    TextButton(onClick = { restoreTarget = file.absolutePath }) {
                                        Text(stringResource(R.string.container_backup_restore_image))
                                    }
                                    Text(
                                        text = "${viewModel.formatSize(file.sizeBytes)} · ${viewModel.formatDate(file.lastModifiedMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
                RestoreStatus(restoreState)

                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                PodroidSectionLabel(stringResource(R.string.container_backup_use_expl_title))
                BackupExplList()
            }
        }
    }

    // Restore confirmation. An image archive only adds, so it cannot destroy
    // anything — but the loaded archive takes over the tag it names, so the
    // file name is worth a look before the tap.

    restoreTarget?.let { target ->
        val name = target.substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(stringResource(R.string.container_backup_restore_confirm_title, name)) },
            text = {
                Text(stringResource(R.string.container_backup_restore_confirm_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreImage(target)
                    restoreTarget = null
                }) {
                    Text(stringResource(R.string.container_backup_restore_image))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
