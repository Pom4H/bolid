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
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import ru.bolid.testdpls.core.domain.ConnectionPhase
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
import ru.bolid.testdpls.core.protocol.parseAuthChallenge
import ru.bolid.testdpls.core.protocol.parseAuthResult
import ru.bolid.testdpls.core.protocol.parseCommandResult
import ru.bolid.testdpls.core.protocol.parseDeviceInfoReport
import ru.bolid.testdpls.core.protocol.parseLogChunk
import ru.bolid.testdpls.core.protocol.parseSettingsResult
import ru.bolid.testdpls.core.protocol.parseStateReport
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32
import ru.bolid.testdpls.core.protocol.readU16
import ru.bolid.testdpls.core.protocol.readU32
import ru.bolid.testdpls.core.session.DplsSessionRuntime

/** iOS application adapter: CoreBluetooth is platform-specific; all wire/domain semantics stay in commonMain. */
internal class IosDplsController : DplsController, IosBleTransportListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(DplsUiState())
    override val uiState: StateFlow<DplsUiState> = mutableState.asStateFlow()

    private val session = DplsSessionRuntime()
    private val transport = IosBleTransport(this)

    private var selectedAddress: String? = null
    private var cachedVerifier: ByteArray? = null
    private var identifyAfterConnect = false
    private var pendingIdentifyAck = false
    private var reconnectAttempt = 0
    private var reachedReady = false
    private var legacyFirmware = false
    private var awaitingDeviceInfo = false

    private sealed interface PendingSettings {
        val commandId: Long
        data class Name(override val commandId: Long) : PendingSettings
        data class Password(override val commandId: Long, val verifier: ByteArray) : PendingSettings
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
        mutableState.value = DplsUiState(phase = ConnectionPhase.SCANNING, statusText = "Поиск Test-DPLS…")
        if (!transport.startScan()) return fail("Включите Bluetooth")
        scanJob = scope.launch {
            delay(SCAN_DURATION_MS)
            if (state.phase == ConnectionPhase.SCANNING) stopScan()
        }
    }

    override fun stopScan() {
        transport.stopScan()
        scanJob?.cancel()
        scanJob = null
        updateState {
            if (it.phase != ConnectionPhase.SCANNING) it else it.copy(
                phase = ConnectionPhase.IDLE,
                statusText = if (it.devices.isEmpty()) "Устройства не найдены" else "Выберите устройство",
            )
        }
    }

    override fun connect(address: String) {
        stopScan()
        reconnectJob?.cancel()
        selectedAddress = address
        legacyFirmware = false
        awaitingDeviceInfo = false
        updateState {
            it.copy(
                phase = ConnectionPhase.CONNECTING,
                statusText = "Подключение…",
                selectedDevice = it.devices.firstOrNull { device -> device.address == address },
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
        if (state.credentialsReady || !transport.hasConnection()) return
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
        val payload = ByteArray(5 + nameBytes.size + salt.size + verifier.size)
        putU32(payload, 0, session.sessionId)
        payload[4] = nameBytes.size.toByte()
        nameBytes.copyInto(payload, 5)
        salt.copyInto(payload, 5 + nameBytes.size)
        verifier.copyInto(payload, 5 + nameBytes.size + salt.size)
        send(DplsProtocol.Type.SETUP, payload)
    }

    override fun requestMode(mode: DplsMode) {
        if (state.controlsEnabled) updateState { it.copy(pendingMode = mode) }
    }

    override fun cancelMode() = updateState { it.copy(pendingMode = null) }

    override fun confirmMode() {
        val mode = state.pendingMode ?: return
        val commandId = session.nextCommandId()
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
            if (state.commandInProgress) {
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
        if (state.logProgress != null || !state.authenticated) return
        logLoadPending = true
        expectedLogEvents = 0
        logRecords.clear()
        keepAliveJob?.cancel()
        stateRefreshJob?.cancel()
        updateState { it.copy(logProgress = 0f, eventLog = emptyList(), error = null) }
        send(
            DplsProtocol.Type.LOG_START,
            session.authenticatedPayload() + ByteArray(2),
            priority = true,
            flush = true,
        )
        logTimeoutJob?.cancel()
        logTimeoutJob = scope.launch {
            delay(LOG_TIMEOUT_MS)
            if (state.logProgress != null) failLog("Не удалось загрузить журнал")
        }
    }

    override fun refreshState() {
        if (state.authenticated && state.logProgress == null && transport.hasConnection()) {
            send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
            scheduleStateRefresh()
        }
    }

    override fun requestDeviceInfo() {
        if (state.authenticated && transport.hasConnection()) requestDeviceInfoInternal()
    }

    override fun clearSettingsOp() {
        clearPendingSettings()
        updateState { it.copy(settingsOp = SettingsOp.NONE, settingsError = null) }
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
        armPendingSettings(PendingSettings.Name(commandId))
        updateState { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null) }
        send(DplsProtocol.Type.NAME_SET, payload)
    }

    override fun changePassword(current: String, newPassword: String) {
        if (newPassword.length < 8) return settingsFailure("Пароль должен содержать не менее 8 символов")
        if (!state.authenticated || !transport.hasConnection()) return settingsFailure("Нет соединения с устройством")

        val currentVerifier = DplsCrypto.deriveVerifier(current, session.authSalt)
        val matches = cachedVerifier?.let { constantTimeEquals(it, currentVerifier) } == true
        currentVerifier.fill(0)
        if (!matches) return settingsFailure("Неверный текущий пароль")

        val salt = secureRandomBytes(DplsAuth.SALT_SIZE)
        val verifier = DplsCrypto.deriveVerifier(newPassword, salt)
        val commandId = session.nextCommandId()
        val payload = ByteArray(64)
        putU32(payload, 0, session.sessionId)
        session.sessionToken.copyInto(payload, 4)
        putU32(payload, 12, commandId)
        salt.copyInto(payload, 16)
        verifier.copyInto(payload, 32)
        armPendingSettings(PendingSettings.Password(commandId, verifier))
        updateState { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null) }
        send(DplsProtocol.Type.PASSWORD_SET, payload)
    }

    override fun disconnect() {
        disconnectInternal(clearSelection = true, clearVerifier = true)
        mutableState.value = DplsUiState()
    }

    override fun eventLogCsv(): String = buildString {
        appendLine("sequence;uptime_seconds;event_type;parameter")
        state.eventLog.forEach { appendLine("${it.sequence};${it.timestampSeconds};${it.type};${it.parameter}") }
    }

    override fun eventLogTxt(): String = buildString {
        appendLine("Журнал событий Тест-ДПЛС")
        appendLine("Устройство: ${state.deviceInfo?.userName ?: state.selectedDevice?.userName ?: "—"}")
        appendLine("Записей: ${state.eventLog.size}")
        appendLine("—".repeat(32))
        state.eventLog.forEach { appendLine("#${it.sequence}  +${it.timestampSeconds} с  событие ${it.type} · ${it.parameter}") }
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
        val item = DiscoveredDevice(device.address, device.name, null, device.deviceId, device.rssi)
        updateState {
            val devices = (it.devices.filterNot { old -> old.address == item.address } + item).sortedByDescending(DiscoveredDevice::rssi)
            it.copy(devices = devices, statusText = "Найдено: ${devices.size}")
        }
    }

    override fun onConnected() = updateState { it.copy(phase = ConnectionPhase.DISCOVERING, statusText = "Подключение…") }

    override fun onSubscribed(writeLimit: Int) {
        if (writeLimit < DplsProtocol.OVERHEAD) return fail("BLE write limit слишком мал: $writeLimit")
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
        } else if (reachedReady) {
            scheduleReconnect()
        } else {
            fail("Ошибка передачи BLE: $errorCode")
        }
    }

    override fun onDisconnected(error: String?) {
        if (selectedAddress != null) {
            scheduleReconnect()
        } else {
            updateState { it.copy(phase = ConnectionPhase.IDLE, statusText = error ?: "Отключено") }
        }
    }

    override fun onTransportError(message: String) = fail(message)

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
            updateState { it.copy(awaitingUserPassword = true) }
            return fail(
                if (result.retryAfterSeconds > 0) "Аутентификация заблокирована на ${result.retryAfterSeconds} с"
                else "Неверный пароль",
            )
        }
        val token = result.sessionToken ?: return fail("AUTH_RESULT без session token")
        session.authenticate(token)
        updateState {
            it.copy(
                authenticated = true,
                awaitingUserPassword = false,
                identifyActive = false,
                identifyLedLive = false,
                phase = ConnectionPhase.SYNCHRONIZING,
                statusText = "Чтение состояния…",
                error = null,
            )
        }
        send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
        scheduleKeepAlive()
    }

    private fun handleCommandResult(payload: ByteArray) {
        val result = parseCommandResult(payload) ?: return fail("Повреждённый COMMAND_RESULT")
        if (result.status != 0) return fail(commandRejectReason(result.status))
        commandTimeoutJob?.cancel()
        updateState { it.copy(commandInProgress = false, statusText = "Команда применена, чтение состояния…", lastAckMillis = nowMillis()) }
        if (state.logProgress == null) send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
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
                    cachedVerifier?.fill(0)
                    cachedVerifier = pending.verifier
                }
                is PendingSettings.Name -> requestDeviceInfoInternal()
            }
            updateState { it.copy(settingsOp = SettingsOp.DONE, settingsError = null) }
        } else {
            if (pending is PendingSettings.Password) pending.verifier.fill(0)
            settingsFailure("Устройство отклонило изменение (код ${result.status})")
        }
    }

    private fun handleState(payload: ByteArray) {
        val now = nowMillis()
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
                commandInProgress = false,
                staleState = false,
                lastAckMillis = now,
                error = null,
            )
        }
        reachedReady = true
        reconnectAttempt = 0
        scheduleStateRefresh()
        if (state.deviceInfo == null && !legacyFirmware && !awaitingDeviceInfo && state.logProgress == null) requestDeviceInfoInternal()
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
        if (logRecords.size >= expectedLogEvents) {
            finishLog()
        } else {
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
        if (state.logProgress != null) return failLog("Ошибка загрузки журнала: $code")
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
        if (!transport.send(bytes, priority, flush)) fail("Кадр ${bytes.size} байт не помещается в BLE write limit")
    }

    private fun requestDeviceInfoInternal() {
        awaitingDeviceInfo = true
        send(DplsProtocol.Type.DEVICE_INFO_GET, session.authenticatedPayload())
    }

    private fun schedulePreAuthKeepAlive() {
        preAuthKeepAliveJob?.cancel()
        preAuthKeepAliveJob = scope.launch {
            while (!state.authenticated && transport.hasConnection()) {
                delay(KEEP_ALIVE_MS)
                if (state.credentialsReady && !state.identifyActive && !state.authenticated) send(DplsProtocol.Type.KEEP_ALIVE)
            }
        }
    }

    private fun scheduleKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (state.authenticated && transport.hasConnection()) {
                delay(KEEP_ALIVE_MS)
                if (state.authenticated && state.logProgress == null && !state.needsPeriodicStateRefresh) {
                    send(DplsProtocol.Type.KEEP_ALIVE, session.authenticatedPayload())
                }
            }
        }
    }

    private fun scheduleStateRefresh() {
        stateRefreshJob?.cancel()
        if (!state.needsPeriodicStateRefresh || !transport.hasConnection()) return
        stateRefreshJob = scope.launch {
            while (state.needsPeriodicStateRefresh && transport.hasConnection()) {
                delay(STATE_REFRESH_MS)
                if (state.needsPeriodicStateRefresh) send(DplsProtocol.Type.STATE_GET, session.authenticatedPayload())
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || selectedAddress == null) return
        preAuthKeepAliveJob?.cancel()
        keepAliveJob?.cancel()
        stateRefreshJob?.cancel()
        session.resetLink()
        updateState {
            it.copy(
                phase = ConnectionPhase.RECONNECTING,
                statusText = if (reachedReady || logLoadPending) "Восстановление связи…" else "Подключение…",
                authenticated = false,
                staleState = it.state != null,
                credentialsReady = cachedVerifier != null,
            )
        }
        val delays = longArrayOf(500, 1_000, 2_000, 4_000, 5_000)
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
        scanJob?.cancel()
        reconnectJob?.cancel()
        preAuthKeepAliveJob?.cancel()
        keepAliveJob?.cancel()
        stateRefreshJob?.cancel()
        logTimeoutJob?.cancel()
        settingsTimeoutJob?.cancel()
        commandTimeoutJob?.cancel()
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

    private fun settingsFailure(message: String) = updateState { it.copy(settingsOp = SettingsOp.FAILED, settingsError = message) }

    private inline fun updateState(block: (DplsUiState) -> DplsUiState) {
        mutableState.value = block(mutableState.value)
    }

    private val state: DplsUiState get() = mutableState.value

    private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()

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
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val KEEP_ALIVE_MS = 3_000L
        private const val STATE_REFRESH_MS = 1_000L
        private const val LOG_TIMEOUT_MS = 240_000L
        private const val SETTINGS_TIMEOUT_MS = 10_000L
        private const val MAX_LOG_EVENTS = 200
    }
}
