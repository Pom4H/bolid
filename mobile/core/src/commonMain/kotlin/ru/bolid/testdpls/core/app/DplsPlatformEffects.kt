package ru.bolid.testdpls.core.app

import androidx.compose.runtime.Composable
import ru.bolid.testdpls.core.domain.DplsUiState

/** OS session effects that Compose hosts must apply: keep-screen-on, and nothing else. */
@Composable
internal expect fun PlatformSessionEffects(state: DplsUiState)
