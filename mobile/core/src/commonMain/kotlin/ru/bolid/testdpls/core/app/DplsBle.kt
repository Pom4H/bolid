package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsAdvertisement

/** BLE contract shared by Android and iOS transports. OS callbacks stay platform-side. */
object DplsBle {
    const val SERVICE_UUID = "7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001"
    const val RX_UUID = "7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001"
    const val TX_UUID = "7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val MANUFACTURER_ID = DplsAdvertisement.COMPANY_ID
    const val PREFERRED_MTU = 247
    const val ATT_HEADER_BYTES = 3

    // Protocol v2 uses one confirmed TX path. Keep the legacy property name until
    // platform adapters are renamed together; the value intentionally enables indication only.
    val CCCD_ENABLE_INDICATE_NOTIFY = byteArrayOf(0x02, 0x00)

    fun displayName(advertisedName: String?, peripheralName: String?, deviceId: Long?): String {
        advertisedName?.takeIf { it.isNotBlank() }?.let { return it }
        peripheralName?.takeIf { it.isNotBlank() }?.let { return it }
        if (deviceId == null) return "Test-DPLS"
        return "Test-DPLS-${(deviceId and 0xffff).toString(16).uppercase().padStart(4, '0')}"
    }

    fun discovered(
        address: String,
        advertisedName: String?,
        peripheralName: String?,
        manufacturerPayload: ByteArray?,
        manufacturerIncludesCompanyId: Boolean,
        rssi: Int,
    ): DplsTransportDevice {
        val parsed = manufacturerPayload?.let { DplsAdvertisement.parse(it, manufacturerIncludesCompanyId) }
        return DplsTransportDevice(
            address = address,
            name = displayName(advertisedName, peripheralName, parsed?.deviceId),
            deviceId = parsed?.deviceId,
            rssi = rssi,
            advStatus = parsed?.status ?: 0,
        )
    }
}

internal object DplsPlatformPrefs {
    const val THEME = "ui_theme"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val HAPTICS = "haptics_enabled"

    fun verifierKey(deviceKey: String): String = "verifier.$deviceKey"
}
