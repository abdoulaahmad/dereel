package app.dereel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.dereel.core.model.Platform

// A calm palette chosen for contrast: body text is at least 4.5:1 on its surface in both
// themes (NFR-03). Status never relies on colour alone, because every chip also has an icon and text.
private val Light = lightColorScheme(
    primary = Color(0xFF1C6A61),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4EDE2),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261900),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF6FAF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF98D3C7),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFB4EDE2),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFF5BE48),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B2120),
    surfaceContainerHigh = Color(0xFF252B2A),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
)

/** Softer, rounder surfaces than the Material defaults, used on every screen. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun DereelTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (darkTheme) Dark else Light
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}

/**
 * A text colour that tells platforms apart in figures such as today's total. These are Dereel's
 * own tints, chosen for contrast on the app's surfaces (the brands' own colours are not all
 * readable as text), and a platform is always named beside the figure as well. The logos
 * themselves are in ui/common/PlatformLogos.
 */
@Composable
fun platformAccent(platform: Platform): Color {
    val dark = isSystemInDarkTheme()
    return when (platform) {
        Platform.INSTAGRAM -> if (dark) Color(0xFFE7A6DA) else Color(0xFFA2428F)
        Platform.YOUTUBE -> if (dark) Color(0xFFF2B27A) else Color(0xFFB5651D)
        Platform.FACEBOOK -> if (dark) Color(0xFFA9C4FF) else Color(0xFF2F62C4)
        Platform.SNAPCHAT -> if (dark) Color(0xFFE0D56A) else Color(0xFF8A7E00)
    }
}
