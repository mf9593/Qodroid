package com.pxkf.qodroid.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pxkf.qodroid.engine.VmMode

/**
 * Dual-terminal host shown as pager page 3. Shows either the built-in Linux
 * terminal (a real TerminalView session) or the Windows / custom-image boot
 * screen (image picker + start/stop). Both profiles share the single VM engine
 * — only one can run at a time, matching VmMode.
 *
 * The three terminal actions (server mode, the X11 viewer, quick settings) are
 * buttons in the pager host's top bar, shown only on this page; this composable
 * owns the surfaces those buttons open. The split matters: the X11 viewer is the
 * only way to watch a graphical Windows session, so the controls must not live
 * in TerminalScreen — the Windows pane could never show them, and the row of
 * buttons was a separate strip eating content height that the bar already has.
 *
 * Both sheets render from here (not from TerminalScreen) so they work in either
 * pane. Quick settings previously rendered only inside TerminalScreen, which
 * meant the ⚙ button did nothing at all while the Windows pane was up.
 *
 * The pane follows the persisted SettingsRepository.vmMode, which the Home
 * page's boot-mode selector writes and the engine reads at start. An earlier
 * version kept a private tab index here, so the terminal page could show the
 * Windows pane while the engine booted Linux; one source of truth removes
 * that whole class of mismatch.
 */
@Composable
fun DualTerminalScreen(
    windowSizeClass: WindowSizeClass,
    terminalViewModel: TerminalViewModel,
    onNavigateToX11: () -> Unit,
    windowsViewModel: WindowsBootViewModel = hiltViewModel(),
    hasStorageAccess: Boolean = true,
    onRequestStorageAccess: () -> Unit = {},
) {
    val vmMode by windowsViewModel.vmMode.collectAsStateWithLifecycle()
    val showServerSheet by terminalViewModel.showServerSheet.collectAsStateWithLifecycle()
    val showQuickSettings by terminalViewModel.showQuickSettings.collectAsStateWithLifecycle()
    val fontSize by terminalViewModel.terminalFontSize.collectAsStateWithLifecycle()
    val colorTheme by terminalViewModel.terminalColorTheme.collectAsStateWithLifecycle()
    val terminalFont by terminalViewModel.terminalFont.collectAsStateWithLifecycle()
    val showExtraKeys by terminalViewModel.showExtraKeysFlow.collectAsStateWithLifecycle()
    val hapticsEnabled by terminalViewModel.hapticsEnabledFlow.collectAsStateWithLifecycle()

    if (showServerSheet) {
        ServerModeSheet(
            onDismiss = { terminalViewModel.closeServerSheet() },
            onEnable = { terminalViewModel.enableServerMode() },
        )
    }

    if (showQuickSettings) {
        QuickSettingsDialog(
            fontSize = fontSize,
            onFontSizeChange = { terminalViewModel.setTerminalFontSize(it) },
            onDismiss = { terminalViewModel.closeQuickSettings() },
            showExtraKeys = showExtraKeys,
            onToggleExtraKeys = { terminalViewModel.updateShowExtraKeys(it) },
            hapticsEnabled = hapticsEnabled,
            onToggleHaptics = { terminalViewModel.updateHapticsEnabled(it) },
            colorTheme = colorTheme,
            onColorThemeChange = { terminalViewModel.setTerminalColorTheme(it) },
            terminalFont = terminalFont,
            onFontChange = { terminalViewModel.setTerminalFont(it) },
            viewModel = terminalViewModel,
        )
    }

    // No IME inset here on purpose. Whether the window shrinks for the keyboard is
    // platform- and path-dependent, and padding the whole pane by it double-counted
    // in one of those cases — leaving a full-height band of #FFFAFAFA window
    // background over the terminal for as long as the keyboard was up. TerminalScreen
    // now applies the inset to its extra-keys row only, which is correct whether or
    // not the window resized; the Windows pane has no bottom controls to lift.
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Opaque so that any gap left by an inset is the terminal's own colour
            // rather than the near-white window background showing through.
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (vmMode == VmMode.WINDOWS) {
            WindowsBootScreen(
                viewModel = windowsViewModel,
                hasStorageAccess = hasStorageAccess,
                onRequestStorageAccess = onRequestStorageAccess,
                onNavigateToX11 = onNavigateToX11,
            )
        } else {
            TerminalScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {},
                onNavigateToX11 = onNavigateToX11,
                viewModel = terminalViewModel,
                embedded = true,
            )
        }
    }
}
