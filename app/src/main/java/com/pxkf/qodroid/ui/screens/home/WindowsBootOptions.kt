package com.pxkf.qodroid.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pxkf.qodroid.R
import com.pxkf.qodroid.engine.VncStatus
import com.pxkf.qodroid.ui.components.PodroidListRow
import com.pxkf.qodroid.ui.components.PodroidPrimaryButton
import com.pxkf.qodroid.ui.components.PodroidStatus
import com.pxkf.qodroid.ui.components.PodroidStatusColors
import com.pxkf.qodroid.ui.components.PodroidSwitch
import com.pxkf.qodroid.ui.theme.PodroidTokens
import com.pxkf.qodroid.x11.X11Constants
import java.io.File

/**
 * Everything that decides what the Windows profile will boot, shown on the Home
 * page under the mode chips: the disk image, whether a display is exposed, and
 * which firmware runs.
 *
 * It is here rather than on the terminal page because those three inputs were
 * previously split across two screens — the mode chip here, the image and
 * options there — so a boot could be configured only by navigating away from
 * the page that starts it.
 *
 * The VNC address is shown rather than just a switch, because it is the one
 * value the user may need elsewhere (a desktop viewer, another machine) and it
 * is the actual thing the switch controls.
 */
@Composable
fun WindowsBootOptions(
    customImagePath: String,
    vncEnabled: Boolean,
    vncExpose: Boolean,
    uefiEnabled: Boolean,
    vmNotRunning: Boolean,
    isWindowsMode: Boolean,
    vncStatus: VncStatus,
    onPickImage: () -> Unit,
    onVncChange: (Boolean) -> Unit,
    onVncExposeChange: (Boolean) -> Unit,
    onUefiChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isWindowsMode) return

    val selected = customImagePath.takeIf { it.isNotBlank() }?.let { File(it) }

    Column(modifier = modifier.fillMaxWidth()) {
        PodroidListRow(
            label = stringResource(R.string.windows_image_title),
            value = selected?.name ?: stringResource(R.string.windows_image_none),
            onClick = onPickImage,
        )
        PodroidPrimaryButton(
            text = stringResource(R.string.windows_image_picker_action),
            onClick = onPickImage,
        )

        Spacer(Modifier.height(PodroidTokens.Spacing.MD))

        PodroidListRow(
            label = stringResource(R.string.vnc_title),
            // Show the status, not just the intent: the switch says what will be
            // asked for, the status line says whether a server is there now.
            value = when (vncStatus) {
                is VncStatus.Listening -> stringResource(
                    R.string.vnc_status_listening, vncStatus.port
                )
                VncStatus.Waiting -> stringResource(R.string.vnc_status_waiting)
                is VncStatus.Failed -> stringResource(R.string.vnc_status_failed)
                VncStatus.Off -> stringResource(R.string.vnc_status_off)
            },
            onClick = { if (vmNotRunning) onVncChange(!vncEnabled) },
            rightSlot = {
                PodroidSwitch(
                    checked = vncEnabled,
                    onCheckedChange = onVncChange,
                    enabled = vmNotRunning,
                )
            },
        )
        Text(
            text = stringResource(R.string.vnc_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PodroidStatus(
            label = stringResource(R.string.vnc_endpoint, X11Constants.QEMU_VNC_PORT),
            dotColor = when (vncStatus) {
                is VncStatus.Listening -> PodroidStatusColors.Running
                VncStatus.Waiting -> PodroidStatusColors.Starting
                is VncStatus.Failed -> MaterialTheme.colorScheme.error
                VncStatus.Off -> PodroidStatusColors.Stopped
            },
        )

        PodroidListRow(
            label = stringResource(R.string.vnc_expose_title),
            // The address shown depends on the switch: off means the in-app
            // viewer on this phone (127.0.0.1), on means any peer on the LAN
            // can reach the same port via this phone's address.
            value = stringResource(
                if (vncExpose) R.string.vnc_expose_lan else R.string.vnc_expose_loopback
            ),
            onClick = { if (vmNotRunning) onVncExposeChange(!vncExpose) },
            rightSlot = {
                PodroidSwitch(
                    checked = vncExpose,
                    onCheckedChange = onVncExposeChange,
                    enabled = vmNotRunning,
                )
            },
        )
        Text(
            text = stringResource(R.string.vnc_expose_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PodroidListRow(
            label = stringResource(R.string.windows_uefi_title),
            value = stringResource(
                if (uefiEnabled) R.string.windows_uefi_ovmf else R.string.windows_uefi_bios
            ),
            onClick = { if (vmNotRunning) onUefiChange(!uefiEnabled) },
            rightSlot = {
                PodroidSwitch(
                    checked = uefiEnabled,
                    onCheckedChange = onUefiChange,
                    enabled = vmNotRunning,
                )
            },
        )
        Text(
            text = stringResource(R.string.windows_uefi_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

