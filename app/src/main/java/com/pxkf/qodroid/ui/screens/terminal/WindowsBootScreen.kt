package com.pxkf.qodroid.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pxkf.qodroid.R
import com.pxkf.qodroid.engine.VmState
import com.pxkf.qodroid.engine.VncStatus
import com.pxkf.qodroid.ui.components.PodroidGhostButton
import com.pxkf.qodroid.ui.components.PodroidPrimaryButton
import com.pxkf.qodroid.ui.components.PodroidStatus
import com.pxkf.qodroid.ui.components.PodroidStatusColors
import com.pxkf.qodroid.ui.theme.PodroidTokens
import com.pxkf.qodroid.x11.X11Constants
import java.io.File

/**
 * Windows / custom-image pane of the terminal page.
 *
 * A Windows guest has no terminal to type into, so this pane is not a terminal
 * — it reports on the boot. It shows engine state, the framebuffer endpoint the
 * preview connects to, and the live guest console, because under TCG those are
 * the only three things that distinguish "still loading" from "hung" and the
 * UI has no other channel for either.
 *
 * Configuration (image, VNC, firmware) lives on the Home page beside the
 * boot-mode chips: all four inputs together answer "what will this VM boot",
 * and splitting them across two screens meant configuring a boot required
 * leaving the page that starts it. This pane reads the same persisted settings.
 */
@Composable
fun WindowsBootScreen(
    viewModel: WindowsBootViewModel,
    hasStorageAccess: Boolean = true,
    onRequestStorageAccess: () -> Unit = {},
    onNavigateToX11: () -> Unit = {},
) {
    val customImagePath by viewModel.customImagePath.collectAsStateWithLifecycle()
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val bootStage by viewModel.bootStage.collectAsStateWithLifecycle()
    val stopping by viewModel.stopping.collectAsStateWithLifecycle()
    val consoleText by viewModel.consoleText.collectAsStateWithLifecycle()
    val vncStatus by viewModel.vncStatus.collectAsStateWithLifecycle()
    val vncEnabled by viewModel.vncEnabled.collectAsStateWithLifecycle()
    val uefiEnabled by viewModel.uefiEnabled.collectAsStateWithLifecycle()

    val isRunning = vmState is VmState.Running
    val isStarting = vmState is VmState.Starting
    val isStopping = stopping && (isRunning || isStarting)
    val error = vmState as? VmState.Error
    val selected = customImagePath.takeIf { it.isNotBlank() }?.let { File(it) }
    val screenOn = isRunning || isStarting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        val (dot, statusLabel) = when {
            isStopping -> PodroidStatusColors.Starting to stringResource(R.string.status_stopping)
            isStarting -> PodroidStatusColors.Starting to
                bootStage.ifEmpty { stringResource(R.string.status_starting) }
            isRunning -> PodroidStatusColors.Running to stringResource(R.string.status_running)
            else -> PodroidStatusColors.Stopped to stringResource(R.string.status_idle)
        }
        PodroidStatus(label = statusLabel, dotColor = dot)

        if (error != null) {
            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(PodroidTokens.Spacing.MD))

        // ── Framebuffer endpoint ─────────────────────────────────────────
        // Shown instead of a terminal because the guest has none, and because
        // the address is what a second viewer (or the in-app preview) needs.
        Text(
            text = stringResource(R.string.windows_screen_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(PodroidTokens.Spacing.XS))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "127.0.0.1:${X11Constants.QEMU_VNC_PORT}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            PodroidStatus(
                label = when (vncStatus) {
                    is VncStatus.Listening -> stringResource(R.string.vnc_short_listening)
                    VncStatus.Waiting -> stringResource(R.string.vnc_short_waiting)
                    is VncStatus.Failed -> stringResource(R.string.vnc_short_failed)
                    VncStatus.Off -> stringResource(R.string.vnc_status_off)
                },
                dotColor = when (vncStatus) {
                    is VncStatus.Listening -> PodroidStatusColors.Running
                    VncStatus.Waiting -> PodroidStatusColors.Starting
                    is VncStatus.Failed -> MaterialTheme.colorScheme.error
                    VncStatus.Off -> PodroidStatusColors.Stopped
                },
            )
        }
        Text(
            text = stringResource(
                if (vncEnabled) R.string.vnc_hint_on else R.string.vnc_hint_off
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── X11 preview ─────────────────────────────────────────────────
        // A Windows guest has no terminal to reach, so the preview is the only
        // way to see it; the affordance belongs in the pane itself, not buried
        // in a top-bar icon. Shown only while the VM is up — before start there
        // is nothing to preview and the top-bar button already covers that.
        if (screenOn) {
            Spacer(Modifier.height(PodroidTokens.Spacing.MD))
            Text(
                text = stringResource(R.string.windows_x11_preview_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PodroidTokens.Spacing.XS))
            PodroidPrimaryButton(
                text = stringResource(R.string.windows_x11_preview_open),
                onClick = onNavigateToX11,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(PodroidTokens.Spacing.MD))

        // ── Start / stop ─────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isStopping) {
                PodroidPrimaryButton(
                    text = stringResource(R.string.stopping_action),
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                )
            } else if (screenOn) {
                PodroidGhostButton(
                    text = stringResource(R.string.windows_stop_vm),
                    onClick = { viewModel.stopVm() },
                    modifier = Modifier.weight(1f),
                )
            } else {
                PodroidPrimaryButton(
                    text = stringResource(R.string.windows_start_vm),
                    onClick = { viewModel.startVm() },
                    enabled = selected != null,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (!screenOn && selected == null) {
            Spacer(Modifier.height(PodroidTokens.Spacing.MD))
            Text(
                text = stringResource(R.string.windows_image_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!hasStorageAccess && !screenOn) {
            Spacer(Modifier.height(PodroidTokens.Spacing.MD))
            Text(
                text = stringResource(R.string.storage_access_needed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PodroidGhostButton(
                text = stringResource(R.string.grant_storage_access),
                onClick = onRequestStorageAccess,
            )
        }

        // ── Guest console ────────────────────────────────────────────────
        // The only progress signal a Windows guest emits. Trimmed to the tail
        // and auto-scrolled so it behaves like a log rather than a page the
        // user has to chase.
        Spacer(Modifier.height(PodroidTokens.Spacing.MD))
        Text(
            text = stringResource(R.string.windows_console_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(PodroidTokens.Spacing.XS))
        val logText = remember(consoleText) { ConsoleTextCleaner.tail(consoleText, CONSOLE_MAX_LINES) }
        val scroll = rememberScrollState()
        LaunchedEffect(logText) {
            if (logText.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 96.dp)
                .clip(RoundedCornerShape(PodroidTokens.Radius.Chip))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(PodroidTokens.Spacing.SM)
                .verticalScroll(scroll),
        ) {
            if (logText.isEmpty()) {
                // Different explanation per firmware: under SeaBIOS the console is
                // silent by design (BIOS writes to the VGA screen), so "no output
                // yet" would otherwise read as a fault that is not there.
                Text(
                    text = stringResource(
                        if (uefiEnabled) R.string.windows_console_empty
                        else R.string.windows_console_empty_bios
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = logText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private const val CONSOLE_MAX_LINES = 200