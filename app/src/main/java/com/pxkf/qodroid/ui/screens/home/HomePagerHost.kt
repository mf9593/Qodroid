package com.pxkf.qodroid.ui.screens.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pxkf.qodroid.R
import com.pxkf.qodroid.ui.components.PodroidTopBar
import com.pxkf.qodroid.ui.screens.settings.SettingsScreen
import com.pxkf.qodroid.ui.screens.terminal.DualTerminalScreen
import com.pxkf.qodroid.ui.screens.terminal.TerminalViewModel
import kotlinx.coroutines.launch

/**
 * Home pager: five swipeable pages replacing the single Home screen.
 *   0 Home (VM status / start-stop, original logic)
 *   1 Script library (Downloads/Qodroid/scripts/ — .sh files)
 *   2 Terminal (embedded live terminal)
 *   3 File manager (browse + long-press folder to cd)
 *   4 About (version / website / update check)
 *
 * System back walks back one page instead of exiting the app while a
 * non-home page is showing. The terminal page disables swipe (its TerminalView
 * consumes horizontal gestures) — tabs still switch to it.
 *
 * The host owns the shared top bar (title follows the current tab; settings /
 * status actions stay reachable from every page) and the compact bottom
 * navigation bar. Inner pages run with embedded = true and must not re-pad
 * the status bar.
 */
@OptIn(ExperimentalLayoutApi::class) // HorizontalPager
@Composable
fun HomePagerHost(
    windowSizeClass: WindowSizeClass,
    terminalViewModel: TerminalViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToStatus: () -> Unit,
    onNavigateToContainerBackup: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToX11: () -> Unit,
    homeViewModel: HomeViewModel,
    pendingPage: Int? = null,
    onPageConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    // Read once here, not inside the pager's page lambda: LocalActivity.current
    // is a composition read and the pager recomposes per page.
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 5 })

    // Lift the pager clear of the keyboard. Under edge-to-edge the window does
    // not shrink for the IME — the keyboard is only reported as an inset — so
    // without this the pager keeps its full height and the bottom of the
    // terminal page (its extra-keys row and the last lines of scrollback) stays
    // underneath the keyboard.
    //
    // Only the *difference* is applied. The bottom bar sits below the pager and
    // the keyboard covers that strip as well, so padding by the raw IME height
    // would leave a dead band the height of the bar between the terminal and
    // the keyboard. Subtracting the bar (plus the system gesture inset) lands
    // the pager's content edge exactly on the keyboard's top edge. When the IME
    // is down the inset is 0 and this collapses to no padding at all.
    val density = LocalDensity.current
    val chromeBelowPagerPx = with(density) { BottomBarHeight.roundToPx() } +
        WindowInsets.navigationBars.getBottom(density)
    val imeLift = with(density) {
        (WindowInsets.ime.getBottom(density) - chromeBelowPagerPx).coerceAtLeast(0).toDp()
    }

    fun checkStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        } else {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Re-read the OS grant on every resume so returning from the system
    // all-files-access screen (or a runtime permission dialog) refreshes the
    // script library / file manager pages immediately.
    var hasStorageAccess by remember { mutableStateOf(checkStorageAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStorageAccess = checkStorageAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled on next composition via hasStorageAccess refresh */ }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        } else {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // System back steps the pager back one page; page 0 behaves normally.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    // A pushed destination (X11) can ask to come back to a specific page. Runs
    // after the pager is laid out — scrolling before that is a no-op on the
    // initial composition and silently drops the request.
    LaunchedEffect(pendingPage) {
        val page = pendingPage ?: return@LaunchedEffect
        pagerState.scrollToPage(page)
        onPageConsumed()
    }

    Column(
        Modifier
            .fillMaxSize(),
    ) {
        // Shared top bar: PodroidTopBar keeps its default window insets so the
        // status bar is padded once by the bar itself (content below it is not
        // double-padded because inner pages run with embedded = true). The title
        // follows the current page and settings/status actions stay reachable.
        PodroidTopBar(
            title = stringResource(pageTitleRes[pagerState.currentPage]),
            actions = {
                // Terminal-only actions, merged into the shared bar and shown
                // only on the terminal page. They used to be a separate row
                // pinned above the pane, which cost a whole strip of content
                // height and duplicated the bar's own background. Page-specific
                // actions go first so the always-present global ones (status,
                // settings) stay rightmost.
                if (pagerState.currentPage == PAGE_TERMINAL) {
                    IconButton(onClick = { terminalViewModel.openServerSheet() }) {
                        Icon(Icons.Default.Bedtime, contentDescription = stringResource(R.string.server_mode))
                    }
                    IconButton(onClick = onNavigateToX11) {
                        Icon(Icons.Default.DesktopWindows, contentDescription = stringResource(R.string.x11_open))
                    }
                    IconButton(onClick = { terminalViewModel.openQuickSettings() }) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.quick_settings))
                    }
                }
                IconButton(onClick = onNavigateToStatus) {
                    Icon(Icons.Default.MonitorHeart, contentDescription = stringResource(R.string.status_page_title))
                }
                // Was the Settings gear. Settings is a tab now, so this action
                // goes to the second-level About page instead of duplicating a
                // tab that is one swipe away.
                IconButton(onClick = onNavigateToAbout) {
                    Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about))
                }
            },
        )

        // Content area — pager grows to fill everything above the nav bar.
        // No global bottom padding here: the terminal page applies its own
        // navigationBars insets, so a pager-wide padding would double it.
        // The Home page handles its own bottom spacing.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = imeLift),
            // Swipe is off on the terminal page only. Its TerminalView does its
            // own vertical scrolling but does not claim the vertical axis early
            // enough, so a plain up/down drag reached the pager and turned into a
            // horizontal page change — scrolling the scrollback flipped pages.
            // The bottom navigation bar still switches pages from here.
            userScrollEnabled = pagerState.currentPage != PAGE_TERMINAL,
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    windowSizeClass = windowSizeClass,
                    onNavigateToTerminal = {
                        scope.launch { pagerState.animateScrollToPage(PAGE_TERMINAL) }
                    },
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToStatus = onNavigateToStatus,
                    onNavigateToContainerBackup = onNavigateToContainerBackup,
                    embedded = true,
                    // The Windows disk-image picker lives on this page now, so it
                    // needs the same all-files state the terminal page had.
                    hasStorageAccess = hasStorageAccess,
                    onRequestStorageAccess = ::requestStorageAccess,
                )
                PAGE_TERMINAL -> DualTerminalScreen(
                    windowSizeClass = windowSizeClass,
                    terminalViewModel = terminalViewModel,
                    onNavigateToX11 = onNavigateToX11,
                    hasStorageAccess = hasStorageAccess,
                    onRequestStorageAccess = ::requestStorageAccess,
                )
                PAGE_FILES -> FileManagerScreen(
                    onCdInto = { path ->
                        terminalViewModel.submitCommand("cd $path")
                        scope.launch { pagerState.animateScrollToPage(PAGE_TERMINAL) }
                    },
                    embedded = true,
                )
                // Settings is a top-level tab now; About moved down to
                // Settings → About, which is where reference information
                // belongs. The scripts page was removed outright rather than
                // left as a dead tab.
                PAGE_SETTINGS -> SettingsScreen(
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = {},
                    // The locale is applied in attachBaseContext, so a
                    // configuration change is the only thing that re-reads it.
                    // The standalone route wires this to activity.recreate();
                    // the tab has to do it itself or picking a language there
                    // changes the setting and nothing on screen.
                    onLanguageChanged = { activity?.recreate() },
                    onNavigateToContainerBackup = onNavigateToContainerBackup,
                    embedded = true,
                )
            }
        }

        // Compact bottom navigation bar. Material3's NavigationBar is 80dp
        // tall with a large icon-label gap, which eats vertical space on
        // phones. This bar is a slim custom row: 56dp tall, 22dp icons, 2dp
        // icon-label spacing, small label text. Colors bind to
        // MaterialTheme.colorScheme so it still follows Settings → Appearance
        // → Dynamic color.
        //
        // Always rendered, keyboard up or not. It used to be dropped while the
        // IME was visible, which meant tapping the terminal page — whose
        // TerminalView grabs focus and raises the keyboard — removed the bar
        // and grew the pager by its full 56 dp, so the whole page jumped up
        // and back down with every keyboard show/hide. Keeping it in place
        // costs the pager a fixed 56 dp and leaves page geometry stable; the
        // terminal page already absorbs the keyboard itself.
        CompactNavigationBar(
            currentPage = pagerState.currentPage,
            // Instant, not animated: an animated scroll away from the terminal
            // page can be cut short by the IME-driven relayout — the terminal
            // grabs focus, the keyboard inset changes the pager's lift
            // mid-flight, and the pager settles on the page the animation
            // happened to be passing through instead of the tapped tab.
            onPageSelected = { scope.launch { pagerState.scrollToPage(it) } },
        )
    }
}

private val pageTitleRes = intArrayOf(
    R.string.pager_tab_home,
    R.string.pager_tab_terminal,
    R.string.pager_tab_files,
    R.string.pager_tab_settings,
)

/** Pager indices. Named so no call site carries a bare index into this list. */
private const val PAGE_TERMINAL = 1
private const val PAGE_FILES = 2
private const val PAGE_SETTINGS = 3

/**
 * Tap-row height of [CompactNavigationBar], excluding the system gesture inset
 * that [navigationBarsPadding] adds below it. HomePagerHost subtracts this (plus
 * that inset) from the IME height to work out how far the pager must lift.
 */
private val BottomBarHeight = 56.dp

@Composable
private fun CompactNavigationBar(
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
) {
    // Must stay index-aligned with pageTitleRes and the pager's page branches.
    val tabs = listOf(
        Pair(R.string.pager_tab_home, Icons.Default.Home),
        Pair(R.string.pager_tab_terminal, Icons.Default.Code),
        Pair(R.string.pager_tab_files, Icons.Default.Folder),
        Pair(R.string.pager_tab_settings, Icons.Default.Settings),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomBarHeight),
        ) {
            tabs.forEachIndexed { index, (labelRes, icon) ->
                val selected = currentPage == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClick = { onPageSelected(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(labelRes),
                        modifier = Modifier.size(22.dp),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
