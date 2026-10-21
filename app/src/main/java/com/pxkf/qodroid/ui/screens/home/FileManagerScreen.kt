package com.pxkf.qodroid.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pxkf.qodroid.R
import com.pxkf.qodroid.engine.guest.GuestLsParser
import com.pxkf.qodroid.ui.theme.PodroidTokens
import java.util.Locale

/**
 * File manager page (pager page 4) — the VM's own filesystem, and nothing else.
 *
 * The Android Downloads tree this page used to also offer was removed: the app
 * is a VM, and browsing the phone's own filesystem inside it duplicated whatever
 * file manager the user already had, while the interesting tree (inside the
 * guest) had been sharing the page with it. Image picking moved to the Home
 * page's picker dialog, so nothing was lost with the Android half.
 *
 * Linux profile only. Reading the guest filesystem means running commands in its
 * shell — there is no filesystem channel in (see
 * [com.pxkf.qodroid.engine.guest.GuestShell]) — and a Windows guest has no
 * shell, so that case says so instead of showing an empty list.
 *
 * Two tabs, because the two questions are unrelated and neither is a mode of the
 * other: **Files** browses the guest tree, **Mounts** reports on the host
 * Downloads folder shared in at /mnt/downloads. Mounting is a host-side setting
 * whose result shows up inside the guest, so putting it in the browser meant
 * either hiding it behind a folder that is simply missing when the share is off,
 * or explaining an absent directory as an error.
 */
@Composable
fun FileManagerScreen(
    onCdInto: (String) -> Unit,
    embedded: Boolean = false,
    guestViewModel: GuestFilesViewModel = hiltViewModel(),
) {
    // Re-resolve on every visit: the boot mode can change while this page sits
    // composed, and the listing has to follow it rather than show the previous
    // guest's files.
    LaunchedEffect(Unit) { guestViewModel.resolveSupport() }

    val supported by guestViewModel.supported.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(FilesTab.FILES) }

    when (supported) {
        null -> GuestMessage(stringResource(R.string.files_guest_checking))
        false -> GuestMessage(stringResource(R.string.files_guest_unsupported))
        else -> Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == FilesTab.FILES,
                    onClick = { tab = FilesTab.FILES },
                    text = { Text(stringResource(R.string.files_tab_files)) },
                )
                Tab(
                    selected = tab == FilesTab.MOUNTS,
                    onClick = { tab = FilesTab.MOUNTS },
                    text = { Text(stringResource(R.string.files_tab_mounts)) },
                )
            }
            when (tab) {
                FilesTab.FILES -> GuestFilesPane(viewModel = guestViewModel, onCdInto = onCdInto)
                FilesTab.MOUNTS -> DownloadsMountPane(viewModel = guestViewModel)
            }
        }
    }
}

private enum class FilesTab { FILES, MOUNTS }

@Composable
private fun GuestFilesPane(
    viewModel: GuestFilesViewModel,
    onCdInto: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val path by viewModel.path.collectAsStateWithLifecycle()
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    // Long-press target: the entry plus its absolute guest path, so the menu's
    // actions (cd, export) both know what they act on.
    var menuTarget by remember { mutableStateOf<Pair<GuestLsParser.Entry, String>?>(null) }
    var pendingCd by remember { mutableStateOf<String?>(null) }
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // An explicit "Up one level" control, not an ArrowBack. The back
            // arrow reads as "leave this screen", which is a different action —
            // this one walks the directory tree — and `..` is no longer listed
            // to duplicate it.
            // Always rendered, disabled at the root. It used to appear only once
            // the stack was non-empty, so the first cd into a folder both
            // shoved the path right and raised the row (a TextButton's min
            // height exceeds the path text), dragging the listing down with it.
            // Holding the slot in the layout keeps the path's left edge and the
            // row height identical at every depth, so navigating only ever
            // changes the path string itself.
            TextButton(
                onClick = { viewModel.goUp() },
                enabled = stack.isNotEmpty(),
            ) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.up_one_level))
            }
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Same refresh affordance the Mounts tab has. Loading is on demand
            // here, so a failed or stale listing needs an explicit way to be
            // re-run that is not buried in a long-press menu.
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.mounts_refresh),
                )
            }
        }

        when (val s = state) {
            is GuestFilesState.Loading -> GuestMessage(
                stringResource(R.string.files_guest_loading)
            )
            is GuestFilesState.Failed -> GuestMessage(
                s.reason,
                onClick = { viewModel.refresh() },
            )
            GuestFilesState.Empty -> GuestMessage(
                stringResource(R.string.empty_folder)
            )
            GuestFilesState.Idle -> GuestMessage(
                stringResource(R.string.files_guest_tap_to_load),
                onClick = { viewModel.refresh() },
            )
            is GuestFilesState.Loaded -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(s.entries, key = { it.name }) { entry ->
                    val name = GuestLsParser.displayName(entry)
                    val child = GuestLsParser.childPath(path, name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                            .combinedClickable(
                                onClick = { if (entry.isDirectory) viewModel.enter(child) },
                                // Both files and folders: export applies to either,
                                // so limiting the menu to directories would hide it
                                // from the half of the listing it matters most for.
                                onLongClick = { menuTarget = entry to child },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (entry.isDirectory) Icons.Default.Folder
                            else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = if (entry.isDirectory) {
                                    stringResource(R.string.folder_hint_long_press)
                                } else {
                                    formatFileSize(entry.sizeBytes)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // Long-press menu. Kept as an AlertDialog rather than a DropdownMenu: the
    // actions have long labels and one is a confirm-shaped operation, which a
    // 48dp-wide dropdown row would truncate. AlertDialog only has a confirm and
    // a dismiss slot, so the three actions live in the body as a full-width
    // column and the dialog's own button row stays for Cancel alone.
    menuTarget?.let { (entry, target) ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = { Text(GuestLsParser.displayName(entry)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            if (entry.isDirectory) R.string.export_dir_hint else R.string.export_file_hint
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    // The absolute guest path, which is the one that means
                    // something to paste into the shell. Offered for files as
                    // well as folders — unlike cd, it is valid for both.
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(target))
                            menuTarget = null
                            Toast.makeText(
                                context, R.string.files_path_copied, Toast.LENGTH_SHORT,
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.files_copy_path)) }
                    // cd is only meaningful for a directory: cd to a file is a
                    // no-op followed by an error, so offering it there would be
                    // an action that cannot succeed.
                    if (entry.isDirectory) {
                        TextButton(
                            onClick = {
                                menuTarget = null
                                pendingCd = target
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // The command it actually runs, not a bare "cd".
                            // The label is the whole instruction — which folder,
                            // absolutely — so the user can see what the tap will
                            // do without opening the confirmation dialog first.
                            Text(
                                text = "${stringResource(R.string.action_cd)} $target",
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            viewModel.exportToDownloads(target, entry.isDirectory)
                            menuTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.export_to_downloads)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { menuTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingCd?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingCd = null },
            title = { Text(stringResource(R.string.cd_into_folder)) },
            text = {
                Text(
                    stringResource(R.string.cd_into_folder_confirm, target),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCd = null
                    onCdInto(target)
                }) { Text(stringResource(R.string.action_cd)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCd = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Export result. One-shot: dismissed on any tap.
    when (val ex = exportState) {
        GuestFilesViewModel.ExportState.Working -> GuestMessage(
            stringResource(R.string.export_working)
        )
        is GuestFilesViewModel.ExportState.Done -> AlertDialog(
            onDismissRequest = { viewModel.clearExportState() },
            title = { Text(stringResource(R.string.export_result_title)) },
            text = {
                Text(
                    text = context.getString(
                        when (ex.reason) {
                            GuestFilesViewModel.ExportState.Reason.Exported ->
                                R.string.export_ok
                            GuestFilesViewModel.ExportState.Reason.NotShared ->
                                R.string.export_not_shared
                            GuestFilesViewModel.ExportState.Reason.NoSession ->
                                R.string.export_no_session
                            GuestFilesViewModel.ExportState.Reason.Failed ->
                                R.string.export_failed
                        },
                        ex.name,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearExportState() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        GuestFilesViewModel.ExportState.Idle -> Unit
    }
}

@Composable
private fun DownloadsMountPane(
    viewModel: GuestFilesViewModel,
) {
    val hostState by viewModel.hostState.collectAsStateWithLifecycle()
    val hostPath by viewModel.hostPath.collectAsStateWithLifecycle()
    val hostStack by viewModel.hostStack.collectAsStateWithLifecycle()
    val deleteError by viewModel.deleteError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { viewModel.refreshHost() }
    LaunchedEffect(deleteError) {
        if (deleteError != null) {
            Toast.makeText(context, deleteError, Toast.LENGTH_LONG).show()
            viewModel.clearDeleteError()
        }
    }

    // Long-press target on the host tree: the entry plus its host path.
    var hostMenuTarget by remember { mutableStateOf<Pair<GuestLsParser.Entry, String>?>(null) }
    var pendingHostDelete by remember { mutableStateOf<String?>(null) }

    // Deliberately the same shape as GuestFilesPane: a path header over the
    // listing, nothing else. The pane answers one question — what is in the
    // folder that gets shared — and the mount state, the host/VM path pair and
    // the long "not enabled" paragraphs all sat above the answer the user came
    // for. Sharing itself is a Settings → Storage toggle, so there is nothing
    // actionable to show here.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Held in the layout at every depth (disabled at the root) for the
            // same reason as the Files tab's control: rendering it on demand
            // shifted the path and the listing below it on the first tap.
            TextButton(
                onClick = { viewModel.goUpHost() },
                enabled = hostStack.isNotEmpty(),
            ) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.up_one_level))
            }
            Text(
                text = hostPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.refreshHost() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.mounts_refresh),
                )
            }
        }

        when (val hs = hostState) {
            GuestFilesViewModel.HostTreeState.Idle -> Unit
            is GuestFilesViewModel.HostTreeState.Loading -> GuestMessage(
                stringResource(R.string.mounts_host_loading)
            )
            GuestFilesViewModel.HostTreeState.Empty -> GuestMessage(
                stringResource(R.string.empty_folder)
            )
            is GuestFilesViewModel.HostTreeState.Failed -> GuestMessage(hs.reason)
            is GuestFilesViewModel.HostTreeState.Loaded -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(hs.entries, key = { it.name }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                            // Menu on long-press (copy path, delete). cd is what a
                            // plain tap already does on this list; exporting is
                            // meaningless — these files are already on the phone,
                            // there is no guest to copy them out of. Delete is
                            // gated behind its own confirmation dialog.
                            .combinedClickable(
                                onClick = {
                                    if (entry.isDirectory) {
                                        viewModel.enterHost(
                                            GuestLsParser.childPath(hostPath, entry.name)
                                        )
                                    }
                                },
                                onLongClick = {
                                    hostMenuTarget = entry to GuestLsParser.childPath(hostPath, entry.name)
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (entry.isDirectory) Icons.Default.Folder
                            else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                GuestLsParser.displayName(entry),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (entry.isDirectory) {
                                    stringResource(R.string.mounts_folder)
                                } else {
                                    formatFileSize(entry.sizeBytes)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // Long-press menu on the host tree. Mirror of the guest pane's dialog: the
    // actions are the two that make sense for files already on the phone —
    // copy the path, or delete (which hands off to a confirmation dialog).
    hostMenuTarget?.let { (entry, target) ->
        AlertDialog(
            onDismissRequest = { hostMenuTarget = null },
            title = { Text(GuestLsParser.displayName(entry)) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(target))
                            hostMenuTarget = null
                            Toast.makeText(context, R.string.files_path_copied, Toast.LENGTH_SHORT)
                                .show()
                        },
                    ) {
                        Text(stringResource(R.string.files_copy_path))
                    }
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    TextButton(
                        onClick = {
                            pendingHostDelete = target
                            hostMenuTarget = null
                        },
                    ) {
                        Text(
                            stringResource(R.string.mounts_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { hostMenuTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Delete confirmation: deleting from the host Downloads is permanent and
    // recursive for folders, so it is never a one-tap action.
    pendingHostDelete?.let { target ->
        val name = target.trimEnd('/').substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { pendingHostDelete = null },
            title = { Text(stringResource(R.string.mounts_delete_confirm_title, name)) },
            text = { Text(stringResource(R.string.item_will_be_deleted, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteHost(target)
                    pendingHostDelete = null
                }) {
                    Text(
                        stringResource(R.string.mounts_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHostDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Page-level notice under the path header.
 *
 * Grey only, deliberately. Everything routed here is an explanation rather than
 * a fault — "still checking Linux", "this folder is empty", "the VM is not
 * running yet", "the share is not mounted". Painting any of those red trained
 * the eye to ignore red on this page, so the genuine failure cases stopped
 * standing out; and it was wrong anyway, since a stopped VM or an empty folder
 * is not an error. Real faults keep the red they have elsewhere in the app
 * (the VM error block on Home).
 */
@Composable
private fun GuestMessage(
    text: String,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .then(
                if (onClick != null) {
                    // A failed listing is retryable by tapping, not dead text.
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
    return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)
}