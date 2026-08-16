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
import ru.bolid.testdpls.core.runtime.SessionChallenge
import ru.bolid.testdpls.core.runtime.authOrNull
import ru.bolid.testdpls.core.runtime.candidateNodeIdOrNull
import ru.bolid.testdpls.core.runtime.challengeOrNull
import ru.bolid.testdpls.core.runtime.credentialKey
import ru.bolid.testdpls.core.runtime.credentialsReady
import ru.bolid.testdpls.core.runtime.endpointOrNull
import ru.bolid.testdpls.core.runtime.isAuthenticated
import ru.bolid.testdpls.core.runtime.nodeIdOrNull
import ru.bolid.testdpls.core.session.FrameSequencer

/**
 * Product orchestration for one Test-DPLS device.
 *
 * Production transport callbacks, UI actions and delayed jobs are serialized on
 * Dispatchers.Main. Delayed work additionally carries a sequence/generation token,
 * so cancellation timing cannot let stale work mutate a newer operation/session.
 */
class DplsClient(
    private val transport: DplsTransport,
    private val platform: DplsPlatformServices,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : DplsController, DplsTransportListener {
    private sealed interface Operation {
        val sequence: Int

        data class Mode(override val sequence: Int) : Operation
        data class DeviceInfo(override val sequence: Int) : Operation
        data class TimeSync(override val sequence: Int) : Operation
        data class Histogram(override val sequence: Int) : Operation
        data class Name(override val sequence: Int, val value: String) : Operation
        data class Password(override val sequence: Int, val verifier: ByteArray) : Operation
    }

    private data class Identify(
        val afterConnect: Boolean = false,
        val responseSequence: Int? = null,
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

    private val frameSequencer = FrameSequencer()
    private val journal = JournalMachine()

    private var session: DeviceSession = DeviceSession.Offline
    private var identify = Identify()
    private var operation: Operation? = null
    private var cachedVerifier: ByteArray? = null
    private var timeSyncAttempted = false
    private var previousMode: DplsMode? = null
    private var lastOperatorAlert: String? = null
    private var logLoadPending = false
    private var drainLog = false
    private var timeAnchors: List<JournalTimeAnchor> = emptyList()

    /** Invalidates delayed work belonging to an older physical link attempt. */
    private var linkGeneration = 0L
    private var scanGeneration = 0L
    private var logTimeoutGeneration = 0L

    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var sessionLoopJob: Job? = null
    private var operationJob: Job? = null
    private var logTimeoutJob: Job? = null
    private var rssiJob: Job? = null
    private var connectTimeoutJob: Job? = null

    init {
        transport.setListener(this)
    }

    override fun startScan() {
        if (session.isAuthenticated && transport.hasConnection()) {
            browseDevices()
            return
        }
        disconnectInternal(clearSelection = true, clearVerifier = true)
        mutableState.value = projectSession(retainedUiState(scanning = true))
        if (transport.startScan()) armScanDeadline(keepSession = false)
    }

    override fun stopScan() {
        transport.stopScan()
        invalidateScanDeadline()
        updateState {
            when {
                session.isAuthenticated -> it.copy(
                    scanning = false,
                    statusText = deviceListCaption(it.devices.size),
                )
                session is DeviceSession.Offline && it.scanning -> it.copy(
                    scanning = false,
                    statusText = deviceListCaption(it.devices.size),
                )
                else -> it.copy(scanning = false)
            }
        }
    }

    override fun resumeSession() {
        if (!session.isAuthenticated || !transport.hasConnection()) return
        transport.stopScan()
        invalidateScanDeadline()
        updateState {
            it.copy(
                browsingDevices = false,
                scanning = false,
                statusText = "Готово",
                error = null,
            )
        }
    }

    private fun browseDevices() {
        updateState {
            it.copy(
                browsingDevices = true,
                scanning = true,
                devices = (listOfNotNull(it.selectedDevice) + it.devices)
                    .distinctBy(DiscoveredDevice::address),
                statusText = "Поиск Test-DPLS…",
                error = null,
            )
        }
        if (transport.startScan()) armScanDeadline(keepSession = true)
    }

    override fun connect(address: String) {
        if (session.isAuthenticated && currentBleAddress() == address && transport.hasConnection()) {
            resumeSession()
            return
        }

        stopScan()
        cancelLinkJobs()
        frameSequencer.reset()
        clearOperation()

        val selected = state.devices.firstOrNull { it.address == address }
        val candidateNodeId = selected?.deviceId
            ?.takeIf { it > 0 }
            ?.let(::NodeId)
        setSession(
            DeviceSession.Connecting(
                endpoint = LinkEndpoint.Ble(address),
                candidateNodeId = candidateNodeId,
            ),
        )

        timeSyncAttempted = false
        previousMode = null
        updateState {
            it.copy(
                statusText = "Подключение…",
                selectedDevice = selected,
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
        if (!transport.connect(address)) {
            fail("Устройство недоступно. Запустите поиск снова.")
            return
        }
        platform.keepConnectionAlive(true)
        lastOperatorAlert = null
        armConnectTimeout()
    }

    override fun identify(address: String) {
        if (session.isAuthenticated && currentBleAddress() == address && transport.hasConnection()) {
            resumeSession()
            return
        }
        updateState {
            it.copy(
                identifyActive = true,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                selectedDevice = it.devices.firstOrNull { device -> device.address == address }
                    ?: it.selectedDevice,
            )
        }
        connect(address)
        identify = Identify(afterConnect = true)
    }

    override fun stopIdentify() {
        identify = Identify()
        stopRssiPoll()
        updateState {
            it.copy(
                identifyActive = false,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                linkRssi = null,
            )
        }
        if (transport.hasConnection()) {
            request(DplsProtocol.Type.IDENTIFY_STOP, priority = true)
        }
    }

    override fun confirmIdentifiedDevice() {
        stopIdentify()
        if (session.credentialsReady) return
        if (!transport.hasConnection()) {
            setSession(DeviceSession.Offline)
            updateState { it.copy(selectedDevice = null) }
            fail("Связь с платой оборвалась. Выберите устройство снова.")
            return
        }
        val linked = session as? DeviceSession.Linked
            ?: return fail("BLE-сессия не готова к аутентификации")
        updateState { it.copy(statusText = "Подключение…", error = null) }
        request(DplsProtocol.Type.HELLO, linked.clientNonce, priority = true)
    }

    override fun updateSetupName(name: String) = updateState { it.copy(setupName = name) }

    override fun updateSetupPassword(password: String) =
        updateState { it.copy(setupPassword = password) }

    override fun updateSetupRepeatPassword(password: String) =
        updateState { it.copy(setupRepeatPassword = password) }

    override fun authenticate(password: String) {
        if (password.length < 8) {
            fail("Пароль должен содержать не менее 8 символов")
            return
        }
        val challenge = session.challengeOrNull
            ?: return fail("Устройство не прислало параметры аутентификации")
        val verifier = DplsCrypto.deriveVerifier(password, challenge.authSalt)
        replaceCachedVerifier(verifier)
        sendAuthProof(verifier)
    }

    override fun setup(name: String, password: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            fail("Введите имя устройства")
            return
        }
        if (password.length < 8) {
            fail("Пароль должен содержать не менее 8 символов")
            return
        }
        val challenge = session.challengeOrNull
            ?: return fail("Устройство не готово к первичной настройке")
        val salt = platform.secureRandomBytes(DplsAuth.SALT_SIZE)
        val verifier = DplsCrypto.deriveVerifier(password, salt)
        replaceCachedVerifier(verifier)
        val nameBytes = utf8Truncate(trimmed, 31)
        val payload = ByteArray(5 + nameBytes.size + salt.size + verifier.size)
        putU32(payload, 0, challenge.sessionId)
        payload[4] = nameBytes.size.toByte()
        nameBytes.copyInto(payload, 5)
        salt.copyInto(payload, 5 + nameBytes.size)
        verifier.copyInto(payload, 5 + nameBytes.size + salt.size)
        request(DplsProtocol.Type.SETUP, payload)
        salt.fill(0)
    }

    override fun requestMode(mode: DplsMode) {
        val controlsReady = session is DeviceSession.Online &&
            state.state != null &&
            !state.commandInProgress
        if (controlsReady && (operation == null || mode == DplsMode.NORMAL)) {
            updateState { it.copy(pendingMode = mode) }
        }
    }

    override fun cancelMode() = updateState { it.copy(pendingMode = null) }

    override fun confirmMode() {
        val mode = state.pendingMode ?: return
        if (session !is DeviceSession.Online) return
        if (operation != null && mode != DplsMode.NORMAL) return

        val payload = authenticatedPayload() + byteArrayOf(mode.wire.toByte())
        if (operation != null) {
            updateState {
                it.copy(
                    pendingMode = null,
                    statusText = "Возврат в Норму…",
                )
            }
            request(DplsProtocol.Type.MODE_SET, payload, priority = true)
            return
        }

        val sequence = request(DplsProtocol.Type.MODE_SET, payload) ?: return
        val pending = Operation.Mode(sequence)
        operation = pending
        updateState {
            it.copy(
                commandInProgress = true,
                pendingMode = null,
                statusText = "Команда отправлена…",
            )
        }
        armOperationTimeout(COMMAND_TIMEOUT_MS, pending) {
            clearOperation()
            updateState { it.copy(commandInProgress = false) }
            if (session.isAuthenticated) {
                request(DplsProtocol.Type.STATE_GET, authenticatedPayload())
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
        if (canStartLog()) startLog(incremental = !journal.isEmpty)
    }

    private fun canStartLog(): Boolean =
        session is DeviceSession.Online &&
            operation == null &&
            !journal.isActive &&
            transport.hasConnection()

    private fun startLog(incremental: Boolean) {
        if (session !is DeviceSession.Online) return
        journal.begin(incremental)
        logLoadPending = true
        sessionLoopJob?.cancel()
        updateState { it.copy(logProgress = 0f, error = null) }
        request(DplsProtocol.Type.LOG_START, authenticatedPayload(), flush = true)
        armLogTimeout()
    }

    override fun loadLogHistogram() {
        if (session !is DeviceSession.Online ||
            !transport.hasConnection() ||
            operation != null ||
            journal.isActive
        ) {
            return
        }
        val sequence = request(
            DplsProtocol.Type.LOG_HIST_GET,
            authenticatedPayload() + byteArrayOf(24),
        ) ?: return
        val pending = Operation.Histogram(sequence)
        operation = pending
        armOperationTimeout(ONE_SHOT_REQUEST_TIMEOUT_MS, pending) {
            clearOperation()
            if (state.eventLog.isEmpty()) loadEventLog()
        }
    }

    override fun loadMoreEventLog() {
        if (session !is DeviceSession.Online ||
            journal.isActive ||
            !state.logHasMore ||
            operation != null
        ) {
            return
        }
        sessionLoopJob?.cancel()
        applyJournalEffect(journal.more())
    }

    override fun loadRemainingEventLog() {
        if (!state.logHasMore) return
        drainLog = true
        loadMoreEventLog()
    }

    override fun refreshState() {
        if (session.isAuthenticated &&
            !journal.isActive &&
            transport.hasConnection() &&
            operation !is Operation.TimeSync
        ) {
            request(DplsProtocol.Type.STATE_GET, authenticatedPayload())
        }
    }

    override fun requestDeviceInfo() {
        if (session.isAuthenticated && transport.hasConnection() && operation == null) {
            requestDeviceInfoInternal()
        }
    }

    override fun clearSettingsOp() {
        clearOperation()
        updateState {
            it.copy(
                settingsOp = SettingsOp.NONE,
                settingsError = null,
                settingsNotice = null,
            )
        }
    }

    override fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return settingsFailure("Введите имя устройства")
        if (session !is DeviceSession.Online || !transport.hasConnection()) {
            return settingsFailure("Нет соединения с устройством")
        }
        if (operation != null) return settingsFailure("Дождитесь завершения текущей операции")

        val bytes = utf8Truncate(trimmed, 31)
        val payload = authenticatedPayload() + byteArrayOf(bytes.size.toByte()) + bytes
        val sequence = request(DplsProtocol.Type.NAME_SET, payload) ?: return
        val pending = Operation.Name(sequence, trimmed)
        operation = pending
        updateState {
            it.copy(
                settingsOp = SettingsOp.IN_PROGRESS,
                settingsError = null,
                settingsNotice = null,
            )
        }
        armSettingsTimeout(pending)
    }

    override fun changePassword(current: String, newPassword: String) {
        if (newPassword.length < 8) {
            return settingsFailure("Пароль должен содержать не менее 8 символов")
        }
        if (session !is DeviceSession.Online || !transport.hasConnection()) {
            return settingsFailure("Нет соединения с устройством")
        }
        if (operation != null) return settingsFailure("Дождитесь завершения текущей операции")

        val auth = authenticatedSession()
        val currentVerifier = DplsCrypto.deriveVerifier(current, auth.authSalt)
        val matches = cachedVerifier?.let { constantTimeEquals(it, currentVerifier) } == true
        currentVerifier.fill(0)
        if (!matches) return settingsFailure("Неверный текущий пароль")

        val salt = platform.secureRandomBytes(DplsAuth.SALT_SIZE)
        val verifier = DplsCrypto.deriveVerifier(newPassword, salt)
        val payload = auth.authenticatedPayload() + salt + verifier
        salt.fill(0)
        val sequence = request(DplsProtocol.Type.PASSWORD_SET, payload) ?: run {
            verifier.fill(0)
            return
        }
        val pending = Operation.Password(sequence, verifier)
        operation = pending
        updateState {
            it.copy(
                settingsOp = SettingsOp.IN_PROGRESS,
                settingsError = null,
                settingsNotice = null,
            )
        }
        armSettingsTimeout(pending)
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
        forgetSavedCredentials()
        updateState {
            it.copy(
                settingsOp = SettingsOp.DONE,
                settingsError = null,
                settingsNotice = "Сохранённый пароль удалён",
            )
        }
    }

    override fun disconnect() {
        stopIdentify()
        disconnectInternal(clearSelection = true, clearVerifier = true)
        mutableState.value = projectSession(retainedUiState())
    }

    override fun openBluetoothSettings() {
        platform.openBluetoothSettings()
    }

    override fun canOpenBluetoothSettings(): Boolean =
        platform.canOpenSystemBluetoothSettings()

    override fun eventLogCsv(): String = buildString {
        appendLine("sequence;timestamp_seconds;time_basis;event_type;parameter")
        state.eventLog.forEach {
            appendLine(
                "${it.sequence};${it.timestampSeconds};${eventTimestampBasis(it.timestampSeconds)};${it.type};${it.parameter}",
            )
        }
    }

    override fun eventLogTxt(): String = buildString {
        appendLine("Журнал событий Тест-ДПЛС")
        appendLine("Устройство: ${state.deviceInfo?.userName ?: state.selectedDevice?.userName ?: "—"}")
        appendLine(
            "Записей: ${state.eventLog.size}${if (state.logHasMore) " из ${state.logTotal} (неполный)" else ""}",
        )
        appendLine("—".repeat(32))
        state.eventLog.forEach {
            appendLine(
                "#${it.sequence}  ${eventTimestampText(it.timestampSeconds)}  событие ${it.type} · ${it.parameter}",
            )
        }
    }

    override fun formatEventTime(record: EventRecord): String =
        journalEventTimeCaption(
            record,
            state.eventLog,
            currentBootFirstSequence(),
            state.deviceBootEpochSeconds,
            timeAnchors,
            platform::formatLocalDateTime,
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
        if (session.endpointOrNull != null && !transport.hasConnection()) {
            scheduleReconnect()
            return
        }
        if ((state.scanning || state.browsingDevices) && transport.startScan()) {
            armScanDeadline(session.isAuthenticated)
        }
    }

    override fun onBluetoothUnavailable() {
        val endpoint = session.endpointOrNull
        val nodeId = session.nodeIdOrNull
        cancelLinkJobs()
        clearOperation()
        pauseJournalForReconnect()
        if (endpoint != null) {
            setSession(DeviceSession.Recovering(nodeId, endpoint))
        } else {
            setSession(DeviceSession.Offline)
        }
        platform.keepConnectionAlive(false)
        updateState {
            it.copy(
                statusText = "Bluetooth выключен",
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
            val merged = it.devices.filterNot { old -> old.address == item.address } + item
            val selected = it.selectedDevice
            val all = if (
                session.isAuthenticated &&
                selected != null &&
                merged.none { row -> row.address == selected.address }
            ) {
                merged + selected
            } else {
                merged
            }
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

    override fun onConnected() {
        val current = session
        if (current !is DeviceSession.Connecting && current !is DeviceSession.Recovering) return
        val endpoint = current.endpointOrNull ?: return
        setSession(
            DeviceSession.Discovering(
                endpoint = endpoint,
                candidateNodeId = current.candidateNodeIdOrNull,
            ),
        )
        updateState {
            it.copy(
                statusText = if (it.identifyActive) "Подключение…" else "Поиск службы…",
                error = null,
            )
        }
    }

    override fun onSubscribed(writeLimit: Int) {
        if (writeLimit < DplsProtocol.OVERHEAD) {
            fail("BLE write limit слишком мал: $writeLimit")
            return
        }
        val current = session
        val endpoint = current.endpointOrNull ?: return fail("Нет активного BLE endpoint")
        val candidateNodeId = current.candidateNodeIdOrNull
        val clientNonce = platform.secureRandomBytes(DplsAuth.NONCE_SIZE)
        setSession(DeviceSession.Linked(endpoint, clientNonce, candidateNodeId))
        if (identify.afterConnect) {
            val sequence = request(DplsProtocol.Type.IDENTIFY_START) ?: return
            identify = Identify(
                responseSequence = sequence,
                sentAtMillis = platform.nowMillis(),
            )
            updateState { it.copy(statusText = "Показать на объекте…") }
        } else {
            updateState { it.copy(statusText = "Подключение…") }
            request(DplsProtocol.Type.HELLO, clientNonce)
        }
    }

    override fun onBytes(bytes: ByteArray) {
        when (val decoded = decodeFrame(bytes)) {
            is DplsProtocol.DecodeResult.Failure -> fail(decoded.reason)
            is DplsProtocol.DecodeResult.Success -> handleMessage(decoded.frame)
        }
    }

    override fun onWriteComplete(errorCode: Long?) {
        if (errorCode == null) return
        if (canRecoverLink()) {
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
                devices = current.devices.map {
                    if (it.address == selected?.address) it.copy(rssi = rssi) else it
                },
            )
        }
    }

    override fun onDisconnected(error: String?) {
        connectTimeoutJob?.cancel()
        if (state.staleBond) return
        if (looksLikeStaleBondError(error)) {
            onStaleBond()
            return
        }
        identify = Identify()
        if (state.identifyActive) {
            fail(error ?: "Связь с платой оборвалась до идентификации")
            return
        }
        if (canRecoverLink()) {
            scheduleReconnect()
            return
        }
        cancelLinkJobs()
        clearOperation()
        cancelLogTimeout()
        journal.fail()
        logLoadPending = false
        drainLog = false
        setSession(DeviceSession.Offline)
        platform.keepConnectionAlive(false)
        updateState {
            it.copy(
                selectedDevice = null,
                statusText = error ?: "Отключено",
                identifyActive = false,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                browsingDevices = false,
                commandInProgress = false,
                logProgress = null,
            )
        }
    }

    override fun onTransportError(message: String) {
        if (looksLikeStaleBondError(message)) onStaleBond() else fail(message)
    }

    override fun onStaleBond() {
        identify = Identify()
        val name = state.selectedDevice?.userName
            ?: state.selectedDevice?.advertisedName
            ?: "Test-DPLS"
        fail(
            "Старое сопряжение с «$name». Настройки → Bluetooth → ⓘ у этого имени → Забыть. Затем «Повторить».",
            staleBond = true,
        )
    }

    private fun handleMessage(frame: DplsProtocol.Frame) {
        if (!frame.isResponse && !frame.isError) {
            fail("Устройство прислало некоррелируемый кадр")
            return
        }
        if (frame.isError) {
            handleDeviceError(frame)
            return
        }
        when (frame.type) {
            DplsProtocol.Type.AUTH_CHALLENGE -> handleAuthChallenge(frame.payload)
            DplsProtocol.Type.AUTH_RESULT -> handleAuthResult(frame.payload)
            DplsProtocol.Type.COMMAND_RESULT -> handleCommandResult(frame)
            DplsProtocol.Type.DEVICE_INFO_REPORT -> handleDeviceInfo(frame)
            DplsProtocol.Type.SETTINGS_RESULT -> handleSettingsResult(frame)
            DplsProtocol.Type.STATE_REPORT -> handleState(frame.payload)
            DplsProtocol.Type.TIME_SYNC -> handleTimeSyncAck(frame)
            DplsProtocol.Type.IDENTIFY_START -> handleIdentifyAck(frame)
            DplsProtocol.Type.IDENTIFY_STOP,
            DplsProtocol.Type.KEEP_ALIVE,
            -> Unit
            DplsProtocol.Type.LOG_INFO -> applyJournalEffect(journal.info(frame.payload))
            DplsProtocol.Type.LOG_CHUNK -> applyJournalEffect(journal.chunk(frame.payload))
            DplsProtocol.Type.LOG_RESULT -> applyJournalEffect(journal.finish())
            DplsProtocol.Type.LOG_HIST_REPORT -> handleHistogram(frame)
            else -> Unit
        }
    }

    private fun handleAuthChallenge(payload: ByteArray) {
        val wireChallenge = parseAuthChallenge(payload)
            ?: return fail("Повреждённый AUTH_CHALLENGE")
        val linked = session as? DeviceSession.Linked
            ?: return fail("AUTH_CHALLENGE получен вне ожидаемого состояния")
        val challenge = SessionChallenge(
            sessionId = wireChallenge.sessionId,
            clientNonce = linked.clientNonce,
            deviceNonce = wireChallenge.deviceNonce,
            authSalt = wireChallenge.salt,
            initialized = wireChallenge.initialized,
        )
        setSession(
            if (wireChallenge.initialized) {
                DeviceSession.Authenticating(
                    linked.endpoint,
                    challenge,
                    linked.candidateNodeId,
                )
            } else {
                DeviceSession.Commissioning(
                    linked.endpoint,
                    challenge,
                    linked.candidateNodeId,
                )
            },
        )
        val autoAuth = wireChallenge.initialized && cachedVerifier != null
        updateState {
            it.copy(
                awaitingUserPassword = !autoAuth,
                statusText = if (autoAuth) "Вход…" else "Подключено",
                setupName = it.setupName.ifBlank {
                    it.selectedDevice?.userName ?: "Test-DPLS-001"
                },
                setupPassword = "",
                setupRepeatPassword = "",
            )
        }
        if (autoAuth) cachedVerifier?.let(::sendAuthProof)
    }

    private fun handleAuthResult(payload: ByteArray) {
        if (session.isAuthenticated) return
        val challenge = session.challengeOrNull
            ?: return fail("AUTH_RESULT получен вне аутентификации")
        val result = parseAuthResult(payload)
            ?: return fail("Повреждённый AUTH_RESULT")

        if (result.status == 3) {
            // The device reboots before DEVICE_INFO can prove NodeId. Persist only
            // against the physical BLE address; stable node keys are written later.
            persistCachedVerifier()
            val endpoint = session.endpointOrNull
                ?: return fail("Потерян endpoint после первичной настройки")
            setSession(DeviceSession.Recovering(session.nodeIdOrNull, endpoint))
            updateState {
                it.copy(
                    statusText = "Настройка сохранена. Повторное подключение…",
                    awaitingUserPassword = false,
                    setupPassword = "",
                    setupRepeatPassword = "",
                    error = null,
                )
            }
            return
        }

        if (result.status != 0) {
            forgetSavedCredentials()
            updateState { it.copy(awaitingUserPassword = true) }
            fail(
                if (result.retryAfterSeconds > 0) {
                    "Аутентификация заблокирована на ${result.retryAfterSeconds} с"
                } else {
                    "Неверный пароль"
                },
            )
            return
        }

        val token = result.sessionToken ?: return fail("AUTH_RESULT без session token")
        val endpoint = session.endpointOrNull
            ?: return fail("Потерян endpoint после аутентификации")
        val candidateNodeId = session.candidateNodeIdOrNull
        setSession(
            DeviceSession.Synchronizing(
                endpoint = endpoint,
                auth = AuthSession(challenge.sessionId, token, challenge.authSalt),
                candidateNodeId = candidateNodeId,
            ),
        )
        updateState {
            it.copy(
                awaitingUserPassword = false,
                identifyActive = false,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                statusText = "Чтение состояния…",
                error = null,
            )
        }
        request(DplsProtocol.Type.STATE_GET, authenticatedPayload())
        startSessionLoop()
    }

    private fun handleCommandResult(frame: DplsProtocol.Frame) {
        val pending = operation as? Operation.Mode ?: return
        if (pending.sequence != frame.sequence) return
        val result = parseCommandResult(frame.payload)
            ?: return fail("Повреждённый COMMAND_RESULT")
        clearOperation()
        if (result.status != 0) {
            fail(commandRejectReason(result.status))
            return
        }
        updateState {
            it.copy(
                commandInProgress = false,
                statusText = "Команда применена, чтение состояния…",
                lastAckMillis = platform.nowMillis(),
            )
        }
        if (session.isAuthenticated) {
            request(DplsProtocol.Type.STATE_GET, authenticatedPayload())
        }
    }

    private fun handleDeviceInfo(frame: DplsProtocol.Frame) {
        val pending = operation as? Operation.DeviceInfo ?: return
        if (pending.sequence != frame.sequence) return
        clearOperation()
        val info = parseDeviceInfoReport(frame.payload)
            ?: return fail("Повреждённый DEVICE_INFO_REPORT")
        val nodeId = info.deviceId
            .takeIf { it > 0 }
            ?.let(::NodeId)
            ?: return fail("Устройство прислало некорректный ID")

        when (val current = session) {
            is DeviceSession.Synchronizing -> {
                val candidate = current.candidateNodeId
                if (candidate != null && candidate != nodeId) {
                    fail("Идентификатор устройства изменился во время подключения")
                    return
                }
                setSession(
                    DeviceSession.Online(
                        nodeId = nodeId,
                        endpoint = current.endpoint,
                        auth = current.auth,
                    ),
                )
            }
            is DeviceSession.Online -> {
                if (current.nodeId != nodeId) {
                    fail("Устройство сменило идентификатор в активной сессии")
                    return
                }
            }
            else -> {
                fail("DEVICE_INFO получен вне активной сессии")
                return
            }
        }

        updateState {
            it.copy(
                deviceInfo = info,
                selectedDevice = it.selectedDevice?.copy(
                    userName = info.userName.ifBlank { it.selectedDevice.userName },
                ),
            )
        }
        persistCachedVerifier()
        loadTimeAnchors()
        persistTimeAnchors()
        attemptTimeSync()
    }

    private fun handleSettingsResult(frame: DplsProtocol.Frame) {
        val result = parseSettingsResult(frame.payload)
            ?: return fail("Повреждённый SETTINGS_RESULT")
        when (val pending = operation) {
            is Operation.Name -> if (pending.sequence == frame.sequence) {
                clearOperation()
                if (result.status == 0) {
                    updateState {
                        it.copy(
                            settingsOp = SettingsOp.DONE,
                            settingsError = null,
                            settingsNotice = "Имя «${pending.value}» применено",
                            selectedDevice = it.selectedDevice?.copy(userName = pending.value),
                            deviceInfo = it.deviceInfo?.copy(userName = pending.value),
                        )
                    }
                    requestDeviceInfoInternal()
                } else {
                    settingsFailure("Устройство отклонило изменение (код ${result.status})")
                }
            }
            is Operation.Password -> if (pending.sequence == frame.sequence) {
                operationJob?.cancel()
                operation = null
                if (result.status == 0) {
                    replaceCachedVerifier(pending.verifier)
                    persistCachedVerifier()
                    updateState {
                        it.copy(
                            settingsOp = SettingsOp.DONE,
                            settingsError = null,
                            settingsNotice = "Пароль изменён",
                        )
                    }
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
        val device = parseStateReport(payload, now)
            ?: return fail("Повреждённый STATE_REPORT")
        updateState {
            it.copy(
                statusText = if (session is DeviceSession.Synchronizing) {
                    "Проверка устройства…"
                } else {
                    "Состояние получено"
                },
                state = device,
                deviceBootEpochSeconds = now / 1000 - device.uptimeSeconds,
                identifyActive = false,
                identifyLedLive = false,
                identifyLedPhaseOffsetMs = 0,
                staleState = false,
                lastAckMillis = now,
                error = null,
            )
        }

        val old = previousMode
        previousMode = device.mode
        if (old?.dangerous == true && device.mode == DplsMode.NORMAL) {
            platform.notifyOperator(
                DplsOperatorAlerts.NORMAL_TITLE,
                DplsOperatorAlerts.NORMAL_BODY,
            )
        }

        when {
            session is DeviceSession.Synchronizing && operation == null ->
                requestDeviceInfoInternal()
            session is DeviceSession.Online && operation == null ->
                attemptTimeSync()
        }
        if (logLoadPending && !journal.isActive && operation == null) {
            refreshEventLog()
        }
    }

    private fun handleTimeSyncAck(frame: DplsProtocol.Frame) {
        val pending = operation as? Operation.TimeSync ?: return
        if (pending.sequence == frame.sequence) clearOperation()
    }

    private fun handleIdentifyAck(frame: DplsProtocol.Frame) {
        if (identify.responseSequence != frame.sequence) return
        val phase = DplsIdentifyLed.phaseAtAckMs(
            identify.sentAtMillis,
            platform.nowMillis(),
        )
        identify = Identify()
        updateState {
            it.copy(
                identifyLedLive = true,
                identifyLedPhaseOffsetMs = phase,
                linkRssi = it.linkRssi ?: it.selectedDevice?.rssi,
            )
        }
        scheduleRssiPoll()
    }

    private fun handleHistogram(frame: DplsProtocol.Frame) {
        val pending = operation as? Operation.Histogram ?: return
        if (pending.sequence != frame.sequence) return
        clearOperation()
        val report = parseLogHistogramReport(frame.payload)
            ?: return fail("Повреждённый LOG_HIST_REPORT")
        updateState {
            it.copy(
                logHistogram = report.takeIf { item ->
                    item.eventCount > 0 && item.counts.isNotEmpty()
                },
                logFirstTimestampSeconds = report.firstTimestampSeconds,
                logLastTimestampSeconds = report.lastTimestampSeconds,
                logTotal = if (it.logTotal == 0) report.eventCount else it.logTotal,
            )
        }
        if (state.eventLog.isEmpty() && !state.logHasMore) loadEventLog()
    }

    private fun handleDeviceError(frame: DplsProtocol.Frame) {
        val code = frame.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
        val pending = operation
        if (pending != null && pending.sequence != frame.sequence) {
            // A late response belongs to an expired operation. Positive responses
            // are ignored the same way; an old error must not kill the new one.
            return
        }
        when (pending) {
            is Operation.Histogram -> {
                clearOperation()
                if (code == 5 && state.eventLog.isEmpty()) {
                    loadEventLog()
                } else if (code != 5) {
                    fail("Ошибка устройства: $code")
                }
            }
            is Operation.DeviceInfo -> {
                clearOperation()
                fail("Устройство не вернуло обязательную информацию: $code")
            }
            is Operation.TimeSync -> {
                clearOperation()
                if (code != 5) fail("Ошибка синхронизации времени: $code")
            }
            is Operation.Name,
            is Operation.Password,
            -> {
                clearOperation()
                settingsFailure(
                    if (code == 5) {
                        "Прошивка устройства не поддерживает изменение настроек"
                    } else {
                        "Ошибка устройства: $code"
                    },
                )
            }
            is Operation.Mode -> fail("Ошибка устройства: $code")
            null -> if (journal.isActive) {
                failLog("Ошибка загрузки журнала: $code")
            } else {
                fail(
                    if (code == 7) {
                        "Окно первичной настройки закрыто. Выключите и включите устройство, затем повторите настройку."
                    } else {
                        "Ошибка устройства: $code"
                    },
                )
            }
        }
    }

    private fun requestDeviceInfoInternal() {
        if (operation != null || !session.isAuthenticated || !transport.hasConnection()) return
        val sequence = request(DplsProtocol.Type.DEVICE_INFO_GET, authenticatedPayload()) ?: return
        val pending = Operation.DeviceInfo(sequence)
        operation = pending
        armOperationTimeout(ONE_SHOT_REQUEST_TIMEOUT_MS, pending) {
            clearOperation()
            if (session is DeviceSession.Synchronizing || state.deviceInfo == null) {
                requestDeviceInfoInternal()
            }
        }
    }

    private fun attemptTimeSync() {
        if (timeSyncAttempted ||
            operation != null ||
            session !is DeviceSession.Online ||
            !transport.hasConnection()
        ) {
            return
        }
        val auth = authenticatedSession()
        val payload = buildTimeSyncPayload(
            auth.sessionId,
            auth.token,
            platform.nowMillis() / 1000L,
        ) ?: return
        timeSyncAttempted = true
        val sequence = request(DplsProtocol.Type.TIME_SYNC, payload) ?: return
        val pending = Operation.TimeSync(sequence)
        operation = pending
        armOperationTimeout(ONE_SHOT_REQUEST_TIMEOUT_MS, pending) {
            clearOperation()
        }
    }

    private fun applyJournalEffect(effect: JournalMachine.Effect) {
        when (effect) {
            is JournalMachine.Effect.Ack -> {
                if (session !is DeviceSession.Online) return
                val suffix = ByteArray(2)
                putU16(suffix, 0, effect.index)
                request(DplsProtocol.Type.LOG_ACK, authenticatedPayload() + suffix)
                armLogTimeout()
            }
            JournalMachine.Effect.Pause,
            JournalMachine.Effect.Complete,
            -> finishJournalPage()
            is JournalMachine.Effect.Error -> failLog(effect.message)
            JournalMachine.Effect.None -> Unit
        }
        publishJournal()
    }

    private fun finishJournalPage() {
        cancelLogTimeout()
        logLoadPending = false
        if (drainLog && journal.snapshot(false).hasMore) {
            applyJournalEffect(journal.more())
        } else {
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
                logFirstTimestampSeconds = journal.firstTimestamp()
                    ?: it.logFirstTimestampSeconds,
                logLastTimestampSeconds = journal.lastTimestamp()
                    ?: it.logLastTimestampSeconds,
                logProgress = snap.progress,
                statusText = if (snap.hasMore) {
                    "Журнал: ${snap.records.size} из ${snap.total}"
                } else {
                    "Журнал загружен: ${snap.records.size} записей"
                },
                error = null,
            )
        }
    }

    private fun failLog(message: String) {
        journal.fail()
        logLoadPending = false
        drainLog = false
        cancelLogTimeout()
        updateState {
            it.copy(
                logProgress = null,
                logHasMore = false,
                error = message,
            )
        }
        startSessionLoop()
    }

    private fun pauseJournalForReconnect() {
        if (journal.isActive) {
            journal.fail()
            logLoadPending = true
        }
        cancelLogTimeout()
    }

    private fun sendAuthProof(verifier: ByteArray) {
        val challenge = session.challengeOrNull ?: return fail("Нет активного challenge")
        val signed = DplsAuth.proofMessage(
            challenge.deviceNonce,
            challenge.clientNonce,
            challenge.sessionId,
        )
        val mac = DplsCrypto.hmacSha256(verifier, signed)
        signed.fill(0)
        request(DplsProtocol.Type.AUTH_PROOF, challenge.clientNonce + mac)
        mac.fill(0)
    }

    private fun request(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
        priority: Boolean = false,
        flush: Boolean = false,
    ): Int? {
        val sequence = frameSequencer.next()
        return if (
            sendFrame(
                type,
                sequence,
                DplsProtocol.Flags.REQUEST,
                payload,
                priority,
                flush,
            )
        ) {
            sequence
        } else {
            null
        }
    }

    private fun oneWay(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
    ) {
        sendFrame(type, frameSequencer.next(), 0, payload, false, false)
    }

    private fun sendFrame(
        type: DplsProtocol.Type,
        sequence: Int,
        flags: Int,
        payload: ByteArray,
        priority: Boolean,
        flush: Boolean,
    ): Boolean {
        val bytes = encodeFrame(DplsProtocol.Frame(type, sequence, flags, payload))
        if (!transport.send(bytes, priority, flush)) {
            fail("Кадр ${bytes.size} байт не помещается в BLE write limit")
            return false
        }
        return true
    }

    private fun startSessionLoop() {
        sessionLoopJob?.cancel()
        if (!session.isAuthenticated || !transport.hasConnection() || journal.isActive) return
        val generation = linkGeneration
        sessionLoopJob = scope.launch {
            var ticks = 0
            while (
                isActive &&
                generation == linkGeneration &&
                session.isAuthenticated &&
                transport.hasConnection()
            ) {
                delay(STATE_REFRESH_MS)
                ++ticks
                if (generation != linkGeneration) return@launch
                if (journal.isActive || operation is Operation.TimeSync) continue

                val shouldRefresh = !state.commandInProgress &&
                    state.logProgress == null &&
                    state.state != null
                if (shouldRefresh) {
                    val receivedAt = state.state?.receivedAtMillis
                    if (receivedAt != null &&
                        platform.nowMillis() - receivedAt >= TELEMETRY_STALE_MS
                    ) {
                        updateState { it.copy(staleState = true) }
                    }
                    request(DplsProtocol.Type.STATE_GET, authenticatedPayload())
                } else if (ticks % KEEP_ALIVE_TICKS == 0) {
                    oneWay(DplsProtocol.Type.KEEP_ALIVE, authenticatedPayload())
                }
            }
        }
    }

    private fun canRecoverLink(): Boolean =
        session.endpointOrNull != null &&
            (cachedVerifier != null || state.state != null || logLoadPending)

    private fun scheduleReconnect() {
        val current = session
        val endpoint = current.endpointOrNull ?: return
        val nodeId = current.nodeIdOrNull
        if (reconnectJob?.isActive == true) return
        val reconnectImmediately = current is DeviceSession.Recovering &&
            current.nodeId == null &&
            cachedVerifier != null &&
            state.state == null

        cancelLinkJobs()
        clearOperation()
        pauseJournalForReconnect()
        timeSyncAttempted = false
        setSession(DeviceSession.Recovering(nodeId, endpoint))
        platform.keepConnectionAlive(true)
        updateState {
            it.copy(
                statusText = if (state.state != null || logLoadPending) {
                    "Восстановление связи…"
                } else {
                    "Подключение…"
                },
                staleState = it.state != null,
                savedCredentials = cachedVerifier != null,
                commandInProgress = false,
            )
        }

        val generation = linkGeneration
        reconnectJob = scope.launch {
            if (!reconnectImmediately) delay(RECONNECT_DELAY_MS)
            if (generation != linkGeneration) return@launch
            if (session.endpointOrNull != endpoint) return@launch
            if (!transport.reconnect()) {
                fail("Устройство недоступно. Запустите поиск снова.")
            }
        }
    }

    /**
     * Timeout identity is both the physical-link generation and frame sequence.
     * A canceled timeout that already became runnable cannot touch another link
     * or a newer operation that happened to reuse the same 16-bit sequence.
     */
    private fun armOperationTimeout(
        delayMs: Long,
        expected: Operation,
        action: () -> Unit,
    ) {
        operationJob?.cancel()
        val generation = linkGeneration
        val sequence = expected.sequence
        operationJob = scope.launch {
            delay(delayMs)
            if (generation == linkGeneration && operation?.sequence == sequence) action()
        }
    }

    private fun armSettingsTimeout(expected: Operation) =
        armOperationTimeout(SETTINGS_TIMEOUT_MS, expected) {
            clearOperation()
            settingsFailure("Устройство не ответило на изменение настроек")
        }

    private fun clearOperation() {
        operationJob?.cancel()
        operationJob = null
        val old = operation
        operation = null
        if (old is Operation.Password && cachedVerifier !== old.verifier) {
            old.verifier.fill(0)
        }
    }

    private fun armLogTimeout() {
        logTimeoutJob?.cancel()
        val generation = ++logTimeoutGeneration
        logTimeoutJob = scope.launch {
            delay(LOG_CHUNK_TIMEOUT_MS)
            if (generation == logTimeoutGeneration && journal.isActive) {
                failLog("Не удалось загрузить журнал")
            }
        }
    }

    private fun cancelLogTimeout() {
        logTimeoutGeneration++
        logTimeoutJob?.cancel()
        logTimeoutJob = null
    }

    private fun armScanDeadline(keepSession: Boolean) {
        scanJob?.cancel()
        val generation = ++scanGeneration
        scanJob = scope.launch {
            delay(SCAN_DURATION_MS)
            if (generation != scanGeneration) return@launch
            transport.stopScan()
            updateState {
                if (keepSession || it.scanning) {
                    it.copy(
                        scanning = false,
                        statusText = deviceListCaption(it.devices.size),
                    )
                } else {
                    it
                }
            }
        }
    }

    private fun invalidateScanDeadline() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
    }

    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        val generation = linkGeneration
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (generation != linkGeneration) return@launch
            val now = state
            if (!now.identifyLedLive &&
                !now.identifyActive &&
                !session.credentialsReady &&
                !session.isAuthenticated
            ) {
                transport.disconnect(clearSelection = false)
                fail("Плата не отвечает по Bluetooth. Выберите устройство снова.")
            }
        }
    }

    /** Cancelling a physical-link scope also invalidates every captured epoch. */
    private fun cancelLinkJobs() {
        linkGeneration++
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
        val generation = linkGeneration
        rssiJob = scope.launch {
            while (
                isActive &&
                generation == linkGeneration &&
                state.identifyLedLive &&
                transport.hasConnection()
            ) {
                transport.readRssi()
                delay(RSSI_POLL_MS)
            }
        }
    }

    private fun stopRssiPoll() {
        rssiJob?.cancel()
        rssiJob = null
    }

    private fun disconnectInternal(
        clearSelection: Boolean,
        clearVerifier: Boolean,
    ) {
        invalidateScanDeadline()
        cancelLinkJobs()
        cancelLogTimeout()
        clearOperation()
        journal.fail()
        transport.disconnect(clearSelection)
        platform.keepConnectionAlive(false)
        frameSequencer.reset()
        setSession(DeviceSession.Offline)
        identify = Identify()
        previousMode = null
        lastOperatorAlert = null
        logLoadPending = false
        drainLog = false
        timeSyncAttempted = false
        if (clearVerifier) replaceCachedVerifier(null)
    }

    private fun authenticatedSession(): AuthSession =
        session.authOrNull ?: error("Authenticated operation without authenticated DeviceSession")

    private fun authenticatedPayload(): ByteArray =
        authenticatedSession().authenticatedPayload()

    private fun currentBleAddress(): String? =
        (session.endpointOrNull as? LinkEndpoint.Ble)?.address

    private fun addressCredentialKeys(): List<String> =
        currentBleAddress()
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf("legacy-addr:$it", "addr:$it") }
            .orEmpty()

    /** Reads are allowed from the verified node or the current physical address. */
    private fun credentialReadKeys(): List<String> = buildList {
        session.nodeIdOrNull?.let {
            add(credentialKey(it))
            add("id:${it.value}")
        }
        addAll(addressCredentialKeys())
    }

    /** Writes never trust advertised candidateNodeId. */
    private fun credentialWriteKeys(): List<String> = credentialReadKeys()

    private fun loadCachedVerifier() {
        val stored = credentialReadKeys().firstNotNullOfOrNull { key ->
            platform.readDeviceVerifier(key)
                ?.takeIf { bytes -> bytes.size == DplsAuth.VERIFIER_SIZE }
        }
        replaceCachedVerifier(stored)
        updateState { it.copy(savedCredentials = stored != null) }
    }

    private fun persistCachedVerifier() {
        val stored = cachedVerifier
        credentialWriteKeys().forEach { platform.writeDeviceVerifier(it, stored) }
        updateState { it.copy(savedCredentials = stored != null) }
    }

    private fun forgetSavedCredentials() {
        (credentialReadKeys() + credentialWriteKeys())
            .distinct()
            .forEach { platform.writeDeviceVerifier(it, null) }
        replaceCachedVerifier(null)
        updateState { it.copy(savedCredentials = false) }
    }

    private fun replaceCachedVerifier(value: ByteArray?) {
        if (cachedVerifier !== value) cachedVerifier?.fill(0)
        cachedVerifier = value
    }

    private fun currentBootFirstSequence(): Long? =
        journalBootFirstSequences(state.eventLog).lastOrNull()

    private fun rememberCurrentBootAnchor(records: List<EventRecord>) {
        val epoch = state.deviceBootEpochSeconds ?: return
        val first = journalBootFirstSequences(records).lastOrNull() ?: return
        val last = records.maxOfOrNull(EventRecord::sequence) ?: return
        timeAnchors = mergeJournalTimeAnchor(
            timeAnchors,
            JournalTimeAnchor(first, epoch, last),
        )
        persistTimeAnchors()
    }

    private fun loadTimeAnchors() {
        var merged = timeAnchors
        credentialWriteKeys().forEach { key ->
            decodeJournalTimeAnchors(platform.readDeviceString("time.$key")).forEach { anchor ->
                merged = mergeJournalTimeAnchor(merged, anchor)
            }
        }
        timeAnchors = merged
        updateState { it.copy(journalTimeAnchors = timeAnchors) }
    }

    private fun persistTimeAnchors() {
        val encoded = encodeJournalTimeAnchors(timeAnchors)
        credentialWriteKeys().forEach { platform.writeDeviceString("time.$it", encoded) }
    }

    private fun retainedUiState(
        status: String = "Готово к поиску",
        scanning: Boolean = false,
    ) = DplsUiState(
        statusText = status,
        scanning = scanning,
        uiTheme = state.uiTheme,
        keepScreenOn = state.keepScreenOn,
        hapticsEnabled = state.hapticsEnabled,
    )

    private fun fail(message: String, staleBond: Boolean = false) {
        val endpoint = session.endpointOrNull
        cancelLinkJobs()
        clearOperation()
        journal.fail()
        cancelLogTimeout()
        logLoadPending = false
        drainLog = false
        platform.keepConnectionAlive(false)
        setSession(DeviceSession.Failed(endpoint, LinkFailure.Platform(message)))
        updateState {
            it.copy(
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

    private fun setSession(next: DeviceSession) {
        session = next
        mutableState.value = projectSession(mutableState.value)
    }

    /**
     * Lifecycle fields are pure projections. No previous lifecycle projection is
     * read back as authority; DeviceSession and cached credentials are sufficient.
     */
    private fun projectSession(ui: DplsUiState): DplsUiState {
        val initialized = when (session) {
            is DeviceSession.Commissioning -> false
            is DeviceSession.Authenticating,
            is DeviceSession.Synchronizing,
            is DeviceSession.Online,
            -> true
            is DeviceSession.Recovering -> cachedVerifier != null
            else -> false
        }
        val credentialsReady = session.credentialsReady ||
            (session is DeviceSession.Recovering && cachedVerifier != null)
        return ui.copy(
            phase = connectionPhase(ui),
            authenticated = session.isAuthenticated,
            initialized = initialized,
            credentialsReady = credentialsReady,
        )
    }

    private fun connectionPhase(ui: DplsUiState): ConnectionPhase = when (session) {
        DeviceSession.Offline -> if (ui.scanning) {
            ConnectionPhase.SCANNING
        } else {
            ConnectionPhase.IDLE
        }
        is DeviceSession.Connecting -> ConnectionPhase.CONNECTING
        is DeviceSession.Discovering -> ConnectionPhase.DISCOVERING
        is DeviceSession.Linked,
        is DeviceSession.Commissioning,
        is DeviceSession.Authenticating,
        -> ConnectionPhase.AUTHENTICATING
        is DeviceSession.Synchronizing -> ConnectionPhase.SYNCHRONIZING
        is DeviceSession.Online -> ConnectionPhase.READY
        is DeviceSession.Recovering -> ConnectionPhase.RECONNECTING
        is DeviceSession.Failed -> ConnectionPhase.ERROR
    }

    private fun settingsFailure(message: String) = updateState {
        it.copy(
            settingsOp = SettingsOp.FAILED,
            settingsError = message,
            settingsNotice = null,
        )
    }

    private inline fun updateState(block: (DplsUiState) -> DplsUiState) {
        mutableState.value = projectSession(block(mutableState.value))
    }

    private val state: DplsUiState
        get() = mutableState.value

    private fun deviceListCaption(count: Int): String =
        if (count == 0) "Устройства не найдены" else "Выберите устройство"

    private fun utf8Truncate(value: String, maxBytes: Int): ByteArray {
        var end = 0
        var size = 0
        while (end < value.length) {
            val high = value[end].code in 0xD800..0xDBFF
            val paired = high &&
                end + 1 < value.length &&
                value[end + 1].code in 0xDC00..0xDFFF
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
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
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
        private const val RSSI_POLL_MS = 350L
        private const val RECONNECT_DELAY_MS = 500L
    }
}
