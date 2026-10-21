package com.pxkf.qodroid.ui.screens.terminal

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.pxkf.qodroid.R
import com.pxkf.qodroid.engine.VmState
import com.pxkf.qodroid.ui.components.AdaptiveContainer
import com.pxkf.qodroid.ui.components.PodroidGhostButton
import com.pxkf.qodroid.ui.components.PodroidTopBar
import com.pxkf.qodroid.ui.components.rememberExtraKeyTapModifier
import com.pxkf.qodroid.ui.theme.PodroidTokens
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onNavigateToX11: () -> Unit,
    embedded: Boolean = false,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val showQuickSettings by viewModel.showQuickSettings.collectAsStateWithLifecycle()
    val showExtraKeys by viewModel.showExtraKeysFlow.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabledFlow.collectAsStateWithLifecycle()
    var showServerSheet by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Forward app-level focus into the VM as xterm focus events (CSI I / CSI O)
    // so nvim's FocusGained/FocusLost autocommands fire. Gated by the emulator's
    // DECSET 1004 mode inside the ViewModel so we never leak literal bytes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.sendFocusEvent(true)
                Lifecycle.Event.ON_PAUSE  -> viewModel.sendFocusEvent(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val colorTheme by viewModel.terminalColorTheme.collectAsStateWithLifecycle()
    val terminalFont by viewModel.terminalFont.collectAsStateWithLifecycle()

    // Only when this screen owns its bar. Embedded, DualTerminalScreen renders the
    // dialog instead — once for whichever pane is up, so the ⚙ button still
    // works while the Windows pane is showing. Rendering it here too would stack
    // two dialogs over one tap.
    if (showQuickSettings && !embedded) {
        // Pass the screen's viewModel explicitly so QuickSettingsDialog uses
        // the same instance rather than resolving a second entry-scoped one via
        // the hiltViewModel() default, which would give it a different (orphaned)
        // instance with stale/empty session state.
        QuickSettingsDialog(
            fontSize = fontSize,
            onFontSizeChange = { viewModel.setTerminalFontSize(it) },
            onDismiss = { viewModel.closeQuickSettings() },
            showExtraKeys = showExtraKeys,
            onToggleExtraKeys = { viewModel.updateShowExtraKeys(it) },
            hapticsEnabled = hapticsEnabled,
            onToggleHaptics = { viewModel.updateHapticsEnabled(it) },
            colorTheme = colorTheme,
            onColorThemeChange = { viewModel.setTerminalColorTheme(it) },
            terminalFont = terminalFont,
            onFontChange = { viewModel.setTerminalFont(it) },
            viewModel = viewModel,
        )
    }

    // Standalone, this screen is the bottom of the window, so it insets itself for
    // the system bars and the IME. Embedded, DualTerminalScreen owns the pane and
    // neither side applies an IME inset — see the note at the extra-keys row.
    val bottomInset = if (embedded) {
        Dp(0f)
    } else {
        with(LocalDensity.current) {
            maxOf(
                WindowInsets.ime.getBottom(this),
                WindowInsets.navigationBars.getBottom(this),
            ).let { Dp(it.toFloat()) }
        }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomInset)
    ) {
        if (!embedded) {
            PodroidTopBar(
                title = stringResource(R.string.terminal_title),
                navigationIcon = {
                    IconButton(onClick = {
                        // The IME here belongs to the embedded TerminalView (an
                        // AndroidView), not a Compose text input session, so
                        // LocalSoftwareKeyboardController would be a no-op. Go
                        // through the platform insets controller instead, so the
                        // keyboard starts closing before the back transition; the
                        // DisposableEffect in TerminalSurface guarantees the hide
                        // for every other way this screen is left.
                        (context as? Activity)?.let { activity ->
                            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                                .hide(WindowInsetsCompat.Type.ime())
                        }
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showServerSheet = true }) {
                        Icon(Icons.Default.Bedtime, contentDescription = stringResource(R.string.server_mode))
                    }
                    Spacer(Modifier.width(PodroidTokens.Spacing.XS))
                    IconButton(onClick = onNavigateToX11) {
                        Icon(Icons.Default.DesktopWindows, contentDescription = stringResource(R.string.x11_open))
                    }
                    Spacer(Modifier.width(PodroidTokens.Spacing.XS))
                    IconButton(onClick = { viewModel.openQuickSettings() }) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        }

        if (showServerSheet) {
            ServerModeSheet(
                onDismiss = { showServerSheet = false },
                onEnable = { viewModel.enableServerMode() },
            )
        }

        when (vmState) {
            is VmState.Idle, is VmState.Stopped -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.status_stopped),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                        Text(
                            stringResource(R.string.vm_not_running_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is VmState.Error -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = PodroidTokens.Spacing.XL),
                    ) {
                        Text(
                            text = stringResource(R.string.error_title),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                        Text(
                            (vmState as VmState.Error).message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(PodroidTokens.Spacing.LG))
                        PodroidGhostButton(
                            text = stringResource(R.string.back_to_home),
                            onClick = onNavigateBack,
                            modifier = Modifier.fillMaxWidth(0.6f),
                        )
                    }
                }
            }

            is VmState.Starting -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.status_starting) + "…",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            is VmState.Running -> {
                // Hoisted into its own composable so toggling chrome state in the
                // parent (showQuickSettings, showExtraKeys, hapticsEnabled, modifier
                // keys, etc.) doesn't invalidate the AndroidView slot. TerminalSurface
                // takes only the ViewModel (stable @HiltViewModel) so Compose's
                // restart-scope skipping kicks in: chrome toggles → parent recomposes,
                // surface skips. The terminal pixels never re-route through Compose
                // unless something the surface actually reads has changed.
                TerminalSurface(
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }

        if (showExtraKeys) {
            // The IME inset goes here, on the keys row only — not on the pane.
            //
            // No IME inset on this row. An earlier attempt padded it by the IME height
            // inside a Box(alignment = BottomCenter); with a keyboard ~300 dp tall
            // that combination pushed the keys out of the visible area entirely,
            // which is worse than the gap it was meant to close. The pane is laid
            // out with no bottom inset at all (see DualTerminalScreen), so the
            // row simply sits at the bottom of whatever space the pane has.
            AdaptiveContainer(
                windowSizeClass = windowSizeClass,
                maxWidth = 800
            ) {
                ExtraKeysRow(
                    onKey = { viewModel.sendExtraKey(it) },
                    ctrlActive = viewModel.extraCtrl,
                    altActive = viewModel.extraAlt,
                    shiftActive = viewModel.extraShift,
                )
            }
        }
    }
}

/**
 * Re-creates the terminal session via [viewModel] and, if one now exists,
 * wires it onto [view]. Shared by TerminalSurface's first-bind
 * DisposableEffect (which proceeds regardless of the result) and its
 * dead-session reconnect LaunchedEffect (which bails out when this returns
 * null).
 */
private fun attachSession(viewModel: TerminalViewModel, view: TerminalView): TerminalSession? {
    viewModel.resetOnRestart()
    viewModel.createSession()
    val sess = viewModel.session
    if (sess != null) {
        view.mTermSession = sess
        view.mEmulator = sess.emulator
    }
    return sess
}

/**
 * Owns the TerminalView. Isolated as its own composable so chrome state
 * (quick-settings sheet, extra-keys toggle, haptics, etc.) lives in the
 * parent's restart scope and never invalidates this slot. The only reads
 * here drive things the View genuinely needs: typeface, palette, font size.
 *
 * `update = { }` — the View manages its own state. All View setters are
 * driven by narrow-keyed LaunchedEffects so Compose only touches the View
 * when the actual driving input changes, never on incidental recomposition.
 */
@Composable
private fun TerminalSurface(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val colorTheme by viewModel.terminalColorTheme.collectAsStateWithLifecycle()
    val terminalFont by viewModel.terminalFont.collectAsStateWithLifecycle()

    // Resolve typeface + palette here (cheap: asset reads, font cache lookup).
    // Each is keyed on its specific upstream string so a theme change does not
    // re-resolve the font and vice versa.
    val typeface = remember(terminalFont) { viewModel.loadFont(terminalFont) }
    val themeBg = remember(colorTheme) { viewModel.loadColorTheme(colorTheme) }

    Box(modifier = modifier) {
        // The View is created per-Composition-context (i.e. per Activity).
        // Caching it across config changes used to leak the destroyed Activity.
        val view = remember(context) {
            TerminalView(context, null).apply {
                setTextSize(fontSize)
                keepScreenOn = true
                isFocusable = true
                isFocusableInTouchMode = true
            }
        }

        // Theme background — keyed only on themeBg so font/size changes don't
        // re-fire it. We also force a repaint here because loadColorTheme()
        // pushes the new palette into the live session's mCurrentColors, but
        // doesn't itself trigger an invalidate — without this the screen
        // keeps painting with the old colors until the next PTY byte.
        LaunchedEffect(view, themeBg) {
            view.setBackgroundColor(themeBg ?: android.graphics.Color.BLACK)
            view.onScreenUpdated()
        }

        // Typeface — keyed only on the resolved Typeface. Re-pushes size to the
        // session because cell metrics (charWidth/lineHeight) change with the
        // font, not just with text size. Use only view.updateSize() (its row
        // math subtracts mFontLineSpacingAndAscent); calling
        // forceUpdateSizeFromView in addition disagrees by ±1 row and causes
        // cursor flicker.
        LaunchedEffect(view, typeface) {
            view.setTypeface(typeface)
            view.post { view.updateSize() }
        }

        // Font size — keyed only on the int. Replaces the old guard-in-update
        // pattern: now `update = { }` is a true no-op and Compose touches the
        // View only when fontSize actually changes.
        LaunchedEffect(view, fontSize) {
            view.setTextSize(fontSize)
            view.post { view.updateSize() }
        }

        // Session/client binding. Keyed on `view` only — we are inside the
        // VmState.Running branch so vmState identity is stable while we live.
        // (Previous `DisposableEffect(view, vmState)` was wider than necessary;
        // narrowing means transient VM state churn can't re-bind the session.)
        DisposableEffect(view) {
            viewModel.bindView(view)
            attachSession(viewModel, view)
            view.setTerminalViewClient(viewModel.viewClient)
            // No view.requestFocus() here. It ran on every (re)attach — including
            // the one that fires when the app returns from the background while
            // the terminal page is merely the pager's adjacent page — so the IME
            // popped up on resume even when the user was on Home or Files. The
            // TerminalView requests focus itself when tapped, which is when the
            // keyboard should appear.
            view.onScreenUpdated()

            // view.updateSize() only — its row math subtracts
            // mFontLineSpacingAndAscent. forceUpdateSizeFromView disagrees by
            // ±1 row in some sizes and causes a visible cursor flicker on
            // first paint.
            view.post {
                view.updateSize()
                view.onScreenUpdated()
            }

            // Cursor blinker: the host app must opt in — TerminalView ships the
            // mechanism but never starts itself. 500ms is the conventional rate.
            // startOnlyIfCursorEnabled=false because the renderer already gates
            // paint via shouldCursorBeVisible() (which honors ?25l), so it's
            // safe to leave the FrameCallback driving and let the emulator say
            // when the cursor is hidden.
            view.setTerminalCursorBlinkerRate(500)
            view.setTerminalCursorBlinkerState(true, false)

            onDispose {
                viewModel.bindView(null)
                // Guaranteed hide for every way this screen is left (back arrow, X11
                // navigation, system back, or the VM stopping): the IME is owned by this
                // TerminalView, not a Compose text field, so LocalSoftwareKeyboardController
                // can't reach it. Use decorView, not `view`: this TerminalView may already
                // be detached when onDispose runs, and hide() no-ops on a detached view.
                (context as? Activity)?.let { activity ->
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        .hide(WindowInsetsCompat.Type.ime())
                }
            }
        }

        // Dead-session auto-reconnect: when the bridge dies while the VM stays
        // Running, the ViewModel bumps reconnectSignal. Re-create the session and
        // re-attach it to this same view so the user isn't stranded on a dead
        // "[Process completed]" buffer. Skips the initial 0 (the DisposableEffect
        // above owns the first attach).
        val reconnectSignal by viewModel.reconnectSignal.collectAsStateWithLifecycle()
        LaunchedEffect(view, reconnectSignal) {
            if (reconnectSignal == 0) return@LaunchedEffect
            attachSession(viewModel, view) ?: return@LaunchedEffect
            view.onScreenUpdated()
            view.post {
                view.updateSize()
                view.onScreenUpdated()
            }
        }

        // Pause the blinker when the activity is backgrounded. Without this
        // the Choreographer FrameCallback keeps firing every VSYNC in
        // paused-without-detach states (split-screen, dialog occlusion, PiP).
        // onDetachedFromWindow already handles the full-detach case.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(view, lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> view.setTerminalCursorBlinkerState(true, false)
                    Lifecycle.Event.ON_PAUSE  -> view.setTerminalCursorBlinkerState(false, false)
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Layout-change debounce: keyboard slide animation fires ~25 layout
        // events. Coroutine debounce collapses them to one SIGWINCH. We also
        // tell the View to suppress its blink toggle during the settle window
        // so the cursor doesn't visibly flicker on/off mid-animation.
        val scope = rememberCoroutineScope()
        DisposableEffect(view) {
            var pending: kotlinx.coroutines.Job? = null
            val listener = android.view.View.OnLayoutChangeListener {
                v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val w = right - left
                val h = bottom - top
                if (w <= 0 || h <= 0) return@OnLayoutChangeListener
                if (w == oldRight - oldLeft && h == oldBottom - oldTop) return@OnLayoutChangeListener
                pending?.cancel()
                val tv = v as TerminalView
                // Repaint straight away at the new canvas size, on every layout
                // event. This keeps pixels flowing while the keyboard slides; the
                // existing grid still fits, so it costs one invalidate and no
                // guest round-trip. Holding this back until the debounce fired
                // left the terminal frozen for the length of the slide animation,
                // which read as a stutter right after the blank-screen fix.
                tv.onScreenUpdated()
                // Only the expensive half is debounced: updateSize() recomputes the
                // row/column grid and raises SIGWINCH in the guest, so ~25 of those
                // across one slide animation means the shell redraws 25 times.
                tv.setLayoutSettling(true)
                pending = scope.launch {
                    // 64 ms ≈ 4 VSYNCs — enough to coalesce a keyboard-slide
                    // burst (~25 layout events over the 200 ms slide animation)
                    // without leaving the prompt visibly lagging behind.
                    kotlinx.coroutines.delay(64)
                    // Just tv.updateSize() — it has the correct row math
                    // (subtracts mFontLineSpacingAndAscent). Calling
                    // forceUpdateSizeFromView too caused a row-count race:
                    // the two computations disagree by 1 in some sizes,
                    // triggering two back-to-back resizes per keyboard
                    // slide and a visible cursor flicker.
                    tv.updateSize()
                    // updateSize() only recomputes the row/column grid — it does
                    // NOT schedule a repaint. Every other resize path in this file
                    // pairs the two calls (theme/font effects, the reconnect
                    // effect, and the first-bind post above all do), and this one
                    // did not: after the keyboard changed the View's size the grid
                    // was correct but nothing was drawn, so the terminal stayed
                    // blank until the next PTY byte arrived — which, while typing
                    // into a prompt that had already drained, was not until the
                    // keyboard was dismissed.
                    tv.onScreenUpdated()
                    tv.setLayoutSettling(false)
                }
            }
            view.addOnLayoutChangeListener(listener)
            onDispose {
                pending?.cancel()
                view.setLayoutSettling(false)
                view.removeOnLayoutChangeListener(listener)
            }
        }

        AndroidView(
            factory = { view },
            update = { },
            modifier = Modifier.fillMaxSize(),
        )

        // Auto-reconnect gave up (the bridge kept dying): stop respawning it and
        // let the user re-trigger a connection by tapping. Cleared on retry or a
        // fresh VM run (see TerminalViewModel.reconnectExhausted).
        val reconnectExhausted by viewModel.reconnectExhausted.collectAsStateWithLifecycle()
        if (reconnectExhausted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { viewModel.retryConnection() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.terminal_disconnected_tap_retry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun ExtraKeysRow(
    onKey: (String) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
) {
    val scroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth()) {
        // 1-px separator so the row reads as chrome, not as terminal content.
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                // fadingEdgesHorizontal() intentionally removed.
                //
                // It faded the two scroll edges with BlendMode.DstIn inside an
                // Offscreen graphicsLayer. DstIn is subtractive: it multiplies the
                // whole layer's alpha by the source alpha, so anything the layer
                // does not cover is erased to transparent. When the keyboard opened
                // and this row was re-measured, the offscreen layer failed to track
                // the new bounds and the row rendered fully transparent — showing the
                // near-white window background over the terminal until the keyboard was
                // dismissed and the layer was rebuilt.
                //
                // Purely decorative (edge fade on horizontal overflow), so dropping it
                // costs nothing but the fade. Restoring it later needs an alpha mask
                // that does not erase the layer outside the faded strips.
                .horizontalScroll(scroll)
                // Vertical padding trimmed: the row's own 8dp plus each key's 9dp
                // stacked to ~50dp of chrome, which on a phone is a third of the
                // lines the terminal can show. The keys keep a 48dp-tall touch
                // target via the height below.
                .padding(horizontal = PodroidTokens.Spacing.SM, vertical = PodroidTokens.Spacing.XS),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton("ESC", onKey)
            KeyButton("TAB", onKey)
            // Backspace. The most-used key in a terminal, and it was absent:
            // editing a long pasted command meant reaching for the soft
            // keyboard. 127 matches what Android's KEYCODE_DEL already sends on
            // line 386, so the on-screen key and the hardware path agree.
            KeyButton("DEL", onKey)
            // Label is English while the sent key name stays "ENTER": the ↵ glyph read as
            // a symbol rather than a key name, and every other key in this row is
            // already Latin text (ESC/TAB/CTRL/HOME/F1…), so it was the odd one
            // out.
            KeyButton("ENTER", onKey, sendKey = "ENTER")
            KeyButton("CTRL", onKey, isActive = ctrlActive)
            KeyButton("SHIFT", onKey, isActive = shiftActive)
            KeyButton("\u2190", onKey, sendKey = "LEFT",  repeatable = true)
            KeyButton("\u2191", onKey, sendKey = "UP",    repeatable = true)
            KeyButton("\u2193", onKey, sendKey = "DOWN",  repeatable = true)
            KeyButton("\u2192", onKey, sendKey = "RIGHT", repeatable = true)
            KeyButton("ALT", onKey, isActive = altActive)
            KeyButton("PASTE", onKey)
            KeyButton("-", onKey); KeyButton("/", onKey); KeyButton("|", onKey)
            KeyButton("HOME", onKey); KeyButton("END", onKey)
            KeyButton("PGUP", onKey); KeyButton("PGDN", onKey)
            (1..12).forEach { KeyButton("F$it", onKey) }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    onKey: (String) -> Unit,
    sendKey: String = label,
    isActive: Boolean = false,
    repeatable: Boolean = false,
) {
    val tapModifier = rememberExtraKeyTapModifier(sendKey, repeatable, onKey)
    Text(
        text = label,
        color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = PodroidTokens.mono(),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(PodroidTokens.Radius.Chip))
            .background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(PodroidTokens.Radius.Chip))
            .then(tapModifier)
            .heightIn(min = 34.dp)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    )
}
