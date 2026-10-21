package com.pxkf.qodroid.ui.screens.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pxkf.qodroid.BuildConfig
import com.pxkf.qodroid.R
import com.pxkf.qodroid.ui.components.PodroidGhostButton
import com.pxkf.qodroid.ui.components.PodroidTopBar
import com.pxkf.qodroid.ui.theme.PodroidTokens
import java.io.File
import com.pxkf.qodroid.ui.components.PodroidListRow
import com.pxkf.qodroid.ui.components.PodroidSectionLabel

/**
 * About page (pager page 5): app version, link to the official site, a manual
 * update check (reusing HomeViewModel's update channel so the same dialog /
 * dismissal semantics apply), and a feature list.
 */
@Composable
fun AboutScreen(
    onCheckUpdates: () -> Unit,
    onNavigateBack: () -> Unit = {},
    onNavigateToChangelog: () -> Unit = {},
    onNavigateToDocs: () -> Unit = {},
) {
    val context = LocalContext.current
    var showLogViewer by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }

    // Whether the x86_64 emulator is actually bundled. Resolved once per page:
    // it cannot change while the process lives, and a stat on every recompose
    // would be wasted.
    val hasX86Qemu = remember {
        runCatching {
            File(context.applicationInfo.nativeLibraryDir, "libqemu-system-x86_64.so").exists()
        }.getOrDefault(false)
    }

    if (showLicense) {
        LicenseDialog(onClose = { showLicense = false })
    }

    if (showLogViewer) {
        Dialog(
            onDismissRequest = { showLogViewer = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                // Draw the dialog window edge to edge. A Dialog window otherwise
                // stops at the system bars, so the log viewer's own top bar —
                // which pads for the status bar itself — would sit below a strip
                // of bare window background instead of under it.
                decorFitsSystemWindows = false,
            ),
        ) {
            // A Dialog gets its OWN window, so the activity's bar appearance does
            // not carry over. With decorFitsSystemWindows = false the bar sits
            // over the log page's light surface while the icons stay white
            // (the default light-appearance setting), i.e. invisible. Force light
            // status bars — dark icons — for this window only, and restore the
            // activity's original setting on dispose rather than leaving it
            // flipped after the dialog closes.
            val activity = context as? Activity
            val originalLightBars = activity?.window?.decorView?.let { view ->
                WindowInsetsControllerCompat(activity.window, view)
                    .isAppearanceLightStatusBars
            }
            DisposableEffect(activity) {
                val window = activity?.window
                if (window != null) {
                    WindowCompat.getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = true
                }
                onDispose {
                    if (window != null && originalLightBars != null) {
                        WindowCompat.getInsetsController(window, window.decorView)
                            .isAppearanceLightStatusBars = originalLightBars
                    }
                }
            }
            LogViewerScreen(onClose = { showLogViewer = false })
        }
    }

    // Second-level destination, so it carries its own bar and back affordance
    // (it used to be a pager page with no bar of its own, which is only correct
    // for a host that draws one).
    Scaffold(
        topBar = {
            PodroidTopBar(
                title = stringResource(R.string.about_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Versions ──────────────────────────────────────────────────
        // Everything that identifies a build, in one place. It used to be split
        // across this page (app version only) and Settings' About section, so
        // half the answers were behind a tab you had to find.
        PodroidSectionLabel(stringResource(R.string.about_versions))
        PodroidListRow(
            label = stringResource(R.string.version_label),
            value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            mono = true,
        )
        PodroidListRow(
            label = stringResource(R.string.about_qemu_aarch64),
            value = BuildConfig.QEMU_VERSION_AARCH64,
            mono = true,
        )
        PodroidListRow(
            label = stringResource(R.string.about_qemu_x86_64),
            // Report "absent" rather than a version the page implies is usable:
            // an APK built without the x86_64 target cannot run an x86_64 guest
            // at all, and the engine reports exactly that at start time.
            value = if (hasX86Qemu) {
                BuildConfig.QEMU_VERSION_X86_64
            } else {
                stringResource(R.string.about_qemu_absent)
            },
            mono = true,
        )
        PodroidListRow(
            label = stringResource(R.string.architecture),
            value = "AArch64",
            mono = true,
        )
        PodroidListRow(
            label = stringResource(R.string.linux_distro),
            value = stringResource(R.string.about_distro_value),
            mono = true,
        )
        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
        Text(
            text = stringResource(R.string.about_versions_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The change list is long and is only ever read on purpose, so it lives
        // on its own page rather than as a block here.
        PodroidGhostButton(
            text = stringResource(R.string.about_changelog_title),
            onClick = onNavigateToChangelog,
            modifier = Modifier.fillMaxWidth(),
        )

        // The in-app docs page holds the commands a user actually has to type.
        PodroidGhostButton(
            text = stringResource(R.string.about_docs_title),
            onClick = onNavigateToDocs,
            modifier = Modifier.fillMaxWidth(),
        )

        // Official website
        PodroidGhostButton(
            text = stringResource(R.string.about_visit_website),
            onClick = {
                val url = context.getString(R.string.about_website_url)
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Manual update check. `onCheckUpdates` reuses HomeViewModel's
        // refreshUpdateCheck() which surfaces the existing update dialog.
        PodroidGhostButton(
            text = stringResource(R.string.about_check_updates),
            onClick = onCheckUpdates,
            modifier = Modifier.fillMaxWidth(),
        )

        // View app + VM logs. Useful for diagnosing "terminal shows nothing
        // after start" — the app logcat and guest console are shown together.
        PodroidGhostButton(
            text = stringResource(R.string.about_view_logs),
            onClick = { showLogViewer = true },
            modifier = Modifier.fillMaxWidth(),
        )
        // Share the same material out of the app. Was in Settings' About
        // section; everything that reports on the build now lives here.
        // No documentation button: the guide lived on a separate GitHub Pages
        // URL that no longer matches the website shown below, and a second link
        // to somewhere else was worse than none. The website row is the single
        // place the app points users outward.

        // Feature highlights
        Spacer(Modifier.height(4.dp))
        PodroidSectionLabel(stringResource(R.string.about_features_title))
        FeatureRow(
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            text = stringResource(R.string.about_feature_vm),
        )
        FeatureRow(
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            text = stringResource(R.string.about_feature_terminal),
        )
        FeatureRow(
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            text = stringResource(R.string.about_feature_x11),
        )
        FeatureRow(
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            text = stringResource(R.string.about_feature_containers),
        )

        // Open source attribution. This is a modified fork, so the upstream
        // is credited inside the app. The license text is bundled rather than
        // linked to, so it stays readable offline and states exactly which
        // terms this binary was built under.
        PodroidSectionLabel(stringResource(R.string.about_opensource_title))
        Text(
            text = stringResource(R.string.about_opensource_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PodroidListRow(
            label = stringResource(R.string.about_opensource_upstream),
            value = stringResource(R.string.about_opensource_upstream_value),
        )
        PodroidListRow(
            label = stringResource(R.string.about_opensource_repository),
            value = stringResource(R.string.about_opensource_repository_value),
            mono = true,
            onClick = {
                val url = context.getString(R.string.about_opensource_repository_url)
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            },
        )
        PodroidListRow(
            label = stringResource(R.string.about_opensource_license),
            value = stringResource(R.string.about_opensource_license_value),
            mono = true,
        )
        PodroidListRow(
            label = stringResource(R.string.about_opensource_copyright),
            value = stringResource(R.string.about_opensource_copyright_value),
        )
        PodroidGhostButton(
            text = stringResource(R.string.about_opensource_view_license),
            onClick = { showLicense = true },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.about_website_url),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun FeatureRow(
    icon: @Composable () -> Unit,
    text: String,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        icon()
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The bundled copy of the upstream license.
 *
 * Shown from the About page rather than linked to: GPL v2 requires the
 * license terms to travel with the binary, and a link only reaches them
 * with a network. The text is read once per dialog and cannot render
 * empty without a fallback.
 */
@Composable
private fun LicenseDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    // The fallback must be read at composable level: a lambda nested inside
    // remember is not itself composable, so stringResource cannot be called
    // within it.
    val licenseFallback = stringResource(R.string.about_opensource_license_unavailable)
    val license = remember {
        runCatching {
            context.assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
        }.getOrElse { licenseFallback }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(PodroidTokens.Spacing.LG)
                .fillMaxSize(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = PodroidTokens.Spacing.LG,
                        vertical = PodroidTokens.Spacing.XL,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.about_opensource_license_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(PodroidTokens.Spacing.MD))
                Text(
                    text = license,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(PodroidTokens.Spacing.MD))
                PodroidGhostButton(
                    text = stringResource(R.string.about_opensource_license_close),
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Guide URL removed with the documentation button; see the note there. */
