package ru.bolid.testdpls.core.app

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSProcessInfo

/** Debug-only launch-env driver. Product behavior remains in shared [DplsClient]. */
internal suspend fun runIosE2eIfRequested(client: DplsClient) {
    val identify = e2eValue("DPLS_E2E_IDENTIFY", "--e2e-identify") ?: return
    val password = e2eValue("DPLS_E2E_PASSWORD", "--e2e-password")
    try {
        delay(400)
        client.startScan()
        val address = if (identify == "auto") {
            withTimeout(8_000) {
                client.uiState.first { it.devices.isNotEmpty() }.devices.first().address
            }
        } else {
            identify
        }
        client.identify(address)
        withTimeout(25_000) {
            client.uiState.first { it.identifyLedLive || !it.error.isNullOrEmpty() }
        }
        if (!client.uiState.value.identifyLedLive) return
        client.confirmIdentifiedDevice()
        if (password.isNullOrEmpty()) return
        withTimeout(15_000) {
            client.uiState.first { it.awaitingUserPassword || it.credentialsReady || it.authenticated }
        }
        if (client.uiState.value.authenticated) return
        client.updateSetupPassword(password)
        if (!client.uiState.value.initialized) {
            val name = client.uiState.value.setupName.ifBlank { "Test-DPLS-001" }
            client.updateSetupName(name)
            client.updateSetupRepeatPassword(password)
            client.setup(name, password)
        } else {
            client.authenticate(password)
        }
        withTimeout(12_000) {
            client.uiState.first { it.authenticated || !it.error.isNullOrEmpty() }
        }
    } catch (_: TimeoutCancellationException) {
        // Product UI already shows the client error or connect timeout.
    } finally {
        val now = client.uiState.value
        if (now.identifyLedLive || now.identifyActive) client.stopIdentify()
    }
}

private fun e2eValue(envKey: String, flag: String): String? {
    val args = NSProcessInfo.processInfo.arguments.mapNotNull { procString(it) }
    val flagIndex = args.indexOf(flag)
    if (flagIndex >= 0 && flagIndex + 1 < args.size) return args[flagIndex + 1]
    val env = NSProcessInfo.processInfo.environment
    return procString(env[envKey])
}

private fun procString(value: Any?): String? {
    if (value == null) return null
    val text = value.toString()
    return text.takeIf { it.isNotEmpty() }
}
