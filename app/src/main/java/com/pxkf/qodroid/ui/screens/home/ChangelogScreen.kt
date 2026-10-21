package com.pxkf.qodroid.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pxkf.qodroid.R
import com.pxkf.qodroid.ui.components.PodroidSectionLabel
import com.pxkf.qodroid.ui.components.PodroidTopBar
import com.pxkf.qodroid.ui.theme.PodroidTokens

/**
 * Changelog page, reached from the About page.
 *
 * Its own destination rather than a block on About: that page is the reference
 * sheet for the build in front of you, and a long change list does not belong
 * competing with it for the first screenful. A page keeps both complete without
 * either one truncating the other.
 *
 * [entries] is data, not UI: the version label and its items are resource ids,
 * so adding a release means appending a section here and the page grows to fit.
 * Newest first, matching how a changelog is actually read.
 */
@Composable
fun ChangelogScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            PodroidTopBar(
                title = stringResource(R.string.about_changelog_title),
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
                .padding(
                    start = PodroidTokens.Spacing.LG,
                    top = PodroidTokens.Spacing.SM,
                    end = PodroidTokens.Spacing.LG,
                    bottom = PodroidTokens.Spacing.XL2,
                ),
            verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
        ) {
            entries.forEach { (titleRes, items) ->
                ChangelogEntry(
                    title = stringResource(titleRes),
                    items = items,
                )
            }
        }
    }
}

/**
 * One release: a version label, then the changes as plain lines.
 *
 * No dividers between items — the items are short and a line for each would add
 * visual noise that reads as more important than it is.
 */
@Composable
private fun ChangelogEntry(
    title: String,
    items: List<Int>,
) {
    PodroidSectionLabel(title)
    Column(verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.XS)) {
        items.forEach { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Every release in this app, newest first. */
private val entries: List<Pair<Int, List<Int>>> = listOf(
    R.string.changelog_200_title to listOf(
        R.string.changelog_200_3,
        R.string.changelog_200_4,
        R.string.changelog_200_5,
        R.string.changelog_200_7,
        R.string.changelog_200_8,
        R.string.changelog_200_12,
        R.string.changelog_200_13,
        R.string.changelog_200_15,
        R.string.changelog_200_16,
        R.string.changelog_200_17,
    ),
)
