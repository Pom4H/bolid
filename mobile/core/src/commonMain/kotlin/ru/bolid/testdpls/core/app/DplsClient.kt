package ru.bolid.testdpls.core.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DiscoveredDevice
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.DplsUiState
import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.domain.JournalTimeAnchor
import ru.bolid.testdpls.core.domain.SettingsOp
import ru.bolid.testdpls.core.domain.UiTheme
import ru.bolid.testdpls.core.protocol.DplsAdvertisement
import ru.bolid.testdpls.core.protocol.DplsAuth
import ru.bolid.testdpls.core.protocol.DplsCrypto
import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.buildTimeSyncPayload
import ru.bolid.testdpls.core.protocol.commandRejectReason
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.protocol.parseAuthChallenge
import ru.bolid.testdpls.core.protocol.parseAuthResult
import ru.bolid.testdpls.core.protocol.parseCommandResult
import ru.bolid.testdpls.core.protocol.parseDeviceInfoReport
import ru.bolid.testdpls.core.protocol.parseLogChunk
import ru.bolid.testdpls.core.protocol.parseLogHistogramReport
import ru.bolid.testdpls.core.protocol.parseSettingsResult
import ru.bolid.testdpls.core.protocol.parseStateReport
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32
import ru.bolid.testdpls.core.protocol.readU16
import ru.bolid.testdpls.core.protocol.readU32
import ru.bolid.testdpls.core.session.DplsSessionRuntime

/**
 * Single cross-platform Test-DPLS application controller.
 *
 * Android and iOS inject BLE transport plus [DplsPlatformServices]
 * (clock, prefs, keep-alive, operator alerts). Protocol parsing, authentication,
 * retries, session state, commands, journal transfer and settings live here once.
 */
class DplsClient(
    private val transport: DplsTransport,
    private val platform: DplsPlatformServices,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : DplsController, DplsTransportListener {
    private val mutableState = MutableStateFlow(
        DplsUiState(
            uiTheme = platform.readUiTheme(),
            keepScreenOn = platform.readKeepScreenOn(),
            hapticsEnabled = platform.readHapticsEnabled(),
        ),
    )
    override val uiState: StateFlow<DplsUiState> = mutableState.asStateFlow()

    private val session = DplsSessionRuntime()
    private var selectedAddress: String? = null
    private var cachedVerifier: ByteArray? = null
    private var identifyAfterConnect = false
    private var pendingIdentifyAck = false
    private var identifySentAtMs = 0L
    private var pendingModeCommandId: Long? = null
    private var reconnectAttempt = 0
    private var reachedReady = false
    private var legacyFirmware = false
    private var awaitingDeviceInfo = false
    private var timeSyncPending = false
    private var timeSyncAttempted = false

    private sealed interface PendingSettings {
        val commandId: Long
        data class Name(override val commandId: Long, val name: String) : PendingSettings
        data class Password(override val commandId: Long, val verifier: ByteArray) : PendingSettings
    }

    private var pendingSettings: PendingSettings? = null
    private var expectedLogEvents = 0
    private var logPrefetchFrom = 0
    private var logPrefetchUntil = 0
    private val logRecords = mutableMapOf<Int, EventRecord>()
    private var logLoadPending = false
    private var logExporting = false
    private var drainLog = false
    private var logIncremental = false
    private var timeAnchors: List<JournalTimeAnchor> = emptyList()
    private var logKnownCount = 0
    private var logKnownMaxSequence = 0L
    private var awaitingLogHistogram = false
    private var previousMode: DplsMode? = null
    private var lastOperatorAlert: String? = null
    private var pendingLogAckIndex: Int? = null
    private var writeOutstanding = false

    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var preAuthKeepAliveJob: Job? = null
    private var keepAliveJob: Job? = null
    private var stateRefreshJob: Job? = null
    private var logTimeoutJob: Job? = null
    private var settingsTimeoutJob: Job? = null
    private var commandTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var rssiJob: Job? = null

    init {
        transport.setListener(this)
    }

    override fun startScan() {
        if (state.authenticated && transport.hasConnection()) {
            browseDevices()
            return
        }
        disconnectInternal(clearSelection = true, clearVerifier = true)
        mutableState.value = retainedUiState(
            phase = ConnectionPhase.SCANNING,
            statusText = "Поиск Test-DPLS…",
            scanning = true,
        )
        if (!transport.startScan()) {
            // Radio may still be coming up; onBluetoothAvailable starts the scan.
            return
        }
        armScanDeadline(keepSession = false)
    }

    override fun stopScan() {
        transport.stopScan()
        scanJob?.cancel()
        scanJob = null
        if (state.authenticated) {
            updateState {
                it.copy(
                    scanning = false,
                    statusText = if (it.devices.isEmpty()) "Устройства не найдены" else "Выберите устройство",
                )
            }
            return
        }
        updateState {
            if (it.phase != ConnectionPhase.SCANNING) it else it.copy(
                phase = ConnectionPhase.IDLE,
                scanning = false,
                statusText = if (it.devices.isEmpty()) "Устройства не найдены" else "Выберите устройство",
            )
        }
    }

    override fun resumeSession() {
        if (!state.authenticated || !transport.hasConnection()) return
        transport.stopScan()
        scanJob?.cancel()
        scanJob = null
        updateState {
            it.copy(
                browsingDevices = false,
                scanning = false,
                phase = ConnectionPhase.READY,
                statusText = "Готово",
                error = null,
            )
        }
    }

    private fun browseDevices() {
        val seed = listOfNotNull(state.selectedDevice)
        updateState {
            val devices = (seed + it.devices).distinctBy(DiscoveredDevice::address)
            it.copy(
                browsingDevices = true,
                scanning = true,
                devices = devices,
                statusText = "Поиск Test-DPLS…",
                error = null,
            )
        }
        if (!transport.startScan()) return
        armScanDeadline(keepSession = true)
    }

    override fun connect(address: String) {
        if (state.authenticated && selectedAddress == address && transport.hasConnection()) {
            resumeSession()
            return
        }
        stopScan()
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        stateRefreshJob?.cancel()
        preAuthKeepAliveJob?.cancel()
        session.resetAll()
        reachedReady = false
        selectedAddress = address
        legacyFirmware = false
        awaitingDeviceInfo = false
        timeSyncPending = false
        timeSyncAttempted = false
        pendingModeCommandId = null
        updateState {
            it.copy(
                phase = ConnectionPhase.CONNECTING,
                statusText = "Подключение…",
                selectedDevice = it.devices.firstOrNull { device -> device.address == address },
                credentialsReady = false,
                authenticated = false,
                browsingDevices = false,
                scanning = false,
                setupPassword = "",
                setupRepeatPassword = "",
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                commandInProgress = false,
                error = null,
                staleBond = false,
            )
        }
        loadCachedVerifier()
        loadTimeAnchors()
        if (!transport.connect(address)) return fail("Устройство недоступно. Запустите поиск снова.")
        platform.keepConnectionAlive(true)
        lastOperatorAlert = null
        armConnectTimeout()
    }

    override fun identify(address: String) {
        if (state.authenticated && selectedAddress == address && transport.hasConnection()) {
            resumeSession()
            return
        }
        identifyAfterConnect = true
        pendingIdentifyAck = false
        updateState {
            it.copy(
                identifyActive = true,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                selectedDevice = it.devices.firstOrNull { device -> device.address == address } ?: it.selectedDevice,
            )
        }
        connect(address)
    }

    override fun stopIdentify() {
        identifyAfterConnect = false
        pendingIdentifyAck = false
        identifySentAtMs = 0
        stopRssiPoll()
        updateState {
            it.copy(
                identifyActive = false,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                linkRssi = null,
            )
        }
        if (transport.hasConnection()) send(DplsProtocol.Type.IDENTIFY_STOP, priority = true)
    }

    override fun confirmIdentifiedDevice() {
        stopIdentify()
        preAuthKeepAliveJob?.cancel()
        if (state.credentialsReady) return
        if (!transport.hasConnection()) {
            selectedAddress = null
            fail("Связь с платой оборвалась. Выберите устройство снова.")
            updateState { it.copy(selectedDevice = null) }
            return
        }
        updateState { it.copy(phase = ConnectionPhase.AUTHENTICATING, statusText = "Подключение…", error = null) }
        send(DplsProtocol.Type.HELLO, session.clientNonce, priority = true)
    }

    override fun updateSetupName(name: String) = updateState { it.copy(setupName = name) }
    override fun updateSetupPassword(password: String) = updateState { it.copy(setupPassword = password) }
    override fun updateSetupRepeatPassword(password: String) = updateState { it.copy(setupRepeatPassword = password) }

    override fun authenticate(password: String) {
        if (password.length < 8) return fail("Пароль должен содержать не менее 8 символов")
        preAuthKeepAliveJob?.cancel()
        val verifier = DplsCrypto.deriveVerifier(password, session.authSalt)
        replaceCachedVerifier(verifier)
        sendAuthProof(verifier)
    }

    override fun setup(name: String, password: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return fail("Введите имя устройства")
        if (password.length < 8) return fail("Пароль должен содержать не менее 8 символов")

        val salt = platform.secureRandomBytes(DplsAuth.SALT_SIZE)
        val verifier = DplsCrypto.deriveVerifier(password, salt)
        replaceCachedVerifier(verifier)

        val nameBytes = utf8Truncate(trimmed, 31)
        val payload = ByteArray(5 + nameBytes.size + salt.size + verifier.size)
        putU32(payload, 0, session.sessionId)
        payload[4] = nameBytes.size.toByte()
        nameBytes.copyInto(payload, 5)
        salt.copyInto(payload, 5 + nameBytes.size)
        verifier.copyInto(payload, 5 + nameBytes.size + salt.size)
        send(DplsProtocol.Type.SETUP, payload)
        salt.fill(0)
    }

    override fun requestMode(mode: DplsMode) {
        if (state.controlsEnabled) updateState { it.copy(pendingMode = mode) }
    }

    override fun cancelMode() = updateState { it.copy(pendingMode = null) }

    override fun confirmMode() {
        val mode = state.pendingMode ?: return
        val commandId = session.nextCommandId()
        pendingModeCommandId = commandId
        val payload = ByteArray(17)
        putU32(payload, 0, session.sessionId)
        session.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, commandId)
        payload[16] = mode.wire.toByte()
        updateState { it.copy(commandInProgress = true, pendingMode = null, statusText = "Команда отправлена…") }
        send(DplsProtocol.Type.MODE_SET, payload)
        scheduleStateRefresh()
        commandTimeoutJob?.cancel()
        commandTimeoutJob = scope.launch {
            delay(COMMAND_TIMEOUT_MS)
            if (state.commandInProgress && pendingModeCommandId == commandId) {
                send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
                updateState { it.copy(statusText = "Запрос состояния устройства…") }
            }
        }
    }

    override fun returnToNormal() {
        updateState { it.copy(pendingMode = DplsMode.NORMAL) }
        confirmMode()
    }

    override fun loadEventLog() {
        if (logExporting || state.logProgress != null || !state.authenticated) return
        if (state.eventLog.isNotEmpty() || state.logHasMore) return
        startEventLogTransfer()
    }

    override fun refreshEventLog() {
        if (logExporting || state.logProgress != null || !state.authenticated) return
        startEventLogTransfer(incremental = logRecords.isNotEmpty())
    }

    private fun startEventLogTransfer(incremental: Boolean = false) {
        val keep = incremental && logRecords.isNotEmpty()
        logExporting = true
        logLoadPending = true
        pendingLogAckIndex = null
        drainLog = false
        logIncremental = keep
        if (keep) {
            logKnownCount = expectedLogEvents
            logKnownMaxSequence = logRecords.values.maxOfOrNull { it.sequence } ?: 0L
        } else {
            expectedLogEvents = 0
            logPrefetchFrom = 0
            logPrefetchUntil = 0
            logKnownCount = 0
            logKnownMaxSequence = 0L
            logRecords.clear()
        }
        keepAliveJob?.cancel()
        stateRefreshJob?.cancel()
        updateState {
            it.copy(
                logProgress = 0f,
                error = null,
            )
        }
        send(
            DplsProtocol.Type.LOG_START,
            session.authenticatedPayload() + ByteArray(2),
            flush = true,
        )
        armLogTimeout()
    }

    override fun loadLogHistogram() {
        if (!state.authenticated || !transport.hasConnection() || awaitingLogHistogram) return
        if (logExporting || state.logProgress != null) return
        awaitingLogHistogram = true
        send(DplsProtocol.Type.LOG_HIST_GET, session.authenticatedPayload() + byteArrayOf(24))
    }

    override fun loadMoreEventLog() {
        if (!state.authenticated || !state.logHasMore || logExporting) return
        val missing = nextGlobalMissingLogIndex() ?: return
        logPrefetchFrom = missing
        val windowEnd = minOf(expectedLogEvents, missing + LOG_PAGE_SIZE)
        logPrefetchUntil = (missing until windowEnd).firstOrNull { it in logRecords } ?: windowEnd
        if (logPrefetchFrom >= logPrefetchUntil) return
        logExporting = true
        keepAliveJob?.cancel()
        stateRefreshJob?.cancel()
        updateLogProgress()
        sendLogAck(nextMissingLogIndex())
        armLogTimeout()
    }

    override fun loadRemainingEventLog() {
        if (!state.authenticated || logExporting || !state.logHasMore) return
        drainLog = true
        loadMoreEventLog()
    }

    override fun refreshState() {
        if (state.authenticated && !logExporting && state.logProgress == null && transport.hasConnection()) {
            send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
            scheduleStateRefresh()
        }
    }

    override fun requestDeviceInfo() {
        if (state.authenticated && transport.hasConnection()) requestDeviceInfoInternal()
    }

    override fun clearSettingsOp() {
        clearPendingSettings()
        updateState { it.copy(settingsOp = SettingsOp.NONE, settingsError = null, settingsNotice = null) }
    }

    override fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return settingsFailure("Введите имя устройства")
        if (!state.authenticated || !transport.hasConnection()) return settingsFailure("Нет соединения с устройством")

        val nameBytes = utf8Truncate(trimmed, 31)
        val commandId = session.nextCommandId()
        val payload = ByteArray(17 + nameBytes.size)
        putU32(payload, 0, session.sessionId)
        session.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, commandId)
        payload[16] = nameBytes.size.toByte()
        nameBytes.copyInto(payload, 17)
        armPendingSettings(PendingSettings.Name(commandId, trimmed))
        updateState { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null, settingsNotice = null) }
        send(DplsProtocol.Type.NAME_SET, payload)
    }

    override fun changePassword(current: String, newPassword: String) {
        if (newPassword.length < 8) return settingsFailure("Пароль должен содержать не менее 8 символов")
        if (!state.authenticated || !transport.hasConnection()) return settingsFailure("Нет соединения с устройством")

        val currentVerifier = DplsCrypto.deriveVerifier(current, session.authSalt)
        val matches = cachedVerifier?.let { constantTimeEquals(it, currentVerifier) } == true
        currentVerifier.fill(0)
        if (!matches) return settingsFailure("Неверный текущий пароль")

        val salt = platform.secureRandomBytes(DplsAuth.SALT_SIZE)
        val verifier = DplsCrypto.deriveVerifier(newPassword, salt)
        val commandId = session.nextCommandId()
        val payload = ByteArray(64)
        putU32(payload, 0, session.sessionId)
        session.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, commandId)
        salt.copyInto(payload, 16)
        verifier.copyInto(payload, 32)
        salt.fill(0)
        armPendingSettings(PendingSettings.Password(commandId, verifier))
        updateState { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null, settingsNotice = null) }
        send(DplsProtocol.Type.PASSWORD_SET, payload)
    }

    override fun setUiTheme(theme: UiTheme) {
        platform.writeUiTheme(theme)
        updateState { it.copy(uiTheme = theme) }
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        platform.writeKeepScreenOn(enabled)
        updateState { it.copy(keepScreenOn = enabled) }
    }

    override fun setHapticsEnabled(enabled: Boolean) {
        platform.writeHapticsEnabled(enabled)
        updateState { it.copy(hapticsEnabled = enabled) }
    }

    override fun forgetSavedPassword() {
        if (cachedVerifier == null && !state.savedCredentials) return
        forgetCachedVerifier()
        updateState {
            it.copy(
                savedCredentials = false,
                settingsOp = SettingsOp.DONE,
                settingsError = null,
                settingsNotice = "Сохранённый пароль удалён",
            )
        }
    }

    override fun disconnect() {
        stopIdentify()
        disconnectInternal(clearSelection = true, clearVerifier = true)
        mutableState.value = retainedUiState()
    }

    override fun openBluetoothSettings() {
        platform.openBluetoothSettings()
    }

    override fun canOpenBluetoothSettings(): Boolean = platform.canOpenSystemBluetoothSettings()

    override fun eventLogCsv(): String = buildString {
        appendLine("sequence;timestamp_seconds;time_basis;event_type;parameter")
        state.eventLog.forEach {
            appendLine("${it.sequence};${it.timestampSeconds};${eventTimestampBasis(it.timestampSeconds)};${it.type};${it.parameter}")
        }
    }

    override fun eventLogTxt(): String = buildString {
        appendLine("Журнал событий Тест-ДПЛС")
        appendLine("Устройство: ${state.deviceInfo?.userName ?: state.selectedDevice?.userName ?: "—"}")
        appendLine("Записей: ${state.eventLog.size}${if (state.logHasMore) " из ${state.logTotal} (неполный)" else ""}")
        appendLine("—".repeat(32))
        state.eventLog.forEach { appendLine("#${it.sequence}  ${eventTimestampText(it.timestampSeconds)}  событие ${it.type} · ${it.parameter}") }
    }

    override fun formatEventTime(record: EventRecord): String {
        val records = state.eventLog
        return journalEventTimeCaption(
            record = record,
            records = records,
            currentBootFirst = currentBootFirstSequence(records),
            currentBootEpoch = state.deviceBootEpochSeconds,
            anchors = timeAnchors,
            formatWall = platform::formatLocalDateTime,
        )
    }

    override fun formatEventInstant(uptimeSeconds: Long): String {
        val boot = state.deviceBootEpochSeconds ?: return ""
        return platform.formatLocalDateTime(boot + uptimeSeconds)
    }

    fun close() {
        disconnectInternal(clearSelection = true, clearVerifier = true)
        transport.close()
        scope.cancel()
    }

    override fun onBluetoothAvailable() {
        reconnectAttempt = 0
        if (selectedAddress != null && !transport.hasConnection()) {
            scheduleReconnect()
            return
        }
        if (state.phase == ConnectionPhase.SCANNING && transport.startScan()) {
            armScanDeadline(keepSession = false)
        }
        if (state.browsingDevices && state.authenticated && transport.startScan()) {
            armScanDeadline(keepSession = true)
        }
    }

    override fun onBluetoothUnavailable() {
        cancelConnectionJobs()
        pendingModeCommandId = null
        timeSyncPending = false
        timeSyncAttempted = false
        session.resetLink()
        updateState {
            it.copy(
                phase = ConnectionPhase.RECONNECTING,
                statusText = "Bluetooth выключен",
                authenticated = false,
                credentialsReady = cachedVerifier != null,
                savedCredentials = cachedVerifier != null,
                staleState = it.state != null,
                commandInProgress = false,
                logProgress = null,
                error = null,
            )
        }
    }

    override fun onDiscovered(device: DplsTransportDevice) {
        val previous = state.devices.firstOrNull { it.address == device.address }
        val item = DiscoveredDevice(
            address = device.address,
            advertisedName = device.name,
            userName = previous?.userName,
            deviceId = device.deviceId,
            rssi = device.rssi,
            realShort = DplsAdvertisement.realShort(device.advStatus),
            fromReserve = DplsAdvertisement.fromReserve(device.advStatus),
            reserveLow = DplsAdvertisement.reserveLow(device.advStatus),
        )
        updateState {
            val merged = (it.devices.filterNot { old -> old.address == item.address } + item)
            val selected = it.selectedDevice
            val devices = if (it.authenticated && selected != null && merged.none { row -> row.address == selected.address }) {
                merged + selected
            } else {
                merged
            }.sortedWith(
                compareByDescending<DiscoveredDevice> { row -> row.realShort }
                    .thenByDescending { row -> row.reserveLow }
                    .thenByDescending { row -> row.fromReserve }
                    .thenByDescending(DiscoveredDevice::rssi),
            )
            it.copy(devices = devices, statusText = "Найдено: ${devices.size}")
        }
    }

    override fun onConnected() = updateState {
        it.copy(
            phase = ConnectionPhase.DISCOVERING,
            statusText = if (it.identifyActive) "Подключение…" else "Поиск службы…",
            error = null,
        )
    }

    override fun onSubscribed(writeLimit: Int) {
        if (writeLimit < DplsProtocol.OVERHEAD) return fail("BLE write limit слишком мал: $writeLimit")
        session.clientNonce = platform.secureRandomBytes(DplsAuth.NONCE_SIZE)
        if (identifyAfterConnect) {
            identifyAfterConnect = false
            pendingIdentifyAck = true
            identifySentAtMs = platform.nowMillis()
            updateState { it.copy(phase = ConnectionPhase.AUTHENTICATING, statusText = "Показать на объекте…") }
            send(DplsProtocol.Type.IDENTIFY_START)
        } else {
            updateState { it.copy(phase = ConnectionPhase.AUTHENTICATING, statusText = "Подключение…") }
            send(DplsProtocol.Type.HELLO, session.clientNonce)
        }
    }

    override fun onBytes(bytes: ByteArray) {
        when (val decoded = decodeFrame(bytes)) {
            is DplsProtocol.DecodeResult.Failure -> fail(decoded.reason)
            is DplsProtocol.DecodeResult.Success -> handleMessage(decoded.frame)
        }
    }

    override fun onWriteComplete(errorCode: Long?) {
        writeOutstanding = false
        if (errorCode == null) {
            if (pendingIdentifyAck) {
                pendingIdentifyAck = false
                val phase = DplsIdentifyLed.phaseAtAckMs(identifySentAtMs, platform.nowMillis())
                updateState {
                    it.copy(
                        identifyLedLive = true,
                        identifyLedPhaseOffsetMs = phase,
                        linkRssi = it.linkRssi ?: it.selectedDevice?.rssi,
                    )
                }
                scheduleRssiPoll()
            }
            trySendPendingLogAck()
        } else if (reachedReady) {
            scheduleReconnect()
        } else {
            fail("Ошибка передачи BLE: $errorCode")
        }
    }

    override fun onRssi(rssi: Int) {
        updateState { current ->
            val selected = current.selectedDevice
            current.copy(
                linkRssi = rssi,
                selectedDevice = selected?.copy(rssi = rssi),
                devices = current.devices.map { device ->
                    if (device.address == selected?.address) device.copy(rssi = rssi) else device
                },
            )
        }
    }

    override fun onDisconnected(error: String?) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        if (state.staleBond) return
        if (looksLikeStaleBondError(error)) {
            onStaleBond()
            return
        }
        if (reachedReady && selectedAddress != null && state.phase != ConnectionPhase.ERROR) {
            scheduleReconnect()
            return
        }
        identifyAfterConnect = false
        pendingIdentifyAck = false
        if (state.identifyActive) {
            fail(error ?: "Связь с платой оборвалась до идентификации")
            return
        }
        selectedAddress = null
        updateState {
            it.copy(
                phase = ConnectionPhase.IDLE,
                selectedDevice = null,
                statusText = error ?: "Отключено",
                identifyActive = false,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                browsingDevices = false,
            )
        }
    }

    override fun onTransportError(message: String) {
        if (looksLikeStaleBondError(message)) {
            onStaleBond()
            return
        }
        fail(message)
    }

    override fun onStaleBond() {
        reachedReady = false
        reconnectJob?.cancel()
        identifyAfterConnect = false
        pendingIdentifyAck = false
        val name = state.selectedDevice?.userName
            ?: state.selectedDevice?.advertisedName
            ?: "Test-DPLS"
        fail(
            "Старое сопряжение с «$name». Настройки → Bluetooth → ⓘ у этого имени → Забыть. Затем «Повторить».",
            staleBond = true,
        )
    }

    private fun handleMessage(frame: DplsProtocol.Frame) {
        when (frame.type) {
            DplsProtocol.Type.AUTH_CHALLENGE -> handleAuthChallenge(frame.payload)
            DplsProtocol.Type.AUTH_RESULT -> handleAuthResult(frame.payload)
            DplsProtocol.Type.COMMAND_RESULT -> handleCommandResult(frame.payload)
            DplsProtocol.Type.DEVICE_INFO_REPORT -> handleDeviceInfo(frame.payload)
            DplsProtocol.Type.SETTINGS_RESULT -> handleSettingsResult(frame.payload)
            DplsProtocol.Type.STATE_REPORT -> handleState(frame.payload)
            DplsProtocol.Type.LOG_INFO -> handleLogInfo(frame.payload)
            DplsProtocol.Type.LOG_CHUNK -> handleLogChunk(frame.payload)
            DplsProtocol.Type.LOG_RESULT -> finishLog()
            DplsProtocol.Type.LOG_HIST_REPORT -> handleLogHistogram(frame.payload)
            DplsProtocol.Type.ERROR -> handleDeviceError(frame.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0)
            else -> Unit
        }
    }

    private fun handleAuthChallenge(payload: ByteArray) {
        val challenge = parseAuthChallenge(payload) ?: return fail("Повреждённый AUTH_CHALLENGE")
        session.setChallenge(challenge.sessionId, challenge.deviceNonce, challenge.salt, challenge.initialized)
        val autoAuth = challenge.initialized && cachedVerifier != null
        updateState {
            it.copy(
                initialized = challenge.initialized,
                credentialsReady = true,
                awaitingUserPassword = !autoAuth,
                statusText = if (autoAuth) "Вход…" else "Подключено",
                setupName = it.setupName.ifBlank { it.selectedDevice?.userName ?: "Test-DPLS-001" },
                setupPassword = "",
                setupRepeatPassword = "",
            )
        }
        schedulePreAuthKeepAlive()
        if (autoAuth) cachedVerifier?.let(::sendAuthProof)
    }

    private fun handleAuthResult(payload: ByteArray) {
        if (state.authenticated) return
        val result = parseAuthResult(payload) ?: return fail("Повреждённый AUTH_RESULT")
        preAuthKeepAliveJob?.cancel()
        if (result.status == 3) {
            persistCachedVerifier()
            updateState {
                it.copy(
                    phase = ConnectionPhase.RECONNECTING,
                    statusText = "Настройка сохранена. Повторное подключение…",
                    credentialsReady = true,
                    initialized = true,
                    awaitingUserPassword = false,
                    setupPassword = "",
                    setupRepeatPassword = "",
                    error = null,
                )
            }
            return
        }
        if (result.status != 0) {
            forgetCachedVerifier()
            updateState { it.copy(awaitingUserPassword = true) }
            return fail(
                if (result.retryAfterSeconds > 0) "Аутентификация заблокирована на ${result.retryAfterSeconds} с"
                else "Неверный пароль",
            )
        }
        persistCachedVerifier()
        val token = result.sessionToken ?: return fail("AUTH_RESULT без session token")
        session.authenticate(token)
        updateState {
            it.copy(
                authenticated = true,
                awaitingUserPassword = false,
                identifyActive = false,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                phase = ConnectionPhase.SYNCHRONIZING,
                statusText = "Чтение состояния…",
                error = null,
            )
        }
        send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
        scheduleKeepAlive()
        scheduleStateRefresh()
    }

    private fun handleCommandResult(payload: ByteArray) {
        val result = parseCommandResult(payload) ?: return fail("Повреждённый COMMAND_RESULT")
        if (pendingModeCommandId != result.commandId) return
        if (result.status != 0) {
            pendingModeCommandId = null
            commandTimeoutJob?.cancel()
            return fail(commandRejectReason(result.status))
        }
        pendingModeCommandId = null
        commandTimeoutJob?.cancel()
        updateState {
            it.copy(
                commandInProgress = false,
                statusText = "Команда применена, чтение состояния…",
                lastAckMillis = platform.nowMillis(),
            )
        }
        if (state.logProgress == null && !logExporting) send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
    }

    private fun handleDeviceInfo(payload: ByteArray) {
        awaitingDeviceInfo = false
        val info = parseDeviceInfoReport(payload) ?: return
        updateState {
            it.copy(
                deviceInfo = info,
                selectedDevice = it.selectedDevice?.copy(userName = info.userName.ifBlank { it.selectedDevice.userName }),
            )
        }
        loadTimeAnchors()
        persistTimeAnchors()
        attemptTimeSync()
    }

    private fun handleSettingsResult(payload: ByteArray) {
        val result = parseSettingsResult(payload) ?: return
        val pending = pendingSettings ?: return
        if (pending.commandId != result.commandId) return
        settingsTimeoutJob?.cancel()
        pendingSettings = null
        if (result.status == 0) {
            when (pending) {
                is PendingSettings.Password -> {
                    replaceCachedVerifier(pending.verifier)
                    persistCachedVerifier()
                    updateState {
                        it.copy(
                            settingsOp = SettingsOp.DONE,
                            settingsError = null,
                            settingsNotice = "Пароль изменён",
                        )
                    }
                }
                is PendingSettings.Name -> {
                    requestDeviceInfoInternal()
                    updateState {
                        it.copy(
                            settingsOp = SettingsOp.DONE,
                            settingsError = null,
                            settingsNotice = "Имя «${pending.name}» применено",
                            selectedDevice = it.selectedDevice?.copy(userName = pending.name),
                            deviceInfo = it.deviceInfo?.copy(userName = pending.name),
                        )
                    }
                }
            }
        } else {
            if (pending is PendingSettings.Password) pending.verifier.fill(0)
            settingsFailure("Устройство отклонило изменение (код ${result.status})")
        }
    }

    private fun handleState(payload: ByteArray) {
        val now = platform.nowMillis()
        val deviceState = parseStateReport(payload, now) ?: return fail("Повреждённый STATE_REPORT")
        updateState {
            it.copy(
                phase = ConnectionPhase.READY,
                statusText = "Состояние получено",
                state = deviceState,
                deviceBootEpochSeconds = now / 1000 - deviceState.uptimeSeconds,
                authenticated = true,
                identifyActive = false,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                staleState = false,
                lastAckMillis = now,
                error = null,
            )
        }
        reachedReady = true
        reconnectAttempt = 0
        val previous = previousMode
        previousMode = deviceState.mode
        if (previous?.dangerous == true && deviceState.mode == DplsMode.NORMAL) {
            platform.notifyOperator(DplsOperatorAlerts.NORMAL_TITLE, DplsOperatorAlerts.NORMAL_BODY)
        }
        if (!logExporting) {
            scheduleStateRefresh()
            if (state.deviceInfo == null && !legacyFirmware && !awaitingDeviceInfo) {
                requestDeviceInfoInternal()
            } else {
                attemptTimeSync()
            }
        }
        if (logLoadPending && !logExporting) loadEventLog()
    }

    private fun handleLogInfo(payload: ByteArray) {
        if (payload.size < 10) return failLog("Повреждённый LOG_INFO")
        val totalBytes = readU32(payload, 4).toInt().coerceAtLeast(0)
        val rawCount = readU16(payload, 8)
        val newCount = minOf(rawCount, totalBytes / 10, MAX_LOG_EVENTS).coerceAtLeast(0)
        if (logIncremental && logRecords.isNotEmpty()) {
            handleIncrementalLogInfo(newCount)
        } else {
            applyFreshLogInfo(newCount)
        }
    }

    private fun applyFreshLogInfo(newCount: Int) {
        logIncremental = false
        expectedLogEvents = newCount
        logRecords.clear()
        logPrefetchUntil = expectedLogEvents
        logPrefetchFrom = maxOf(0, expectedLogEvents - LOG_PAGE_SIZE)
        if (expectedLogEvents == 0) {
            finishLog()
        } else {
            updateLogProgress()
            sendLogAck(nextMissingLogIndex())
        }
    }

    private fun handleIncrementalLogInfo(newCount: Int) {
        if (newCount == 0 || newCount < logKnownCount || newCount < logRecords.size) {
            applyFreshLogInfo(newCount)
            return
        }
        expectedLogEvents = newCount
        logPrefetchFrom = if (newCount > logKnownCount) {
            (logKnownCount - 1).coerceAtLeast(0)
        } else {
            (newCount - 1).coerceAtLeast(0)
        }
        logPrefetchUntil = newCount
        if (logPrefetchFrom >= logPrefetchUntil) {
            finishLog()
            return
        }
        updateLogProgress()
        sendLogAck(logPrefetchFrom)
    }

    private fun handleLogChunk(payload: ByteArray) {
        val batch = parseLogChunk(payload) ?: return failLog("Повреждённый LOG_CHUNK")
        batch.records.forEachIndexed { offset, record ->
            val index = batch.firstIndex + offset
            if (index !in 0 until expectedLogEvents) return@forEachIndexed
            val previous = logRecords[index]
            if (logIncremental && previous != null && previous.sequence != record.sequence) {
                applyFreshLogInfo(expectedLogEvents)
                return
            }
            if (logIncremental && previous == null && index >= logKnownCount &&
                logKnownMaxSequence > 0L && record.sequence <= logKnownMaxSequence
            ) {
                applyFreshLogInfo(expectedLogEvents)
                return
            }
            if (previous == null) logRecords[index] = record
        }
        publishLogRecords(inFlight = true)
        when {
            logRecords.size >= expectedLogEvents -> finishLog()
            nextMissingLogIndex() < logPrefetchUntil -> {
                updateLogProgress()
                sendLogAck(nextMissingLogIndex())
            }
            logIncremental -> pauseLogPaging()
            0 !in logRecords -> {
                logPrefetchFrom = 0
                logPrefetchUntil = minOf(LOG_PAGE_SIZE, expectedLogEvents)
                updateLogProgress()
                sendLogAck(nextMissingLogIndex())
            }
            else -> pauseLogPaging()
        }
    }

    private fun publishedLogRecords(): List<EventRecord> =
        logRecords.values.sortedByDescending { it.sequence }

    private fun currentBootFirstSequence(records: List<EventRecord> = publishedLogRecords()): Long? =
        journalBootFirstSequences(records).lastOrNull()

    private fun rememberCurrentBootAnchor(records: List<EventRecord>) {
        val epoch = state.deviceBootEpochSeconds ?: return
        val bootFirst = currentBootFirstSequence(records) ?: return
        val lastSequence = records.maxOfOrNull { it.sequence } ?: return
        timeAnchors = mergeJournalTimeAnchor(
            timeAnchors,
            JournalTimeAnchor(bootFirst, epoch, lastSequence),
        )
        persistTimeAnchors()
    }

    private fun loadTimeAnchors() {
        var merged = timeAnchors
        for (key in credentialKeys()) {
            for (anchor in decodeJournalTimeAnchors(platform.readDeviceString(timeAnchorKey(key)))) {
                merged = mergeJournalTimeAnchor(merged, anchor)
            }
        }
        timeAnchors = merged
        updateState { it.copy(journalTimeAnchors = timeAnchors) }
    }

    private fun persistTimeAnchors() {
        val encoded = encodeJournalTimeAnchors(timeAnchors)
        for (key in credentialKeys()) {
            platform.writeDeviceString(timeAnchorKey(key), encoded)
        }
    }

    private fun timeAnchorKey(deviceKey: String): String = "time.$deviceKey"

    private fun publishLogRecords(inFlight: Boolean) {
        val records = publishedLogRecords()
        if (records.isNotEmpty()) rememberCurrentBootAnchor(records)
        val hasMore = records.size < expectedLogEvents
        val lastIndex = (expectedLogEvents - 1).coerceAtLeast(0)
        val spanReady = expectedLogEvents > 0 && 0 in logRecords && lastIndex in logRecords
        updateState {
            it.copy(
                eventLog = records,
                journalTimeAnchors = timeAnchors,
                logTotal = expectedLogEvents,
                logHasMore = hasMore,
                logFirstTimestampSeconds = if (spanReady) logRecords[0]?.timestampSeconds else it.logFirstTimestampSeconds,
                logLastTimestampSeconds = if (spanReady) logRecords[lastIndex]?.timestampSeconds else it.logLastTimestampSeconds,
                logProgress = if (inFlight && expectedLogEvents > 0) {
                    (records.size.toFloat() / expectedLogEvents.toFloat()).coerceIn(0.05f, 1f)
                } else {
                    null
                },
                statusText = if (hasMore) {
                    "Журнал: ${records.size} из $expectedLogEvents"
                } else {
                    "Журнал загружен: ${records.size} записей"
                },
                error = null,
            )
        }
    }

    private fun pauseLogPaging() {
        logTimeoutJob?.cancel()
        logLoadPending = false
        logExporting = false
        pendingLogAckIndex = null
        publishLogRecords(inFlight = false)
        if (drainLog && state.logHasMore) {
            loadMoreEventLog()
            return
        }
        drainLog = false
        scheduleKeepAlive()
        scheduleStateRefresh()
    }

    private fun finishLog() {
        logTimeoutJob?.cancel()
        logLoadPending = false
        logExporting = false
        drainLog = false
        logIncremental = false
        pendingLogAckIndex = null
        expectedLogEvents = logRecords.size
        publishLogRecords(inFlight = false)
        scheduleKeepAlive()
        scheduleStateRefresh()
    }

    private fun updateLogProgress() {
        if (expectedLogEvents <= 0) return
        val progress = (logRecords.size.toFloat() / expectedLogEvents.toFloat()).coerceIn(0.05f, 1f)
        updateState { it.copy(logProgress = progress, logTotal = expectedLogEvents) }
    }

    private fun armLogTimeout() {
        logTimeoutJob?.cancel()
        logTimeoutJob = scope.launch {
            delay(LOG_CHUNK_TIMEOUT_MS)
            if (logExporting) failLog("Не удалось загрузить журнал")
        }
    }

    private fun nextMissingLogIndex(): Int =
        (logPrefetchFrom until logPrefetchUntil).firstOrNull { it !in logRecords } ?: logPrefetchUntil

    private fun nextGlobalMissingLogIndex(): Int? =
        (0 until expectedLogEvents).firstOrNull { it !in logRecords }

    private fun sendLogAck(index: Int) {
        pendingLogAckIndex = index
        trySendPendingLogAck()
    }

    private fun trySendPendingLogAck() {
        if (writeOutstanding || !logExporting) return
        val index = pendingLogAckIndex ?: return
        pendingLogAckIndex = null
        val suffix = ByteArray(2)
        putU16(suffix, 0, index)
        send(DplsProtocol.Type.LOG_ACK, session.authenticatedPayload() + suffix)
    }

    private fun handleLogHistogram(payload: ByteArray) {
        awaitingLogHistogram = false
        val report = parseLogHistogramReport(payload) ?: return
        updateState {
            it.copy(
                logHistogram = report.takeIf { item -> item.eventCount > 0 && item.counts.isNotEmpty() },
                logFirstTimestampSeconds = report.firstTimestampSeconds,
                logLastTimestampSeconds = report.lastTimestampSeconds,
                logTotal = if (it.logTotal == 0) report.eventCount else it.logTotal,
            )
        }
        if (state.eventLog.isEmpty() && !state.logHasMore && !logExporting) loadEventLog()
    }

    private fun handleDeviceError(code: Int) {
        if (code == 5 && timeSyncPending) {
            /* Firmware before TIME_SYNC reports "unsupported message". Keep the
             * connection usable and fall back to the legacy uptime journal. */
            timeSyncPending = false
            legacyFirmware = true
            if (state.state == null) send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
            return
        }
        if (awaitingLogHistogram) {
            awaitingLogHistogram = false
            if (code == 5) legacyFirmware = true
            if (state.eventLog.isEmpty() && !logExporting) loadEventLog()
            return
        }
        if (logExporting || state.logProgress != null) return failLog("Ошибка загрузки журнала: $code")
        if (code == 5 && awaitingDeviceInfo) {
            awaitingDeviceInfo = false
            legacyFirmware = true
            attemptTimeSync()
            return
        }
        if (code == 5 && pendingSettings != null) {
            clearPendingSettings()
            legacyFirmware = true
            return settingsFailure("Прошивка устройства не поддерживает изменение настроек")
        }
        fail(
            if (code == 7) "Окно первичной настройки закрыто. Выключите и включите устройство, затем повторите настройку."
            else "Ошибка устройства: $code",
        )
    }

    private fun sendAuthProof(verifier: ByteArray) {
        val signed = DplsAuth.proofMessage(session.deviceNonce, session.clientNonce, session.sessionId)
        val mac = DplsCrypto.hmacSha256(verifier, signed)
        signed.fill(0)
        send(DplsProtocol.Type.AUTH_PROOF, session.clientNonce + mac)
        mac.fill(0)
    }

    private fun send(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
        priority: Boolean = false,
        flush: Boolean = false,
    ) {
        val bytes = encodeFrame(DplsProtocol.Frame(type, session.nextSequence(), payload = payload))
        writeOutstanding = true
        if (!transport.send(bytes, priority, flush)) {
            writeOutstanding = false
            fail("Кадр ${bytes.size} байт не помещается в BLE write limit")
        }
    }

    private fun requestDeviceInfoInternal() {
        awaitingDeviceInfo = true
        send(DplsProtocol.Type.DEVICE_INFO_GET, session.authenticatedPayload())
    }

    private fun attemptTimeSync() {
        if (timeSyncAttempted || legacyFirmware) return
        val unixSeconds = platform.nowMillis() / 1000L
        val payload = buildTimeSyncPayload(session.sessionId, session.sessionToken, unixSeconds) ?: return
        timeSyncAttempted = true
        timeSyncPending = true
        send(DplsProtocol.Type.TIME_SYNC, payload)
    }

    private fun schedulePreAuthKeepAlive() {
        preAuthKeepAliveJob?.cancel()
        preAuthKeepAliveJob = scope.launch {
            while (!state.authenticated && transport.hasConnection()) {
                delay(KEEP_ALIVE_MS)
                if (state.credentialsReady && !state.identifyActive && !state.authenticated) {
                    send(DplsProtocol.Type.KEEP_ALIVE)
                }
            }
        }
    }

    private fun scheduleKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (state.authenticated && transport.hasConnection()) {
                delay(KEEP_ALIVE_MS)
                if (state.authenticated && !logExporting && state.logProgress == null && !state.needsPeriodicStateRefresh) {
                    send(DplsProtocol.Type.KEEP_ALIVE, session.authenticatedPayload())
                }
            }
        }
    }

    private fun scheduleStateRefresh() {
        stateRefreshJob?.cancel()
        if (logExporting || !state.needsPeriodicStateRefresh || !transport.hasConnection()) return
        stateRefreshJob = scope.launch {
            while (!logExporting && state.needsPeriodicStateRefresh && transport.hasConnection()) {
                delay(STATE_REFRESH_MS)
                if (logExporting || !state.needsPeriodicStateRefresh) break
                val receivedAt = state.state?.receivedAtMillis
                if (receivedAt != null && platform.nowMillis() - receivedAt >= TELEMETRY_STALE_MS) {
                    updateState { it.copy(staleState = true) }
                }
                send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || selectedAddress == null) return
        cancelConnectionJobs()
        pendingModeCommandId = null
        timeSyncPending = false
        timeSyncAttempted = false
        session.resetLink()
        updateState {
            it.copy(
                phase = ConnectionPhase.RECONNECTING,
                statusText = if (reachedReady || logLoadPending) "Восстановление связи…" else "Подключение…",
                authenticated = false,
                staleState = it.state != null,
                credentialsReady = cachedVerifier != null,
                savedCredentials = cachedVerifier != null,
                commandInProgress = false,
            )
        }
        val delays = longArrayOf(500, 1_000, 2_000, 4_000, 5_000)
        val delayMs = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            if (selectedAddress != null && !transport.reconnect()) {
                fail("Устройство недоступно. Запустите поиск снова.")
            }
        }
    }

    private fun armPendingSettings(op: PendingSettings) {
        clearPendingSettings()
        pendingSettings = op
        settingsTimeoutJob = scope.launch {
            delay(SETTINGS_TIMEOUT_MS)
            if (pendingSettings != null) {
                clearPendingSettings()
                settingsFailure("Устройство не ответило на изменение настроек")
            }
        }
    }

    private fun clearPendingSettings() {
        settingsTimeoutJob?.cancel()
        (pendingSettings as? PendingSettings.Password)?.verifier?.fill(0)
        pendingSettings = null
    }

    private fun armScanDeadline(keepSession: Boolean) {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_DURATION_MS)
            if (keepSession) {
                transport.stopScan()
                updateState {
                    it.copy(
                        scanning = false,
                        statusText = if (it.devices.isEmpty()) "Устройства не найдены" else "Выберите устройство",
                    )
                }
            } else if (state.phase == ConnectionPhase.SCANNING) {
                stopScan()
            }
        }
    }

    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val now = state
            if (now.identifyLedLive || now.identifyActive || now.authenticated || now.awaitingUserPassword || now.credentialsReady) {
                return@launch
            }
            transport.disconnect(clearSelection = false)
            fail("Плата не отвечает по Bluetooth. Выберите устройство снова.")
        }
    }

    private fun cancelConnectionJobs() {
        reconnectJob?.cancel()
        preAuthKeepAliveJob?.cancel()
        keepAliveJob?.cancel()
        stateRefreshJob?.cancel()
        commandTimeoutJob?.cancel()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        stopRssiPoll()
    }

    private fun scheduleRssiPoll() {
        rssiJob?.cancel()
        rssiJob = scope.launch {
            while (isActive && state.identifyLedLive && transport.hasConnection()) {
                transport.readRssi()
                delay(RSSI_POLL_MS)
            }
        }
    }

    private fun stopRssiPoll() {
        rssiJob?.cancel()
        rssiJob = null
    }

    private fun disconnectInternal(clearSelection: Boolean, clearVerifier: Boolean) {
        scanJob?.cancel()
        cancelConnectionJobs()
        pendingModeCommandId = null
        timeSyncPending = false
        timeSyncAttempted = false
        logTimeoutJob?.cancel()
        settingsTimeoutJob?.cancel()
        clearPendingSettings()
        transport.disconnect(clearSelection)
        platform.keepConnectionAlive(false)
        session.resetAll()
        reachedReady = false
        reconnectAttempt = 0
        previousMode = null
        lastOperatorAlert = null
        logLoadPending = false
        logExporting = false
        drainLog = false
        pendingLogAckIndex = null
        logPrefetchUntil = 0
        logPrefetchFrom = 0
        writeOutstanding = false
        if (clearSelection) selectedAddress = null
        if (clearVerifier) replaceCachedVerifier(null)
    }

    private fun replaceCachedVerifier(verifier: ByteArray?) {
        val old = cachedVerifier
        if (old !== verifier) old?.fill(0)
        cachedVerifier = verifier
    }

    private fun credentialKeys(): List<String> {
        val keys = ArrayList<String>(2)
        selectedAddress?.takeIf { it.isNotBlank() }?.let { keys += "addr:$it" }
        state.selectedDevice?.deviceId?.let { keys += "id:$it" }
        state.deviceInfo?.deviceId?.let { keys += "id:$it" }
        return keys
    }

    private fun loadCachedVerifier() {
        val stored = credentialKeys().firstNotNullOfOrNull { key ->
            platform.readDeviceVerifier(key)?.takeIf { it.size == DplsAuth.VERIFIER_SIZE }
        }
        replaceCachedVerifier(stored)
        updateState { it.copy(savedCredentials = stored != null) }
    }

    private fun persistCachedVerifier() {
        val stored = cachedVerifier
        for (key in credentialKeys()) {
            platform.writeDeviceVerifier(key, stored)
        }
        updateState { it.copy(savedCredentials = stored != null) }
    }

    private fun forgetCachedVerifier() {
        replaceCachedVerifier(null)
        persistCachedVerifier()
    }

    private fun retainedUiState(
        phase: ConnectionPhase = ConnectionPhase.IDLE,
        statusText: String = "Готово к поиску",
        scanning: Boolean = false,
    ) = DplsUiState(
        phase = phase,
        statusText = statusText,
        scanning = scanning,
        uiTheme = state.uiTheme,
        keepScreenOn = state.keepScreenOn,
        hapticsEnabled = state.hapticsEnabled,
    )

    private fun failLog(message: String) {
        logLoadPending = false
        logExporting = false
        drainLog = false
        logIncremental = false
        pendingLogAckIndex = null
        logTimeoutJob?.cancel()
        updateState { it.copy(logProgress = null, logHasMore = false, error = message) }
        scheduleKeepAlive()
        scheduleStateRefresh()
    }

    private fun fail(message: String, staleBond: Boolean = false) {
        pendingModeCommandId = null
        commandTimeoutJob?.cancel()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        stopRssiPoll()
        updateState {
            it.copy(
                phase = ConnectionPhase.ERROR,
                statusText = message,
                error = message,
                commandInProgress = false,
                logProgress = null,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                linkRssi = null,
                staleBond = staleBond,
            )
        }
        if (message != lastOperatorAlert) {
            lastOperatorAlert = message
            platform.notifyOperator(DplsOperatorAlerts.ERROR_TITLE, message)
        }
    }

    private fun settingsFailure(message: String) =
        updateState { it.copy(settingsOp = SettingsOp.FAILED, settingsError = message, settingsNotice = null) }

    private inline fun updateState(block: (DplsUiState) -> DplsUiState) {
        mutableState.value = block(mutableState.value)
    }

    private val state: DplsUiState get() = mutableState.value

    private fun utf8Truncate(value: String, maxBytes: Int): ByteArray {
        var end = 0
        var size = 0
        while (end < value.length) {
            val high = value[end].code in 0xD800..0xDBFF
            val paired = high && end + 1 < value.length && value[end + 1].code in 0xDC00..0xDFFF
            val next = end + if (paired) 2 else 1
            val encoded = value.substring(end, next).encodeToByteArray()
            if (size + encoded.size > maxBytes) break
            size += encoded.size
            end = next
        }
        return value.substring(0, end).encodeToByteArray()
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (index in a.indices) diff = diff or (a[index].toInt() xor b[index].toInt())
        return diff == 0
    }

    companion object {
        private const val SCAN_DURATION_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val KEEP_ALIVE_MS = 3_000L
        private const val STATE_REFRESH_MS = 1_000L
        private const val TELEMETRY_STALE_MS = 3_000L
        private const val LOG_CHUNK_TIMEOUT_MS = 15_000L
        private const val SETTINGS_TIMEOUT_MS = 10_000L
        private const val MAX_LOG_EVENTS = 200
        private const val LOG_PAGE_SIZE = 15
        private const val RSSI_POLL_MS = 350L
    }
}
