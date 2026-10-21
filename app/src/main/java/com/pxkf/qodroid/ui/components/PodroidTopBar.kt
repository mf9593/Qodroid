package com.pxkf.qodroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pxkf.qodroid.ui.theme.PodroidTokens

/**
 * Podroid's app bar: surface background, 1-px bottom border, medium-weight
 * title, standard navigationIcon + actions slots.
 *
 * Hand-rolled rather than a Material `TopAppBar`: that one is a fixed 64 dp
 * tall, which burned a sixth of a phone screen on a single line of title.
 * [BAR_HEIGHT] is 48 dp — the same height Material's *small* app bar uses for
 * touch targets, so the icon buttons keep their 48 dp hit area.
 *
 * [windowInsets] defaults to the status bar (plus horizontal cutouts in
 * landscape) so the bar sits *below* the system status bar; pass
 * `WindowInsets(0)` only for a host that already pads it once for every page
 * (the pager host does).
 */
@Composable
fun PodroidTopBar(
    title: String,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.systemBars.only(
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
    ),
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // The old TopAppBarColors call set these explicitly; a bare Row would
        // inherit whatever the host left in LocalContentColor, which on some
        // pages is not the scheme's onSurface.
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(windowInsets)
                    .height(BAR_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back/leading slot: wraps its content, does NOT take the
                // leftover width. A weight(1f) here used to swallow the row and
                // push the title + actions off to the right edge.
                Box(contentAlignment = Alignment.CenterStart) {
                    navigationIcon()
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Absorbs ALL the leftover width, so the actions row below is
                    // pushed flush against the right edge with nothing to its
                    // right.
                    //
                    // This used to be weight(1f, fill = false) followed by a
                    // second Spacer(weight(1f)), splitting the remainder in half
                    // between title and gap. That renders the title hugging the
                    // back arrow with a half-width void before the actions — on
                    // a short title it reads as the button cluster floating in
                    // the middle. One weighted title is both simpler and the only
                    // arrangement that guarantees right-alignment.
                    //
                    // Leading padding is MD (12dp), not XS (4dp): the trailing
                    // action icons sit 12dp from the edge (48dp IconButton minus
                    // its 12dp internal pad), so 4dp here left the title hugging
                    // the screen edge while the right side looked inset. 12dp on
                    // both sides reads as one margin rather than two unrelated
                    // ones — most visible on the pager pages, which pass no
                    // navigationIcon and so had nothing but this 4dp between the
                    // title and the bezel.
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = PodroidTokens.Spacing.MD, end = PodroidTokens.Spacing.MD),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
        )
    }
}

private val BAR_HEIGHT = 48.dp
