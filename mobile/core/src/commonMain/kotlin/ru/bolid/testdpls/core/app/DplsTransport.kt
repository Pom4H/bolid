package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.domain.UiTheme

/**
 * OS-neutral BLE transport boundary used by the shared Test-DPLS client.
 *
 * Platform implementations must serialize listener callbacks onto their UI/main
 * event loop. DplsClient deliberately relies on that confinement instead of adding
 * locks around every lifecycle transition.
 */
interface DplsTransport {
    fun setListener(listener: DplsTransportListener)
    fun startScan(): Boolean
    fun stopScan()
    fun connect(address: String): Boolean
    fun reconnect(): Boolean
    fun send(bytes: ByteArray, priority: Boolean = false, flush: Boolean = false): Boolean
    fun readRssi(): Boolean
    fun disconnect(clearSelection: Boolean = true)
    fun hasConnection(): Boolean
    fun close()
}

interface DplsTransportListener {
    fun onBluetoothAvailable()
    fun onBluetoothUnavailable()
    fun onDiscovered(device: DplsTransportDevice)
    fun onConnected()
    fun onSubscribed(writeLimit: Int)
    fun onBytes(bytes: ByteArray)
    fun onWriteComplete(errorCode: Long?)
    fun onDisconnected(error: String?)
    fun onRssi(rssi: Int)
    fun onTransportError(message: String)
    fun onStaleBond()
}

data class DplsTransportDevice(
    val address: String,
    val name: String,
    val deviceId: Long?,
    val rssi: Int,
    val advStatus: Int = 0,
)

/** Tiny platform surface that cannot be made deterministic in commonMain. */
interface DplsPlatformServices {
    fun nowMillis(): Long
    fun secureRandomBytes(count: Int): ByteArray
    fun readUiTheme(): UiTheme = UiTheme.SYSTEM
    fun writeUiTheme(theme: UiTheme) = Unit
    fun readKeepScreenOn(): Boolean = true
    fun writeKeepScreenOn(enabled: Boolean) = Unit
    fun readHapticsEnabled(): Boolean = true
    fun writeHapticsEnabled(enabled: Boolean) = Unit
    fun readDeviceVerifier(deviceKey: String): ByteArray? = null
    fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) = Unit
    fun readDeviceString(key: String): String? = null
    fun writeDeviceString(key: String, value: String?) = Unit
    fun formatLocalDateTime(epochSeconds: Long): String = formatUtcDateTime(epochSeconds)
    fun openBluetoothSettings(): Boolean = false
    fun canOpenSystemBluetoothSettings(): Boolean = false
    fun keepConnectionAlive(active: Boolean) = Unit
    fun notifyOperator(title: String, body: String) = Unit

    /**
     * Optional real-time session breadcrumb for capture tools.
     * Default is silent; Android mirrors selected messages to logcat for
     * `tools/session_capture` and phone E2E timelines.
     */
    fun sessionTrace(message: String) = Unit
}

internal fun looksLikeStaleBondError(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val text = message.lowercase()
    return text.contains("peer removed pairing") ||
        text.contains("removed pairing information") ||
        text.contains("encryption timed out") ||
        (text.contains("сопряжен") && (text.contains("удалил") || text.contains("удалён") || text.contains("удален")))
}
