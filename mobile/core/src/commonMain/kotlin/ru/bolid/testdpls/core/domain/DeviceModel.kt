package ru.bolid.testdpls.core.domain

import kotlin.jvm.JvmInline

enum class DplsMode(val wire: Int, val dangerous: Boolean) {
    NORMAL(0, false),
    OPEN_T(1, true),
    OPEN_MAIN(2, true),
    SHORT_1(3, true),
    SHORT_2(4, true),
    SHORT_T(5, true);

    companion object {
        fun fromWire(value: Int): DplsMode? = entries.firstOrNull { it.wire == value }
    }
}

enum class PowerSource { DPLS, RESERVE }

@JvmInline
value class DeviceCapabilities(val bits: Int) {
    fun has(mask: Int): Boolean = bits and mask != 0
    val adcPresent: Boolean get() = has(ADC_PRESENT)
    val hardwareReadback: Boolean get() = has(HW_READBACK)
    val adcCalibrated: Boolean get() = has(ADC_CALIBRATED)
    val multiVoltageReport: Boolean get() = has(MULTI_VOLTAGE)
    val mesh: Boolean get() = has(MESH)
    val serialMonitor: Boolean get() = has(SERIAL_MONITOR)
    val hostSim: Boolean get() = has(HOST_SIM)

    companion object {
        const val ADC_PRESENT = 1 shl 0
        const val HW_READBACK = 1 shl 1
        const val ADC_CALIBRATED = 1 shl 2
        const val MULTI_VOLTAGE = 1 shl 3
        const val MESH = 1 shl 4
        const val SERIAL_MONITOR = 1 shl 5
        const val HOST_SIM = 1 shl 6
    }
}

data class Voltages(
    val lineMv: Int?,
    val port1Mv: Int?,
    val port2Mv: Int?,
    val branchMv: Int?,
    val reserveMv: Int?,
)

data class DeviceState(
    val mode: DplsMode,
    val powerSource: PowerSource,
    val reserveLow: Boolean,
    val realShort: Boolean,
    val automaticReturnSeconds: Int,
    val uptimeSeconds: Long,
    val revision: Long,
    val receivedAtMillis: Long = 0L,
    val voltages: Voltages = Voltages(null, null, null, null, null),
    val powerKnown: Boolean = false,
    val autoIsolationKnown: Boolean = false,
    val adcCalibrated: Boolean = false,
) {
    val voltageMv: Int get() = voltages.lineMv ?: 0
    val port1VoltageMv: Int get() = voltages.port1Mv ?: 0
    val port2VoltageMv: Int get() = voltages.port2Mv ?: 0
    val portTVoltageMv: Int get() = voltages.branchMv ?: 0
    val reserveVoltageMv: Int get() = voltages.reserveMv ?: 0
    val lineVoltageValid: Boolean get() = voltages.lineMv != null
    val reserveValid: Boolean get() = voltages.reserveMv != null
    val powerValid: Boolean get() = powerKnown
    val autoIsoValid: Boolean get() = autoIsolationKnown
    val port1VoltageValid: Boolean get() = voltages.port1Mv != null
    val port2VoltageValid: Boolean get() = voltages.port2Mv != null
    val portTVoltageValid: Boolean get() = voltages.branchMv != null
    val reserveVoltageValid: Boolean get() = voltages.reserveMv != null
}

data class EventRecord(
    val sequence: Long,
    val timestampSeconds: Long,
    val type: Int,
    val parameter: Int,
)

data class JournalTimeAnchor(
    val bootFirstSequence: Long,
    val bootEpochSeconds: Long,
    val lastSequence: Long,
)

data class LogHistogram(
    val firstTimestampSeconds: Long,
    val lastTimestampSeconds: Long,
    val firstSequence: Long,
    val lastSequence: Long,
    val eventCount: Int,
    val bucketSeconds: Long,
    val counts: List<Int>,
) {
    val bucketCount: Int get() = counts.size
    val startSeconds: Long get() = firstTimestampSeconds
    val endSeconds: Long get() = startSeconds + bucketSeconds * bucketCount
    fun bucketStart(index: Int): Long = startSeconds + bucketSeconds * index.coerceAtLeast(0)
    fun rangeSeconds(fromBucket: Int, toBucket: Int): Pair<Long, Long> {
        if (bucketCount <= 0) return firstTimestampSeconds to lastTimestampSeconds
        val from = minOf(fromBucket, toBucket).coerceIn(0, bucketCount - 1)
        val to = maxOf(fromBucket, toBucket).coerceIn(0, bucketCount - 1)
        return bucketStart(from) to bucketStart(to + 1)
    }
}

data class DeviceInfo(
    val deviceId: Long,
    val protocolVersion: Int,
    val firmwareVersion: String,
    val hardwareRevision: Int,
    val capabilities: DeviceCapabilities,
    val userName: String,
) {
    val adcPresent: Boolean get() = capabilities.adcPresent
    val hardwareReadback: Boolean get() = capabilities.hardwareReadback
    val adcCalibrated: Boolean get() = capabilities.adcCalibrated
    val multiVoltageReport: Boolean get() = capabilities.multiVoltageReport
    val hostSim: Boolean get() = capabilities.hostSim
    val shortId: String get() = "DPLS-${deviceId.toString(16).uppercase().padStart(8, '0')}"
}
