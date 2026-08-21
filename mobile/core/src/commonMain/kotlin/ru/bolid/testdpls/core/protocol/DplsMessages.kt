package ru.bolid.testdpls.core.protocol

import ru.bolid.testdpls.core.domain.DeviceCapabilities
import ru.bolid.testdpls.core.domain.DeviceInfo
import ru.bolid.testdpls.core.domain.DeviceState
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.domain.LogHistogram
import ru.bolid.testdpls.core.domain.PowerSource
import ru.bolid.testdpls.core.domain.Voltages

object StateValidity {
    const val LINE = 1 shl 0
    const val RESERVE = 1 shl 1
    const val POWER = 1 shl 2
    const val AUTO_ISO = 1 shl 3
    const val ADC_CALIBRATED = 1 shl 4
    const val PORT_2 = 1 shl 5
    const val PORT_T = 1 shl 6
}

data class LogChunkBatch(val firstIndex: Int, val records: List<EventRecord>)

fun parseLogHistogramReport(raw: ByteArray): LogHistogram? {
    if (raw.size < 23) return null
    val bucketCount = raw[22].toInt() and 0xff
    if (bucketCount > 48 || raw.size != 23 + bucketCount) return null
    return LogHistogram(
        firstTimestampSeconds = readU32(raw, 0),
        lastTimestampSeconds = readU32(raw, 4),
        firstSequence = readU32(raw, 8),
        lastSequence = readU32(raw, 12),
        eventCount = readU16(raw, 16),
        bucketSeconds = readU32(raw, 18),
        counts = List(bucketCount) { raw[23 + it].toInt() and 0xff },
    )
}

fun parseDeviceInfoReport(raw: ByteArray): DeviceInfo? {
    if (raw.size < 12) return null
    val nameLen = raw[11].toInt() and 0xff
    if (raw.size != 12 + nameLen) return null
    val name = if (nameLen == 0) "" else raw.copyOfRange(12, raw.size).decodeToString()
    return DeviceInfo(
        deviceId = readU32(raw, 0),
        protocolVersion = raw[4].toInt() and 0xff,
        firmwareVersion = "${raw[5].toInt() and 0xff}.${raw[6].toInt() and 0xff}.${raw[7].toInt() and 0xff}",
        hardwareRevision = raw[8].toInt() and 0xff,
        capabilities = DeviceCapabilities(raw[9].toInt() and 0xff),
        userName = name,
    )
}

fun parseStateReport(raw: ByteArray, nowMillis: Long): DeviceState? {
    if (raw.size != 25) return null
    val mode = DplsMode.fromWire(raw[0].toInt() and 0xff) ?: return null
    val power = if ((raw[1].toInt() and 0xff) == 0) PowerSource.DPLS else PowerSource.RESERVE
    val validity = raw[16].toInt() and 0xff
    fun measured(mask: Int, value: Int): Int? = value.takeIf { validity and mask != 0 }

    return DeviceState(
        mode = mode,
        powerSource = power,
        reserveLow = raw[6].toInt() != 0,
        realShort = raw[7].toInt() and 0x02 != 0,
        automaticReturnSeconds = readU16(raw, 4),
        uptimeSeconds = readU32(raw, 8),
        revision = readU32(raw, 12),
        receivedAtMillis = nowMillis,
        voltages = Voltages(
            lineMv = measured(StateValidity.LINE, readU16(raw, 2)),
            port1Mv = measured(StateValidity.LINE, readU16(raw, 17)),
            port2Mv = measured(StateValidity.PORT_2, readU16(raw, 19)),
            branchMv = measured(StateValidity.PORT_T, readU16(raw, 21)),
            reserveMv = measured(StateValidity.RESERVE, readU16(raw, 23)),
        ),
        powerKnown = validity and StateValidity.POWER != 0,
        autoIsolationKnown = validity and StateValidity.AUTO_ISO != 0,
        adcCalibrated = validity and StateValidity.ADC_CALIBRATED != 0,
    )
}

fun parseEventRecord(raw: ByteArray): EventRecord? {
    if (raw.size != 10) return null
    return EventRecord(
        sequence = readU32(raw, 0),
        timestampSeconds = readU32(raw, 4),
        type = raw[8].toInt() and 0xff,
        parameter = raw[9].toInt() and 0xff,
    )
}

fun parseLogChunk(raw: ByteArray): LogChunkBatch? {
    if (raw.size < 3) return null
    val first = readU16(raw, 0)
    val count = raw[2].toInt() and 0xff
    if (count == 0 || raw.size != 3 + count * 10) return null
    val records = ArrayList<EventRecord>(count)
    repeat(count) { index ->
        val offset = 3 + index * 10
        records += parseEventRecord(raw.copyOfRange(offset, offset + 10)) ?: return null
    }
    return LogChunkBatch(first, records)
}

fun commandRejectReason(status: Int): String = when (status) {
    3 -> "Команда отклонена: недопустимый режим"
    4 -> "Команда отклонена: аппаратное переключение не удалось"
    5 -> "Команда отклонена: активна автоизоляция реального КЗ"
    6 -> "Команда отклонена: резервный источник разряжен"
    7 -> "Команда отклонена: измерения ещё не готовы"
    8 -> "Команда отклонена: защищённая сессия уже недействительна"
    else -> "Команда отклонена устройством: $status"
}
