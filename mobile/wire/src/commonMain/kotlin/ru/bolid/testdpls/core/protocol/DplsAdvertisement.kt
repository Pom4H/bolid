package ru.bolid.testdpls.core.protocol

/** Manufacturer ADV payload: company 0x0B01, device id LE32, optional status, optional fw. */
object DplsAdvertisement {
    const val COMPANY_ID = 0x0B01
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
     * 8-char ADV local name. Flags + 128-bit service UUID leave 10 bytes for the
     * name AD; CoreBluetooth cannot fit `Test-DPLS-XXXX` in the same PDU.
     * Keep in sync with `DplsBle.swift` peripheral advertising.
     */
    fun compactAirName(deviceId: Long): String = "DPLS${idSuffix(deviceId)}"

    fun parseAirName(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val match = AIR_NAME.matchEntire(raw.trim()) ?: return null
        return match.groupValues[1].toLong(16)
    }

    private val AIR_NAME = Regex("^(?:Test-)?DPLS-?([0-9A-Fa-f]{4})$")

    fun parse(raw: ByteArray, includesCompanyId: Boolean): Fields {
        val offset = if (includesCompanyId) {
            if (raw.size < 2) return Fields()
            val company = (raw[0].toInt() and 0xff) or ((raw[1].toInt() and 0xff) shl 8)
            if (company != COMPANY_ID) return Fields()
            2
        } else {
            0
        }
        if (raw.size < offset + 4) return Fields()
        val deviceId = (raw[offset].toLong() and 0xff) or
            ((raw[offset + 1].toLong() and 0xff) shl 8) or
            ((raw[offset + 2].toLong() and 0xff) shl 16) or
            ((raw[offset + 3].toLong() and 0xff) shl 24)
        val extra = raw.size - offset - 4
        val status = if (extra >= 1) raw[offset + 4].toInt() and 0xff else 0
        val firmwareVersion = if (extra >= 4) {
            formatFirmware(
                raw[offset + 5].toInt() and 0xff,
                raw[offset + 6].toInt() and 0xff,
                raw[offset + 7].toInt() and 0xff,
            )
        } else {
            null
        }
        return Fields(deviceId, status, firmwareVersion)
    }

    data class Fields(
        val deviceId: Long? = null,
        val status: Int = 0,
        val firmwareVersion: String? = null,
    )
}
