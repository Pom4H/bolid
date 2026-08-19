package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsAdvertisement

/** BLE contract shared by Android and iOS transports. OS callbacks stay platform-side. */
object DplsBle {
    const val SERVICE_UUID = "7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001"
    const val RX_UUID = "7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001"
    const val TX_UUID = "7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val PREFERRED_MTU = 247
    const val ATT_HEADER_BYTES = 3

    // Samsung SM-A135F only completes CCCD when both bits are set (0x03).
    // Firmware then uses GATT_Notification so AUTH_RESULT is not stuck on a
    // confirmation that this stack never sends. Mac CBPeripheralManager may
    // reject 0x03 (GATT 245); the Android transport falls back to notify-only.
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
        val deviceId = DplsAdvertisement.parseAirName(advertisedName)
            ?: DplsAdvertisement.parseAirName(peripheralName)
        return DplsTransportDevice(
            address = address,
            name = displayName(advertisedName, peripheralName, deviceId),
            deviceId = deviceId,
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
