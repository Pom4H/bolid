package ru.bolid.testdpls.core.protocol

/** Current BLE discovery helpers. Device data itself is read from GATT DEVICE_INFO. */
object DplsAdvertisement {
    const val REAL_SHORT = 0x01
    const val FROM_RESERVE = 0x02
    const val RESERVE_LOW = 0x04

    fun realShort(status: Int): Boolean = status and REAL_SHORT != 0
    fun fromReserve(status: Int): Boolean = status and FROM_RESERVE != 0
    fun reserveLow(status: Int): Boolean = status and RESERVE_LOW != 0

    fun formatFirmware(major: Int, minor: Int, patch: Int): String = "$major.$minor.$patch"

    fun idSuffix(deviceId: Long): String =
        (deviceId and 0xffff).toString(16).uppercase().padStart(4, '0')

    fun displayName(deviceId: Long): String = "Test-DPLS-${idSuffix(deviceId)}"

    /**
     * 8-char local name used by the macOS lab peripheral when the 128-bit UUID
     * must fit the same legacy advertising PDU.
     */
    fun compactAirName(deviceId: Long): String = "DPLS${idSuffix(deviceId)}"

    fun parseAirName(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val match = AIR_NAME.matchEntire(raw.trim()) ?: return null
        return match.groupValues[1].toLong(16)
    }

    private val AIR_NAME = Regex("^(?:Test-)?DPLS-?([0-9A-Fa-f]{4})$")
}
