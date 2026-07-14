package com.thebutton.ble.ble

enum class DplsMode(val wire: Int, val title: String, val dangerous: Boolean) {
    NORMAL(0, "Норма", false),
    OPEN_T(1, "Обрыв +Т", true),
    OPEN_MAIN(2, "Обрыв магистрали", true),
    SHORT_1(3, "КЗ +1", true),
    SHORT_2(4, "КЗ +2", true),
    SHORT_T(5, "КЗ +Т", true);

    companion object { fun fromWire(value: Int) = entries.firstOrNull { it.wire == value } }
}

enum class PowerSource(val title: String) { DPLS("ДПЛС"), RESERVE("Резерв") }

enum class ConnectionPhase {
    IDLE, SCANNING, CONNECTING, PAIRING, NEGOTIATING_MTU, DISCOVERING,
    SUBSCRIBING, AUTHENTICATING, SYNCHRONIZING, READY, RECONNECTING, ERROR
}

data class DiscoveredDevice(
    val address: String,
    val advertisedName: String,
    val userName: String?,
    val deviceId: Long?,
    val rssi: Int,
)

data class DeviceState(
    val mode: DplsMode,
    val voltageMv: Int,
    val powerSource: PowerSource,
    val reserveLow: Boolean,
    /** Hardware is isolating a real downstream short circuit (BRIZ-T function). */
    val realShort: Boolean,
    val automaticReturnSeconds: Int,
    val uptimeSeconds: Long,
    val revision: Long,
    /** Wall-clock millis when this snapshot was received from the device. */
    val receivedAtMillis: Long = 0L,
    /** STATE_REPORT validity mask (byte 16). A clear bit means the field is not
     * actually measured (e.g. ADC sampling disabled) and must not be shown as
     * a real value. Legacy 16-byte reports default every bit to valid. */
    val lineVoltageValid: Boolean = true,
    val reserveValid: Boolean = true,
    val powerValid: Boolean = true,
    val autoIsoValid: Boolean = true,
    val adcCalibrated: Boolean = false,
)

data class EventRecord(
    val sequence: Long,
    val timestampSeconds: Long,
    val type: Int,
    val parameter: Int,
)

data class DplsUiState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val statusText: String = "Готово к поиску",
    val devices: List<DiscoveredDevice> = emptyList(),
    val selectedDevice: DiscoveredDevice? = null,
    val initialized: Boolean = false,
    val credentialsReady: Boolean = false,
    val authenticated: Boolean = false,
    val state: DeviceState? = null,
    val pendingMode: DplsMode? = null,
    val commandInProgress: Boolean = false,
    val staleState: Boolean = false,
    val lastAckMillis: Long? = null,
    val eventLog: List<EventRecord> = emptyList(),
    /** Phone wall-clock epoch (s) when device uptime was 0; used to show journal time on phone. */
    val deviceBootEpochSeconds: Long? = null,
    val logProgress: Float? = null,
    val identifyActive: Boolean = false,
    val identifyLedLive: Boolean = false,
    val setupName: String = "",
    val setupPassword: String = "",
    val setupRepeatPassword: String = "",
    /** True when UI should collect password/setup; false during auto-reauth after setup or cached login. */
    val awaitingUserPassword: Boolean = false,
    val error: String? = null,
) {
    val controlsEnabled: Boolean
        get() = phase == ConnectionPhase.READY && authenticated && !commandInProgress

    val setupFormReady: Boolean
        get() = credentialsReady &&
            setupPassword.length >= 8 &&
            (initialized || (setupRepeatPassword == setupPassword && setupName.isNotBlank()))
}
