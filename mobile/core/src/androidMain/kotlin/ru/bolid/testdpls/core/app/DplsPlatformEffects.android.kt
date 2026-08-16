package ru.bolid.testdpls.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import ru.bolid.testdpls.core.domain.DplsUiState

@Composable
internal actual fun PlatformSessionEffects(state: DplsUiState) {
    val view = LocalView.current
    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
}
