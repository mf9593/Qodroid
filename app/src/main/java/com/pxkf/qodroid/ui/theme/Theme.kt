/*
 * Podroid Compose theme.
 *
 * Default identity: fixed dark/light palette derived from PodroidTokens, with
 * a lime-green accent (#4ade80). Material You wallpaper-derived dynamic color
 * is OFF by default — users opt in via Settings → Appearance → Dynamic color,
 * which flows in via the dynamicColor parameter.
 */
package com.pxkf.qodroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val PodroidDark = darkColorScheme(
    primary             = PodroidAccent,
    onPrimary           = PodroidAccentInk,
    primaryContainer    = PodroidDarkSurface2,
    onPrimaryContainer  = PodroidDarkText,

    secondary           = PodroidAccent,
    onSecondary         = PodroidAccentInk,
    secondaryContainer  = PodroidDarkSurface2,
    onSecondaryContainer= PodroidDarkText,

    tertiary            = PodroidAmber,
    onTertiary          = PodroidAccentInk,

    background          = PodroidDarkBg,
    onBackground        = PodroidDarkText,
    surface             = PodroidDarkSurface,
    onSurface           = PodroidDarkText,
    surfaceVariant      = PodroidDarkSurface2,
    onSurfaceVariant    = PodroidDarkTextMute,
    surfaceContainer    = PodroidDarkSurface2,
    surfaceContainerHighest = PodroidDarkSurface2,

    outline             = PodroidDarkBorder,
    outlineVariant      = PodroidDarkBorder,

    error               = PodroidRed,
    onError             = PodroidDarkText,
    errorContainer      = PodroidDarkSurface2,
    onErrorContainer    = PodroidRed,
)

private val PodroidLight = lightColorScheme(
    primary             = PodroidAccent,
    onPrimary           = PodroidAccentInk,
    primaryContainer    = PodroidLightSurface2,
    onPrimaryContainer  = PodroidLightText,

    secondary           = PodroidAccent,
    onSecondary         = PodroidAccentInk,
    secondaryContainer  = PodroidLightSurface2,
    onSecondaryContainer= PodroidLightText,

    tertiary            = PodroidAmber,
    onTertiary          = PodroidLightText,

    background          = PodroidLightBg,
    onBackground        = PodroidLightText,
    surface             = PodroidLightSurface,
    onSurface           = PodroidLightText,
    surfaceVariant      = PodroidLightSurface2,
    onSurfaceVariant    = PodroidLightTextMute,
    surfaceContainer    = PodroidLightSurface,
    surfaceContainerHighest = PodroidLightSurface2,

    outline             = PodroidLightBorder,
    outlineVariant      = PodroidLightBorder,

    error               = PodroidRed,
    onError             = PodroidLightText,
    errorContainer      = PodroidLightSurface2,
    onErrorContainer    = PodroidRed,
)

@Composable
fun PodroidTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val effectiveDark = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        effectiveDark -> PodroidDark
        else          -> PodroidLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = buildPodroidTypography(),
        content     = content,
    )
}
