package com.pxkf.qodroid.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pxkf.qodroid.R
import com.pxkf.qodroid.ui.components.PodroidTopBar
import com.pxkf.qodroid.ui.theme.PodroidTokens

/**
 * In-app documentation: the everyday operations, on one page.
 *
 * The project's docs live on a website and in the repo, neither of which the
 * app can reach offline or without the user going somewhere else to read. This
 * page covers the handful of things a user actually has to type: starting the
 * VM, creating a container, reaching it from the phone, and backing up.
 */
@Composable
fun DocsScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            PodroidTopBar(
                title = stringResource(R.string.docs_title),
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
            verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.LG),
        ) {
            docSections.forEach { section -> DocSectionView(section) }
            Spacer(Modifier.height(PodroidTokens.Spacing.XL2))
        }
    }
}

/**
 * Render one documentation section: a title, an explanation, optionally a
 * command block the user can copy, and optional bullets.
 */
@Composable
private fun DocSectionView(section: DocSection) {
    Column(verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM)) {
        Text(
            text = stringResource(section.titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(section.introRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (section.codeRes != 0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(
                    text = stringResource(section.codeRes),
                    modifier = Modifier.padding(PodroidTokens.Spacing.LG),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        section.bullets.forEach { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One documentation section: title, explanation, a command block and bullets. */
private data class DocSection(
    val titleRes: Int,
    val introRes: Int,
    val codeRes: Int = 0,
    val bullets: List<Int> = emptyList(),
)

/** The order the sections appear in on the page. */
private val docSections: List<DocSection> = listOf(
    DocSection(
        R.string.docs_start_title,
        R.string.docs_start_desc,
        R.string.docs_start_code,
    ),
    DocSection(
        R.string.docs_create_title,
        R.string.docs_create_desc,
        R.string.docs_create_code,
    ),
    DocSection(
        R.string.docs_ports_title,
        R.string.docs_ports_desc,
        R.string.docs_ports_code,
    ),
    DocSection(
        R.string.docs_backup_title,
        R.string.docs_backup_desc,
        R.string.docs_backup_code,
    ),
    DocSection(
        R.string.docs_faq_title,
        R.string.docs_faq_desc,
        bullets = listOf(
            R.string.docs_faq_no_backup,
            R.string.docs_faq_no_containers,
            R.string.docs_faq_pull_fail,
            R.string.docs_faq_share,
            R.string.docs_faq_dns,
        ),
    ),
)
