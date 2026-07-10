package com.thebutton.ble.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF005B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2DD),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635D),
    tertiary = Color(0xFF3F6374),
    error = Color(0xFFBA1A1A),
    surface = Color(0xFFF7FAF8),
    surfaceVariant = Color(0xFFDAE5E1),
)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF82D5C1),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005044),
    onPrimaryContainer = Color(0xFF9EF2DD),
    secondary = Color(0xFFB1CCC4),
    tertiary = Color(0xFFA7CDDF),
    error = Color(0xFFFFB4AB),
    surface = Color(0xFF101412),
    surfaceVariant = Color(0xFF3F4945),
)

@Composable
fun TheButtonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
