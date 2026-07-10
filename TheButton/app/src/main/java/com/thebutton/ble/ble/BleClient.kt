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
    private var initialized = false
    private var logBytes = ByteArray(0)
    private var logExpectedBytes = 0
    private var logNextChunk = 0
    private var identifyAfterConnect = false
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private var reachedReady = false
    private var lastKnownBondState = BluetoothDevice.BOND_NONE
    private var reconnectRunnable: Runnable? = null

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
                            lastKnownBondState = BluetoothDevice.BOND_BONDED
                            if (_uiState.value.phase == ConnectionPhase.PAIRING) beginGattNegotiation()
                        }
                        BluetoothDevice.BOND_NONE -> fail("Не удалось создать защищённое BLE-соединение")
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
            Context.RECEIVER_NOT_EXPORTED,
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
                error = null,
            )
        }
        closeCurrentGatt()
        val connection = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gatt = connection
        Log.i(TAG, "connectGatt address=$address attempt=$reconnectAttempt")
    }

    fun identify(address: String) {
        identifyAfterConnect = true
        connect(address)
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
        val payload = ByteBuffer.allocate(1 + name.size + salt.size + verifier.size)
            .order(ByteOrder.LITTLE_ENDIAN).put(name.size.toByte()).put(name).put(salt).put(verifier).array()
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

    fun loadEventLog() {
        logBytes = byteArrayOf()
        logExpectedBytes = 0
        logNextChunk = 0
        _uiState.update { it.copy(logProgress = 0f, eventLog = emptyList()) }
        send(DplsProtocol.Type.LOG_START, authenticatedPayload() + ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0).array())
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
                if (current.device.bondState == BluetoothDevice.BOND_BONDED) beginGattNegotiation()
                else {
                    _uiState.update { it.copy(phase = ConnectionPhase.PAIRING, statusText = "Подключение…") }
                    if (!current.device.createBond()) fail("Не удалось начать сопряжение")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                current.close()
                if (current === gatt) gatt = null
                cachedVerifier?.fill(0)
                cachedVerifier = null
                if (selectedAddress != null) scheduleReconnect() else _uiState.update { it.copy(phase = ConnectionPhase.IDLE) }
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
            _uiState.update { it.copy(phase = ConnectionPhase.AUTHENTICATING, statusText = "Подключение…") }
            send(DplsProtocol.Type.HELLO, clientNonce)
            if (identifyAfterConnect) {
                identifyAfterConnect = false
                send(DplsProtocol.Type.IDENTIFY_START, byteArrayOf(60))
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt === this@BleClient.gatt && characteristic.uuid == TX_UUID) {
                Log.i(TAG, "RX indication bytes=${value.size}")
                handleFrame(value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== this@BleClient.gatt || characteristic.uuid != RX_UUID) return
            Log.i(TAG, "TX write status=$status")
            writeInProgress = false
            if (status != BluetoothGatt.GATT_SUCCESS) fail("Ошибка передачи BLE: $status") else drainWriteQueue()
        }
    }

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
                sessionId = payload.u32()
                payload.get(deviceNonce)
                payload.get(authSalt)
                initialized = payload.u8() != 0
                _uiState.update { it.copy(initialized = initialized, credentialsReady = true, statusText = "Подключено") }
                schedulePreAuthKeepAlive()
                cachedVerifier?.takeIf { initialized }?.let(::sendAuthProof)
            }
            DplsProtocol.Type.AUTH_RESULT -> {
                handler.removeCallbacks(preAuthKeepAlive)
                val ok = payload.u8() == 0
                val retryAfter = payload.u16()
                if (!ok) return fail(if (retryAfter > 0) "Аутентификация заблокирована на $retryAfter с" else "Неверный пароль")
                if (payload.remaining() >= 8) payload.get(sessionToken)
                _uiState.update { it.copy(authenticated = true, phase = ConnectionPhase.SYNCHRONIZING, statusText = "Чтение состояния…", error = null) }
                send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
                scheduleKeepAlive()
            }
            DplsProtocol.Type.COMMAND_RESULT -> {
                if (frame.payload.size < 8) return fail("Повреждённый COMMAND_RESULT")
                payload.u32()
                val result = payload.u8()
                val mode = DplsMode.fromWire(payload.u8()) ?: DplsMode.NORMAL
                val remaining = payload.u16()
                if (result != 0) return fail("Команда отклонена устройством: $result")
                _uiState.update { it.copy(commandInProgress = false, statusText = "Команда выполнена, проверка состояния…", lastAckMillis = System.currentTimeMillis()) }
                send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
            }
            DplsProtocol.Type.STATE_REPORT -> parseState(payload)
            DplsProtocol.Type.LOG_INFO -> {
                if (frame.payload.size < 10) return fail("Повреждённый LOG_INFO")
                payload.u32()
                logExpectedBytes = payload.int
                payload.u16()
                logBytes = byteArrayOf()
                logNextChunk = 0
            }
            DplsProtocol.Type.LOG_CHUNK -> parseLogChunk(payload)
            DplsProtocol.Type.LOG_RESULT -> finishLog()
            DplsProtocol.Type.ERROR -> fail("Ошибка устройства: ${frame.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0}")
            else -> Unit
        }
    }

    private fun parseState(payload: ByteBuffer) {
        if (payload.remaining() < 16) return fail("Повреждённый STATE_REPORT")
        val mode = DplsMode.fromWire(payload.u8()) ?: DplsMode.NORMAL
        val power = if (payload.u8() == 0) PowerSource.DPLS else PowerSource.RESERVE
        val voltage = payload.u16()
        val automaticReturn = payload.u16()
        val reserveLow = payload.u8() != 0
        payload.u8() // state flags; reserved for hardware-confirmation bits
        val state = DeviceState(
            mode = mode,
            powerSource = power,
            voltageMv = voltage,
            automaticReturnSeconds = automaticReturn,
            reserveLow = reserveLow,
            uptimeSeconds = payload.u32(),
            revision = payload.u32(),
        )
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.READY,
                statusText = "Состояние подтверждено",
                state = state,
                authenticated = true,
                commandInProgress = false,
                staleState = false,
                lastAckMillis = System.currentTimeMillis(),
                error = null,
            )
        }
        reachedReady = true
        reconnectAttempt = 0
    }

    private fun parseLogChunk(payload: ByteBuffer) {
        if (payload.remaining() < 2) return
        val chunk = payload.u16()
        if (chunk != logNextChunk) return sendLogAck()
        val data = ByteArray(payload.remaining()).also(payload::get)
        logBytes += data
        logNextChunk++
        _uiState.update { it.copy(logProgress = if (logExpectedBytes == 0) 0f else (logBytes.size.toFloat() / logExpectedBytes).coerceAtMost(1f)) }
        sendLogAck()
    }

    private fun sendLogAck() {
        val chunk = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(logNextChunk.toShort()).array()
        send(DplsProtocol.Type.LOG_ACK, authenticatedPayload() + chunk)
    }

    private fun finishLog() {
        val records = logBytes.asList().chunked(10).mapNotNull { raw ->
            if (raw.size != 10) null else ByteBuffer.wrap(raw.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).let {
                EventRecord(it.u32(), it.u32(), it.u8(), it.u8())
            }
        }
        _uiState.update { it.copy(eventLog = records, logProgress = null, statusText = "Журнал загружен: ${records.size} записей") }
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
        val bytes = DplsProtocol.encode(DplsProtocol.Frame(type, nextSequence(), payload = payload))
        if (bytes.size > negotiatedMtu - 3) return fail("Кадр ${bytes.size} байт не помещается в MTU $negotiatedMtu")
        writeQueue.addLast(bytes)
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInProgress) return
        val current = gatt ?: return
        val characteristic = rx ?: return
        val bytes = writeQueue.removeFirstOrNull() ?: return
        writeInProgress = true
        val result = current.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (result != BluetoothStatusCodes.SUCCESS) {
            writeInProgress = false
            fail("BLE write не запущен: $result")
        }
    }

    private fun nextSequence(): Int = sequence.also { sequence = (sequence + 1) and 0xffff }

    private fun schedulePreAuthKeepAlive() {
        handler.removeCallbacks(preAuthKeepAlive)
        handler.postDelayed(preAuthKeepAlive, PRE_AUTH_KEEP_ALIVE_MS)
    }

    private val preAuthKeepAlive = object : Runnable {
        override fun run() {
            if (_uiState.value.credentialsReady && !_uiState.value.authenticated && gatt != null) {
                send(DplsProtocol.Type.KEEP_ALIVE)
                handler.postDelayed(this, PRE_AUTH_KEEP_ALIVE_MS)
            }
        }
    }

    private fun scheduleKeepAlive() {
        handler.removeCallbacks(keepAlive)
        handler.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            if (_uiState.value.authenticated && gatt != null) {
                send(DplsProtocol.Type.KEEP_ALIVE, authenticatedPayload())
                handler.postDelayed(this, KEEP_ALIVE_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothOff() {
        handler.removeCallbacks(preAuthKeepAlive)
        handler.removeCallbacks(keepAlive)
        cancelReconnect()
        closeCurrentGatt()
        scanning = false
        rx = null
        tx = null
        writeQueue.clear()
        writeInProgress = false
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
        writeQueue.clear()
        writeInProgress = false
        sessionToken.fill(0)
        val lastState = _uiState.value.state
        if (!reachedReady && reconnectAttempt >= MAX_INITIAL_RECONNECT_ATTEMPTS) {
            fail(
                if (lastKnownBondState == BluetoothDevice.BOND_BONDED)
                    "Ключи BLE устарели. Удалите сопряжение Test-DPLS в настройках Bluetooth и подключитесь снова."
                else
                    "Не удалось установить устойчивое BLE-соединение",
            )
            return
        }
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.RECONNECTING,
                statusText = if (reachedReady) "Восстановление связи…" else "Подключение…",
                staleState = lastState != null,
                credentialsReady = false,
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
        cancelReconnect()
        closeCurrentGatt()
        rx = null
        tx = null
        reachedReady = false
        reconnectAttempt = 0
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

    private fun fail(message: String) {
        Log.e(TAG, message)
        _uiState.update { it.copy(phase = ConnectionPhase.ERROR, statusText = message, error = message, commandInProgress = false) }
    }

    companion object {
        private const val TAG = "TestDplsBle"
        private const val SCAN_DURATION_MS = 20_000L
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val PRE_AUTH_KEEP_ALIVE_MS = 3_000L
        private const val KEEP_ALIVE_MS = 3_000L
        private const val MAX_INITIAL_RECONNECT_ATTEMPTS = 3
        private const val PBKDF2_ITERATIONS = 10_000
        private const val PREFERRED_MTU = 247
        private const val MANUFACTURER_ID = 0x0B01
        val SERVICE_UUID: UUID = UUID.fromString("7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001")
        val RX_UUID: UUID = UUID.fromString("7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001")
        val TX_UUID: UUID = UUID.fromString("7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
