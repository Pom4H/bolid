package ru.bolid.testdpls.core.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController

/** The only iOS UI entry point exported by the KMP framework. */
fun MainViewController(): UIViewController {
    val client = DplsClient(IosBleTransport(), IosPlatformServices)
    var host: UIViewController? = null
    val viewController = ComposeUIViewController {
        DisposableEffect(Unit) {
            onDispose(client::close)
        }
        DplsApp(
            controller = client,
            shareText = { _, text ->
                host?.presentViewController(
                    UIActivityViewController(
                        activityItems = listOf(text),
                        applicationActivities = null,
                    ),
                    animated = true,
                    completion = null,
                )
            },
        )
    }
    host = viewController
    return viewController
}
