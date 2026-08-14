package ru.bolid.testdpls.core.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSDate
import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DeviceInfo
import ru.bolid.testdpls.core.domain.DiscoveredDevice
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.DplsUiState
import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.domain.SettingsOp
import ru.bolid.testdpls.core.protocol.DplsAuth
import ru.bolid.testdpls.core.protocol.DplsCrypto
import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.commandRejectReason
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.protocol.parseDeviceInfoReport
import ru.bolid.testdpls.core.protocol.parseLogChunk
import ru.bolid.testdpls.core.protocol.parseStateReport
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32
import ru.bolid.testdpls.core.protocol.readU16
import ru.bolid.testdpls.core.protocol.readU32
import ru.bolid.testdpls.core.session.DplsSessionRuntime

internal class IosDplsController : DplsController, IosBleTransportListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _uiState = MutableStateFlow(DplsUiState())
    override val uiState: StateFlow<DplsUiState> = _uiState.asStateFlow()
    private val session = DplsSessionRuntime()
    private val transport = IosBleTransport(this)

    private var selectedAddress: String? = null
    private var cachedVerifier: ByteArray? = null
    private var identifyAfterConnect = false
    private var pendingIdentifyAck = false
    private var reconnectAttempt = 0
    private var legacyFirmware = false
    private var awaitingDeviceInfo = false
    private var reachedReady = false

    private sealed interface PendingSettings {
        val id: Long
        data class Name(override val id: Long) : PendingSettings
        data class Password(override val id: Long, val verifier: ByteArray) : PendingSettings
    }
    private var pendingSettings: PendingSettings? = null

    private var expectedLogEvents = 0
    private val logRecords = mutableMapOf<Int, EventRecord>()
    private var logLoadPending = false

    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var preAuthKeepAliveJob: Job? = null
    private var keepAliveJob: Job? = null
    private var stateRefreshJob: Job? = null
    private var logTimeoutJob: Job? = null
    private var settingsTimeoutJob: Job? = null
    private var commandTimeoutJob: Job? = null

    override fun startScan() {
        disconnectInternal(clearSelection = true, clearVerifier = true)
        _uiState.value = DplsUiState(phase = ConnectionPhase.SCANNING, statusText = "Поиск Test-DPLS…")
        if (!transport.startScan()) return fail("Включите Bluetooth")
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_DURATION_MS)
            if (_uiState.value.phase == ConnectionPhase.SCANNING) stopScan()
        }
    }

    override fun stopScan() {
        transport.stopScan()
        scanJob?.cancel()
        scanJob = null
        updateState { state ->
            if (state.phase != ConnectionPhase.SCANNING) state else state.copy(
                phase = ConnectionPhase.IDLE,
                statusText = if (state.devices.isEmpty()) "Устройства не найдены" else "Выберите устройство",
            )
        }
    }

    override fun connect(address: String) {
        stopScan()
        reconnectJob?.cancel()
        selectedAddress = address
        legacyFirmware = false
        awaitingDeviceInfo = false
        updateState { state ->
            state.copy(
                phase = ConnectionPhase.CONNECTING,
                statusText = "Подключение…",
                selectedDevice = state.devices.firstOrNull { it.address == address },
                credentialsReady = false,
                authenticated = false,
                identifyLedLive = false,
                error = null,
            )
        }
        if (!transport.connect(address)) fail("Устройство недоступно. Запустите поиск снова.")
    }

    override fun identify(address: String) {
        identifyAfterConnect = true
        pendingIdentifyAck = false
        updateState { it.copy(identifyActive = true, identifyLedLive = false) }
        connect(address)
    }

    override fun stopIdentify() {
        identifyAfterConnect = false
        pendingIdentifyAck = false
        updateState { it.copy(identifyActive = false, identifyLedLive = false) }
        if (transport.hasConnection()) send(DplsProtocol.Type.IDENTIFY_STOP, priority = true)
    }

    override fun confirmIdentifiedDevice() {
        stopIdentify()
        preAuthKeepAliveJob?.cancel()
        if (_uiState.value.credentialsReady || !transport.hasConnection()) return
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
        cachedVerifier?.fill(0)
        cachedVerifier = verifier
        sendAuthProof(verifier)
    }

    override fun setup(name: String, password: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return fail("Введите имя устройства")
        if (password.length < 8) return fail("Пароль должен содержать не менее 8 символов")
        val salt = secureRandomBytes(DplsAuth.SALT_SIZE)
        val verifier = DplsCrypto.deriveVerifier(password, salt)
        cachedVerifier?.fill(0)
        cachedVerifier = verifier
        val nameBytes = utf8Truncate(trimmed, 31)
        val payload = ByteArray(4 + 1 + nameBytes.size + salt.size + verifier.size)
        putU32(payload, 0, session.sessionId)
        payload[4] = nameBytes.size.toByte()
        nameBytes.copyInto(payload, 5)
        salt.copyInto(payload, 5 + nameBytes.size)
        verifier.copyInto(payload, 5 + nameBytes.size + salt.size)
        send(DplsProtocol.Type.SETUP, payload)
    }

    override fun requestMode(mode: DplsMode) {
        if (_uiState.value.controlsEnabled) updateState { it.copy(pendingMode = mode) }
    }

    override fun cancelMode() = updateState { it.copy(pendingMode = null) }

    override fun confirmMode() {
        val mode = _uiState.value.pendingMode ?: return
        val id = session.nextCommandId()
        val payload = ByteArray(17)
        putU32(payload, 0, session.sessionId)
        session.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, id)
        payload[16] = mode.wire.toByte()
        updateState { it.copy(commandInProgress = true, pendingMode = null, statusText = "Команда отправлена…") }
        send(DplsProtocol.Type.MODE_SET, payload)
        scheduleStateRefresh()
        commandTimeoutJob?.cancel()
        commandTimeoutJob = scope.launch {
            delay(COMMAND_TIMEOUT_MS)
            if (_uiState.value.commandInProgress) {
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
        if (_uiState.value.logProgress != null || !_uiState.value.authenticated) return
        logLoadPending = true
        expectedLogEvents = 0
        logRecords.clear()
        keepAliveJob?.cancel()
        stateRefreshJob?.cancel()
        updateState { it.copy(logProgress = 0f, eventLog = emptyList(), error = null) }
        val window = ByteArray(2)
        send(DplsProtocol.Type.LOG_START, session.authenticatedPayload() + window, priority = true, flush = true)
        logTimeoutJob?.cancel()
        logTimeoutJob = scope.launch {
            delay(LOG_TIMEOUT_MS)
            if (_uiState.value.logProgress != null) failLog("Не удалось загрузить журнал")
        }
    }

    override fun refreshState() {
        if (_uiState.value.authenticated && _uiState.value.logProgress == null && transport.hasConnection()) {
            send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
            scheduleStateRefresh()
        }
    }

    override fun requestDeviceInfo() {
        if (_uiState.value.authenticated && transport.hasConnection()) requestDeviceInfoInternal()
    }

    override fun clearSettingsOp() {
        clearPendingSettings()
        updateState { it.copy(settingsOp = SettingsOp.NONE, settingsError = null) }
    }

    override fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return settingsFailure("Введите имя устройства")
        if (!_uiState.value.authenticated || !transport.hasConnection()) return settingsFailure("Нет соединения с устройством")
        val bytes = utf8Truncate(trimmed, 31)
        val id = session.nextCommandId()
        val payload = ByteArray(17 + bytes.size)
        putU32(payload, 0, session.sessionId)
        session.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, id)
        payload[16] = bytes.size.toByte()
        bytes.copyInto(payload, 17)
        armPendingSettings(PendingSettings.Name(id))
        updateState { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null) }
        send(DplsProtocol.Type.NAME_SET, payload)
    }

    override fun changePassword(current: String, newPassword: String) {
        if (newPassword.length < 8) return settingsFailure("Пароль должен содержать не менее 8 символов")
        if (!_uiState.value.authenticated || !transport.hasConnection()) return settingsFailure("Нет соединения с устройством")
        val currentVerifier = DplsCrypto.deriveVerifier(current, session.authSalt)
        val cached = cachedVerifier
        val matches = cached != null && constantTimeEquals(cached, currentVerifier)
        currentVerifier.fill(0)
        if (!matches) return settingsFailure("Неверный текущий пароль")
        val salt = secureRandomBytes(DplsAuth.SALT_SIZE)
        val verifier = DplsCrypto.deriveVerifier(newPassword, salt)
        val id = session.nextCommandId()
        val payload = ByteArray(64)
        putU32(payload, 0, session.sessionId)
        session.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, id)
        salt.copyInto(payload, 16)
        verifier.copyInto(payload, 32)
        armPendingSettings(PendingSettings.Password(id, verifier))
        updateState { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null) }
        send(DplsProtocol.Type.PASSWORD_SET, payload)
    }

    override fun disconnect() {
        disconnectInternal(clearSelection = true, clearVerifier = true)
        _uiState.value = DplsUiState()
    }

    override fun eventLogCsv(): String = buildString {
        appendLine("sequence;uptime_seconds;event_type;parameter")
        _uiState.value.eventLog.forEach { appendLine("${it.sequence};${it.timestampSeconds};${it.type};${it.parameter}") }
    }

    override fun eventLogTxt(): String = buildString {
        appendLine("Журнал событий Тест-ДПЛС")
        appendLine("Устройство: ${_uiState.value.deviceInfo?.userName ?: _uiState.value.selectedDevice?.userName ?: "—"}")
        appendLine("Записей: ${_uiState.value.eventLog.size}")
        appendLine("—".repeat(32))
        _uiState.value.eventLog.forEach { appendLine("#${it.sequence}  +${it.timestampSeconds} с  событие ${it.type} · ${it.parameter}") }
    }

    fun close() {
        disconnectInternal(clearSelection = true, clearVerifier = true)
        scope.cancel()
    }

    override fun onBluetoothUnavailable() {
        updateState {
            it.copy(
                phase = ConnectionPhase.RECONNECTING,
                statusText = "Bluetooth выключен",
                authenticated = false,
                credentialsReady = false,
                staleState = it.state != null,
                commandInProgress = false,
            )
        }
    }

    override fun onDiscovered(device: IosDiscoveredDevice) {
        val discovered = DiscoveredDevice(device.address, device.name, null, device.deviceId, device.rssi)
        updateState { state ->
            val devices = (state.devices.filterNot { it.address == device.address } + discovered).sortedByDescending { it.rssi }
            state.copy(devices = devices, statusText = "Найдено: ${devices.size}")
        }
    }

    override fun onConnected() {
        updateState { it.copy(phase = ConnectionPhase.DISCOVERING, statusText = "Подключение…") }
    }

    override fun onSubscribed(writeLimit: Int) {
        session.clientNonce = secureRandomBytes(DplsAuth.NONCE_SIZE)
        if (identifyAfterConnect) {
            identifyAfterConnect = false
            pendingIdentifyAck = true
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
        if (errorCode == null) {
            if (pendingIdentifyAck) {
                pendingIdentifyAck = false
                updateState { it.copy(identifyLedLive = true) }
            }
            return
        }
        if (reachedReady) scheduleReconnect() else fail("Ошибка передачи BLE: $errorCode")
    }

    override fun onDisconnected(error: String?) {
        if (selectedAddress != null) scheduleReconnect() else updateState { it.copy(phase = ConnectionPhase.IDLE) }
    }

    override fun onTransportError(message: String) = fail(message)

    private fun handleMessage(frame: DplsProtocol.Frame) {
        val payload = frame.payload
        when (frame.type) {
            DplsProtocol.Type.AUTH_CHALLENGE -> {
                if (payload.size < 37) return fail("Повреждённый AUTH_CHALLENGE")
                session.setChallenge(
                    sessionId = readU32(payload, 0),
                    deviceNonce = payload.copyOfRange(4, 20),
                    authSalt = payload.copyOfRange(20, 36),
                    initialized = payload[36].toInt() != 0,
                )
                val autoAuth = session.initialized && cachedVerifier != null
                updateState {
                    it.copy(
                        initialized = session.initialized,
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
            DplsProtocol.Type.AUTH_RESULT -> handleAuthResult(payload)
            DplsProtocol.Type.COMMAND_RESULT -> handleCommandResult(payload)
            DplsProtocol.Type.DEVICE_INFO_REPORT -> handleDeviceInfo(payload)
            DplsProtocol.Type.SETTINGS_RESULT -> handleSettingsResult(payload)
            DplsProtocol.Type.STATE_REPORT -> handleState(payload)
            DplsProtocol.Type.LOG_INFO -> handleLogInfo(payload)
            DplsProtocol.Type.LOG_CHUNK -> handleLogChunk(payload)
            DplsProtocol.Type.LOG_RESULT -> finishLog()
            DplsProtocol.Type.ERROR -> handleDeviceError(payload.firstOrNull()?.toInt()?.and(0xff) ?: 0)
            else -> Unit
        }
    }

    private fun handleAuthResult(payload: ByteArray) {
        if (payload.isEmpty() || _uiState.value.authenticated) return
        preAuthKeepAliveJob?.cancel()
        val status = payload[0].toInt() and 0xff
        val retry = if (payload.size >= 3) readU16(payload, 1) else 0
        if (status == 3) {
            updateState { it.copy(phase = ConnectionPhase.RECONNECTING, statusText = "Настройка сохранена. Повторное подключение…", credentialsReady = true, initialized = true, awaitingUserPassword = false, error = null) }
            return
        }
        if (status != 0) {
            updateState { it.copy(awaitingUserPassword = true) }
            return fail(if (retry > 0) "Аутентификация заблокирована на $retry с" else "Неверный пароль")
        }
        if (payload.size >= 9) session.authenticate(payload.copyOfRange(1, 9))
        updateState { it.copy(authenticated = true, awaitingUserPassword = false, identifyActive = false, identifyLedLive = false, phase = ConnectionPhase.SYNCHRONIZING, statusText = "Чтение состояния…", error = null) }
        send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
        scheduleKeepAlive()
    }

    private fun handleCommandResult(payload: ByteArray) {
        if (payload.size < 8) return fail("Повреждённый COMMAND_RESULT")
        val result = payload[4].toInt() and 0xff
        if (result != 0) return fail(commandRejectReason(result))
        commandTimeoutJob?.cancel()
        updateState { it.copy(commandInProgress = false, statusText = "Команда применена, чтение состояния…", lastAckMillis = nowMillis()) }
        if (_uiState.value.logProgress == null) send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
    }

    private fun handleDeviceInfo(payload: ByteArray) {
        awaitingDeviceInfo = false
        val info = parseDeviceInfoReport(payload) ?: return
        updateState { state -> state.copy(deviceInfo = info, selectedDevice = state.selectedDevice?.copy(userName = info.userName.ifBlank { state.selectedDevice.userName })) }
    }

    private fun handleSettingsResult(payload: ByteArray) {
        if (payload.size < 5) return
        val id = readU32(payload, 0)
        val status = payload[4].toInt() and 0xff
        val op = pendingSettings ?: return
        if (op.id != id) return
        settingsTimeoutJob?.cancel()
        pendingSettings = null
        if (status == 0) {
            if (op is PendingSettings.Password) {
                cachedVerifier?.fill(0)
                cachedVerifier = op.verifier
            } else if (op is PendingSettings.Name) {
                requestDeviceInfoInternal()
            }
            updateState { it.copy(settingsOp = SettingsOp.DONE, settingsError = null) }
        } else {
            if (op is PendingSettings.Password) op.verifier.fill(0)
            settingsFailure("Устройство отклонило изменение (код $status)")
        }
    }

    private fun handleState(payload: ByteArray) {
        val now = nowMillis()
        val state = parseStateReport(payload, now) ?: return fail("Повреждённый STATE_REPORT")
        updateState {
            it.copy(
                phase = ConnectionPhase.READY,
                statusText = "Состояние получено",
                state = state,
                deviceBootEpochSeconds = now / 1000 - state.uptimeSeconds,
                authenticated = true,
                identifyActive = false,
                identifyLedLive = false,
                commandInProgress = false,
                staleState = false,
                lastAckMillis = now,
                error = null,
            )
        }
        reachedReady = true
        reconnectAttempt = 0
        scheduleStateRefresh()
        if (_uiState.value.deviceInfo == null && !legacyFirmware && !awaitingDeviceInfo && _uiState.value.logProgress == null) requestDeviceInfoInternal()
        if (logLoadPending) loadEventLog()
    }

    private fun handleLogInfo(payload: ByteArray) {
        if (payload.size < 10) return failLog("Повреждённый LOG_INFO")
        val totalBytes = readU32(payload, 4).toInt().coerceAtLeast(0)
        val rawCount = readU16(payload, 8)
        expectedLogEvents = minOf(rawCount, totalBytes / 10, MAX_LOG_EVENTS).coerceAtLeast(0)
        logRecords.clear()
        if (expectedLogEvents == 0) finishLog() else updateLogProgress()
    }

    private fun handleLogChunk(payload: ByteArray) {
        val batch = parseLogChunk(payload) ?: return
        batch.records.forEachIndexed { offset, record ->
            val index = batch.firstIndex + offset
            if (index in 0 until expectedLogEvents) logRecords.putIfAbsent(index, record)
        }
        if (logRecords.size >= expectedLogEvents) finishLog() else {
            updateLogProgress()
            sendLogAck(nextMissingLogIndex())
        }
    }

    private fun finishLog() {
        logTimeoutJob?.cancel()
        logLoadPending = false
        val records = logRecords.toSortedMap().values.sortedByDescending { it.sequence }
        updateState { it.copy(eventLog = records, logProgress = null, statusText = "Журнал загружен: ${records.size} записей", error = null) }
        scheduleKeepAlive()
        scheduleStateRefresh()
    }

    private fun updateLogProgress() {
        if (expectedLogEvents <= 0) return
        val progress = (logRecords.size.toFloat() / expectedLogEvents.toFloat()).coerceIn(0.05f, 1f)
        updateState { it.copy(logProgress = progress) }
    }

    private fun nextMissingLogIndex(): Int = (0 until expectedLogEvents).firstOrNull { it !in logRecords } ?: expectedLogEvents

    private fun sendLogAck(index: Int) {
        val suffix = ByteArray(2)
        putU16(suffix, 0, index)
        send(DplsProtocol.Type.LOG_ACK, session.authenticatedPayload() + suffix, priority = true)
    }

    private fun handleDeviceError(code: Int) {
        if (_uiState.value.logProgress != null) return failLog("Ошибка загрузки журнала: $code")
        if (code == 5 && awaitingDeviceInfo) {
            awaitingDeviceInfo = false
            legacyFirmware = true
            return
        }
        if (code == 5 && pendingSettings != null) {
            clearPendingSettings()
            legacyFirmware = true
            return settingsFailure("Прошивка устройства не поддерживает изменение настроек")
        }
        fail(if (code == 7) "Окно первичной настройки закрыто. Выключите и включите устройство, затем повторите настройку." else "Ошибка устройства: $code")
    }

    private fun sendAuthProof(verifier: ByteArray) {
        val signed = DplsAuth.proofMessage(session.deviceNonce, session.clientNonce, session.sessionId)
        val mac = DplsCrypto.hmacSha256(verifier, signed)
        signed.fill(0)
        send(DplsProtocol.Type.AUTH_PROOF, session.clientNonce + mac)
        mac.fill(0)
    }

    private fun send(type: DplsProtocol.Type, payload: ByteArray = byteArrayOf(), priority: Boolean = false, flush: Boolean = false) {
        val frame = encodeFrame(DplsProtocol.Frame(type, session.nextSequence(), payload = payload))
        if (!transport.send(frame, priority, flush)) fail("Кадр ${frame.size} байт не помещается в BLE write limit")
    }

    private fun requestDeviceInfoInternal() {
        awaitingDeviceInfo = true
        send(DplsProtocol.Type.DEVICE_INFO_GET, session.authenticatedPayload())
    }

    private fun schedulePreAuthKeepAlive() {
        preAuthKeepAliveJob?.cancel()
        preAuthKeepAliveJob = scope.launch {
            while (!_uiState.value.authenticated && transport.hasConnection()) {
                delay(KEEP_ALIVE_MS)
                val state = _uiState.value
                if (state.credentialsReady && !state.identifyActive && !state.authenticated) send(DplsProtocol.Type.KEEP_ALIVE)
            }
        }
    }

    private fun scheduleKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (_uiState.value.authenticated && transport.hasConnection()) {
                delay(KEEP_ALIVE_MS)
                val state = _uiState.value
                if (state.authenticated && state.logProgress == null && !state.needsPeriodicStateRefresh) send(DplsProtocol.Type.KEEP_ALIVE, session.authenticatedPayload())
            }
        }
    }

    private fun scheduleStateRefresh() {
        stateRefreshJob?.cancel()
        if (!_uiState.value.needsPeriodicStateRefresh || !transport.hasConnection()) return
        stateRefreshJob = scope.launch {
            while (_uiState.value.needsPeriodicStateRefresh && transport.hasConnection()) {
                delay(STATE_REFRESH_MS)
                if (_uiState.value.needsPeriodicStateRefresh) send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || selectedAddress == null) return
        preAuthKeepAliveJob?.cancel()
        keepAliveJob?.cancel()
        stateRefreshJob?.cancel()
        session.resetLink()
        updateState { it.copy(phase = ConnectionPhase.RECONNECTING, statusText = if (reachedReady || logLoadPending) "Восстановление связи…" else "Подключение…", authenticated = false, staleState = it.state != null, credentialsReady = cachedVerifier != null) }
        val delays = longArrayOf(500, 1000, 2000, 4000, 5000)
        val delayMs = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            if (selectedAddress != null && !transport.reconnect()) fail("Устройство недоступно. Запустите поиск снова.")
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

    private fun disconnectInternal(clearSelection: Boolean, clearVerifier: Boolean) {
        scanJob?.cancel(); reconnectJob?.cancel(); preAuthKeepAliveJob?.cancel(); keepAliveJob?.cancel(); stateRefreshJob?.cancel()
        logTimeoutJob?.cancel(); settingsTimeoutJob?.cancel(); commandTimeoutJob?.cancel()
        clearPendingSettings()
        transport.disconnect(clearSelection)
        session.resetAll()
        reachedReady = false
        reconnectAttempt = 0
        if (clearSelection) selectedAddress = null
        if (clearVerifier) {
            cachedVerifier?.fill(0)
            cachedVerifier = null
        }
    }

    private fun failLog(message: String) {
        logLoadPending = false
        logTimeoutJob?.cancel()
        updateState { it.copy(logProgress = null, error = message) }
        scheduleKeepAlive()
    }

    private fun fail(message: String) {
        updateState { it.copy(phase = ConnectionPhase.ERROR, statusText = message, error = message, commandInProgress = false, logProgress = null) }
    }

    private fun settingsFailure(message: String) {
        updateState { it.copy(settingsOp = SettingsOp.FAILED, settingsError = message) }
    }

    private inline fun updateState(block: (DplsUiState) -> DplsUiState) {
        _uiState.value = block(_uiState.value)
    }

    private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    private fun utf8Truncate(value: String, maxBytes: Int): ByteArray {
        val out = ArrayList<Byte>(maxBytes)
        for (ch in value) {
            val encoded = ch.toString().encodeToByteArray()
            if (out.size + encoded.size > maxBytes) break
            encoded.forEach(out::add)
        }
        return out.toByteArray()
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        private const val SCAN_DURATION_MS = 20_000L
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val KEEP_ALIVE_MS = 3_000L
        private const val STATE_REFRESH_MS = 1_000L
        private const val LOG_TIMEOUT_MS = 240_000L
        private const val SETTINGS_TIMEOUT_MS = 10_000L
        private const val MAX_LOG_EVENTS = 200
    }
}
