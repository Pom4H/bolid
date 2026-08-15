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
import ru.bolid.testdpls.core.protocol.parseLogHistogramReport
import ru.bolid.testdpls.core.protocol.parseSettingsResult
import ru.bolid.testdpls.core.protocol.parseStateReport
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32
import ru.bolid.testdpls.core.runtime.AuthSession
import ru.bolid.testdpls.core.runtime.DeviceSession
import ru.bolid.testdpls.core.runtime.LinkEndpoint
import ru.bolid.testdpls.core.runtime.LinkFailure
import ru.bolid.testdpls.core.runtime.NodeId
import ru.bolid.testdpls.core.runtime.credentialKey
import ru.bolid.testdpls.core.session.DplsSessionRuntime

class DplsClient(
    private val transport: DplsTransport,
    private val platform: DplsPlatformServices,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : DplsController, DplsTransportListener {
    private data class Target(
        val address: String,
        val reachedReady: Boolean = false,
        val expectSetupReconnect: Boolean = false,
    )

    private sealed interface Operation {
        data class Mode(val id: Long) : Operation
        data object DeviceInfo : Operation
        data object TimeSync : Operation
        data object Histogram : Operation
        data class Name(val id: Long, val value: String) : Operation
        data class Password(val id: Long, val verifier: ByteArray) : Operation
    }

    private data class Identify(
        val afterConnect: Boolean = false,
        val awaitingWriteAck: Boolean = false,
        val sentAtMillis: Long = 0L,
    )

    private val mutableState = MutableStateFlow(
        DplsUiState(
            uiTheme = platform.readUiTheme(),
            keepScreenOn = platform.readKeepScreenOn(),
            hapticsEnabled = platform.readHapticsEnabled(),
        ),
    )
    override val uiState: StateFlow<DplsUiState> = mutableState.asStateFlow()

    private val wireSession = DplsSessionRuntime()
    private val journal = JournalMachine()
    private var runtimeSession: DeviceSession = DeviceSession.Offline
    private var target: Target? = null
    private var identify = Identify()
    private var operation: Operation? = null
    private var cachedVerifier: ByteArray? = null
    private var reconnectAttempt = 0
    private var legacyFirmware = false
    private var timeSyncAttempted = false
    private var previousMode: DplsMode? = null
    private var lastOperatorAlert: String? = null
    private var logLoadPending = false
    private var drainLog = false
    private var timeAnchors: List<JournalTimeAnchor> = emptyList()

    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var sessionLoopJob: Job? = null
    private var operationJob: Job? = null
    private var logTimeoutJob: Job? = null
    private var rssiJob: Job? = null
    private var connectTimeoutJob: Job? = null

    init { transport.setListener(this) }

    override fun startScan() {
        if (state.authenticated && transport.hasConnection()) return browseDevices()
        disconnectInternal(clearSelection = true, clearVerifier = true)
        mutableState.value = retainedUiState(ConnectionPhase.SCANNING, "Поиск Test-DPLС…", scanning = true)
        if (transport.startScan()) armScanDeadline(keepSession = false)
    }

    override fun stopScan() {
        transport.stopScan()
        scanJob?.cancel()
        scanJob = null
        updateState {
            when {
                it.authenticated -> it.copy(scanning = false, statusText = deviceListCaption(it.devices.size))
                it.phase == ConnectionPhase.SCANNING -> it.copy(
                    phase = ConnectionPhase.IDLE,
                    scanning = false,
                    statusText = deviceListCaption(it.devices.size),
                )
                else -> it
            }
        }
    }

    override fun resumeSession() {
        if (!state.authenticated || !transport.hasConnection()) return
        transport.stopScan()
        scanJob?.cancel()
        scanJob = null
        updateState { it.copy(browsingDevices = false, scanning = false, phase = ConnectionPhase.READY, statusText = "Готово", error = null) }
    }

    private fun browseDevices() {
        updateState {
            val selected = listOfNotNull(it.selectedDevice)
            it.copy(
                browsingDevices = true,
                scanning = true,
                devices = (selected + it.devices).distinctBy(DiscoveredDevice::address),
                statusText = "Поиск Test-DPLS…",
                error = null,
            )
        }
        if (transport.startScan()) armScanDeadline(keepSession = true)
    }

    override fun connect(address: String) {
        if (state.authenticated && target?.address == address && transport.hasConnection()) return resumeSession()
        stopScan()
        cancelLinkJobs()
        wireSession.resetAll()
        operation = null
        target = Target(address)
        runtimeSession = DeviceSession.Connecting(LinkEndpoint.Ble(address))
        legacyFirmware = false
        timeSyncAttempted = false
        reconnectAttempt = 0
        previousMode = null
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
        if (state.authenticated && target?.address == address && transport.hasConnection()) return resumeSession()
        identify = Identify(afterConnect = true)
        updateState {
            it.copy(
                identifyActive = true,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                selectedDevice = it.devices.firstOrNull { device -> device.address == address } ?: it.selectedDevice,
            )
        }
        connect(address)
        identify = Identify(afterConnect = true)
    }

    override fun stopIdentify() {
        identify = Identify()
        stopRssiPoll()
        updateState { it.copy(identifyActive = false, identifyLedLive = false, identifyLedPhaseOffsetMs = 0, linkRssi = null) }
        if (transport.hasConnection()) send(DplsProtocol.Type.IDENTIFY_STOP, priority = true)
    }

    override fun confirmIdentifiedDevice() {
        stopIdentify()
        if (state.credentialsReady) return
        if (!transport.hasConnection()) {
            target = null
            updateState { it.copy(selectedDevice = null) }
            return fail("Связь с платой оборвалась. Выберите устройство снова.")
        }
        updateState { it.copy(phase = ConnectionPhase.AUTHENTICATING, statusText = "Подключение…", error = null) }
        send(DplsProtocol.Type.HELLO, wireSession.clientNonce, priority = true)
    }

    override fun updateSetupName(name: String) = updateState { it.copy(setupName = name) }
    override fun updateSetupPassword(password: String) = updateState { it.copy(setupPassword = password) }
    override fun updateSetupRepeatPassword(password: String) = updateState { it.copy(setupRepeatPassword = password) }

    override fun authenticate(password: String) {
        if (password.length < 8) return fail("Пароль должен содержать не менее 8 символов")
        val verifier = DplsCrypto.deriveVerifier(password, wireSession.authSalt)
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
        putU32(payload, 0, wireSession.sessionId)
        payload[4] = nameBytes.size.toByte()
        nameBytes.copyInto(payload, 5)
        salt.copyInto(payload, 5 + nameBytes.size)
        verifier.copyInto(payload, 5 + nameBytes.size + salt.size)
        send(DplsProtocol.Type.SETUP, payload)
        salt.fill(0)
    }

    override fun requestMode(mode: DplsMode) {
        if (state.controlsEnabled && (operation == null || mode == DplsMode.NORMAL)) updateState { it.copy(pendingMode = mode) }
    }

    override fun cancelMode() = updateState { it.copy(pendingMode = null) }

    override fun confirmMode() {
        val mode = state.pendingMode ?: return
        if (operation != null && mode != DplsMode.NORMAL) return
        val id = wireSession.nextCommandId()
        val payload = ByteArray(17)
        putU32(payload, 0, wireSession.sessionId)
        wireSession.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, id)
        payload[16] = mode.wire.toByte()

        if (operation != null && mode == DplsMode.NORMAL) {
            updateState { it.copy(pendingMode = null, statusText = "Возврат в Норму…") }
            send(DplsProtocol.Type.MODE_SET, payload, priority = true)
            return
        }

        operation = Operation.Mode(id)
        updateState { it.copy(commandInProgress = true, pendingMode = null, statusText = "Команда отправлена…") }
        send(DplsProtocol.Type.MODE_SET, payload)
        armOperationTimeout(COMMAND_TIMEOUT_MS) {
            if (operation is Operation.Mode) {
                send(DplsProtocol.Type.STATE_GET, wireSession.authenticatedPayload())
                updateState { it.copy(statusText = "Запрос состояния устройства…") }
            }
        }
    }

    override fun returnToNormal() {
        updateState { it.copy(pendingMode = DplsMode.NORMAL) }
        confirmMode()
    }

    override fun loadEventLog() {
        if (!canStartLog() || !journal.isEmpty || state.logHasMore) return
        startLog(incremental = false)
    }

    override fun refreshEventLog() {
        if (!canStartLog()) return
        startLog(incremental = !journal.isEmpty)
    }

    private fun canStartLog(): Boolean = state.authenticated && operation == null && !journal.isActive && transport.hasConnection()

    private fun startLog(incremental: Boolean) {
        journal.begin(incremental)
        logLoadPending = true
        sessionLoopJob?.cancel()
        updateState { it.copy(logProgress = 0f, error = null) }
        send(DplsProtocol.Type.LOG_START, wireSession.authenticatedPayload() + ByteArray(2), flush = true)
        armLogTimeout()
    }

    override fun loadLogHistogram() {
        if (!state.authenticated || !transport.hasConnection() || operation != null || journal.isActive) return
        operation = Operation.Histogram
        send(DplsProtocol.Type.LOG_HIST_GET, wireSession.authenticatedPayload() + byteArrayOf(24))
        armOperationTimeout(ONE_SHOT_REQUEST_TIMEOUT_MS) {
            if (operation is Operation.Histogram) {
                clearOperation()
                if (state.eventLog.isEmpty()) loadEventLog()
            }
        }
    }

    override fun loadMoreEventLog() {
        if (!state.authenticated || journal.isActive || !state.logHasMore || operation != null) return
        sessionLoopJob?.cancel()
        applyJournalEffect(journal.more())
    }

    override fun loadRemainingEventLog() {
        if (!state.logHasMore) return
        drainLog = true
        loadMoreEventLog()
    }

    override fun refreshState() {
        if (state.authenticated && !journal.isActive && transport.hasConnection() && operation !is Operation.TimeSync) {
            send(DplsProtocol.Type.STATE_GET, wireSession.authenticatedPayload())
        }
    }

    override fun requestDeviceInfo() {
        if (state.authenticated && transport.hasConnection() && operation == null) requestDeviceInfoInternal()
    }

    override fun clearSettingsOp() {
        clearOperation()
        updateState { it.copy(settingsOp = SettingsOp.NONE, settingsError = null, settingsNotice = null) }
    }

    override fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return settingsFailure("Введите имя устройства")
        if (!state.authenticated || !transport.hasConnection()) return settingsFailure("Нет соединения с устройством")
        if (operation != null) return settingsFailure("Дождитесь завершения текущей операции")
        val bytes = utf8Truncate(trimmed, 31)
        val id = wireSession.nextCommandId()
        val payload = ByteArray(17 + bytes.size)
        putU32(payload, 0, wireSession.sessionId)
        wireSession.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, id)
        payload[16] = bytes.size.toByte()
        bytes.copyInto(payload, 17)
        operation = Operation.Name(id, trimmed)
        updateState { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null, settingsNotice = null) }
        send(DplsProtocol.Type.NAME_SET, payload)
        armSettingsTimeout()
    }

    override fun changePassword(current: String, newPassword: String) {
        if (newPassword.length < 8) return settingsFailure("Пароль должен содержать не менее 8 символов")
        if (!state.authenticated || !transport.hasConnection()) return settingsFailure("Нет соединения с устройством")
        if (operation != null) return settingsFailure("Дождитесь завершения текущей операции")
        val currentVerifier = DplsCrypto.deriveVerifier(current, wireSession.authSalt)
        val matches = cachedVerifier?.let { constantTimeEquals(it, currentVerifier) } == true
        currentVerifier.fill(0)
        if (!matches) return settingsFailure("Неверный текущий пароль")
        val salt = platform.secureRandomBytes(DplsAuth.SALT_SIZE)
        val verifier = DplsCrypto.deriveVerifier(newPassword, salt)
        val id = wireSession.nextCommandId()
        val payload = ByteArray(64)
        putU32(payload, 0, wireSession.sessionId)
        wireSession.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, id)
        salt.copyInto(payload, 16)
        verifier.copyInto(payload, 32)
        salt.fill(0)
        operation = Operation.Password(id, verifier)
        updateState { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null, settingsNotice = null) }
        send(DplsProtocol.Type.PASSWORD_SET, payload)
        armSettingsTimeout()
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
        updateState { it.copy(savedCredentials = false, settingsOp = SettingsOp.DONE, settingsError = null, settingsNotice = "Сохранённый пароль удалён") }
    }

    override fun disconnect() {
        stopIdentify()
        disconnectInternal(clearSelection = true, clearVerifier = true)
        mutableState.value = retainedUiState()
    }

    override fun openBluetoothSettings() { platform.openBluetoothSettings() }
    override fun canOpenBluetoothSettings(): Boolean = platform.canOpenSystemBluetoothSettings()

    override fun eventLogCsv(): String = buildString {
        appendLine("sequence;timestamp_seconds;time_basis;event_type;parameter")
        state.eventLog.forEach { appendLine("${it.sequence};${it.timestampSeconds};${eventTimestampBasis(it.timestampSeconds)};${it.type};${it.parameter}") }
    }

    override fun eventLogTxt(): String = buildString {
        appendLine("Журнал событий Тест-ДПЛС")
        appendLine("Устройство: ${state.deviceInfo?.userName ?: state.selectedDevice?.userName ?: "—"}")
        appendLine("Записей: ${state.eventLog.size}${if (state.logHasMore) " из ${state.logTotal} (неполный)" else ""}")
        appendLine("—".repeat(32))
        state.eventLog.forEach { appendLine("#${it.sequence}  ${eventTimestampText(it.timestampSeconds)}  событие ${it.type} · ${it.parameter}") }
    }

    override fun formatEventTime(record: EventRecord): String = journalEventTimeCaption(
        record = record,
        records = state.eventLog,
        currentBootFirst = currentBootFirstSequence(),
        currentBootEpoch = state.deviceBootEpochSeconds,
        anchors = timeAnchors,
        formatWall = platform::formatLocalDateTime,
    )

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
        if (target != null && !transport.hasConnection()) return scheduleReconnect()
        if ((state.phase == ConnectionPhase.SCANNING || state.browsingDevices) && transport.startScan()) armScanDeadline(state.authenticated)
    }

    override fun onBluetoothUnavailable() {
        cancelLinkJobs()
        clearOperation()
        wireSession.resetLink()
        runtimeSession = target?.let { DeviceSession.Recovering(nodeIdOrNull(), LinkEndpoint.Ble(it.address), reconnectAttempt) } ?: DeviceSession.Offline
        platform.keepConnectionAlive(false)
        updateState { it.copy(phase = ConnectionPhase.RECONNECTING, statusText = "Bluetooth выключен", authenticated = false, credentialsReady = cachedVerifier != null, savedCredentials = cachedVerifier != null, staleState = it.state != null, commandInProgress = false, logProgress = null, error = null) }
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
            val merged = it.devices.filterNot { old -> old.address == item.address } + item
            val selected = it.selectedDevice
            val all = if (it.authenticated && selected != null && merged.none { row -> row.address == selected.address }) merged + selected else merged
            it.copy(
                devices = all.sortedWith(
                    compareByDescending<DiscoveredDevice> { row -> row.realShort }
                        .thenByDescending { row -> row.reserveLow }
                        .thenByDescending { row -> row.fromReserve }
                        .thenByDescending(DiscoveredDevice::rssi),
                ),
                statusText = "Найдено: ${all.size}",
            )
        }
    }

    override fun onConnected() = updateState {
        it.copy(phase = ConnectionPhase.DISCOVERING, statusText = if (it.identifyActive) "Подключение…" else "Поиск службы…", error = null)
    }

    override fun onSubscribed(writeLimit: Int) {
        if (writeLimit < DplsProtocol.OVERHEAD) return fail("BLE write limit слишком мал: $writeLimit")
        wireSession.clientNonce = platform.secureRandomBytes(DplsAuth.NONCE_SIZE)
        if (identify.afterConnect) {
            identify = Identify(awaitingWriteAck = true, sentAtMillis = platform.nowMillis())
            updateState { it.copy(phase = ConnectionPhase.AUTHENTICATING, statusText = "Показать на объекте…") }
            send(DplsProtocol.Type.IDENTIFY_START)
        } else {
            updateState { it.copy(phase = ConnectionPhase.AUTHENTICATING, statusText = "Подключение…") }
            send(DplsProtocol.Type.HELLO, wireSession.clientNonce)
        }
    }

    override fun onBytes(bytes: ByteArray) {
        when (val decoded = decodeFrame(bytes)) {
            is DplsProtocol.DecodeResult.Failure -> fail(decoded.reason)
            is DplsProtocol.DecodeResult.Success -> handleMessage(decoded.frame)
        }
    }

    override fun onWriteComplete(errorCode: Long?) {
        if (errorCode == null && identify.awaitingWriteAck) {
            val phase = DplsIdentifyLed.phaseAtAckMs(identify.sentAtMillis, platform.nowMillis())
            identify = identify.copy(awaitingWriteAck = false)
            updateState { it.copy(identifyLedLive = true, identifyLedPhaseOffsetMs = phase, linkRssi = it.linkRssi ?: it.selectedDevice?.rssi) }
            scheduleRssiPoll()
        } else if (errorCode != null) {
            if (target?.reachedReady == true) scheduleReconnect() else fail("Ошибка передачи BLE: $errorCode")
        }
    }

    override fun onRssi(rssi: Int) {
        updateState { current ->
            val selected = current.selectedDevice
            current.copy(
                linkRssi = rssi,
                selectedDevice = selected?.copy(rssi = rssi),
                devices = current.devices.map { if (it.address == selected?.address) it.copy(rssi = rssi) else it },
            )
        }
    }

    override fun onDisconnected(error: String?) {
        connectTimeoutJob?.cancel()
        if (state.staleBond) return
        if (looksLikeStaleBondError(error)) return onStaleBond()
        val current = target
        if (current?.expectSetupReconnect == true) {
            target = current.copy(expectSetupReconnect = false)
            return scheduleReconnect(immediate = true)
        }
        if (current?.reachedReady == true && state.phase != ConnectionPhase.ERROR) return scheduleReconnect()
        identify = Identify()
        if (state.identifyActive) return fail(error ?: "Связь с платой оборвалась до идентификации")
        target = null
        runtimeSession = DeviceSession.Offline
        platform.keepConnectionAlive(false)
        updateState { it.copy(phase = ConnectionPhase.IDLE, selectedDevice = null, statusText = error ?: "Отключено", identifyActive = false, identifyLedLive = false, identifyLedPhaseOffsetMs = 0, browsingDevices = false) }
    }

    override fun onTransportError(message: String) {
        if (looksLikeStaleBondError(message)) onStaleBond() else fail(message)
    }

    override fun onStaleBond() {
        target = target?.copy(reachedReady = false, expectSetupReconnect = false)
        identify = Identify()
        val name = state.selectedDevice?.userName ?: state.selectedDevice?.advertisedName ?: "Test-DPLS"
        fail("Старое сопряжение с «$name». Настройки → Bluetooth → ⓘ у этого имени → Забыть. Затем «Повторить».", staleBond = true)
    }

    private fun handleMessage(frame: DplsProtocol.Frame) {
        when (frame.type) {
            DplsProtocol.Type.AUTH_CHALLENGE -> handleAuthChallenge(frame.payload)
            DplsProtocol.Type.AUTH_RESULT -> handleAuthResult(frame.payload)
            DplsProtocol.Type.COMMAND_RESULT -> handleCommandResult(frame.payload)
            DplsProtocol.Type.DEVICE_INFO_REPORT -> handleDeviceInfo(frame.payload)
            DplsProtocol.Type.SETTINGS_RESULT -> handleSettingsResult(frame.payload)
            DplsProtocol.Type.STATE_REPORT -> handleState(frame.payload)
            DplsProtocol.Type.LOG_INFO -> applyJournalEffect(journal.info(frame.payload))
            DplsProtocol.Type.LOG_CHUNK -> applyJournalEffect(journal.chunk(frame.payload))
            DplsProtocol.Type.LOG_RESULT -> applyJournalEffect(journal.finish())
            DplsProtocol.Type.LOG_HIST_REPORT -> handleHistogram(frame.payload)
            DplsProtocol.Type.ERROR -> handleDeviceError(frame.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0)
            else -> Unit
        }
    }

    private fun handleAuthChallenge(payload: ByteArray) {
        val challenge = parseAuthChallenge(payload) ?: return fail("Повреждённый AUTH_CHALLENGE")
        wireSession.setChallenge(challenge.sessionId, challenge.deviceNonce, challenge.salt, challenge.initialized)
        val endpoint = LinkEndpoint.Ble(target?.address ?: return fail("Нет выбранного устройства"))
        runtimeSession = if (challenge.initialized) DeviceSession.Authenticating(endpoint, challenge.sessionId)
        else DeviceSession.Commissioning(endpoint, challenge.sessionId)
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
        if (autoAuth) cachedVerifier?.let(::sendAuthProof)
    }

    private fun handleAuthResult(payload: ByteArray) {
        if (state.authenticated) return
        val result = parseAuthResult(payload) ?: return fail("Повреждённый AUTH_RESULT")
        if (result.status == 3) {
            persistCachedVerifier()
            target = target?.copy(expectSetupReconnect = true)
            updateState { it.copy(phase = ConnectionPhase.RECONNECTING, statusText = "Настройка сохранена. Повторное подключение…", credentialsReady = true, initialized = true, awaitingUserPassword = false, setupPassword = "", setupRepeatPassword = "", error = null) }
            return
        }
        if (result.status != 0) {
            forgetCachedVerifier()
            updateState { it.copy(awaitingUserPassword = true) }
            return fail(if (result.retryAfterSeconds > 0) "Аутентификация заблокирована на ${result.retryAfterSeconds} с" else "Неверный пароль")
        }
        persistCachedVerifier()
        val token = result.sessionToken ?: return fail("AUTH_RESULT без session token")
        wireSession.authenticate(token)
        updateState { it.copy(authenticated = true, awaitingUserPassword = false, identifyActive = false, identifyLedLive = false, identifyLedPhaseOffsetMs = 0, phase = ConnectionPhase.SYNCHRONIZING, statusText = "Чтение состояния…", error = null) }
        send(DplsProtocol.Type.STATE_GET, wireSession.authenticatedPayload())
        startSessionLoop()
    }

    private fun handleCommandResult(payload: ByteArray) {
        val result = parseCommandResult(payload) ?: return fail("Повреждённый COMMAND_RESULT")
        val pending = operation as? Operation.Mode ?: return
        if (pending.id != result.commandId) return
        clearOperation()
        if (result.status != 0) return fail(commandRejectReason(result.status))
        updateState { it.copy(commandInProgress = false, statusText = "Команда применена, чтение состояния…", lastAckMillis = platform.nowMillis()) }
        send(DplsProtocol.Type.STATE_GET, wireSession.authenticatedPayload())
    }

    private fun handleDeviceInfo(payload: ByteArray) {
        if (operation is Operation.DeviceInfo) clearOperation()
        val info = parseDeviceInfoReport(payload) ?: return fail("Повреждённый DEVICE_INFO_REPORT")
        updateState { it.copy(deviceInfo = info, selectedDevice = it.selectedDevice?.copy(userName = info.userName.ifBlank { it.selectedDevice.userName })) }
        val id = nodeIdOrNull()
        val endpoint = target?.address?.let { LinkEndpoint.Ble(it) }
        if (id != null && endpoint != null) runtimeSession = DeviceSession.Online(id, endpoint, AuthSession(wireSession.sessionId, wireSession.sessionToken))
        loadTimeAnchors()
        persistTimeAnchors()
        attemptTimeSync()
    }

    private fun handleSettingsResult(payload: ByteArray) {
        val result = parseSettingsResult(payload) ?: return
        when (val pending = operation) {
            is Operation.Name -> if (pending.id == result.commandId) {
                clearOperation()
                if (result.status == 0) {
                    updateState { it.copy(settingsOp = SettingsOp.DONE, settingsError = null, settingsNotice = "Имя «${pending.value}» применено", selectedDevice = it.selectedDevice?.copy(userName = pending.value), deviceInfo = it.deviceInfo?.copy(userName = pending.value)) }
                    requestDeviceInfoInternal()
                } else settingsFailure("Устройство отклонило изменение (код ${result.status})")
            }
            is Operation.Password -> if (pending.id == result.commandId) {
                operationJob?.cancel()
                operation = null
                if (result.status == 0) {
                    replaceCachedVerifier(pending.verifier)
                    persistCachedVerifier()
                    updateState { it.copy(settingsOp = SettingsOp.DONE, settingsError = null, settingsNotice = "Пароль изменён") }
                } else {
                    pending.verifier.fill(0)
                    settingsFailure("Устройство отклонило изменение (код ${result.status})")
                }
            }
            else -> Unit
        }
    }

    private fun handleState(payload: ByteArray) {
        val now = platform.nowMillis()
        val device = parseStateReport(payload, now) ?: return fail("Повреждённый STATE_REPORT")
        target = target?.copy(reachedReady = true)
        reconnectAttempt = 0
        updateState { it.copy(phase = ConnectionPhase.READY, statusText = "Состояние получено", state = device, deviceBootEpochSeconds = now / 1000 - device.uptimeSeconds, authenticated = true, identifyActive = false, identifyLedLive = false, identifyLedPhaseOffsetMs = 0, staleState = false, lastAckMillis = now, error = null) }
        val old = previousMode
        previousMode = device.mode
        if (old?.dangerous == true && device.mode == DplsMode.NORMAL) platform.notifyOperator(DplsOperatorAlerts.NORMAL_TITLE, DplsOperatorAlerts.NORMAL_BODY)
        if (state.deviceInfo == null && !legacyFirmware && operation == null) requestDeviceInfoInternal()
        else if (operation == null) attemptTimeSync()
        if (logLoadPending && !journal.isActive && operation == null) loadEventLog()
    }

    private fun handleHistogram(payload: ByteArray) {
        if (operation is Operation.Histogram) clearOperation()
        val report = parseLogHistogramReport(payload) ?: return
        updateState { it.copy(logHistogram = report.takeIf { item -> item.eventCount > 0 && item.counts.isNotEmpty() }, logFirstTimestampSeconds = report.firstTimestampSeconds, logLastTimestampSeconds = report.lastTimestampSeconds, logTotal = if (it.logTotal == 0) report.eventCount else it.logTotal) }
        if (state.eventLog.isEmpty() && !state.logHasMore) loadEventLog()
    }

    private fun handleDeviceError(code: Int) {
        when (val pending = operation) {
            Operation.Histogram -> {
                clearOperation()
                if (code == 5) legacyFirmware = true else return fail("Ошибка устройства: $code")
                if (state.eventLog.isEmpty()) loadEventLog()
            }
            Operation.DeviceInfo -> {
                if (code != 5) return fail("Ошибка устройства: $code")
                clearOperation()
                legacyFirmware = true
                attemptTimeSync()
            }
            Operation.TimeSync -> {
                if (code != 5) return fail("Ошибка устройства: $code")
                clearOperation()
                legacyFirmware = true
            }
            is Operation.Name, is Operation.Password -> {
                if (code != 5) return fail("Ошибка устройства: $code")
                clearOperation()
                legacyFirmware = true
                settingsFailure("Прошивка устройства не поддерживает изменение настроек")
            }
            is Operation.Mode -> fail("Ошибка устройства: $code")
            null -> if (journal.isActive) failLog("Ошибка загрузки журнала: $code")
            else -> fail(if (code == 7) "Окно первичной настройки закрыто. Выключите и включите устройство, затем повторите настройку." else "Ошибка устройства: $code")
        }
    }

    private fun requestDeviceInfoInternal() {
        if (operation != null || !state.authenticated || !transport.hasConnection()) return
        operation = Operation.DeviceInfo
        send(DplsProtocol.Type.DEVICE_INFO_GET, wireSession.authenticatedPayload())
        armOperationTimeout(ONE_SHOT_REQUEST_TIMEOUT_MS) {
            if (operation is Operation.DeviceInfo) {
                clearOperation()
                if (state.deviceInfo == null && !legacyFirmware) requestDeviceInfoInternal()
            }
        }
    }

    private fun attemptTimeSync() {
        if (timeSyncAttempted || operation != null || legacyFirmware || !state.authenticated || !transport.hasConnection()) return
        val payload = buildTimeSyncPayload(wireSession.sessionId, wireSession.sessionToken, platform.nowMillis() / 1000L) ?: return
        timeSyncAttempted = true
        operation = Operation.TimeSync
        send(DplsProtocol.Type.TIME_SYNC, payload)
        armOperationTimeout(TIME_SYNC_ERROR_WINDOW_MS) { if (operation is Operation.TimeSync) clearOperation() }
    }

    private fun applyJournalEffect(effect: JournalMachine.Effect) {
        when (effect) {
            is JournalMachine.Effect.Ack -> {
                val suffix = ByteArray(2)
                putU16(suffix, 0, effect.index)
                send(DplsProtocol.Type.LOG_ACK, wireSession.authenticatedPayload() + suffix)
                armLogTimeout()
            }
            JournalMachine.Effect.Pause, JournalMachine.Effect.Complete -> finishJournalPage()
            is JournalMachine.Effect.Error -> failLog(effect.message)
            JournalMachine.Effect.None -> Unit
        }
        publishJournal()
    }

    private fun finishJournalPage() {
        logTimeoutJob?.cancel()
        logLoadPending = false
        if (drainLog && journal.snapshot(false).hasMore) applyJournalEffect(journal.more())
        else {
            drainLog = false
            startSessionLoop()
        }
    }

    private fun publishJournal() {
        val snap = journal.snapshot()
        if (snap.records.isNotEmpty()) rememberCurrentBootAnchor(snap.records)
        updateState {
            it.copy(
                eventLog = snap.records,
                journalTimeAnchors = timeAnchors,
                logTotal = snap.total,
                logHasMore = snap.hasMore,
                logFirstTimestampSeconds = journal.firstTimestamp() ?: it.logFirstTimestampSeconds,
                logLastTimestampSeconds = journal.lastTimestamp() ?: it.logLastTimestampSeconds,
                logProgress = snap.progress,
                statusText = if (snap.hasMore) "Журнал: ${snap.records.size} из ${snap.total}" else "Журнал загружен: ${snap.records.size} записей",
                error = null,
            )
        }
    }

    private fun failLog(message: String) {
        journal.fail()
        logLoadPending = false
        drainLog = false
        logTimeoutJob?.cancel()
        updateState { it.copy(logProgress = null, logHasMore = false, error = message) }
        startSessionLoop()
    }

    private fun sendAuthProof(verifier: ByteArray) {
        val signed = DplsAuth.proofMessage(wireSession.deviceNonce, wireSession.clientNonce, wireSession.sessionId)
        val mac = DplsCrypto.hmacSha256(verifier, signed)
        signed.fill(0)
        send(DplsProtocol.Type.AUTH_PROOF, wireSession.clientNonce + mac)
        mac.fill(0)
    }

    private fun send(type: DplsProtocol.Type, payload: ByteArray = byteArrayOf(), priority: Boolean = false, flush: Boolean = false) {
        val bytes = encodeFrame(DplsProtocol.Frame(type, wireSession.nextSequence(), payload = payload))
        if (!transport.send(bytes, priority, flush)) fail("Кадр ${bytes.size} байт не помещается в BLE write limit")
    }

    private fun startSessionLoop() {
        sessionLoopJob?.cancel()
        if (!state.authenticated || !transport.hasConnection() || journal.isActive) return
        sessionLoopJob = scope.launch {
            var ticks = 0
            while (isActive && state.authenticated && transport.hasConnection()) {
                delay(STATE_REFRESH_MS)
                ++ticks
                if (journal.isActive || operation is Operation.TimeSync) continue
                if (state.needsPeriodicStateRefresh) {
                    val receivedAt = state.state?.receivedAtMillis
                    if (receivedAt != null && platform.nowMillis() - receivedAt >= TELEMETRY_STALE_MS) updateState { it.copy(staleState = true) }
                    send(DplsProtocol.Type.STATE_GET, wireSession.authenticatedPayload())
                } else if (ticks % KEEP_ALIVE_TICKS == 0) {
                    send(DplsProtocol.Type.KEEP_ALIVE, wireSession.authenticatedPayload())
                }
            }
        }
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        val current = target ?: return
        if (reconnectJob?.isActive == true) return
        cancelLinkJobs()
        clearOperation()
        wireSession.resetLink()
        runtimeSession = DeviceSession.Recovering(nodeIdOrNull(), LinkEndpoint.Ble(current.address), reconnectAttempt)
        platform.keepConnectionAlive(true)
        updateState { it.copy(phase = ConnectionPhase.RECONNECTING, statusText = if (current.reachedReady || logLoadPending) "Восстановление связи…" else "Подключение…", authenticated = false, staleState = it.state != null, credentialsReady = cachedVerifier != null, savedCredentials = cachedVerifier != null, commandInProgress = false) }
        val delays = longArrayOf(500, 1_000, 2_000, 4_000, 5_000)
        val wait = if (immediate) 0L else delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt++
        reconnectJob = scope.launch {
            if (wait > 0) delay(wait)
            if (target != null && !transport.reconnect()) fail("Устройство недоступно. Запустите поиск снова.")
        }
    }

    private fun armOperationTimeout(delayMs: Long, action: () -> Unit) {
        operationJob?.cancel()
        operationJob = scope.launch { delay(delayMs); action() }
    }

    private fun armSettingsTimeout() = armOperationTimeout(SETTINGS_TIMEOUT_MS) {
        if (operation is Operation.Name || operation is Operation.Password) {
            clearOperation()
            settingsFailure("Устройство не ответило на изменение настроек")
        }
    }

    private fun clearOperation() {
        operationJob?.cancel()
        val old = operation
        operation = null
        if (old is Operation.Password && cachedVerifier !== old.verifier) old.verifier.fill(0)
    }

    private fun armLogTimeout() {
        logTimeoutJob?.cancel()
        logTimeoutJob = scope.launch { delay(LOG_CHUNK_TIMEOUT_MS); if (journal.isActive) failLog("Не удалось загрузить журнал") }
    }

    private fun armScanDeadline(keepSession: Boolean) {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_DURATION_MS)
            transport.stopScan()
            updateState {
                if (keepSession || it.phase == ConnectionPhase.SCANNING) {
                    it.copy(
                        scanning = false,
                        phase = if (keepSession) it.phase else ConnectionPhase.IDLE,
                        statusText = deviceListCaption(it.devices.size),
                    )
                } else it
            }
        }
    }

    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val now = state
            if (!now.identifyLedLive && !now.identifyActive && !now.authenticated && !now.awaitingUserPassword && !now.credentialsReady) {
                transport.disconnect(clearSelection = false)
                fail("Плата не отвечает по Bluetooth. Выберите устройство снова.")
            }
        }
    }

    private fun cancelLinkJobs() {
        reconnectJob?.cancel()
        sessionLoopJob?.cancel()
        operationJob?.cancel()
        connectTimeoutJob?.cancel()
        rssiJob?.cancel()
        reconnectJob = null
        sessionLoopJob = null
        operationJob = null
        connectTimeoutJob = null
        rssiJob = null
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
        cancelLinkJobs()
        logTimeoutJob?.cancel()
        clearOperation()
        journal.fail()
        transport.disconnect(clearSelection)
        platform.keepConnectionAlive(false)
        wireSession.resetAll()
        runtimeSession = DeviceSession.Offline
        identify = Identify()
        reconnectAttempt = 0
        previousMode = null
        lastOperatorAlert = null
        logLoadPending = false
        drainLog = false
        timeSyncAttempted = false
        if (clearSelection) target = null
        if (clearVerifier) replaceCachedVerifier(null)
    }

    private fun nodeIdOrNull(): NodeId? {
        val raw = state.deviceInfo?.deviceId ?: state.selectedDevice?.deviceId ?: return null
        return raw.takeIf { it > 0 }?.let(::NodeId)
    }

    private fun credentialKeys(): List<String> = buildList {
        val id = nodeIdOrNull()
        if (id != null) {
            add(credentialKey(id))
            add("id:${id.value}")
        }
        target?.address?.takeIf { it.isNotBlank() }?.let {
            add("legacy-addr:$it")
            add("addr:$it")
        }
    }

    private fun loadCachedVerifier() {
        val stored = credentialKeys().firstNotNullOfOrNull { key ->
            platform.readDeviceVerifier(key)?.takeIf { bytes -> bytes.size == DplsAuth.VERIFIER_SIZE }
        }
        replaceCachedVerifier(stored)
        updateState { it.copy(savedCredentials = stored != null) }
    }

    private fun persistCachedVerifier() {
        val stored = cachedVerifier
        credentialKeys().forEach { platform.writeDeviceVerifier(it, stored) }
        updateState { it.copy(savedCredentials = stored != null) }
    }

    private fun forgetCachedVerifier() {
        replaceCachedVerifier(null)
        persistCachedVerifier()
    }

    private fun replaceCachedVerifier(value: ByteArray?) {
        if (cachedVerifier !== value) cachedVerifier?.fill(0)
        cachedVerifier = value
    }

    private fun currentBootFirstSequence(): Long? = journalBootFirstSequences(state.eventLog).lastOrNull()

    private fun rememberCurrentBootAnchor(records: List<EventRecord>) {
        val epoch = state.deviceBootEpochSeconds ?: return
        val first = journalBootFirstSequences(records).lastOrNull() ?: return
        val last = records.maxOfOrNull(EventRecord::sequence) ?: return
        timeAnchors = mergeJournalTimeAnchor(timeAnchors, JournalTimeAnchor(first, epoch, last))
        persistTimeAnchors()
    }

    private fun loadTimeAnchors() {
        var merged = timeAnchors
        credentialKeys().forEach { key ->
            decodeJournalTimeAnchors(platform.readDeviceString("time.$key")).forEach { anchor ->
                merged = mergeJournalTimeAnchor(merged, anchor)
            }
        }
        timeAnchors = merged
        updateState { it.copy(journalTimeAnchors = timeAnchors) }
    }

    private fun persistTimeAnchors() {
        val encoded = encodeJournalTimeAnchors(timeAnchors)
        credentialKeys().forEach { platform.writeDeviceString("time.$it", encoded) }
    }

    private fun retainedUiState(
        phase: ConnectionPhase = ConnectionPhase.IDLE,
        status: String = "Готово к поиску",
        scanning: Boolean = false,
    ) = DplsUiState(
        phase = phase,
        statusText = status,
        scanning = scanning,
        uiTheme = state.uiTheme,
        keepScreenOn = state.keepScreenOn,
        hapticsEnabled = state.hapticsEnabled,
    )

    private fun fail(message: String, staleBond: Boolean = false) {
        cancelLinkJobs()
        clearOperation()
        journal.fail()
        platform.keepConnectionAlive(false)
        runtimeSession = DeviceSession.Failed(target?.address?.let { LinkEndpoint.Ble(it) }, LinkFailure.Platform(message))
        updateState { it.copy(phase = ConnectionPhase.ERROR, statusText = message, error = message, commandInProgress = false, logProgress = null, identifyLedLive = false, identifyLedPhaseOffsetMs = 0, linkRssi = null, staleBond = staleBond) }
        if (message != lastOperatorAlert) {
            lastOperatorAlert = message
            platform.notifyOperator(DplsOperatorAlerts.ERROR_TITLE, message)
        }
    }

    private fun settingsFailure(message: String) = updateState { it.copy(settingsOp = SettingsOp.FAILED, settingsError = message, settingsNotice = null) }
    private inline fun updateState(block: (DplsUiState) -> DplsUiState) { mutableState.value = block(mutableState.value) }
    private val state: DplsUiState get() = mutableState.value
    private fun deviceListCaption(count: Int): String = if (count == 0) "Устройства не найдены" else "Выберите устройство"

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
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        private const val SCAN_DURATION_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val STATE_REFRESH_MS = 1_000L
        private const val TELEMETRY_STALE_MS = 3_000L
        private const val KEEP_ALIVE_TICKS = 3
        private const val LOG_CHUNK_TIMEOUT_MS = 15_000L
        private const val SETTINGS_TIMEOUT_MS = 10_000L
        private const val ONE_SHOT_REQUEST_TIMEOUT_MS = 2_000L
        private const val TIME_SYNC_ERROR_WINDOW_MS = 1_500L
        private const val RSSI_POLL_MS = 350L
    }
}
