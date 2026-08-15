package ru.bolid.testdpls.core.domain

enum class DplsMode(
    val wire: Int,
    val title: String,
    val dangerous: Boolean,
    val portHint: String = "",
    val controllerEffect: String = "",
) {
    NORMAL(0, "Норма", false, "Штатное прохождение линии"),
    OPEN_T(1, "Обрыв +Т", true, "Ответвление +Т", "КДЛ: потеря устройств ответвления"),
    OPEN_MAIN(2, "Обрыв магистрали", true, "Магистраль +1 ↔ +2", "КДЛ: «Нет связи» с устройствами за разрывом"),
    SHORT_1(3, "КЗ +1", true, "Порт +1", "КДЛ: «Короткое замыкание ДПЛС»"),
    SHORT_2(4, "КЗ +2", true, "Порт +2", "КДЛ: «Короткое замыкание ДПЛС»"),
    SHORT_T(5, "КЗ +Т", true, "Ответвление +Т", "КДЛ: «Короткое замыкание ДПЛС»");

    companion object {
        fun fromWire(value: Int): DplsMode? = entries.firstOrNull { it.wire == value }
    }
}

enum class PowerSource(val title: String) {
    DPLS("ДПЛС"),
    RESERVE("Резерв"),
}

enum class ConnectionPhase {
    IDLE,
    SCANNING,
    CONNECTING,
    PAIRING,
    NEGOTIATING_MTU,
    DISCOVERING,
    SUBSCRIBING,
    AUTHENTICATING,
    SYNCHRONIZING,
    READY,
    RECONNECTING,
    ERROR,
}

enum class SettingsOp { NONE, IN_PROGRESS, DONE, FAILED }

enum class UiTheme(val title: String, val wire: String) {
    SYSTEM("Система", "system"),
    DARK("Тёмная", "dark"),
    LIGHT("Светлая", "light");

    companion object {
        fun fromWire(value: String?): UiTheme = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

data class DiscoveredDevice(
    val address: String,
    val advertisedName: String,
    val userName: String?,
    val deviceId: Long?,
    val rssi: Int,
    val realShort: Boolean = false,
    val fromReserve: Boolean = false,
    val reserveLow: Boolean = false,
) {
    val hasLineFault: Boolean get() = realShort || reserveLow || fromReserve
}

data class DeviceState(
    val mode: DplsMode,
    val voltageMv: Int,
    val powerSource: PowerSource,
    val reserveLow: Boolean,
    val realShort: Boolean,
    val automaticReturnSeconds: Int,
    val uptimeSeconds: Long,
    val revision: Long,
    val receivedAtMillis: Long = 0L,
    val lineVoltageValid: Boolean = true,
    val reserveValid: Boolean = true,
    val powerValid: Boolean = true,
    val autoIsoValid: Boolean = true,
    val adcCalibrated: Boolean = false,
    val port1VoltageMv: Int = voltageMv,
    val port2VoltageMv: Int = 0,
    val portTVoltageMv: Int = 0,
    val reserveVoltageMv: Int = 0,
    val port1VoltageValid: Boolean = lineVoltageValid,
    val port2VoltageValid: Boolean = false,
    val portTVoltageValid: Boolean = false,
    val reserveVoltageValid: Boolean = false,
)

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
    val adcPresent: Boolean,
    val hardwareReadback: Boolean,
    val adcCalibrated: Boolean,
    val userName: String,
    val multiVoltageReport: Boolean = false,
) {
    val shortId: String
        get() = "DPLS-${deviceId.toString(16).uppercase().padStart(8, '0')}"
}

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
    val deviceBootEpochSeconds: Long? = null,
    val logProgress: Float? = null,
    val logTotal: Int = 0,
    val logHasMore: Boolean = false,
    val logFirstTimestampSeconds: Long? = null,
    val logLastTimestampSeconds: Long? = null,
    val logHistogram: LogHistogram? = null,
    val journalTimeAnchors: List<JournalTimeAnchor> = emptyList(),
    val browsingDevices: Boolean = false,
    val scanning: Boolean = false,
    val identifyActive: Boolean = false,
    val identifyLedLive: Boolean = false,
    val identifyLedPhaseOffsetMs: Long = 0,
    val linkRssi: Int? = null,
    val setupName: String = "",
    val setupPassword: String = "",
    val setupRepeatPassword: String = "",
    val awaitingUserPassword: Boolean = false,
    val deviceInfo: DeviceInfo? = null,
    val settingsOp: SettingsOp = SettingsOp.NONE,
    val settingsError: String? = null,
    val settingsNotice: String? = null,
    val error: String? = null,
    val staleBond: Boolean = false,
    val uiTheme: UiTheme = UiTheme.SYSTEM,
    val keepScreenOn: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val savedCredentials: Boolean = false,
) {
    val controlsEnabled: Boolean
        get() = phase == ConnectionPhase.READY && authenticated && !commandInProgress

    val needsPeriodicStateRefresh: Boolean
        get() = authenticated && !commandInProgress && logProgress == null &&
            (state != null || phase == ConnectionPhase.SYNCHRONIZING)

    val setupFormReady: Boolean
        get() = credentialsReady &&
            setupPassword.length >= 8 &&
            (initialized || (setupRepeatPassword == setupPassword && setupName.isNotBlank()))
}
