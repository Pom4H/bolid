package ru.bolid.testdpls.core.protocol

/** Manufacturer ADV payload: company 0x0B01, device id LE32, optional status byte. */
object DplsAdvertisement {
    const val COMPANY_ID = 0x0B01
    const val REAL_SHORT = 0x01
    const val FROM_RESERVE = 0x02
    const val RESERVE_LOW = 0x04

    fun realShort(status: Int): Boolean = status and REAL_SHORT != 0
    fun fromReserve(status: Int): Boolean = status and FROM_RESERVE != 0
    fun reserveLow(status: Int): Boolean = status and RESERVE_LOW != 0

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
        val status = if (raw.size > offset + 4) raw[offset + 4].toInt() and 0xff else 0
        return Fields(deviceId, status)
    }

    data class Fields(
        val deviceId: Long? = null,
        val status: Int = 0,
    )
}
