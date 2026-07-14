package ru.bolid.testdpls.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Colors = darkColorScheme(
    primary = Color(0xFF2878E8), onPrimary = Color.White,
    background = Color(0xFF071923), onBackground = Color(0xFFF2F6F8),
    surface = Color(0xFF0C202B), onSurface = Color(0xFFF2F6F8),
    surfaceVariant = Color(0xFF142A35), onSurfaceVariant = Color(0xFF91A2AC),
    outline = Color(0xFF263B46), error = Color(0xFFFF6A2A),
)

@Composable fun TestDplsTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = Color(0xFF071923).value.toInt()
        window.navigationBarColor = Color(0xFF071923).value.toInt()
        WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
    }
    MaterialTheme(colorScheme = Colors, content = content)
}
