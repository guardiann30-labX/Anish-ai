package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.model.ThemePreference

private val LightColorScheme = lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = Color.White,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = TerracottaOnContainer,
    secondary = SageSecondary,
    onSecondary = Color.White,
    secondaryContainer = SageContainer,
    onSecondaryContainer = SageOnContainer,
    tertiary = OchreTertiary,
    onTertiary = Color.White,
    tertiaryContainer = OchreContainer,
    onTertiaryContainer = OchreOnContainer,
    background = LinenBackground,
    onBackground = EspressoInk,
    surface = LinenSurface,
    onSurface = EspressoInk,
    surfaceVariant = LinenSurfaceVariant,
    onSurfaceVariant = WarmSlateInk,
    surfaceContainerLow = LinenBackground,
    surfaceContainer = LinenSurface,
    surfaceContainerHigh = LinenSurfaceVariant,
    outline = WarmOutline,
    outlineVariant = WarmOutlineVariant
)

private val DarkColorScheme = darkColorScheme(
    primary = SunlitClayPrimary,
    onPrimary = Color(0xFF2B1007),
    primaryContainer = SunlitClayContainer,
    onPrimaryContainer = SunlitClayOnContainer,
    secondary = EucalyptusSecondary,
    onSecondary = Color(0xFF102518),
    secondaryContainer = EucalyptusContainer,
    onSecondaryContainer = EucalyptusOnContainer,
    tertiary = AmberTertiary,
    onTertiary = Color(0xFF2C1D04),
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberOnContainer,
    background = UmberBackground,
    onBackground = AlabasterInk,
    surface = UmberSurface,
    onSurface = AlabasterInk,
    surfaceVariant = UmberSurfaceVariant,
    onSurfaceVariant = MutedSandInk,
    surfaceContainerLow = UmberBackground,
    surfaceContainer = UmberSurface,
    surfaceContainerHigh = UmberSurfaceVariant,
    outline = UmberOutline,
    outlineVariant = UmberOutlineVariant
)

val AnnShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun AnnTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AnnTypography,
        shapes = AnnShapes,
        content = content
    )
}
