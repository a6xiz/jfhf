package app.comfypix.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Friendly "animation studio" palette: sky blue, lamp yellow, coral, mint on deep navy.
val Sky = Color(0xFF2F7BFF)
val Sun = Color(0xFFFFC53D)
val Coral = Color(0xFFFF6B5E)
val Mint = Color(0xFF2FCB9B)
val Navy = Color(0xFF16224A)

private val LightColors = lightColorScheme(
    primary = Sky,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FF),
    onPrimaryContainer = Navy,
    secondary = Sun,
    onSecondary = Navy,
    secondaryContainer = Color(0xFFFFEBB3),
    onSecondaryContainer = Navy,
    tertiary = Coral,
    background = Color(0xFFEAF4FF),
    onBackground = Navy,
    surface = Color.White,
    onSurface = Navy,
    surfaceVariant = Color(0xFFE3EEFB),
    onSurfaceVariant = Color(0xFF51608A),
    outline = Color(0xFFB4C4E4),
    error = Color(0xFFD93A3A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EA5FF),
    onPrimary = Navy,
    primaryContainer = Color(0xFF223A78),
    onPrimaryContainer = Color.White,
    secondary = Sun,
    onSecondary = Navy,
    secondaryContainer = Color(0xFF5B4A14),
    onSecondaryContainer = Color.White,
    tertiary = Coral,
    background = Color(0xFF0F1530),
    onBackground = Color(0xFFEEF2FF),
    surface = Color(0xFF1A2347),
    onSurface = Color(0xFFEEF2FF),
    surfaceVariant = Color(0xFF243060),
    onSurfaceVariant = Color(0xFFB4BEE0),
    outline = Color(0xFF47558F),
    error = Color(0xFFFF7B7B),
)

private val ComfyShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun ComfyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.surface.toArgb()
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, shapes = ComfyShapes, typography = Typography(), content = content)
}
