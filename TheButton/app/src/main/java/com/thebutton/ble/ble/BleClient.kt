package com.thebutton.ble.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.thebutton.ble.protocol.DplsProtocol
import com.thebutton.ble.protocol.u16
import com.thebutton.ble.protocol.u32
import com.thebutton.ble.protocol.u8
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Owns the single Android GATT connection and the Test-DPLS application session. */
class BleClient(context: Context) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java).adapter
    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val _uiState = MutableStateFlow(DplsUiState())
    val uiState: StateFlow<DplsUiState> = _uiState.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var selectedAddress: String? = null
    private var reconnectAttempt = 0
    private var negotiatedMtu = 23
    private var sequence = 1
    private var commandId = 1L
    private var sessionId = 0L
    private var sessionToken = ByteArray(8)
    private var clientNonce = ByteArray(16)
    private var deviceNonce = ByteArray(16)
    private var authSalt = ByteArray(16)
    private var cachedVerifier: ByteArray? = null
    private var pendingSetupName: String? = null
    private var pendingSettingsCommandId: Long? = null
    private var pendingNewVerifier: ByteArray? = null
    private var initialized = false
    private var logBytes = ByteArray(0)
    private var logExpectedBytes = 0
    private var logExpectedEvents = 0
    private var logReceivedEvents = 0
    private var logChunkReceived = BooleanArray(0)
    private var logNextChunk = 0
    private var logInfoReceived = false
    private var logLoadPending = false
    private var pendingLogAckIndex: Int? = null
    private val logPendingChunks = mutableListOf<Pair<Int, ByteArray>>()
    private var identifyAfterConnect = false
    private var pendingIdentifyAck = false
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private var pendingWrite: ByteArray? = null
    private var writeRetryCount = 0
    private var reachedReady = false
    private var bondRecoveryCount = 0
    private var preAuthGatt133Count = 0
    private var lastKnownBondState = BluetoothDevice.BOND_NONE
    private var reconnectRunnable: Runnable? = null
    private var pairingTimeoutRunnable: Runnable? = null
    private var pairingPollRunnable: Runnable? = null
    private var e2eModeTarget: DplsMode? = null
    private var e2eModePhase = E2eModePhase.DONE
    private var e2eModeDeadlineMs = 0L

    private enum class E2eModePhase { APPLY, RETURN, DONE }

    private val e2eModeRunner = object : Runnable {
        override fun run() {
            val target = e2eModeTarget ?: return
            if (System.currentTimeMillis() > e2eModeDeadlineMs) {
                Log.e(TAG, "E2E mode timeout: ${target.title}")
                e2eModeTarget = null
                e2eModePhase = E2eModePhase.DONE
                return
            }
            val state = _uiState.value
            when (e2eModePhase) {
                E2eModePhase.APPLY -> {
                    if (state.commandInProgress || state.state?.mode != target) {
                        handler.postDelayed(this, E2E_MODE_POLL_MS)
                        return
                    }
                    Log.i(TAG, "E2E mode applied: ${target.title}")
                    e2eModePhase = E2eModePhase.RETURN
                    returnToNormal()
                    handler.postDelayed(this, E2E_MODE_POLL_MS)
                }
                E2eModePhase.RETURN -> {
                    if (state.commandInProgress || state.state?.mode != DplsMode.NORMAL) {
                        handler.postDelayed(this, E2E_MODE_POLL_MS)
                        return
                    }
                    Log.i(TAG, "E2E mode done: ${target.title}")
                    e2eModeTarget = null
                    e2eModePhase = E2eModePhase.DONE
                }
                E2eModePhase.DONE -> Unit
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = acceptScan(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::acceptScan)
        override fun onScanFailed(errorCode: Int) = fail("Ошибка BLE-сканирования: $errorCode")
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    if (device.address != selectedAddress) return
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            cancelPairingTimeout()
                            lastKnownBondState = BluetoothDevice.BOND_BONDED
                            if (_uiState.value.phase == ConnectionPhase.PAIRING) resumeAfterPairing()
                        }
                        BluetoothDevice.BOND_NONE -> {
                            if (_uiState.value.phase == ConnectionPhase.PAIRING) {
                                cancelPairingTimeout()
                                fail("Не удалось создать защищённое BLE-соединение")
                                closeCurrentGatt()
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                ) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handleBluetoothOff()
                    BluetoothAdapter.STATE_ON -> {
                        reconnectAttempt = 0
                        if (selectedAddress != null) scheduleReconnect()
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        appContext.registerReceiver(
            bluetoothReceiver,
            filter,
            // Bond and adapter state are emitted by Android's Bluetooth
            // process, not by this application.
            Context.RECEIVER_EXPORTED,
        )
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        disconnectGatt(clearSelection = true)
        _uiState.value = DplsUiState(phase = ConnectionPhase.SCANNING, statusText = "Поиск Test-DPLS…")
        val scanner = adapter.bluetoothLeScanner ?: return fail("Bluetooth выключен")
        scanning = true
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, scanCallback)
        handler.postDelayed({ if (scanning) stopScan() }, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.IDLE,
                statusText = if (it.devices.isEmpty()) "Устройства не найдены" else "Выберите устройство",
            )
        }
    }

    fun showExternalError(message: String) = fail(message)

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        stopScan()
        cancelReconnect()
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return fail("Некорректный адрес устройства")
        selectedAddress = address
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.CONNECTING,
                statusText = "Подключение…",
                selectedDevice = it.devices.firstOrNull { item -> item.address == address },
                credentialsReady = false,
                setupPassword = "",
                setupRepeatPassword = "",
                identifyLedLive = false,
                error = null,
            )
        }
        closeCurrentGatt()
        pendingIdentifyAck = false
        val connection = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gatt = connection
        Log.i(TAG, "connectGatt address=$address attempt=$reconnectAttempt")
    }

    fun identify(address: String) {
        if (_uiState.value.error?.contains("связк", ignoreCase = true) == true ||
            _uiState.value.error?.contains("сопряж", ignoreCase = true) == true
        ) {
            clearStalePhoneBond(address)
        }
        resetBondRecoveryState()
        identifyAfterConnect = true
        pendingIdentifyAck = false
        _uiState.update { it.copy(identifyActive = true, identifyLedLive = false) }
        connect(address)
    }

    fun stopIdentify() {
        identifyAfterConnect = false
        pendingIdentifyAck = false
        _uiState.update { it.copy(identifyActive = false, identifyLedLive = false) }
        if (gatt != null && rx != null) sendPriority(DplsProtocol.Type.IDENTIFY_STOP, byteArrayOf())
    }

    fun confirmIdentifiedDevice() {
        stopIdentify()
        handler.removeCallbacks(preAuthKeepAlive)
        if (_uiState.value.credentialsReady || gatt == null || rx == null) return
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.AUTHENTICATING,
                statusText = "Подключение…",
                identifyActive = false,
                identifyLedLive = false,
                error = null,
            )
        }
        sendPriority(DplsProtocol.Type.HELLO, clientNonce)
    }

    fun updateSetupName(name: String) {
        _uiState.update { it.copy(setupName = name) }
    }

    fun updateSetupPassword(password: String) {
        _uiState.update { it.copy(setupPassword = password) }
    }

    fun updateSetupRepeatPassword(password: String) {
        _uiState.update { it.copy(setupRepeatPassword = password) }
    }

    fun fillSetupFormForE2e(name: String, password: String) {
        _uiState.update { it.copy(setupName = name, setupPassword = password, setupRepeatPassword = password) }
        handler.post { setup(name, password.toCharArray()) }
    }

    fun fillLoginFormForE2e(password: String) {
        _uiState.update { it.copy(setupPassword = password) }
        handler.post { authenticate(password.toCharArray()) }
    }

    fun setNameForE2e(name: String) {
        handler.post { setDeviceName(name) }
    }

    fun changePasswordForE2e(current: String, new: String) {
        handler.post { changePassword(current.toCharArray(), new.toCharArray()) }
    }

    fun authenticate(password: CharArray) {
        if (password.size < 8) return fail("Пароль должен содержать не менее 8 символов")
        handler.removeCallbacks(preAuthKeepAlive)
        val verifier = deriveVerifier(password, authSalt)
        password.fill('\u0000')
        cachedVerifier = verifier
        sendAuthProof(verifier)
    }

    fun setup(deviceName: String, password: CharArray) {
        if (deviceName.isBlank()) return fail("Введите имя устройства")
        if (password.size < 8) return fail("Пароль должен содержать не менее 8 символов")
        val salt = ByteArray(16).also(random::nextBytes)
        val verifier = deriveVerifier(password, salt)
        password.fill('\u0000')
        cachedVerifier = verifier
        pendingSetupName = deviceName.trim()
        val name = pendingSetupName!!.encodeToByteArray().take(31).toByteArray()
        val payload = ByteBuffer.allocate(4 + 1 + name.size + salt.size + verifier.size)
            .order(ByteOrder.LITTLE_ENDIAN).putInt(sessionId.toInt()).put(name.size.toByte()).put(name).put(salt).put(verifier).array()
        send(DplsProtocol.Type.SETUP, payload)
    }

    fun requestMode(mode: DplsMode) {
        if (!_uiState.value.controlsEnabled) return
        _uiState.update { it.copy(pendingMode = mode) }
    }

    fun cancelMode() = _uiState.update { it.copy(pendingMode = null) }

    fun confirmMode() {
        val mode = _uiState.value.pendingMode ?: return
        val id = commandId++
        val payload = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sessionId.toInt()).put(sessionToken).putInt(id.toInt()).put(mode.wire.toByte()).array()
        _uiState.update { it.copy(commandInProgress = true, pendingMode = null, statusText = "Команда отправлена, ожидается подтверждение…") }
        updateStateRefreshSchedule()
        send(DplsProtocol.Type.MODE_SET, payload)
        handler.postDelayed({
            if (_uiState.value.commandInProgress) {
                send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
                _uiState.update { it.copy(statusText = "Проверка фактического состояния…") }
            }
        }, COMMAND_TIMEOUT_MS)
    }

    fun returnToNormal() {
        _uiState.update { it.copy(pendingMode = DplsMode.NORMAL) }
        confirmMode()
    }

    fun requestDeviceInfo() {
        if (!_uiState.value.authenticated || gatt == null) return
        send(DplsProtocol.Type.DEVICE_INFO_GET, authenticatedPayload())
    }

    /** Reset the edit-screen result state when opening/closing a settings screen. */
    fun clearSettingsOp() {
        _uiState.update { it.copy(settingsOp = SettingsOp.NONE, settingsError = null) }
    }

    fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(settingsOp = SettingsOp.FAILED, settingsError = "Введите имя устройства") }
            return
        }
        if (!_uiState.value.authenticated || gatt == null) {
            _uiState.update { it.copy(settingsOp = SettingsOp.FAILED, settingsError = "Нет соединения с устройством") }
            return
        }
        val nameBytes = trimmed.encodeToByteArray().take(31).toByteArray()
        val id = commandId++
        pendingSettingsCommandId = id
        val payload = ByteBuffer.allocate(12 + 4 + 1 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sessionId.toInt()).put(sessionToken).putInt(id.toInt())
            .put(nameBytes.size.toByte()).put(nameBytes).array()
        _uiState.update { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null) }
        send(DplsProtocol.Type.NAME_SET, payload)
    }

    fun changePassword(current: CharArray, new: CharArray) {
        if (new.size < 8) {
            current.fill(' '); new.fill(' ')
            _uiState.update { it.copy(settingsOp = SettingsOp.FAILED, settingsError = "Пароль должен содержать не менее 8 символов") }
            return
        }
        if (!_uiState.value.authenticated || gatt == null) {
            current.fill(' '); new.fill(' ')
            _uiState.update { it.copy(settingsOp = SettingsOp.FAILED, settingsError = "Нет соединения с устройством") }
            return
        }
        // Verify the current password locally against the cached verifier before
        // touching the device: the firmware replaces the verifier unconditionally,
        // so this guard prevents an accidental change from a mistyped old password.
        val cached = cachedVerifier
        val currentVerifier = deriveVerifier(current, authSalt)
        current.fill(' ')
        if (cached == null || !currentVerifier.contentEquals(cached)) {
            new.fill(' ')
            _uiState.update { it.copy(settingsOp = SettingsOp.FAILED, settingsError = "Неверный текущий пароль") }
            return
        }
        val newSalt = ByteArray(16).also(random::nextBytes)
        val newVerifier = deriveVerifier(new, newSalt)
        new.fill(' ')
        val id = commandId++
        pendingSettingsCommandId = id
        pendingNewVerifier = newVerifier
        val payload = ByteBuffer.allocate(12 + 4 + 16 + 32).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sessionId.toInt()).put(sessionToken).putInt(id.toInt())
            .put(newSalt).put(newVerifier).array()
        _uiState.update { it.copy(settingsOp = SettingsOp.IN_PROGRESS, settingsError = null) }
        send(DplsProtocol.Type.PASSWORD_SET, payload)
    }

    fun runTestModeForE2e(wire: Int) {
        val mode = DplsMode.fromWire(wire)
        if (mode == null || !mode.dangerous) {
            Log.e(TAG, "E2E mode invalid wire=$wire")
            return
        }
        handler.removeCallbacks(e2eModeRunner)
        e2eModeTarget = mode
        e2eModePhase = E2eModePhase.APPLY
        e2eModeDeadlineMs = System.currentTimeMillis() + E2E_MODE_TIMEOUT_MS
        // After the lockout restart the reconnect can leave the phase parked in
        // ERROR even though the link is authenticated and up, so controls stay
        // disabled. Poll for readiness up to the deadline and, while waiting,
        // nudge a STATE_GET once per second: a fresh STATE_REPORT drives the
        // phase back to READY deterministically instead of relying on luck.
        var lastNudgeMs = 0L
        val starter = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() > e2eModeDeadlineMs) {
                    val s = _uiState.value
                    Log.e(
                        TAG,
                        "E2E mode blocked: controls disabled " +
                            "phase=${s.phase} auth=${s.authenticated} " +
                            "cmdInProgress=${s.commandInProgress} hasState=${s.state != null}",
                    )
                    e2eModeTarget = null
                    return
                }
                val s = _uiState.value
                if (!s.controlsEnabled) {
                    val now = System.currentTimeMillis()
                    if (s.authenticated && now - lastNudgeMs >= 1_000L) {
                        lastNudgeMs = now
                        send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
                    }
                    handler.postDelayed(this, E2E_MODE_POLL_MS)
                    return
                }
                requestMode(mode)
                confirmMode()
                handler.post(e2eModeRunner)
            }
        }
        handler.post(starter)
    }

    fun exportLogCsvForE2e() {
        val records = _uiState.value.eventLog
        if (records.isEmpty()) {
            Log.e(TAG, "E2E export empty log")
            return
        }
        val content = buildString {
            appendLine("sequence;timestamp_seconds;event_type;parameter")
            records.forEach { appendLine("${it.sequence};${it.timestampSeconds};${it.type};${it.parameter}") }
        }
        File(appContext.cacheDir, "e2e-export.csv").writeText(content, Charsets.UTF_8)
        Log.i(TAG, "E2E export done records=${records.size}")
    }

    @SuppressLint("MissingPermission")
    private fun clearStalePhoneBond(address: String): Boolean {
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        if (device.bondState != BluetoothDevice.BOND_BONDED) return false
        Log.w(TAG, "Clearing stale phone bond before reconnect address=$address")
        return removeBond(device)
    }

    private fun resetBondRecoveryState() {
        bondRecoveryCount = 0
        preAuthGatt133Count = 0
    }

    @SuppressLint("MissingPermission")
    private fun tryRecoverBond(address: String): Boolean {
        if (bondRecoveryCount >= MAX_BOND_RECOVERY) return false
        bondRecoveryCount++
        preAuthGatt133Count = 0
        cancelReconnect()
        closeCurrentGatt()
        reconnectAttempt = 0
        reachedReady = false
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.RECONNECTING,
                statusText = "Повторное сопряжение…",
                error = null,
            )
        }
        Log.w(TAG, "Bond recovery attempt $bondRecoveryCount/$MAX_BOND_RECOVERY address=$address")
        scheduleBondClearedReconnect(address)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun scheduleBondClearedReconnect(address: String) {
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            clearStalePhoneBond(address)
        }
        val deadline = System.currentTimeMillis() + BOND_CLEAR_WAIT_MS
        val poller = object : Runnable {
            override fun run() {
                if (selectedAddress != address) return
                val bonded = runCatching {
                    adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED
                }.getOrDefault(false)
                if (!bonded || System.currentTimeMillis() >= deadline) {
                    connect(address)
                    return
                }
                handler.postDelayed(this, BOND_CLEAR_POLL_MS)
            }
        }
        handler.postDelayed(poller, BOND_CLEAR_POLL_MS)
    }

    private fun removeBond(device: BluetoothDevice): Boolean = try {
        device.javaClass.getMethod("removeBond").invoke(device) as Boolean
    } catch (_: ReflectiveOperationException) {
        false
    }

    @SuppressLint("MissingPermission")
    fun unpairDplsBondsForE2e() {
        handler.removeCallbacks(e2eUnpairCheck)
        val targets = adapter.bondedDevices.orEmpty().filter { device ->
            val name = device.name.orEmpty()
            name.contains("DPLS", ignoreCase = true) || name.contains("Test-DPLS", ignoreCase = true)
        }
        if (targets.isEmpty()) {
            Log.i(TAG, "E2E unpair done: none")
            return
        }
        for (device in targets) {
            val ok = removeBond(device)
            Log.i(TAG, "E2E unpair requested address=${device.address} ok=$ok")
        }
        handler.postDelayed(e2eUnpairCheck, E2E_UNPAIR_CHECK_MS)
    }

    private val e2eUnpairCheck = Runnable {
        val remaining = adapter.bondedDevices.orEmpty().count { device ->
            val name = device.name.orEmpty()
            name.contains("DPLS", ignoreCase = true) || name.contains("Test-DPLS", ignoreCase = true)
        }
        if (remaining == 0) {
            Log.i(TAG, "E2E unpair done")
        } else {
            Log.e(TAG, "E2E unpair incomplete remaining=$remaining")
        }
    }

    fun loadEventLog() {
        if (_uiState.value.logProgress != null) return
        logLoadPending = true
        handler.removeCallbacks(logLoadTimeout)
        handler.removeCallbacks(keepAlive)
        handler.removeCallbacks(stateRefresh)
        resetLogTransfer()
        _uiState.update { it.copy(logProgress = 0f, eventLog = emptyList(), error = null) }
        sendPriority(
            DplsProtocol.Type.LOG_START,
            authenticatedPayload() + ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0).array(),
            flush = true,
        )
        handler.postDelayed(logLoadTimeout, LOG_LOAD_TIMEOUT_MS)
    }

    fun refreshState() {
        if (!_uiState.value.authenticated || gatt == null || _uiState.value.logProgress != null) return
        send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
        updateStateRefreshSchedule()
    }

    private fun resetLogTransfer() {
        logBytes = byteArrayOf()
        logExpectedBytes = 0
        logExpectedEvents = 0
        logReceivedEvents = 0
        logChunkReceived = booleanArrayOf()
        logNextChunk = 0
        logInfoReceived = false
        pendingLogAckIndex = null
        logPendingChunks.clear()
    }

    fun disconnect() {
        selectedAddress = null
        cachedVerifier?.fill(0)
        cachedVerifier = null
        disconnectGatt(clearSelection = true)
        _uiState.value = DplsUiState()
    }

    fun release() {
        disconnect()
        appContext.unregisterReceiver(bluetoothReceiver)
    }

    @SuppressLint("MissingPermission")
    private fun acceptScan(result: ScanResult) {
        val record = result.scanRecord ?: return
        if (!record.serviceUuids.orEmpty().contains(ParcelUuid(SERVICE_UUID))) return
        val manufacturer = record.getManufacturerSpecificData(MANUFACTURER_ID)
        val id = manufacturer?.takeIf { it.size >= 4 }?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
        }
        val name = record.deviceName ?: result.device.name ?: id?.let { "Test-DPLS-%04X".format(it and 0xffff) } ?: "Test-DPLS"
        val discovered = DiscoveredDevice(result.device.address, name, null, id, result.rssi)
        _uiState.update { state ->
            val devices = (state.devices.filterNot { it.address == discovered.address } + discovered)
                .sortedByDescending { it.rssi }
            state.copy(devices = devices, statusText = "Найдено: ${devices.size}")
        }
        if (selectedAddress == result.device.address && _uiState.value.phase == ConnectionPhase.RECONNECTING) connect(result.device.address)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) {
            if (current !== gatt) {
                Log.i(TAG, "Ignoring stale GATT callback status=$status state=$newState")
                current.close()
                return
            }
            lastKnownBondState = current.device.bondState
            Log.i(TAG, "Connection state status=$status state=$newState bond=${current.device.bondState}")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                when (current.device.bondState) {
                    BluetoothDevice.BOND_BONDED -> beginGattNegotiation()
                    BluetoothDevice.BOND_BONDING -> {
                        _uiState.update { it.copy(phase = ConnectionPhase.PAIRING, statusText = pairingStatusText()) }
                        schedulePairingTimeout()
                    }
                    else -> {
                        _uiState.update { it.copy(phase = ConnectionPhase.PAIRING, statusText = pairingStatusText()) }
                        schedulePairingTimeout()
                        if (!current.device.createBond()) {
                            cancelPairingTimeout()
                            fail("Не удалось начать сопряжение")
                        }
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val phase = _uiState.value.phase
                val wasLoadingLog = _uiState.value.logProgress != null
                current.close()
                if (current === gatt) gatt = null
                if (selectedAddress == null) {
                    cachedVerifier?.fill(0)
                    cachedVerifier = null
                }
                if (wasLoadingLog) {
                    handler.removeCallbacks(logLoadTimeout)
                    logInfoReceived = false
                    logLoadPending = true
                    resetWriteState()
                    _uiState.update {
                        it.copy(
                            logProgress = null,
                            error = null,
                            statusText = "Восстановление связи…",
                        )
                    }
                }
                if (selectedAddress != null) {
                    if (phase == ConnectionPhase.PAIRING) {
                        _uiState.update { it.copy(statusText = pairingStatusText()) }
                    } else {
                        scheduleReconnect()
                    }
                } else {
                    _uiState.update { it.copy(phase = ConnectionPhase.IDLE) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@BleClient.gatt || _uiState.value.phase != ConnectionPhase.NEGOTIATING_MTU) return
            Log.i(TAG, "MTU changed mtu=$mtu status=$status")
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            _uiState.update { it.copy(phase = ConnectionPhase.DISCOVERING, statusText = "Подключение…") }
            if (!gatt.discoverServices()) fail("Не удалось запустить поиск BLE-службы")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@BleClient.gatt || _uiState.value.phase != ConnectionPhase.DISCOVERING) return
            Log.i(TAG, "Services discovered status=$status")
            val service = gatt.getService(SERVICE_UUID)
            rx = service?.getCharacteristic(RX_UUID)
            tx = service?.getCharacteristic(TX_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null) return fail("Служба Test-DPLS не найдена")
            _uiState.update { it.copy(phase = ConnectionPhase.SUBSCRIBING, statusText = "Подключение…") }
            if (!gatt.setCharacteristicNotification(tx, true)) return fail("Не удалось включить уведомления")
            val cccd = tx!!.getDescriptor(CCCD_UUID) ?: return fail("Дескриптор уведомлений не найден")
            val result = gatt.writeDescriptor(cccd, byteArrayOf(0x03, 0x00))
            if (result != BluetoothStatusCodes.SUCCESS) fail("Не удалось подписаться: $result")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== this@BleClient.gatt || descriptor.uuid != CCCD_UUID ||
                _uiState.value.phase != ConnectionPhase.SUBSCRIBING
            ) return
            Log.i(TAG, "CCCD written status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("Подписка на BLE-события отклонена")
            clientNonce = ByteArray(16).also(random::nextBytes)
            if (identifyAfterConnect) {
                identifyAfterConnect = false
                pendingIdentifyAck = true
                _uiState.update { it.copy(phase = ConnectionPhase.AUTHENTICATING, statusText = "Показать на объекте…") }
                send(DplsProtocol.Type.IDENTIFY_START)
            } else {
                _uiState.update { it.copy(phase = ConnectionPhase.AUTHENTICATING, statusText = "Подключение…") }
                send(DplsProtocol.Type.HELLO, clientNonce)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt !== this@BleClient.gatt || characteristic.uuid != TX_UUID) return
            Log.i(TAG, "RX indication bytes=${value.size}")
            val frame = value.copyOf()
            if (_uiState.value.logProgress != null) {
                handler.post { handleFrame(frame) }
            } else {
                handleFrame(frame)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== this@BleClient.gatt || characteristic.uuid != RX_UUID) return
            handler.post { completeCharacteristicWrite(status) }
        }
    }

    private fun completeCharacteristicWrite(status: Int) {
        Log.i(TAG, "TX write status=$status")
        writeInProgress = false
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (pendingIdentifyAck) {
                pendingIdentifyAck = false
                _uiState.update { it.copy(identifyLedLive = true) }
                Log.i(TAG, "E2E identify led live")
            }
            writeRetryCount = 0
            pendingWrite = null
            drainWriteQueue()
            flushLogAck()
            return
        }
        handleWriteFailure(status)
    }

    @SuppressLint("MissingPermission")
    private fun resumeAfterPairing() {
        cancelPairingTimeout()
        val address = selectedAddress ?: return
        val current = gatt
        if (current == null) {
            Log.i(TAG, "Re-opening GATT after pairing address=$address")
            connect(address)
            return
        }
        beginGattNegotiation()
    }

    private fun pairingStatusText(): String =
        if (_uiState.value.identifyActive) "Подтвердите сопряжение…" else "Подключение…"

    @SuppressLint("MissingPermission")
    private fun beginGattNegotiation() {
        val current = gatt ?: return
        if (_uiState.value.phase !in setOf(
                ConnectionPhase.CONNECTING,
                ConnectionPhase.PAIRING,
                ConnectionPhase.RECONNECTING,
            )
        ) return
        _uiState.update { it.copy(phase = ConnectionPhase.NEGOTIATING_MTU, statusText = "Подключение…") }
        if (!current.requestMtu(PREFERRED_MTU)) {
            negotiatedMtu = 23
            current.discoverServices()
        }
    }

    private fun handleFrame(bytes: ByteArray) {
        when (val decoded = DplsProtocol.decode(bytes)) {
            is DplsProtocol.DecodeResult.Failure -> fail(decoded.reason)
            is DplsProtocol.DecodeResult.Success -> handleMessage(decoded.frame)
        }
    }

    private fun handleMessage(frame: DplsProtocol.Frame) {
        val payload = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        when (frame.type) {
            DplsProtocol.Type.AUTH_CHALLENGE -> {
                if (frame.payload.size < 37) return fail("Повреждённый AUTH_CHALLENGE")
                preAuthGatt133Count = 0
                sessionId = payload.u32()
                payload.get(deviceNonce)
                payload.get(authSalt)
                initialized = payload.u8() != 0
                val autoAuth = initialized && cachedVerifier != null
                _uiState.update {
                    it.copy(
                        initialized = initialized,
                        credentialsReady = true,
                        awaitingUserPassword = !autoAuth,
                        statusText = if (autoAuth) "Вход…" else "Подключено",
                        setupName = it.setupName.ifBlank {
                            it.selectedDevice?.userName ?: "Test-DPLS-001"
                        },
                        setupPassword = "",
                        setupRepeatPassword = "",
                    )
                }
                schedulePreAuthKeepAlive()
                cachedVerifier?.takeIf { initialized }?.let(::sendAuthProof)
                if (!autoAuth) {
                    Log.i(TAG, if (initialized) "E2E login ready" else "E2E setup ready")
                }
            }
            DplsProtocol.Type.AUTH_RESULT -> {
                // The lockout flow (and a user who retypes fast) sends several
                // AUTH_PROOFs; the device answers each. A delayed "wrong
                // password" AUTH_RESULT from an earlier attempt can land AFTER a
                // later one succeeded — dropping the live authenticated session
                // into ERROR ("Неверный пароль" on the test screen). Once
                // authenticated, any further AUTH_RESULT is stale: ignore it.
                if (_uiState.value.authenticated) {
                    Log.w(TAG, "Ignoring stale AUTH_RESULT while authenticated")
                    return
                }
                handler.removeCallbacks(preAuthKeepAlive)
                val ok = payload.u8() == 0
                val retryAfter = payload.u16()
                if (!ok && frame.payload[0].toInt() == 3) {
                    _uiState.update {
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
                if (!ok) {
                    _uiState.update { it.copy(awaitingUserPassword = true) }
                    if (retryAfter > 0) {
                        Log.i(TAG, "E2E auth blocked seconds=$retryAfter")
                    } else {
                        Log.i(TAG, "E2E auth wrong")
                    }
                    return fail(if (retryAfter > 0) "Аутентификация заблокирована на $retryAfter с" else "Неверный пароль")
                }
                if (payload.remaining() >= 8) payload.get(sessionToken)
                _uiState.update {
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
                send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
                scheduleKeepAlive()
                Log.i(TAG, "E2E auth done")
            }
            DplsProtocol.Type.COMMAND_RESULT -> {
                if (frame.payload.size < 8) return fail("Повреждённый COMMAND_RESULT")
                payload.u32()
                val result = payload.u8()
                val mode = DplsMode.fromWire(payload.u8()) ?: DplsMode.NORMAL
                val remaining = payload.u16()
                if (result != 0) return fail(commandRejectReason(result))
                _uiState.update { it.copy(commandInProgress = false, statusText = "Команда выполнена, проверка состояния…", lastAckMillis = System.currentTimeMillis()) }
                if (_uiState.value.logProgress == null) send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
            }
            DplsProtocol.Type.DEVICE_INFO_REPORT -> parseDeviceInfo(frame.payload)
            DplsProtocol.Type.SETTINGS_RESULT -> {
                if (frame.payload.size < 5) return
                val cmdId = payload.u32()
                val status = payload.u8()
                if (pendingSettingsCommandId != cmdId) return // stale/unmatched
                pendingSettingsCommandId = null
                if (status == 0) {
                    // A password change succeeded: adopt the new verifier so the
                    // reconnect the device triggers auto-authenticates with the
                    // new password. A name change leaves the verifier untouched
                    // and we re-pull DEVICE_INFO to pick up the new name (the
                    // device sends only the one indication).
                    val wasPasswordChange = pendingNewVerifier != null
                    pendingNewVerifier?.let {
                        cachedVerifier?.fill(0)
                        cachedVerifier = it
                        pendingNewVerifier = null
                    }
                    _uiState.update { it.copy(settingsOp = SettingsOp.DONE, settingsError = null) }
                    Log.i(TAG, "E2E settings saved")
                    if (!wasPasswordChange && gatt != null) {
                        send(DplsProtocol.Type.DEVICE_INFO_GET, authenticatedPayload())
                    }
                } else {
                    pendingNewVerifier = null
                    _uiState.update {
                        it.copy(settingsOp = SettingsOp.FAILED, settingsError = "Устройство отклонило изменение (код $status)")
                    }
                    Log.i(TAG, "E2E settings rejected status=$status")
                }
            }
            DplsProtocol.Type.STATE_REPORT -> parseState(payload)
            DplsProtocol.Type.LOG_INFO -> {
                if (frame.payload.size < 10) return failLog("Повреждённый LOG_INFO")
                payload.u32()
                val totalBytes = payload.int
                val rawCount = payload.u16()
                logExpectedEvents = minOf(rawCount, totalBytes / 10, MAX_LOG_EVENTS).coerceIn(0, MAX_LOG_EVENTS)
                logExpectedBytes = logExpectedEvents * 10
                logInfoReceived = true
                logReceivedEvents = 0
                logNextChunk = 0
                if (rawCount != logExpectedEvents) {
                    Log.w(TAG, "LOG_INFO clamped events $rawCount -> $logExpectedEvents (totalBytes=$totalBytes)")
                } else {
                    Log.i(TAG, "LOG_INFO events=$logExpectedEvents bytes=$logExpectedBytes")
                }
                if (logExpectedEvents == 0) {
                    logBytes = byteArrayOf()
                    logChunkReceived = booleanArrayOf()
                    finishLog()
                    return
                }
                logBytes = ByteArray(logExpectedBytes)
                logChunkReceived = BooleanArray(logExpectedEvents)
                logPendingChunks.sortedBy { it.first }.forEach { (chunk, data) ->
                    applyLogChunk(chunk, data)
                }
                logPendingChunks.clear()
                if (logReceivedEvents < logExpectedEvents) scheduleLogAck()
            }
            DplsProtocol.Type.LOG_CHUNK -> parseLogChunk(payload)
            DplsProtocol.Type.LOG_RESULT -> finishLog()
            DplsProtocol.Type.ERROR -> {
                val code = frame.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
                if (_uiState.value.logProgress != null) failLog("Ошибка загрузки журнала: $code")
                else fail(deviceErrorReason(code))
            }
            else -> Unit
        }
    }

    private fun deviceErrorReason(code: Int): String = when (code) {
        // Code 7: the commissioning window has closed. First setup is only
        // accepted for a while after power-on, so ask the operator to power-cycle.
        7 -> "Окно первичной настройки закрыто. Выключите и включите устройство, затем повторите настройку в течение нескольких минут."
        else -> "Ошибка устройства: $code"
    }

    private fun parseDeviceInfo(raw: ByteArray) {
        if (raw.size < 12) return
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val deviceId = b.u32()
        val proto = b.u8()
        val major = b.u8(); val minor = b.u8(); val patch = b.u8()
        val hwRev = b.u8()
        val caps = b.u8()
        b.u8() // settings_state — не показываем отдельно
        val nameLen = b.u8()
        val name = if (nameLen in 1..(raw.size - 12)) String(raw, 12, nameLen, Charsets.UTF_8) else ""
        val info = DeviceInfo(
            deviceId = deviceId,
            protocolVersion = proto,
            firmwareVersion = "$major.$minor.$patch",
            hardwareRevision = hwRev,
            adcPresent = (caps and 0x01) != 0,
            hardwareReadback = (caps and 0x02) != 0,
            adcCalibrated = (caps and 0x04) != 0,
            userName = name,
        )
        _uiState.update { st ->
            st.copy(
                deviceInfo = info,
                selectedDevice = st.selectedDevice?.copy(
                    userName = name.ifBlank { st.selectedDevice.userName },
                ),
            )
        }
        Log.i(TAG, "DEVICE_INFO id=${info.shortId} fw=${info.firmwareVersion} name=$name")
    }

    private fun commandRejectReason(status: Int): String = when (status) {
        3 -> "Команда отклонена: недопустимый режим"
        4 -> "Команда отклонена: аппаратное переключение не удалось"
        5 -> "Команда отклонена: активна автоизоляция реального КЗ"
        else -> "Команда отклонена устройством: $status"
    }

    private fun parseState(payload: ByteBuffer) {
        if (payload.remaining() < 16) return fail("Повреждённый STATE_REPORT")
        val mode = DplsMode.fromWire(payload.u8()) ?: DplsMode.NORMAL
        val power = if (payload.u8() == 0) PowerSource.DPLS else PowerSource.RESERVE
        val voltage = payload.u16()
        val automaticReturn = payload.u16()
        val reserveLow = payload.u8() != 0
        val flags = payload.u8() // bit0 = connected, bit1 = real-short auto-isolation
        val realShort = (flags and 0x02) != 0
        val uptimeSeconds = payload.u32()
        val revision = payload.u32()
        // Byte 16 (validity mask) is present on firmware ≥ this build. A legacy
        // 16-byte report has no byte to read, so default every field to valid.
        val validity = if (payload.remaining() >= 1) payload.u8() else 0xff
        val bootEpoch = System.currentTimeMillis() / 1000 - uptimeSeconds
        val state = DeviceState(
            mode = mode,
            powerSource = power,
            voltageMv = voltage,
            automaticReturnSeconds = automaticReturn,
            reserveLow = reserveLow,
            realShort = realShort,
            uptimeSeconds = uptimeSeconds,
            revision = revision,
            receivedAtMillis = System.currentTimeMillis(),
            lineVoltageValid = (validity and 0x01) != 0,
            reserveValid = (validity and 0x02) != 0,
            powerValid = (validity and 0x04) != 0,
            autoIsoValid = (validity and 0x08) != 0,
            adcCalibrated = (validity and 0x10) != 0,
        )
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.READY,
                statusText = "Состояние подтверждено",
                state = state,
                deviceBootEpochSeconds = bootEpoch,
                authenticated = true,
                identifyActive = false,
                identifyLedLive = false,
                commandInProgress = false,
                staleState = false,
                lastAckMillis = System.currentTimeMillis(),
                error = null,
            )
        }
        reachedReady = true
        reconnectAttempt = 0
        resetBondRecoveryState()
        if (_uiState.value.logProgress == null) updateStateRefreshSchedule()
        // Pull identity/capabilities once per session so the Settings/About
        // screens show the device's real name, firmware and id.
        if (_uiState.value.deviceInfo == null && _uiState.value.logProgress == null) {
            send(DplsProtocol.Type.DEVICE_INFO_GET, authenticatedPayload())
        }
        if (logLoadPending) {
            handler.post { loadEventLog() }
        }
    }

    private fun parseLogChunk(payload: ByteBuffer) {
        if (payload.remaining() < 12) return
        val chunk = payload.u16()
        val data = ByteArray(10)
        payload.get(data)
        if (!logInfoReceived) {
            logPendingChunks.removeAll { it.first == chunk }
            logPendingChunks.add(chunk to data)
            return
        }
        applyLogChunk(chunk, data)
    }

    private fun applyLogChunk(chunk: Int, data: ByteArray) {
        if (logExpectedEvents == 0) return
        if (chunk >= logExpectedEvents) {
            Log.w(TAG, "LOG_CHUNK out of range chunk=$chunk expected=$logExpectedEvents")
            if (logReceivedEvents >= logExpectedEvents) finishLog()
            return
        }
        if (!logChunkReceived[chunk]) {
            System.arraycopy(data, 0, logBytes, chunk * 10, 10)
            logChunkReceived[chunk] = true
            logReceivedEvents++
            val progress = logReceivedEvents.toFloat() / logExpectedEvents.toFloat()
            _uiState.update { it.copy(logProgress = progress.coerceIn(0.05f, 1f)) }
            Log.i(TAG, "LOG_CHUNK $chunk ok received=$logReceivedEvents/$logExpectedEvents")
        }
        if (logReceivedEvents >= logExpectedEvents) {
            finishLog()
            return
        }
        scheduleLogAck()
    }

    private fun scheduleLogAck() {
        val next = logChunkReceived.indices.firstOrNull { !logChunkReceived[it] } ?: logExpectedEvents
        pendingLogAckIndex = next
        handler.removeCallbacks(flushLogAckRunnable)
        handler.postDelayed(flushLogAckRunnable, LOG_ACK_DELAY_MS)
    }

    private val flushLogAckRunnable = Runnable { flushLogAck() }

    private fun flushLogAck() {
        if (_uiState.value.logProgress == null) return
        if (writeInProgress) {
            handler.postDelayed(flushLogAckRunnable, LOG_ACK_DELAY_MS)
            return
        }
        val index = pendingLogAckIndex ?: return
        pendingLogAckIndex = null
        logNextChunk = index
        val chunk = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(index.toShort()).array()
        val bytes = DplsProtocol.encode(
            DplsProtocol.Frame(DplsProtocol.Type.LOG_ACK, nextSequence(), payload = authenticatedPayload() + chunk),
        )
        writeQueue.clear()
        writeQueue.addLast(bytes)
        drainWriteQueue()
    }

    private fun finishLog() {
        handler.removeCallbacks(logLoadTimeout)
        handler.removeCallbacks(flushLogAckRunnable)
        logInfoReceived = false
        val records = logBytes.asList().chunked(10).mapNotNull { raw ->
            if (raw.size != 10) null else ByteBuffer.wrap(raw.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).let {
                EventRecord(it.u32(), it.u32(), it.u8(), it.u8())
            }
        }.sortedWith(
                compareByDescending<EventRecord> { it.timestampSeconds }
                    .thenByDescending { it.sequence },
            )
        val rawCount = if (logExpectedBytes == 0) 0 else logBytes.size / 10
        Log.i(TAG, "LOG_DONE raw=$rawCount records=${records.size}")
        if (records.isNotEmpty()) {
            val seqMin = records.minOf { it.sequence }
            val seqMax = records.maxOf { it.sequence }
            Log.i(TAG, "E2E journal ready records=${records.size} seq_min=$seqMin seq_max=$seqMax")
        }
        logLoadPending = false
        _uiState.update { it.copy(eventLog = records, logProgress = null, statusText = "Журнал загружен: ${records.size} записей", error = null) }
        scheduleKeepAlive()
        updateStateRefreshSchedule()
    }

    private val logLoadTimeout = Runnable {
        if (_uiState.value.logProgress != null) {
            logInfoReceived = false
            logLoadPending = false
            _uiState.update { it.copy(logProgress = null, error = "Не удалось загрузить журнал") }
            scheduleKeepAlive()
            updateStateRefreshSchedule()
        }
    }

    private fun sendAuthProof(verifier: ByteArray) {
        val signed = deviceNonce + clientNonce + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sessionId.toInt()).array()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(verifier, "HmacSHA256")) }.doFinal(signed)
        send(DplsProtocol.Type.AUTH_PROOF, clientNonce + mac)
    }

    private fun deriveVerifier(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { spec.clearPassword() }
    }

    private fun authenticatedPayload(): ByteArray = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(sessionId.toInt()).put(sessionToken).array()

    @SuppressLint("MissingPermission")
    private fun send(type: DplsProtocol.Type, payload: ByteArray = byteArrayOf()) {
        enqueueWrite(DplsProtocol.encode(DplsProtocol.Frame(type, nextSequence(), payload = payload)))
    }

    private fun sendPriority(type: DplsProtocol.Type, payload: ByteArray = byteArrayOf(), flush: Boolean = false) {
        handler.post {
            if (flush) resetWriteState()
            if (payload.size > negotiatedMtu - 3) {
                fail("Кадр ${payload.size} байт не помещается в MTU $negotiatedMtu")
                return@post
            }
            val bytes = DplsProtocol.encode(DplsProtocol.Frame(type, nextSequence(), payload = payload))
            writeQueue.addFirst(bytes)
            drainWriteQueue()
        }
    }

    private fun enqueueWrite(bytes: ByteArray, front: Boolean = false) {
        if (bytes.size > negotiatedMtu - 3) {
            fail("Кадр ${bytes.size} байт не помещается в MTU $negotiatedMtu")
            return
        }
        handler.post {
            if (_uiState.value.logProgress != null) return@post
            if (front) writeQueue.addFirst(bytes) else writeQueue.addLast(bytes)
            drainWriteQueue()
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInProgress) return
        val current = gatt ?: return
        val characteristic = rx ?: return
        val bytes = writeQueue.removeFirstOrNull() ?: return
        writeInProgress = true
        pendingWrite = bytes
        val result = current.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (result != BluetoothStatusCodes.SUCCESS) {
            writeInProgress = false
            handleWriteFailure(result)
        }
    }

    private fun handleWriteFailure(status: Int) {
        val bytes = pendingWrite
        pendingWrite = null
        val state = _uiState.value
        val preAuth = state.credentialsReady && !state.authenticated
        val identifying = state.identifyActive
        val transient = isTransientWriteStatus(status)

        if (bytes != null && transient && writeRetryCount < MAX_WRITE_RETRIES) {
            writeRetryCount++
            Log.w(TAG, "TX write retry $writeRetryCount/$MAX_WRITE_RETRIES after status=$status")
            writeQueue.addFirst(bytes)
            handler.postDelayed({ drainWriteQueue() }, 150L * writeRetryCount)
            return
        }
        writeRetryCount = 0

        if (preAuth || identifying || state.phase == ConnectionPhase.RECONNECTING) {
            if (status == 133 && !reachedReady) {
                preAuthGatt133Count++
                Log.w(TAG, "TX write status=133 during pre-auth count=$preAuthGatt133Count")
                if (preAuthGatt133Count >= PRE_AUTH_GATT133_RECOVERY_THRESHOLD) {
                    selectedAddress?.let { tryRecoverBond(it) }
                    return
                }
            }
            Log.w(TAG, "TX write status=$status ignored during pre-auth/identify/reconnect")
            if (bytes != null) writeQueue.addLast(bytes)
            return
        }

        if (status == 133) {
            Log.w(TAG, "TX write status=133; waiting for disconnect/reconnect")
            if (state.logProgress != null && bytes != null) {
                pendingLogAckIndex = logNextChunk
                handler.postDelayed(flushLogAckRunnable, 250L)
            }
            return
        }

        // A post-auth write can fail with a hard status while the link is still
        // settling right after a reconnect (the churny re-pairing path makes
        // this intermittent). Terminal fail() dead-ended an otherwise
        // recoverable session in ERROR — controls greyed out with no way back.
        // Drop the link instead and let the normal reconnect + cached-verifier
        // re-auth restore the session.
        if (reachedReady) {
            Log.w(TAG, "Post-auth write failed status=$status; reconnecting")
            gatt?.disconnect()
            return
        }

        fail("Ошибка передачи BLE: $status")
    }

    private fun isTransientWriteStatus(status: Int): Boolean =
        status in TRANSIENT_WRITE_STATUSES

    private fun resetWriteState() {
        writeQueue.clear()
        writeInProgress = false
        pendingWrite = null
        writeRetryCount = 0
    }

    private fun nextSequence(): Int = sequence.also { sequence = (sequence + 1) and 0xffff }

    private fun schedulePreAuthKeepAlive() {
        handler.removeCallbacks(preAuthKeepAlive)
        handler.postDelayed(preAuthKeepAlive, PRE_AUTH_KEEP_ALIVE_MS)
    }

    private val preAuthKeepAlive = object : Runnable {
        override fun run() {
            val state = _uiState.value
            if (state.identifyActive) {
                handler.postDelayed(this, PRE_AUTH_KEEP_ALIVE_MS)
                return
            }
            if (state.credentialsReady && !state.authenticated && gatt != null) {
                send(DplsProtocol.Type.KEEP_ALIVE)
                handler.postDelayed(this, PRE_AUTH_KEEP_ALIVE_MS)
            }
        }
    }

    private fun scheduleKeepAlive() {
        handler.removeCallbacks(keepAlive)
        handler.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }

    private fun updateStateRefreshSchedule() {
        handler.removeCallbacks(stateRefresh)
        if (_uiState.value.logProgress != null) return
        val state = _uiState.value
        val mode = state.state?.mode
        if (state.authenticated && !state.commandInProgress && mode != null && mode != DplsMode.NORMAL && gatt != null) {
            handler.postDelayed(stateRefresh, STATE_REFRESH_MS)
        }
    }

    private val stateRefresh = object : Runnable {
        override fun run() {
            val current = _uiState.value
            if (current.logProgress != null) return
            if (current.authenticated && current.state?.mode != DplsMode.NORMAL && gatt != null) {
                send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
                handler.postDelayed(this, STATE_REFRESH_MS)
            }
        }
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            if (_uiState.value.authenticated && gatt != null && _uiState.value.logProgress == null) {
                send(DplsProtocol.Type.KEEP_ALIVE, authenticatedPayload())
            }
            if (_uiState.value.authenticated && gatt != null) {
                handler.postDelayed(this, KEEP_ALIVE_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothOff() {
        handler.removeCallbacks(preAuthKeepAlive)
        handler.removeCallbacks(keepAlive)
        handler.removeCallbacks(stateRefresh)
        cancelPairingTimeout()
        cancelReconnect()
        closeCurrentGatt()
        scanning = false
        rx = null
        tx = null
        resetWriteState()
        sessionToken.fill(0)
        cachedVerifier?.fill(0)
        cachedVerifier = null
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.RECONNECTING,
                statusText = "Bluetooth выключен",
                credentialsReady = false,
                authenticated = false,
                staleState = it.state != null,
                commandInProgress = false,
                error = null,
            )
        }
    }

    private fun scheduleReconnect() {
        if (reconnectRunnable != null) return
        handler.removeCallbacks(preAuthKeepAlive)
        rx = null
        tx = null
        resetWriteState()
        sessionToken.fill(0)
        val lastState = _uiState.value.state
        if (!reachedReady && reconnectAttempt >= MAX_INITIAL_RECONNECT_ATTEMPTS) {
            val address = selectedAddress
            if (address != null && tryRecoverBond(address)) {
                return
            }
            fail(
                if (bondRecoveryCount >= MAX_BOND_RECOVERY)
                    "Не удалось восстановить BLE-связку. Удерживайте кнопку сброса на устройстве 5 с и подключитесь снова."
                else if (lastKnownBondState == BluetoothDevice.BOND_BONDED)
                    "Связка Bluetooth рассинхронизирована. Нажмите «Повторить сопряжение»."
                else
                    "Не удалось установить устойчивое BLE-соединение",
            )
            return
        }
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.RECONNECTING,
                statusText = when {
                    logLoadPending -> "Восстановление связи…"
                    reachedReady -> "Восстановление связи…"
                    else -> "Подключение…"
                },
                staleState = lastState != null,
                credentialsReady = cachedVerifier != null,
                authenticated = false,
            )
        }
        val address = selectedAddress ?: return
        val delays = longArrayOf(500, 1000, 2000, 4000, 5000)
        val delay = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt++
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            if (selectedAddress == address) connect(address)
        }.also { handler.postDelayed(it, delay) }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt(clearSelection: Boolean) {
        stopScan()
        handler.removeCallbacks(preAuthKeepAlive)
        handler.removeCallbacks(keepAlive)
        handler.removeCallbacks(stateRefresh)
        cancelPairingTimeout()
        cancelReconnect()
        closeCurrentGatt()
        rx = null
        tx = null
        resetWriteState()
        reachedReady = false
        reconnectAttempt = 0
        if (clearSelection) resetBondRecoveryState()
        if (clearSelection) selectedAddress = null
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt() {
        val current = gatt
        gatt = null
        current?.disconnect()
        current?.close()
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun schedulePairingTimeout() {
        cancelPairingTimeout()
        pairingPollRunnable = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                if (_uiState.value.phase != ConnectionPhase.PAIRING) return
                if (gatt?.device?.bondState == BluetoothDevice.BOND_BONDED) {
                    resumeAfterPairing()
                    return
                }
                handler.postDelayed(this, PAIRING_POLL_MS)
            }
        }.also { handler.postDelayed(it, PAIRING_POLL_MS) }
        pairingTimeoutRunnable = Runnable {
            pairingTimeoutRunnable = null
            if (_uiState.value.phase == ConnectionPhase.PAIRING) {
                fail("Сопряжение не подтверждено. Повторите попытку и нажмите «Сопряжение» в системном окне")
                closeCurrentGatt()
            }
        }.also { handler.postDelayed(it, PAIRING_TIMEOUT_MS) }
    }

    private fun cancelPairingTimeout() {
        pairingTimeoutRunnable?.let(handler::removeCallbacks)
        pairingTimeoutRunnable = null
        pairingPollRunnable?.let(handler::removeCallbacks)
        pairingPollRunnable = null
    }

    private fun failLog(message: String) {
        Log.e(TAG, message)
        handler.removeCallbacks(logLoadTimeout)
        logInfoReceived = false
        logLoadPending = false
        _uiState.update { it.copy(logProgress = null, error = message) }
        scheduleKeepAlive()
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        handler.removeCallbacks(logLoadTimeout)
        logInfoReceived = false
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.ERROR,
                statusText = message,
                error = message,
                commandInProgress = false,
                logProgress = null,
            )
        }
    }

    companion object {
        private const val TAG = "TestDplsBle"
        private const val SCAN_DURATION_MS = 20_000L
        private const val PAIRING_TIMEOUT_MS = 45_000L
        private const val PAIRING_POLL_MS = 250L
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val E2E_MODE_TIMEOUT_MS = 30_000L
        private const val E2E_MODE_POLL_MS = 40L
        private const val E2E_UNPAIR_CHECK_MS = 1_500L
        private const val PRE_AUTH_KEEP_ALIVE_MS = 3_000L
        private const val KEEP_ALIVE_MS = 3_000L
        private const val STATE_REFRESH_MS = 1_000L
        /* One indication per LOG_ACK ≈ 0.45 s/record on the stock connection
         * interval, so a full 200-record journal takes ~95 s end to end. */
        private const val LOG_LOAD_TIMEOUT_MS = 240_000L
        private const val LOG_ACK_DELAY_MS = 20L
        private const val MAX_LOG_EVENTS = 200
        private const val MAX_WRITE_RETRIES = 3
        private const val MAX_INITIAL_RECONNECT_ATTEMPTS = 3
        private const val MAX_BOND_RECOVERY = 3
        private const val PRE_AUTH_GATT133_RECOVERY_THRESHOLD = 2
        private const val BOND_CLEAR_WAIT_MS = 6_000L
        private const val BOND_CLEAR_POLL_MS = 200L
        // 17 = ATT_ERR_INSUFFICIENT_RESOURCES: the device's RX queue is full and
        // NAK'd the write on purpose — retry rather than fail the frame.
        private val TRANSIENT_WRITE_STATUSES = setOf(8, 14, 17, 143, 201)
        private const val PBKDF2_ITERATIONS = 10_000
        private const val PREFERRED_MTU = 247
        private const val MANUFACTURER_ID = 0x0B01
        val SERVICE_UUID: UUID = UUID.fromString("7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001")
        val RX_UUID: UUID = UUID.fromString("7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001")
        val TX_UUID: UUID = UUID.fromString("7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
