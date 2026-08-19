package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsAdvertisement

/** Общий BLE-контракт Android и iOS. Системные callbacks остаются в platform-коде. */
object DplsBle {
    const val SERVICE_UUID = "7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001"
    const val RX_UUID = "7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001"
    const val TX_UUID = "7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val PREFERRED_MTU = 247
    const val ATT_HEADER_BYTES = 3

    // Samsung SM-A135F завершает запись CCCD только при установленных обоих битах (0x03).
    // Прошивка в этом случае использует GATT_Notification, чтобы AUTH_RESULT не зависел
    // от подтверждения, которое этот стек не присылает. На macOS CBPeripheralManager
    // может отклонить 0x03 (GATT 245), поэтому Android умеет откатиться на notify-only.
    val CCCD_ENABLE_NOTIFY = byteArrayOf(0x01, 0x00)
    val CCCD_ENABLE_INDICATE_NOTIFY = byteArrayOf(0x03, 0x00)

    fun displayName(advertisedName: String?, peripheralName: String?, deviceId: Long?): String {
        val fromId = deviceId?.let(DplsAdvertisement::displayName)
        val raw = advertisedName?.takeIf { it.isNotBlank() }
            ?: peripheralName?.takeIf { it.isNotBlank() }
        if (raw != null) {
            DplsAdvertisement.parseAirName(raw)?.let { return DplsAdvertisement.displayName(it) }
            if (fromId != null && (raw == "Test-DPLS" || fromId.startsWith(raw))) return fromId
            return raw
        }
        return fromId ?: "Test-DPLS"
    }

    fun discovered(
        address: String,
        advertisedName: String?,
        peripheralName: String?,
        rssi: Int,
    ): DplsTransportDevice {
        /* Radio name contains only the low 16 bits (XXXX) of the 32-bit serial.
         * It is a display/discovery hint, not a NodeId. The authoritative deviceId
         * appears only after DEVICE_INFO_REPORT. */
        return DplsTransportDevice(
            address = address,
            name = displayName(advertisedName, peripheralName, null),
            deviceId = null,
            rssi = rssi,
            advStatus = 0,
            firmwareVersion = null,
        )
    }
}

internal object DplsPlatformPrefs {
    const val THEME = "ui_theme"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val HAPTICS = "haptics_enabled"

    fun verifierKey(deviceKey: String): String = "verifier.$deviceKey"
}
