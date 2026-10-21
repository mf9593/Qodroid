package com.pxkf.qodroid.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pxkf.qodroid.R
import com.pxkf.qodroid.engine.VmMode
import com.pxkf.qodroid.ui.components.PodroidChipColors
import com.pxkf.qodroid.ui.components.PodroidSectionLabel
import com.pxkf.qodroid.ui.theme.PodroidTokens

/**
 * Home-page boot-mode selector: the one place the Linux | Windows choice is
 * made, before the VM starts. The terminal page follows the same persisted
 * value instead of keeping a private tab index, so the pane you see and the
 * image the engine boots can never disagree.
 *
 * The selection is persisted via SettingsRepository.vmMode, which the engine
 * reads at VM start. [customImagePath] is shown as a hint so a Windows
 * selection without an image is obvious up front rather than a boot failure
 * (the image itself is picked on the terminal page, which browses Downloads).
 */
@Composable
fun BootModeSelector(
    selected: VmMode,
    customImagePath: String,
    onSelect: (VmMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PodroidSectionLabel(stringResource(R.string.boot_mode))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
        ) {
            FilterChip(
                selected = selected == VmMode.LINUX,
                onClick = { onSelect(VmMode.LINUX) },
                label = {
                    Text(
                        text = stringResource(R.string.dual_terminal_linux),
                        fontFamily = FontFamily.Monospace,
                    )
                },
                shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                colors = PodroidChipColors(),
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = selected == VmMode.WINDOWS,
                onClick = { onSelect(VmMode.WINDOWS) },
                label = {
                    Text(
                        text = stringResource(R.string.dual_terminal_windows),
                        fontFamily = FontFamily.Monospace,
                    )
                },
                shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                colors = PodroidChipColors(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(PodroidTokens.Spacing.XS))
        Text(
            text = stringResource(R.string.boot_mode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (selected == VmMode.WINDOWS) {
            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            if (customImagePath.isBlank()) {
                // No image chosen yet: booting Windows mode would fail, so say so
                // instead of letting the user discover it through a boot error.
                Text(
                    text = stringResource(R.string.boot_mode_windows_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = PodroidTokens.Spacing.XS),
                )
            } else {
                Text(
                    text = customImagePath.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = PodroidTokens.Spacing.XS),
                )
            }
            // Set expectations: a second, larger emulator binary emulating a
            // foreign architecture is slow, and the user will otherwise read the
            // first hang as a bug.
            Text(
                text = stringResource(R.string.boot_mode_windows_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = PodroidTokens.Spacing.XS),
            )
        }
    }
}
