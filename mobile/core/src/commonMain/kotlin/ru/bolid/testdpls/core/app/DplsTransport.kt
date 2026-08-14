package ru.bolid.testdpls.core.app

/** OS-neutral BLE transport boundary used by the shared Test-DPLS client. */
interface DplsTransport {
    fun setListener(listener: DplsTransportListener)
    fun startScan(): Boolean
    fun stopScan()
    fun connect(address: String): Boolean
    fun reconnect(): Boolean
    fun send(bytes: ByteArray, priority: Boolean = false, flush: Boolean = false): Boolean
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
    fun onTransportError(message: String)
}

data class DplsTransportDevice(
    val address: String,
    val name: String,
    val deviceId: Long?,
    val rssi: Int,
)

/** Tiny platform surface that cannot be made deterministic in commonMain. */
interface DplsPlatformServices {
    fun nowMillis(): Long
    fun secureRandomBytes(count: Int): ByteArray
}
