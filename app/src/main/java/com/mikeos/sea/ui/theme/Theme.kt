package com.mikeos.sea.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MikeSea palette — deep sea #0a1119, teal accent #22d3a0, info blue #3aa0ff.
val SeaAccent = Color(0xFF22D3A0)
val SeaBlue = Color(0xFF3AA0FF)
val SeaBg = Color(0xFF0A1119)
val SeaSurface = Color(0xFF0E1621)
val SeaSurfaceVariant = Color(0xFF16202D)
val SeaOnSurface = Color(0xFFE7EEF6)
val SeaMuted = Color(0xFF8AA0B6)
val SeaRed = Color(0xFFF85149)

private val SeaDarkColors = darkColorScheme(
    primary = SeaAccent,
    onPrimary = Color(0xFF04120C),
    secondary = SeaBlue,
    background = SeaBg,
    onBackground = SeaOnSurface,
    surface = SeaSurface,
    onSurface = SeaOnSurface,
    surfaceVariant = SeaSurfaceVariant,
    onSurfaceVariant = SeaMuted,
    error = SeaRed,
)

@Composable
fun MikeSeaTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SeaDarkColors,
        typography = Typography(),
        content = content,
    )
}
