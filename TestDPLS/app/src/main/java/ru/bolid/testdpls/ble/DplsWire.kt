package ru.bolid.testdpls.ble

import ru.bolid.testdpls.protocol.u16
import ru.bolid.testdpls.protocol.u32
import ru.bolid.testdpls.protocol.u8
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Validity bits in STATE_REPORT byte 16 — same as Firmware `DPLS_STATE_*`. */
object StateValidity {
    const val LINE = 1 shl 0
    const val RESERVE = 1 shl 1
    const val POWER = 1 shl 2
    const val AUTO_ISO = 1 shl 3
    const val ADC_CALIBRATED = 1 shl 4
    const val PORT_2 = 1 shl 5
    const val PORT_T = 1 shl 6
}

/** Capability bits in DEVICE_INFO_REPORT — same as Firmware `DPLS_CAP_*`. */
object DeviceCaps {
    const val ADC_PRESENT = 1 shl 0
    const val HW_READBACK = 1 shl 1
    const val ADC_CALIBRATED = 1 shl 2
    const val MULTI_VOLTAGE = 1 shl 3
}

data class LogChunkBatch(val firstIndex: Int, val records: List<EventRecord>)

fun parseDeviceInfoReport(raw: ByteArray): DeviceInfo? {
    if (raw.size < 12) return null
    val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val deviceId = b.u32()
    val proto = b.u8()
    val major = b.u8()
    val minor = b.u8()
    val patch = b.u8()
    val hwRev = b.u8()
    val caps = b.u8()
    b.u8()
    val nameLen = b.u8()
    val name = if (nameLen in 1..(raw.size - 12)) String(raw, 12, nameLen, Charsets.UTF_8) else ""
    return DeviceInfo(
        deviceId = deviceId,
        protocolVersion = proto,
        firmwareVersion = "$major.$minor.$patch",
        hardwareRevision = hwRev,
        adcPresent = (caps and DeviceCaps.ADC_PRESENT) != 0,
        hardwareReadback = (caps and DeviceCaps.HW_READBACK) != 0,
        adcCalibrated = (caps and DeviceCaps.ADC_CALIBRATED) != 0,
        multiVoltageReport = (caps and DeviceCaps.MULTI_VOLTAGE) != 0,
        userName = name,
    )
}

fun parseStateReport(raw: ByteArray, nowMillis: Long): DeviceState? {
    if (raw.size < 16) return null
    val payload = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val mode = DplsMode.fromWire(payload.u8()) ?: DplsMode.NORMAL
    val power = if (payload.u8() == 0) PowerSource.DPLS else PowerSource.RESERVE
    val voltage = payload.u16()
    val automaticReturn = payload.u16()
    val reserveLow = payload.u8() != 0
    val flags = payload.u8()
    val realShort = (flags and 0x02) != 0
    val uptimeSeconds = payload.u32()
    val revision = payload.u32()
    val validity = if (payload.remaining() >= 1) payload.u8() else 0x00
    val extendedVoltages = payload.remaining() >= 8
    val port1Voltage = if (extendedVoltages) payload.u16() else voltage
    val port2Voltage = if (extendedVoltages) payload.u16() else 0
    val portTVoltage = if (extendedVoltages) payload.u16() else 0
    val reserveVoltage = if (extendedVoltages) payload.u16() else 0
    return DeviceState(
        mode = mode,
        powerSource = power,
        voltageMv = voltage,
        automaticReturnSeconds = automaticReturn,
        reserveLow = reserveLow,
        realShort = realShort,
        uptimeSeconds = uptimeSeconds,
        revision = revision,
        receivedAtMillis = nowMillis,
        lineVoltageValid = (validity and StateValidity.LINE) != 0,
        reserveValid = (validity and StateValidity.RESERVE) != 0,
        powerValid = (validity and StateValidity.POWER) != 0,
        autoIsoValid = (validity and StateValidity.AUTO_ISO) != 0,
        adcCalibrated = (validity and StateValidity.ADC_CALIBRATED) != 0,
        port1VoltageMv = port1Voltage,
        port2VoltageMv = port2Voltage,
        portTVoltageMv = portTVoltage,
        reserveVoltageMv = reserveVoltage,
        port1VoltageValid = (validity and StateValidity.LINE) != 0,
        port2VoltageValid = extendedVoltages && (validity and StateValidity.PORT_2) != 0,
        portTVoltageValid = extendedVoltages && (validity and StateValidity.PORT_T) != 0,
        reserveVoltageValid = extendedVoltages && (validity and StateValidity.RESERVE) != 0,
    )
}

fun parseEventRecord(raw: ByteArray): EventRecord? {
    if (raw.size != 10) return null
    val it = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    return EventRecord(it.u32(), it.u32(), it.u8(), it.u8())
}

fun parseLogChunk(raw: ByteArray): LogChunkBatch? {
    if (raw.size < 3) return null
    val payload = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val first = payload.u16()
    val count = payload.u8()
    if (count == 0 || payload.remaining() < count * 10) return null
    val records = ArrayList<EventRecord>(count)
    repeat(count) {
        val data = ByteArray(10)
        payload.get(data)
        val event = parseEventRecord(data) ?: return null
        records.add(event)
    }
    return LogChunkBatch(first, records)
}

fun commandRejectReason(status: Int): String = when (status) {
    3 -> "Команда отклонена: недопустимый режим"
    4 -> "Команда отклонена: аппаратное переключение не удалось"
    5 -> "Команда отклонена: активна автоизоляция реального КЗ"
    else -> "Команда отклонена устройством: $status"
}

fun formatEventLogCsv(
    events: List<EventRecord>,
    currentRunFirstSeq: Long,
    bootEpochSec: Long?,
): String = buildString {
    appendLine("sequence;datetime;uptime_seconds;event_type;parameter;event")
    events.forEach {
        val ts = dplsEventTime(it, currentRunFirstSeq, bootEpochSec)
        appendLine("${it.sequence};${ts.full};${it.timestampSeconds};${it.type};${it.parameter};\"${dplsEventTitle(it.type, it.parameter)}\"")
    }
}

fun formatEventLogTxt(
    events: List<EventRecord>,
    currentRunFirstSeq: Long,
    bootEpochSec: Long?,
    deviceName: String,
): String = buildString {
    appendLine("Журнал событий Тест-ДПЛС")
    appendLine("Устройство: $deviceName")
    appendLine("Записей: ${events.size}")
    appendLine("—".repeat(32))
    events.forEach {
        val ts = dplsEventTime(it, currentRunFirstSeq, bootEpochSec)
        appendLine("#${it.sequence}  ${ts.full}  ${dplsEventTitle(it.type, it.parameter)}")
    }
}

fun currentRunFirstSeq(events: List<EventRecord>): Long =
    events.filter { it.type == 1 }.maxOfOrNull { it.sequence } ?: 0L
