package ru.bolid.testdpls.core.app

import androidx.compose.runtime.Composable
import ru.bolid.testdpls.core.domain.DplsUiState

/** JVM hosts have no idle-timer / keep-screen-on API to wire. */
@Composable
internal actual fun PlatformSessionEffects(@Suppress("UNUSED_PARAMETER") state: DplsUiState) {
    // Intentionally empty for desktop/JVM test hosts.
}
