@file:OptIn(ExperimentalWasmJsInterop::class)

package ru.bolid.testdpls.web

import ru.bolid.testdpls.core.app.DplsTransport
import ru.bolid.testdpls.core.app.DplsTransportDevice
import ru.bolid.testdpls.core.app.DplsTransportListener

/**
 * Maps [DplsTransport] onto the lab WebSocket.
 *
 * Virtual simulators and laptop Bluetooth both appear as [onDiscovered]
 * rows. Connect/FRAME are addressed so several devices can live at once.
 */
class LabBleTransport : DplsTransport {
    private lateinit var listener: DplsTransportListener
    private var socket: JsAny? = null
    private var linked: String? = null
    private var closed = false
    private var scanning = false
    private val advertised = LinkedHashMap<String, DplsTransportDevice>()

    override fun setListener(listener: DplsTransportListener) {
        this.listener = listener
        socket = openRawSocket(
            labWsUrl(),
            onOpen = {
                if (!closed) listener.onBluetoothAvailable()
            },
            onClose = {
                if (closed || linked == null) return@openRawSocket
                linked = null
                listener.onDisconnected("websocket closed")
            },
            onMessage = { raw ->
                runCatching { onMessage(raw) }
            },
        )
    }

    override fun startScan(): Boolean {
        scanning = true
        advertised.values.forEach(listener::onDiscovered)
        send("""{"type":"scan"}""")
        return true
    }

    override fun stopScan() {
        scanning = false
    }

    override fun connect(address: String): Boolean {
        if (closed) return false
        send("""{"type":"connect","address":${jsonStringLiteral(address)}}""")
        linked = address
        return true
    }

    override fun reconnect(): Boolean = linked?.let(::connect) ?: false

    override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean): Boolean {
        if (linked == null || closed) return false
        send("""{"type":"frame","hex":"${bytes.toHex()}"}""")
        listener.onWriteComplete(null)
        return true
    }

    override fun readRssi(): Boolean {
        val current = linked ?: return false
        val rssi = advertised[current]?.rssi ?: RSSI
        listener.onRssi(rssi)
        return true
    }

    override fun disconnect(clearSelection: Boolean) {
        if (linked == null) return
        send("""{"type":"disconnect"}""")
        linked = null
        listener.onDisconnected(null)
    }

    override fun hasConnection(): Boolean = linked != null

    override fun close() {
        if (closed) return
        closed = true
        linked = null
        scanning = false
        socket?.let(::socketClose)
        socket = null
    }

    private fun send(json: String) {
        val current = socket ?: return
        socketSend(current, json)
    }

    private fun onMessage(raw: String) {
        when (jsonString(raw, "type")) {
            "discovered" -> {
                val device = deviceFromDiscovered(raw) ?: return
                advertised[device.address] = device
                listener.onDiscovered(device)
            }
            "tx" -> {
                val id = jsonString(raw, "id")
                if (id != null && id != linked) return
                val hex = jsonString(raw, "hex") ?: return
                listener.onBytes(hex.fromHex())
            }
            "disconnect" -> {
                val id = jsonString(raw, "id")
                if (id != null && id != linked) return
                if (linked == null) return
                linked = null
                listener.onDisconnected(null)
            }
            "subscribed" -> {
                val id = jsonString(raw, "id")
                if (id != null && id != linked) return
                if (linked == null || closed) return
                listener.onConnected()
                listener.onSubscribed(WRITE_LIMIT)
            }
        }
    }

    companion object {
        const val WRITE_LIMIT = 244
        const val RSSI = -42
    }
}

internal fun deviceFromDiscovered(json: String): DplsTransportDevice? {
    val address = jsonString(json, "address") ?: return null
    val name = jsonString(json, "name") ?: "Test-DPLS"
    val deviceId = jsonLong(json, "deviceId")
    val rssi = jsonInt(json, "rssi") ?: LabBleTransport.RSSI
    val firmware = jsonString(json, "firmware")
    val kind = jsonString(json, "kind")
    // The real PHY6252 target currently reserves the ADV status byte but emits 0.
    // Do not let soft-BLE teach DplsClient a richer discovery contract than hardware.
    val status = if (kind == "sim") 0 else jsonInt(json, "advStatus") ?: 0
    return DplsTransportDevice(
        address = address,
        name = name,
        deviceId = deviceId,
        rssi = rssi,
        advStatus = status,
        firmwareVersion = firmware,
        kind = kind,
    )
}

internal fun jsonStringLiteral(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

internal fun jsonString(json: String, key: String): String? {
    val keyToken = "\"$key\""
    val keyAt = json.indexOf(keyToken)
    if (keyAt < 0) return null
    var index = json.indexOf(':', keyAt + keyToken.length)
    if (index < 0) return null
    index += 1
    while (index < json.length && json[index].isWhitespace()) index += 1
    if (index >= json.length || json[index] != '"') return null
    index += 1
    val start = index
    while (index < json.length && json[index] != '"') {
        if (json[index] == '\\') index += 1
        index += 1
    }
    if (index >= json.length) return null
    return json.substring(start, index)
}

internal fun jsonInt(json: String, key: String): Int? = jsonLong(json, key)?.toInt()

internal fun jsonLong(json: String, key: String): Long? {
    val keyToken = "\"$key\""
    val keyAt = json.indexOf(keyToken)
    if (keyAt < 0) return null
    var index = json.indexOf(':', keyAt + keyToken.length)
    if (index < 0) return null
    index += 1
    while (index < json.length && json[index].isWhitespace()) index += 1
    val start = index
    if (index < json.length && json[index] == '-') index += 1
    while (index < json.length && json[index].isDigit()) index += 1
    if (index == start || (index == start + 1 && json[start] == '-')) return null
    return json.substring(start, index).toLong()
}

internal fun ByteArray.toHex(): String {
    val alphabet = "0123456789ABCDEF"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        out.append(alphabet[value shr 4])
        out.append(alphabet[value and 15])
    }
    return out.toString()
}

internal fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "odd hex length: $length" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
