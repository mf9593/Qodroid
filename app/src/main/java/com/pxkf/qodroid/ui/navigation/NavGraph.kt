package com.pxkf.qodroid.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.LocalActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pxkf.qodroid.engine.control.ControlProviderEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.pxkf.qodroid.ui.screens.home.HomeScreen
import com.pxkf.qodroid.ui.screens.home.HomePagerHost
import com.pxkf.qodroid.ui.screens.home.HomeViewModel
import com.pxkf.qodroid.ui.screens.home.AboutScreen
import com.pxkf.qodroid.ui.screens.home.ChangelogScreen
import com.pxkf.qodroid.ui.screens.home.DocsScreen
import com.pxkf.qodroid.ui.screens.settings.SettingsScreen
import com.pxkf.qodroid.ui.screens.setup.SetupScreen
import com.pxkf.qodroid.ui.screens.terminal.TerminalScreen
import com.pxkf.qodroid.ui.screens.terminal.TerminalViewModel
import com.pxkf.qodroid.ui.screens.backup.ContainerBackupScreen
import com.pxkf.qodroid.ui.screens.status.StatusScreen
import com.pxkf.qodroid.ui.screens.x11.X11Screen

object Routes {
    const val SETUP         = "setup"
    const val HOME          = "home"
    const val TERMINAL      = "terminal"
    const val TERMINAL_X11  = "terminal/x11"
    const val SETTINGS      = "settings"
    const val ABOUT         = "about"
    const val CHANGELOG     = "changelog"
    const val DOCS          = "docs"
    const val STATUS        = "status"
    const val CONTAINER_BACKUP = "container_backup"
}

/**
 * Pager pages a pushed destination can ask the Home host to land on. Mirrors
 * HomePagerHost's private PAGE_* constants; they live here because the request
 * is set from NavGraph, which is above the pager.
 */
object HomePagerPage {
    const val TERMINAL = 1
}

@Composable
fun PodroidNavGraph(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController = rememberNavController(),
) {
    // Read isSetupDone from a Hilt-scoped helper so MainActivity doesn't need
    // a field-injected SettingsRepository just to drive the start destination.
    val isSetupDone by hiltViewModel<NavGraphViewModel>()
        .isSetupDone
        .collectAsStateWithLifecycle(initialValue = null)

    // Scoped to PodroidNavGraph composable — survives all navigation including popUpTo(0)
    val terminalViewModel: TerminalViewModel = hiltViewModel()

    // Which pager page the Home host should land on when it next composes.
    // Set just before popping back to HOME from a pushed destination (X11), so
    // Back returns to the terminal rather than to whichever pager page the user
    // last happened to be on. Hoisted here because the X11 entry has no access
    // to HomeViewModel (that is scoped to the HOME back-stack entry), and
    // rememberSaveable keeps it across the language-change activity.recreate().
    var pendingPagerPage by rememberSaveable { mutableStateOf<Int?>(null) }

    if (isSetupDone == null) {
        // isSetupDone starts null on every fresh composition (e.g. the
        // activity.recreate() a language change triggers) until the flow's
        // first value arrives. Paint the themed background instead of
        // returning with nothing composed, so that frame doesn't flash an
        // empty window.
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val startDestination = when (isSetupDone) {
        true  -> Routes.HOME
        else  -> Routes.SETUP
    }

    // adb `content call --method navigate` control surface (ControlProvider):
    // collect the Hilt-singleton route bus instead of field-injecting it, so
    // NavGraph doesn't need its own ViewModel wiring just for this. Emissions
    // while the wizard is showing are ignored - jumping mid-setup is meaningless.
    val context = LocalContext.current
    LaunchedEffect(navController) {
        val navigator = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ControlProviderEntryPoint::class.java,
        ).controlNavigator()
        navigator.routes.collect { route ->
            if (navController.currentDestination?.route != Routes.SETUP) {
                navController.navigate(route) { launchSingleTop = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                windowSizeClass = windowSizeClass,
                onSetupComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            // Resolve HomeViewModel at composition time (not inside a callback)
            // so the About page can trigger the same update-check channel.
            val homeViewModel: HomeViewModel = hiltViewModel()
            HomePagerHost(
                windowSizeClass = windowSizeClass,
                terminalViewModel = terminalViewModel,
                homeViewModel = homeViewModel,
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
                onNavigateToAbout = {
                    navController.navigate(Routes.ABOUT) { launchSingleTop = true }
                },
                onNavigateToStatus = {
                    navController.navigate(Routes.STATUS) { launchSingleTop = true }
                },
                onNavigateToContainerBackup = {
                    navController.navigate(Routes.CONTAINER_BACKUP) { launchSingleTop = true }
                },
                onNavigateToTerminal = {
                    navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                },
                onNavigateToX11 = {
                    navController.navigate(Routes.TERMINAL_X11) { launchSingleTop = true }
                },
                pendingPage = pendingPagerPage,
                onPageConsumed = { pendingPagerPage = null },
            )
        }

        composable(Routes.STATUS) {
            StatusScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.STATUS) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.TERMINAL) {
            TerminalScreen(
                windowSizeClass = windowSizeClass,
                viewModel = terminalViewModel,
                onNavigateBack = {
                    // Only pop if we're not already at HOME to avoid the warning
                    if (navController.currentDestination?.route == Routes.TERMINAL) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onNavigateToX11 = {
                    navController.navigate(Routes.TERMINAL_X11) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.TERMINAL_X11) {
            // Back from X11 must land on the Home pager's terminal page.
            //
            // This used to popBackStack(Routes.TERMINAL) — but the X11 route is
            // entered from the Home pager, so the stack is HOME → TERMINAL_X11
            // and there is no TERMINAL entry to find. The pop returned false and
            // the fallback *pushed a separate full-screen terminal* on top, so
            // Back from the viewer landed in a different terminal than the one
            // that launched it. Now the pager page is requested explicitly and
            // the stack is popped to wherever it came from.
            val leaveViewer = {
                // Only request a pager page when the viewer was opened from the
                // Home pager. It is also reachable from the standalone terminal
                // route, and asking Home to jump to its terminal page on the way
                // back from *that* one would leave a stale request that fires the
                // next time Home is shown.
                val fromHomePager =
                    navController.previousBackStackEntry?.destination?.route == Routes.HOME
                if (fromHomePager) pendingPagerPage = HomePagerPage.TERMINAL
                if (!navController.popBackStack()) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            X11Screen(
                onNavigateBack = leaveViewer,
                onNavigateToTerminal = leaveViewer,
            )
        }

        composable(Routes.CONTAINER_BACKUP) {
            ContainerBackupScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.CONTAINER_BACKUP) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            val activity = LocalActivity.current
            val onLanguageChanged = remember(activity) {
                { activity?.recreate() ?: Unit }
            }
            SettingsScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.SETTINGS) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onLanguageChanged = onLanguageChanged,
                onNavigateToContainerBackup = {
                    navController.navigate(Routes.CONTAINER_BACKUP) { launchSingleTop = true }
                },
            )
        }

        // About is a second-level destination (reached from Settings' About
        // section), not a top-level tab — it holds reference information nobody
        // needs while starting or stopping a VM.
        composable(Routes.ABOUT) {
            val homeViewModel: HomeViewModel = hiltViewModel()
            AboutScreen(
                onCheckUpdates = { homeViewModel.refreshUpdateCheck() },
                onNavigateBack = { navController.popBackStack() },
                onNavigateToChangelog = { navController.navigate(Routes.CHANGELOG) },
                onNavigateToDocs = { navController.navigate(Routes.DOCS) },
            )
        }

        // The docs page is reached from About, like the changelog: reference
        // material that is useful but not part of the day-to-day flow.
        composable(Routes.DOCS) {
            DocsScreen(onNavigateBack = { navController.popBackStack() })
        }

        // The changelog has its own destination rather than a block on the About
        // page: the About page is reference information for the build in front of
        // you, and a long change list belongs out of the way of it.
        composable(Routes.CHANGELOG) {
            ChangelogScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
