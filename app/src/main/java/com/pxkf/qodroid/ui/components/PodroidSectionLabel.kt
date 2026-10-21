package com.pxkf.qodroid.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.pxkf.qodroid.ui.theme.PodroidTokens

/**
 * Tiny uppercase tracked label used as a section heading throughout the app.
 * Pairs naturally with PodroidListRow groups.
 */
@Composable
fun PodroidSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = PodroidTokens.ui(),
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(top = PodroidTokens.Spacing.LG, bottom = PodroidTokens.Spacing.XS),
    )
}
