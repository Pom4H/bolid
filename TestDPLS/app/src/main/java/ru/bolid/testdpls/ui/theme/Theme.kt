package ru.bolid.testdpls.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BB4FF),
    onPrimary = Color(0xFF002E69),
    background = Color(0xFF071923),
    onBackground = Color(0xFFF2F6F8),
    surface = Color(0xFF0C202B),
    onSurface = Color(0xFFF2F6F8),
    surfaceVariant = Color(0xFF142A35),
    onSurfaceVariant = Color(0xFFB8C8D1),
    outline = Color(0xFF78909C),
    error = Color(0xFFFFB59B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF075DB7),
    onPrimary = Color.White,
    background = Color(0xFFF7FAFC),
    onBackground = Color(0xFF102027),
    surface = Color.White,
    onSurface = Color(0xFF102027),
    surfaceVariant = Color(0xFFE7EEF2),
    onSurfaceVariant = Color(0xFF43545D),
    outline = Color(0xFF6F8088),
    error = Color(0xFFB3261E),
)

@Composable
fun TestDplsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.value.toInt()
            window.navigationBarColor = colors.background.value.toInt()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
