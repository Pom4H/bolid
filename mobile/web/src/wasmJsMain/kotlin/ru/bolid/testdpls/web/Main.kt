package ru.bolid.testdpls.web

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ru.bolid.testdpls.core.app.DplsApp
import ru.bolid.testdpls.core.app.DplsClient

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "phone") {
        val client = remember {
            DplsClient(LabBleTransport(), LabPlatformServices())
        }
        DisposableEffect(Unit) {
            onDispose { client.close() }
        }
        DplsApp(controller = client)
    }
}
