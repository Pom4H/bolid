package ru.bolid.testdpls.core.protocol

import ru.bolid.testdpls.core.domain.DeviceInfo
import ru.bolid.testdpls.core.domain.DeviceState
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.domain.LogHistogram
import ru.bolid.testdpls.core.domain.PowerSource

object StateValidity {
    const val LINE = 1 shl 0
    const val RESERVE = 1 shl 1
    const val POWER = 1 shl 2
    const val AUTO_ISO = 1 shl 3
    const val ADC_CALIBRATED = 1 shl 4
    const val PORT_2 = 1 shl 5
    const val PORT_T = 1 shl 6
}

object DeviceCaps {
    const val ADC_PRESENT = 1 shl 0
    const val HW_READBACK = 1 shl 1
    const val ADC_CALIBRATED = 1 shl 2
    const val MULTI_VOLTAGE = 1 shl 3
}

data class LogChunkBatch(val firstIndex: Int, val records: List<EventRecord>)

fun parseLogHistogramReport(raw: ByteArray): LogHistogram? {
    if (raw.size < 23) return null
    val bucketCount = raw[22].toInt() and 0xff
    if (bucketCount > 48 || raw.size != 23 + bucketCount) return null
    val counts = IntArray(bucketCount) { raw[23 + it].toInt() and 0xff }
    return LogHistogram(
        firstTimestampSeconds = readU32(raw, 0),
        lastTimestampSeconds = readU32(raw, 4),
        firstSequence = readU32(raw, 8),
        lastSequence = readU32(raw, 12),
        eventCount = readU16(raw, 16),
        bucketSeconds = readU32(raw, 18),
        counts = counts.toList(),
    )
}

fun parseDeviceInfoReport(raw: ByteArray): DeviceInfo? {
    if (raw.size < 12) return null
    val deviceId = readU32(raw, 0)
    val proto = raw[4].toInt() and 0xff
    val major = raw[5].toInt() and 0xff
    val minor = raw[6].toInt() and 0xff
    val patch = raw[7].toInt() and 0xff
    val hwRev = raw[8].toInt() and 0xff
    val caps = raw[9].toInt() and 0xff
    val nameLen = raw[11].toInt() and 0xff
    if (nameLen > raw.size - 12) return null
    val name = if (nameLen == 0) "" else raw.copyOfRange(12, 12 + nameLen).decodeToString()
    return DeviceInfo(
        deviceId = deviceId,
        protocolVersion = proto,
        firmwareVersion = "$major.$minor.$patch",
        hardwareRevision = hwRev,
        adcPresent = caps and DeviceCaps.ADC_PRESENT != 0,
        hardwareReadback = caps and DeviceCaps.HW_READBACK != 0,
        adcCalibrated = caps and DeviceCaps.ADC_CALIBRATED != 0,
        multiVoltageReport = caps and DeviceCaps.MULTI_VOLTAGE != 0,
        userName = name,
    )
}

fun parseStateReport(raw: ByteArray, nowMillis: Long): DeviceState? {
    if (raw.size < 16) return null
    val mode = DplsMode.fromWire(raw[0].toInt() and 0xff) ?: return null
    val power = if ((raw[1].toInt() and 0xff) == 0) PowerSource.DPLS else PowerSource.RESERVE
    val voltage = readU16(raw, 2)
    val automaticReturn = readU16(raw, 4)
    val reserveLow = raw[6].toInt() != 0
    val flags = raw[7].toInt() and 0xff
    val uptimeSeconds = readU32(raw, 8)
    val revision = readU32(raw, 12)
    val validity = raw.getOrNull(16)?.toInt()?.and(0xff) ?: 0
    val extended = raw.size >= 25
    val port1 = if (extended) readU16(raw, 17) else voltage
    val port2 = if (extended) readU16(raw, 19) else 0
    val portT = if (extended) readU16(raw, 21) else 0
    val reserve = if (extended) readU16(raw, 23) else 0
    return DeviceState(
        mode = mode,
        voltageMv = voltage,
        powerSource = power,
        reserveLow = reserveLow,
        realShort = flags and 0x02 != 0,
        automaticReturnSeconds = automaticReturn,
        uptimeSeconds = uptimeSeconds,
        revision = revision,
        receivedAtMillis = nowMillis,
        lineVoltageValid = validity and StateValidity.LINE != 0,
        reserveValid = validity and StateValidity.RESERVE != 0,
        powerValid = validity and StateValidity.POWER != 0,
        autoIsoValid = validity and StateValidity.AUTO_ISO != 0,
        adcCalibrated = validity and StateValidity.ADC_CALIBRATED != 0,
        port1VoltageMv = port1,
        port2VoltageMv = port2,
        portTVoltageMv = portT,
        reserveVoltageMv = reserve,
        port1VoltageValid = validity and StateValidity.LINE != 0,
        port2VoltageValid = extended && validity and StateValidity.PORT_2 != 0,
        portTVoltageValid = extended && validity and StateValidity.PORT_T != 0,
        reserveVoltageValid = extended && validity and StateValidity.RESERVE != 0,
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
    else -> "Команда отклонена устройством: $status"
}
