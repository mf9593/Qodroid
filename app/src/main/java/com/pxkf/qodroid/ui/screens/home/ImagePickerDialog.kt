package com.pxkf.qodroid.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import com.pxkf.qodroid.R
import com.pxkf.qodroid.ui.components.PodroidPrimaryButton
import com.pxkf.qodroid.ui.theme.PodroidTokens
import java.io.File
import java.util.Locale

/**
 * Disk-image picker for the Windows boot profile, opened from the Home page.
 *
 * It is a dialog rather than a page of its own because the image is one of
 * three settings that decide what the VM will boot — mode, image, and whether
 * to expose a display — so picking it inline with the boot-mode chips keeps
 * that decision in one place. It used to live as a full file browser on the
 * terminal page, which meant switching panes to finish configuring a boot.
 *
 * Selection is explicit (tap to navigate into folders, long-press or the
 * footer button to choose a file) because a single tap cannot mean both
 * "descend" and "select" and a mis-tap into the wrong folder on the way to
 * an installer ISO is worse than one extra press.
 */
@Composable
fun ImagePickerDialog(
    startDir: File,
    selectedPath: String,
    hasStorageAccess: Boolean,
    onRequestStorageAccess: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // Own the sizing so the surface can be inset from the window edge —
        // against the platform default the rounded corners get clipped by the
        // screen and read as a plain rectangle.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A Compose Dialog window has a transparent background, so without a
        // surface the picker floated on the scrim with the page showing through
        // the title area. The Surface supplies both the fill and the 1dp outline
        // that make the rounded shape read as a dialog.
        Surface(
            shape = RoundedCornerShape(PodroidTokens.Radius.Dialog),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxSize()
                // Inset from the window edges so the rounded corners are
                // actually visible, and clear of the status/nav bars so the
                // title is not drawn under the clock.
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(
                    start = PodroidTokens.Spacing.MD,
                    end = PodroidTokens.Spacing.MD,
                    top = PodroidTokens.Spacing.XL,
                    bottom = PodroidTokens.Spacing.MD,
                ),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PodroidTokens.Spacing.MD),
            verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.XS),
        ) {
            // Title row is part of the surface, so it is not a transparent strip
            // floating over the scrim.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.windows_image_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = PodroidTokens.Spacing.XS),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (!hasStorageAccess) {
                Text(
                    text = stringResource(R.string.storage_access_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                PodroidPrimaryButton(
                    text = stringResource(R.string.grant_storage_access),
                    onClick = onRequestStorageAccess,
                )
                return@Column
            }

            // PickerState must survive re-entry into a directory, so it is keyed
            // on nothing and kept via remember — the Dialog's content composable
            // is retained while the dialog is up.
            var currentDir by remember { mutableStateOf(startDir) }
            var pending by remember { mutableStateOf<File?>(null) }
            var dirStack by remember { mutableStateOf(listOf<File>()) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dirStack.isNotEmpty()) {
                    IconButton(onClick = {
                        currentDir = dirStack.last()
                        dirStack = dirStack.dropLast(1)
                        pending = null
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                }
                Text(
                    text = currentDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            val entries = remember(currentDir) {
                currentDir.listFiles()?.toList().orEmpty()
                    .filter { it.isDirectory || it.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.windows_image_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(entries, key = { it.absolutePath }) { file ->
                        val isDir = file.isDirectory
                        val isChosen = pending?.absolutePath == file.absolutePath
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (isDir) {
                                            dirStack = dirStack + currentDir
                                            currentDir = file
                                            pending = null
                                        } else {
                                            pending = file
                                        }
                                    },
                                    onLongClick = { if (!isDir) pending = file },
                                ),
                            leadingContent = {
                                Icon(
                                    imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (isDir) MaterialTheme.colorScheme.primary
                                    else if (isChosen) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = file.name + if (isChosen) " ✓" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = if (isDir) stringResource(R.string.folder_hint_long_press)
                                    else "${file.extension.uppercase(Locale.ROOT)} · ${formatImageSize(file.length())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.windows_image_picker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PodroidPrimaryButton(
                text = pending?.let { stringResource(R.string.windows_image_picker_confirm, it.name) }
                    ?: stringResource(R.string.windows_image_picker_confirm_disabled),
                enabled = pending != null,
                onClick = { pending?.let { onSelect(it.absolutePath) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = PodroidTokens.Spacing.SM),
            )
        }
        }
    }
}

private val IMAGE_EXTENSIONS = setOf("qcow2", "qcow", "img", "iso", "raw")

private fun formatImageSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
    return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)
}