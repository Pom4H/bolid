package ru.bolid.testdpls.ble

import ru.bolid.testdpls.core.protocol.commandRejectReason as coreCommandRejectReason
import ru.bolid.testdpls.core.protocol.parseDeviceInfoReport as coreParseDeviceInfoReport
import ru.bolid.testdpls.core.protocol.parseEventRecord as coreParseEventRecord
import ru.bolid.testdpls.core.protocol.parseLogChunk as coreParseLogChunk
import ru.bolid.testdpls.core.protocol.parseStateReport as coreParseStateReport

typealias StateValidity = ru.bolid.testdpls.core.protocol.StateValidity
typealias DeviceCaps = ru.bolid.testdpls.core.protocol.DeviceCaps
typealias LogChunkBatch = ru.bolid.testdpls.core.protocol.LogChunkBatch

fun parseDeviceInfoReport(raw: ByteArray): DeviceInfo? = coreParseDeviceInfoReport(raw)
fun parseStateReport(raw: ByteArray, nowMillis: Long): DeviceState? = coreParseStateReport(raw, nowMillis)
fun parseEventRecord(raw: ByteArray): EventRecord? = coreParseEventRecord(raw)
fun parseLogChunk(raw: ByteArray): LogChunkBatch? = coreParseLogChunk(raw)
fun commandRejectReason(status: Int): String = coreCommandRejectReason(status)

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
