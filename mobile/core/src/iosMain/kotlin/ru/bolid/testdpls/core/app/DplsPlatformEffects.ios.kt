package ru.bolid.testdpls.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication
import ru.bolid.testdpls.core.domain.DplsUiState

@Composable
internal actual fun PlatformSessionEffects(state: DplsUiState) {
    DisposableEffect(state.keepScreenOn) {
        UIApplication.sharedApplication.idleTimerDisabled = state.keepScreenOn
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}
