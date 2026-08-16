package ru.bolid.testdpls.core.domain

enum class ConnectionPhase {
    IDLE, SCANNING, CONNECTING, PAIRING, NEGOTIATING_MTU, DISCOVERING,
    SUBSCRIBING, AUTHENTICATING, SYNCHRONIZING, READY, RECONNECTING, ERROR,
}

enum class SettingsOp { NONE, IN_PROGRESS, DONE, FAILED }

enum class UiTheme(val title: String, val wire: String) {
    SYSTEM("Система", "system"), DARK("Тёмная", "dark"), LIGHT("Светлая", "light");
    companion object { fun fromWire(value: String?): UiTheme = entries.firstOrNull { it.wire == value } ?: SYSTEM }
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
    val firmwareVersion: String? = null,
    val kind: String? = null,
) {
    val hasLineFault: Boolean get() = realShort || reserveLow || fromReserve
}

/**
 * Read-only presentation snapshot consumed by Compose.
 *
 * Lifecycle/auth flags, command progress and saved-credential availability are
 * projections of their authoritative owners in DplsClient; UI code may display
 * them but product logic must never use them as authority.
 */
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
    val controlsEnabled: Boolean get() = phase == ConnectionPhase.READY && authenticated && !commandInProgress
    val needsPeriodicStateRefresh: Boolean
        get() = authenticated && !commandInProgress && logProgress == null &&
            (state != null || phase == ConnectionPhase.SYNCHRONIZING)
    val setupFormReady: Boolean
        get() = credentialsReady && setupPassword.length >= 8 &&
            (initialized || (setupRepeatPassword == setupPassword && setupName.isNotBlank()))
}
