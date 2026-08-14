package ru.bolid.testdpls.core.protocol

import ru.bolid.testdpls.core.domain.PowerSource

/** Primitive/String projection of binary message parsers for Swift. */
class DplsMessageBridge {
    fun parseDeviceInfoHex(payloadHex: String): DplsDeviceInfoValue? {
        val raw = payloadHex.hexToBytesOrNull() ?: return null
        val info = parseDeviceInfoReport(raw) ?: return null
        return DplsDeviceInfoValue(
            deviceId = info.deviceId,
            protocolVersion = info.protocolVersion,
            firmwareVersion = info.firmwareVersion,
            hardwareRevision = info.hardwareRevision,
            adcPresent = info.adcPresent,
            hardwareReadback = info.hardwareReadback,
            adcCalibrated = info.adcCalibrated,
            multiVoltageReport = info.multiVoltageReport,
            userName = info.userName,
        )
    }

    fun parseStateHex(payloadHex: String, nowMillis: Long): DplsStateValue? {
        val raw = payloadHex.hexToBytesOrNull() ?: return null
        val state = parseStateReport(raw, nowMillis) ?: return null
        return DplsStateValue(
            mode = state.mode.wire,
            voltageMv = state.voltageMv,
            powerSource = if (state.powerSource == PowerSource.DPLS) 0 else 1,
            reserveLow = state.reserveLow,
            realShort = state.realShort,
            automaticReturnSeconds = state.automaticReturnSeconds,
            uptimeSeconds = state.uptimeSeconds,
            revision = state.revision,
            receivedAtMillis = state.receivedAtMillis,
            lineVoltageValid = state.lineVoltageValid,
            reserveValid = state.reserveValid,
            powerValid = state.powerValid,
            autoIsoValid = state.autoIsoValid,
            adcCalibrated = state.adcCalibrated,
            port1VoltageMv = state.port1VoltageMv,
            port2VoltageMv = state.port2VoltageMv,
            portTVoltageMv = state.portTVoltageMv,
            reserveVoltageMv = state.reserveVoltageMv,
            port1VoltageValid = state.port1VoltageValid,
            port2VoltageValid = state.port2VoltageValid,
            portTVoltageValid = state.portTVoltageValid,
            reserveVoltageValid = state.reserveVoltageValid,
        )
    }

    fun parseEventHex(recordHex: String): DplsEventValue? {
        val raw = recordHex.hexToBytesOrNull() ?: return null
        val event = parseEventRecord(raw) ?: return null
        return DplsEventValue(
            sequence = event.sequence,
            timestampSeconds = event.timestampSeconds,
            type = event.type,
            parameter = event.parameter,
        )
    }

    fun commandRejectReason(status: Int): String =
        ru.bolid.testdpls.core.protocol.commandRejectReason(status)
}

class DplsDeviceInfoValue(
    val deviceId: Long,
    val protocolVersion: Int,
    val firmwareVersion: String,
    val hardwareRevision: Int,
    val adcPresent: Boolean,
    val hardwareReadback: Boolean,
    val adcCalibrated: Boolean,
    val multiVoltageReport: Boolean,
    val userName: String,
)

class DplsStateValue(
    val mode: Int,
    val voltageMv: Int,
    val powerSource: Int,
    val reserveLow: Boolean,
    val realShort: Boolean,
    val automaticReturnSeconds: Int,
    val uptimeSeconds: Long,
    val revision: Long,
    val receivedAtMillis: Long,
    val lineVoltageValid: Boolean,
    val reserveValid: Boolean,
    val powerValid: Boolean,
    val autoIsoValid: Boolean,
    val adcCalibrated: Boolean,
    val port1VoltageMv: Int,
    val port2VoltageMv: Int,
    val portTVoltageMv: Int,
    val reserveVoltageMv: Int,
    val port1VoltageValid: Boolean,
    val port2VoltageValid: Boolean,
    val portTVoltageValid: Boolean,
    val reserveVoltageValid: Boolean,
)

class DplsEventValue(
    val sequence: Long,
    val timestampSeconds: Long,
    val type: Int,
    val parameter: Int,
)
