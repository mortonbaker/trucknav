package com.morton.trucknav.ui.theme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
private val CockpitScheme = darkColorScheme(
    primary = CockpitColors.Accent, onPrimary = CockpitColors.Background,
    primaryContainer = CockpitColors.Selected, onPrimaryContainer = CockpitColors.Text,
    inversePrimary = CockpitColors.Selected,
    secondary = CockpitColors.Accent, onSecondary = CockpitColors.Background,
    secondaryContainer = CockpitColors.Raised, onSecondaryContainer = CockpitColors.Text,
    tertiary = CockpitColors.Accent, onTertiary = CockpitColors.Background,
    tertiaryContainer = CockpitColors.Raised, onTertiaryContainer = CockpitColors.Text,
    background = CockpitColors.Background, onBackground = CockpitColors.Text,
    surface = CockpitColors.Card, onSurface = CockpitColors.Text,
    surfaceVariant = CockpitColors.Raised, onSurfaceVariant = CockpitColors.Secondary,
    surfaceTint = Color.Transparent,
    inverseSurface = CockpitColors.Text, inverseOnSurface = CockpitColors.Background,
    error = CockpitColors.Error, onError = CockpitColors.Background,
    errorContainer = CockpitColors.ErrorContainer, onErrorContainer = CockpitColors.Text,
    outline = CockpitColors.Outline, outlineVariant = CockpitColors.Raised, scrim = Color.Black,
    surfaceBright = CockpitColors.Raised, surfaceDim = CockpitColors.Background,
    surfaceContainerLowest = CockpitColors.Background, surfaceContainerLow = CockpitColors.Rail,
    surfaceContainer = CockpitColors.Card, surfaceContainerHigh = CockpitColors.Raised,
    surfaceContainerHighest = CockpitColors.Raised,
)
@Composable
fun FerrostarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CockpitScheme, typography = Typography, content = content)
}
