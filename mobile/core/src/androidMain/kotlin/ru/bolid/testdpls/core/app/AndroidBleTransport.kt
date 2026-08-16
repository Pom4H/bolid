package ru.bolid.testdpls.core.app

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
import java.util.UUID

/**
 * Android-only BluetoothGatt adapter.
 *
 * All mutable GATT state is confined to [handler]'s main looper. Product
 * semantics live in common DplsClient; this class only serializes Android BLE.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport(context: Context) : DplsTransport {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var listener: DplsTransportListener? = null

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var selectedAddress: String? = null
    private var negotiatedMtu = 23
    private var pairing = false
    private var subscribed = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private var pendingWrite: ByteArray? = null
    private var writeRetryCount = 0
    private var pairingTimeout: Runnable? = null
    private var pairingPoll: Runnable? = null
    private var closingGatt: BluetoothGatt? = null
    private var closeTimeout: Runnable? = null
    private var reopenRunnable: Runnable? = null
    private var cccdRetry: Runnable? = null
    private var connectAttempts = 0
    private var cccdRetryCount = 0
    private var suppressDisconnectEvent = false
    private var pairingFailed = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = acceptScan(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) =
            results.forEach(::acceptScan)

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                listener?.onTransportError("Ошибка BLE-сканирования: $errorCode")
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    ) ?: return
                    if (device.address != selectedAddress) return
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            pairing = false
                            cancelPairingTimeout()
                            if (gatt != null) {
                                beginGattNegotiation()
                            } else {
                                Log.i(TAG, "bond complete, reopening GATT")
                                scheduleOpenGatt(REOPEN_DELAY_MS)
                            }
                        }
                        BluetoothDevice.BOND_NONE -> if (pairing && !pairingFailed) {
                            failPairingNotConfirmed()
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                ) {
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF,
                    -> {
                        suppressDisconnectEvent = true
                        releaseGatt()
                        rx = null
                        tx = null
                        subscribed = false
                        resetWrites()
                        emit { onBluetoothUnavailable() }
                    }
                    BluetoothAdapter.STATE_ON -> emit { onBluetoothAvailable() }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun setListener(listener: DplsTransportListener) {
        this.listener = listener
    }

    private fun emit(block: DplsTransportListener.() -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener?.block()
        } else {
            handler.post { listener?.block() }
        }
    }

    @SuppressLint("MissingPermission")
    override fun startScan(): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: return false
        if (!adapter.isEnabled) return false
        stopScan()
        scanning = true
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
        return true
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        if (!scanning) return
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    @SuppressLint("MissingPermission")
    override fun connect(address: String): Boolean {
        runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        stopScan()
        handler.post { connectOnMain(address) }
        return true
    }

    override fun reconnect(): Boolean = selectedAddress?.let(::connect) ?: false

    override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean): Boolean {
        if (bytes.size > negotiatedMtu - ATT_HEADER_BYTES) return false
        handler.post {
            // Drop queued writes only. An in-flight GATT write must finish first.
            if (flush) writeQueue.clear()
            if (priority) writeQueue.addFirst(bytes.copyOf()) else writeQueue.addLast(bytes.copyOf())
            drainWriteQueue()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    override fun readRssi(): Boolean {
        val current = gatt ?: return false
        handler.post {
            if (gatt !== current) return@post
            current.readRemoteRssi()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    override fun disconnect(clearSelection: Boolean) {
        stopScan()
        handler.post {
            cancelPairingTimeout()
            cancelReopen()
            connectAttempts = 0
            suppressDisconnectEvent = true
            releaseGatt()
            rx = null
            tx = null
            pairing = false
            subscribed = false
            resetWrites()
            if (clearSelection) selectedAddress = null
        }
    }

    override fun hasConnection(): Boolean = gatt != null && rx != null && subscribed

    override fun close() {
        disconnect(clearSelection = true)
        listener = null
        runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
    }

    @SuppressLint("MissingPermission")
    fun unpairDplsBondsForE2e() {
        adapter.bondedDevices.orEmpty()
            .filter { device -> device.name.orEmpty().contains("DPLS", ignoreCase = true) }
            .forEach(::removeBond)
    }

    @SuppressLint("MissingPermission")
    private fun acceptScan(result: ScanResult) {
        val record = result.scanRecord ?: return
        if (!record.serviceUuids.orEmpty().contains(ParcelUuid(SERVICE_UUID))) return
        emit {
            onDiscovered(
                DplsBle.discovered(
                    address = result.device.address,
                    advertisedName = record.deviceName,
                    peripheralName = result.device.name,
                    manufacturerPayload = record.getManufacturerSpecificData(DplsBle.MANUFACTURER_ID),
                    manufacturerIncludesCompanyId = false,
                    rssi = result.rssi,
                ),
            )
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "gatt state=$newState status=$status pairing=$pairing")
            if (current === closingGatt) {
                finishClosed(current)
                return
            }
            if (current !== gatt) {
                current.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectAttempts = 0
                suppressDisconnectEvent = false
                emit { onConnected() }
                when (current.device.bondState) {
                    BluetoothDevice.BOND_BONDED -> beginGattNegotiation()
                    BluetoothDevice.BOND_BONDING -> {
                        pairing = true
                        schedulePairingTimeout()
                    }
                    else -> {
                        pairing = true
                        schedulePairingTimeout()
                        if (!current.device.createBond()) {
                            pairing = false
                            cancelPairingTimeout()
                            emit { onTransportError("Не удалось начать сопряжение") }
                        }
                    }
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val bonded = current.device.bondState == BluetoothDevice.BOND_BONDED
                val wasPairing = pairing
                val wasSubscribed = subscribed
                current.close()
                if (gatt === current) gatt = null
                rx = null
                tx = null
                subscribed = false
                resetWrites()
                cancelCccdRetry()
                if (closingGatt === current) {
                    finishClosed(current)
                    return
                }

                if (!wasSubscribed && status == GATT_CONN_TERMINATE_PEER) {
                    failPairingNotConfirmed()
                    return
                }

                if (wasPairing && status in PAIRING_DISCONNECT_STATUSES) {
                    Log.i(TAG, "pairing disconnect status=$status bonded=$bonded")
                    if (bonded) {
                        pairing = false
                        cancelPairingTimeout()
                        scheduleOpenGatt(REOPEN_DELAY_MS)
                    }
                    return
                }

                pairing = false
                cancelPairingTimeout()
                if (suppressDisconnectEvent) {
                    suppressDisconnectEvent = false
                    return
                }
                if (status in TRANSIENT_CONNECT_STATUSES && connectAttempts < MAX_CONNECT_ATTEMPTS) {
                    Log.i(TAG, "retry connect after GATT $status attempt=$connectAttempts")
                    scheduleOpenGatt(REOPEN_DELAY_MS)
                    return
                }
                if (status == GATT_ERROR && bonded) {
                    emit { onStaleBond() }
                    return
                }
                emit {
                    onDisconnected(
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            null
                        } else {
                            "Не удалось подключиться по Bluetooth (GATT $status)"
                        },
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@AndroidBleTransport.gatt) return
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            if (!gatt.discoverServices()) {
                emit { onTransportError("Не удалось запустить поиск BLE-службы") }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@AndroidBleTransport.gatt) return
            val service = gatt.getService(SERVICE_UUID)
            rx = service?.getCharacteristic(RX_UUID)
            tx = service?.getCharacteristic(TX_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null) {
                emit { onTransportError("Служба Test-DPLS не найдена") }
                return
            }
            writeCccd()
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt !== this@AndroidBleTransport.gatt || descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cccdRetryCount = 0
                subscribed = true
                emit { onSubscribed((negotiatedMtu - ATT_HEADER_BYTES).coerceAtLeast(20)) }
                return
            }
            Log.i(TAG, "CCCD write status=$status")
            if (status in CCCD_AUTH_STATUSES) {
                pairing = true
                schedulePairingTimeout()
                if (gatt.device.bondState == BluetoothDevice.BOND_NONE && !gatt.device.createBond()) {
                    failPairingNotConfirmed()
                    return
                }
                scheduleCccdRetry()
                return
            }
            if (status in TRANSIENT_CCCD_STATUSES && cccdRetryCount < MAX_CCCD_RETRIES) {
                scheduleCccdRetry()
                return
            }
            retryLinkOrFail("Не удалось подписаться на события платы")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== this@AndroidBleTransport.gatt || characteristic.uuid != TX_UUID) return
            emit { onBytes(value.copyOf()) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== this@AndroidBleTransport.gatt || characteristic.uuid != RX_UUID) return
            completeWrite(status)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (gatt !== this@AndroidBleTransport.gatt || status != BluetoothGatt.GATT_SUCCESS) return
            emit { onRssi(rssi) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginGattNegotiation() {
        val current = gatt ?: return
        pairing = false
        cancelPairingTimeout()
        handler.postDelayed(
            {
                if (gatt !== current) return@postDelayed
                if (!current.requestMtu(PREFERRED_MTU)) {
                    negotiatedMtu = 23
                    if (!current.discoverServices()) {
                        emit { onTransportError("Не удалось запустить поиск BLE-службы") }
                    }
                }
            },
            POST_BOND_SETTLE_MS,
        )
    }

    @SuppressLint("MissingPermission")
    private fun writeCccd() {
        val current = gatt ?: return
        val notify = tx ?: return
        if (!current.setCharacteristicNotification(notify, true)) {
            retryLinkOrFail("Не удалось включить BLE-индикации")
            return
        }
        val cccd = notify.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            emit { onTransportError("Дескриптор BLE-индикаций не найден") }
            return
        }
        val result = current.writeDescriptor(cccd, DplsBle.CCCD_ENABLE_INDICATE_NOTIFY)
        if (result == BluetoothStatusCodes.SUCCESS) return
        Log.i(TAG, "CCCD submit result=$result retry=$cccdRetryCount")
        if (result in TRANSIENT_CCCD_SUBMIT && cccdRetryCount < MAX_CCCD_RETRIES) {
            scheduleCccdRetry()
            return
        }
        retryLinkOrFail("Не удалось подписаться на события платы")
    }

    private fun scheduleCccdRetry() {
        cancelCccdRetry()
        cccdRetryCount++
        cccdRetry = Runnable { writeCccd() }.also {
            handler.postDelayed(it, CCCD_RETRY_BASE_MS * cccdRetryCount.coerceAtMost(4))
        }
    }

    private fun cancelCccdRetry() {
        cccdRetry?.let(handler::removeCallbacks)
        cccdRetry = null
    }

    private fun retryLinkOrFail(message: String) {
        if (connectAttempts < MAX_CONNECT_ATTEMPTS) {
            Log.i(TAG, "retry link after $message attempt=$connectAttempts")
            suppressDisconnectEvent = true
            releaseGatt()
            scheduleOpenGatt(REOPEN_DELAY_MS)
            return
        }
        emit { onTransportError(message) }
    }

    private fun failPairingNotConfirmed() {
        if (pairingFailed) {
            suppressDisconnectEvent = true
            return
        }
        pairingFailed = true
        pairing = false
        cancelPairingTimeout()
        cancelReopen()
        cancelCccdRetry()
        emit { onTransportError(PAIRING_NOT_CONFIRMED) }
        suppressDisconnectEvent = true
        releaseGatt()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInProgress) return
        val current = gatt ?: return
        val characteristic = rx ?: return
        val bytes = writeQueue.removeFirstOrNull() ?: return
        writeInProgress = true
        pendingWrite = bytes
        val result = current.writeCharacteristic(
            characteristic,
            bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        if (result != BluetoothStatusCodes.SUCCESS) {
            writeInProgress = false
            completeWrite(result)
        }
    }

    private fun completeWrite(status: Int) {
        writeInProgress = false
        if (status == BluetoothGatt.GATT_SUCCESS) {
            pendingWrite = null
            writeRetryCount = 0
            listener?.onWriteComplete(null)
            drainWriteQueue()
            return
        }
        val retry = pendingWrite
        pendingWrite = null
        if (retry != null &&
            status in TRANSIENT_WRITE_STATUSES &&
            writeRetryCount < MAX_WRITE_RETRIES
        ) {
            writeRetryCount++
            writeQueue.addFirst(retry)
            handler.postDelayed(::drainWriteQueue, WRITE_RETRY_BASE_MS * writeRetryCount)
            return
        }
        writeRetryCount = 0
        listener?.onWriteComplete(status.toLong())
    }

    private fun resetWrites() {
        writeQueue.clear()
        writeInProgress = false
        pendingWrite = null
        writeRetryCount = 0
    }

    private fun schedulePairingTimeout() {
        cancelPairingTimeout()
        pairingPoll = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                if (!pairing) return
                if (gatt?.device?.bondState == BluetoothDevice.BOND_BONDED) {
                    beginGattNegotiation()
                } else {
                    handler.postDelayed(this, PAIRING_POLL_MS)
                }
            }
        }.also { handler.postDelayed(it, PAIRING_POLL_MS) }
        pairingTimeout = Runnable {
            if (pairing) failPairingNotConfirmed()
        }.also { handler.postDelayed(it, PAIRING_TIMEOUT_MS) }
    }

    private fun cancelPairingTimeout() {
        pairingTimeout?.let(handler::removeCallbacks)
        pairingTimeout = null
        pairingPoll?.let(handler::removeCallbacks)
        pairingPoll = null
    }

    @SuppressLint("MissingPermission")
    private fun connectOnMain(address: String) {
        selectedAddress = address
        pairing = false
        subscribed = false
        negotiatedMtu = 23
        resetWrites()
        cancelPairingTimeout()
        cancelReopen()
        suppressDisconnectEvent = true
        pairingFailed = false
        cccdRetryCount = 0
        cancelCccdRetry()
        connectAttempts = 0
        if (gatt != null || closingGatt != null) {
            releaseGatt()
            scheduleOpenGatt(REOPEN_DELAY_MS)
            return
        }
        scheduleOpenGatt(SCAN_SETTLE_MS)
    }

    @SuppressLint("MissingPermission")
    private fun scheduleOpenGatt(delayMs: Long) {
        cancelReopen()
        val address = selectedAddress ?: return
        reopenRunnable = Runnable { openGatt(address) }.also {
            handler.postDelayed(it, delayMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(address: String) {
        reopenRunnable = null
        if (selectedAddress != address) return
        if (gatt != null || closingGatt != null) {
            scheduleOpenGatt(REOPEN_DELAY_MS)
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            suppressDisconnectEvent = false
            emit { onTransportError("Устройство недоступно. Запустите поиск снова.") }
            return
        }
        connectAttempts++
        cccdRetryCount = 0
        pairingFailed = false
        Log.i(TAG, "connectGatt $address attempt=$connectAttempts")
        gatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            handler,
        )
        if (gatt == null) {
            suppressDisconnectEvent = false
            emit { onTransportError("Не удалось открыть BLE-соединение") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun releaseGatt() {
        cancelCloseTimeout()
        val current = gatt
        gatt = null
        if (current == null) {
            closingGatt?.let { stale ->
                stale.close()
                closingGatt = null
            }
            return
        }
        closingGatt = current
        current.disconnect()
        closeTimeout = Runnable {
            if (closingGatt === current) finishClosed(current)
        }.also { handler.postDelayed(it, CLOSE_TIMEOUT_MS) }
    }

    @SuppressLint("MissingPermission")
    private fun finishClosed(current: BluetoothGatt) {
        cancelCloseTimeout()
        current.close()
        if (gatt === current) gatt = null
        if (closingGatt === current) closingGatt = null
    }

    private fun cancelReopen() {
        reopenRunnable?.let(handler::removeCallbacks)
        reopenRunnable = null
    }

    private fun cancelCloseTimeout() {
        closeTimeout?.let(handler::removeCallbacks)
        closeTimeout = null
    }

    private fun removeBond(device: BluetoothDevice): Boolean = try {
        device.javaClass.getMethod("removeBond").invoke(device) as Boolean
    } catch (_: ReflectiveOperationException) {
        false
    }

    companion object {
        private const val TAG = "TestDplsBle"
        private const val PREFERRED_MTU = DplsBle.PREFERRED_MTU
        private const val ATT_HEADER_BYTES = DplsBle.ATT_HEADER_BYTES
        private const val PAIRING_TIMEOUT_MS = 45_000L
        private const val PAIRING_POLL_MS = 250L
        private const val POST_BOND_SETTLE_MS = 280L
        private const val MAX_WRITE_RETRIES = 3
        private const val WRITE_RETRY_BASE_MS = 150L
        private const val MAX_CCCD_RETRIES = 8
        private const val CCCD_RETRY_BASE_MS = 180L
        private const val GATT_CONN_TIMEOUT = 8
        private const val GATT_CONN_TERMINATE_PEER = 19
        private const val GATT_CONN_TERMINATE_LOCAL = 22
        private const val GATT_ERROR = 133
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
        private const val CLOSE_TIMEOUT_MS = 1_200L
        private const val REOPEN_DELAY_MS = 500L
        private const val SCAN_SETTLE_MS = 180L
        private const val MAX_CONNECT_ATTEMPTS = 4
        private const val PAIRING_NOT_CONFIRMED =
            "Сопряжение не подтверждено. Повторите попытку и подтвердите системный диалог Bluetooth"
        private val TRANSIENT_WRITE_STATUSES = setOf(8, 14, 17, 143, 201)
        private val CCCD_AUTH_STATUSES = setOf(
            GATT_INSUFFICIENT_AUTHENTICATION,
            GATT_INSUFFICIENT_ENCRYPTION,
        )
        private val TRANSIENT_CCCD_STATUSES = setOf(
            GATT_CONN_TIMEOUT,
            GATT_ERROR,
            137,
            143,
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
        )
        private val TRANSIENT_CCCD_SUBMIT = setOf(
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
            BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED,
        )
        private val PAIRING_DISCONNECT_STATUSES = setOf(
            BluetoothGatt.GATT_SUCCESS,
            GATT_CONN_TIMEOUT,
            GATT_CONN_TERMINATE_LOCAL,
        )
        private val TRANSIENT_CONNECT_STATUSES = setOf(
            GATT_CONN_TIMEOUT,
            GATT_CONN_TERMINATE_LOCAL,
            GATT_ERROR,
        )

        val SERVICE_UUID: UUID = UUID.fromString(DplsBle.SERVICE_UUID)
        val RX_UUID: UUID = UUID.fromString(DplsBle.RX_UUID)
        val TX_UUID: UUID = UUID.fromString(DplsBle.TX_UUID)
        private val CCCD_UUID: UUID = UUID.fromString(DplsBle.CCCD_UUID)
    }
}
