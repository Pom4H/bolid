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
    private enum class PairingTrigger {
        LINK,
        CCCD,
        RX_WRITE,
    }

    /**
     * Single owner of Android SMP state. A frame blocked by the encrypted RX
     * boundary lives in this state until bonding succeeds, fails or is cancelled.
     */
    private sealed interface SecurityState {
        data object Idle : SecurityState
        data class Pairing(
            val trigger: PairingTrigger,
            val blockedWrite: ByteArray? = null,
        ) : SecurityState
        data class Resuming(val blockedWrite: ByteArray) : SecurityState
        data object Failed : SecurityState
    }

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
    private var securityState: SecurityState = SecurityState.Idle
    private var subscribed = false
    private var servicesDiscovered = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private var pendingWrite: ByteArray? = null
    private var writeRetryCount = 0
    private var pairingPoll: Runnable? = null
    private var closingGatt: BluetoothGatt? = null
    private var closeTimeout: Runnable? = null
    private var reopenRunnable: Runnable? = null
    private var cccdRetry: Runnable? = null
    private var connectAttempts = 0
    private var cccdRetryCount = 0
    private var suppressDisconnectEvent = false
    private var cccdValue = DplsBle.CCCD_ENABLE_INDICATE_NOTIFY

    private val pairing: Boolean
        get() = securityState is SecurityState.Pairing

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
                        BluetoothDevice.BOND_BONDED -> handleBonded()
                        BluetoothDevice.BOND_NONE -> if (pairing) failPairingNotConfirmed()
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                ) {
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF,
                    -> {
                        suppressDisconnectEvent = true
                        securityState = SecurityState.Idle
                        cancelPairingPoll()
                        releaseGatt()
                        rx = null
                        tx = null
                        subscribed = false
                        servicesDiscovered = false
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
            securityState = SecurityState.Idle
            cancelPairingPoll()
            cancelReopen()
            connectAttempts = 0
            suppressDisconnectEvent = true
            releaseGatt()
            rx = null
            tx = null
            subscribed = false
            servicesDiscovered = false
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
                    rssi = result.rssi,
                ),
            )
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "gatt state=$newState status=$status security=${securityState::class.simpleName}")
            if (current === closingGatt) {
                finishClosed(current)
                return
            }
            if (current !== gatt) {
                current.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                /* Do not reset connectAttempts here. A physical link that always
                 * fails service/CCCD negotiation must remain bounded. The attempt
                 * budget resets only after a usable subscription exists. */
                suppressDisconnectEvent = false
                emit { onConnected() }
                when (current.device.bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        if (securityState is SecurityState.Pairing) handleBonded()
                        else beginGattNegotiation()
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        if (!pairing) securityState = SecurityState.Pairing(PairingTrigger.LINK)
                        schedulePairingPoll()
                    }
                    else -> beginGattNegotiation()
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val bonded = current.device.bondState == BluetoothDevice.BOND_BONDED
                val securityAtDisconnect = securityState
                val wasSecurityHandshake = securityAtDisconnect is SecurityState.Pairing ||
                    securityAtDisconnect is SecurityState.Resuming
                current.close()
                if (gatt === current) gatt = null
                rx = null
                tx = null
                subscribed = false
                servicesDiscovered = false
                resetWrites()
                cancelCccdRetry()
                if (closingGatt === current) {
                    finishClosed(current)
                    return
                }

                /* ACL loss is not a security outcome. Pairing waits for the bond
                 * event; Resuming already owns a bonded blocked frame and must
                 * explicitly reopen GATT so that frame cannot be stranded. */
                if (wasSecurityHandshake) {
                    Log.i(TAG, "security disconnect status=$status bonded=$bonded state=$securityAtDisconnect")
                    when (securityAtDisconnect) {
                        is SecurityState.Pairing -> if (bonded) handleBonded()
                        is SecurityState.Resuming -> {
                            cancelPairingPoll()
                            scheduleOpenGatt(REOPEN_DELAY_MS)
                        }
                    }
                    return
                }

                cancelPairingPoll()
                if (suppressDisconnectEvent) {
                    suppressDisconnectEvent = false
                    return
                }
                if (status in TRANSIENT_CONNECT_STATUSES && connectAttempts < MAX_CONNECT_ATTEMPTS) {
                    Log.i(TAG, "retry connect after GATT $status attempt=$connectAttempts")
                    scheduleOpenGatt(REOPEN_DELAY_MS)
                    return
                }
                /* Android GATT 133 is a generic controller/stack failure and is
                 * not evidence that persisted SMP keys are stale. Stale-bond is
                 * reported only when a bonded peer still gets 5/15 on protected
                 * RX in startPairing(). */
                emit {
                    onDisconnected(
                        if (status == BluetoothGatt.GATT_SUCCESS) null
                        else "Не удалось подключиться по Bluetooth (GATT $status)",
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@AndroidBleTransport.gatt) return
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            Log.i(TAG, "MTU changed mtu=$negotiatedMtu status=$status")
            if (servicesDiscovered || subscribed) return
            if (!gatt.discoverServices()) retryLinkOrFail("Не удалось запустить поиск BLE-службы")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@AndroidBleTransport.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retryLinkOrFail("Не удалось получить BLE-службу (GATT $status)")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            rx = service?.getCharacteristic(RX_UUID)
            tx = service?.getCharacteristic(TX_UUID)
            if (rx == null || tx == null) {
                emit { onTransportError("Служба Test-DPLS не найдена") }
                return
            }
            Log.i(TAG, "Services discovered")
            servicesDiscovered = true
            if (!subscribed) writeCccd()
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
                connectAttempts = 0
                if (subscribed) return
                subscribed = true
                Log.i(TAG, "CCCD written")
                if (securityState is SecurityState.Resuming) resumeSecurityWrite()
                emit { onSubscribed((negotiatedMtu - ATT_HEADER_BYTES).coerceAtLeast(20)) }
                return
            }
            Log.i(TAG, "CCCD write status=$status")
            if (AndroidGattSecurityPolicy.requiresPairing(status)) {
                startPairing(PairingTrigger.CCCD, null)
                return
            }
            if (status == GATT_CCCD_REJECTED &&
                !cccdValue.contentEquals(DplsBle.CCCD_ENABLE_NOTIFY)
            ) {
                cccdValue = DplsBle.CCCD_ENABLE_NOTIFY
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
            Log.i(TAG, "RX indication bytes=${value.size} hex=${value.toHexUpper()}")
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
        if (!pairing) cancelPairingPoll()
        if (!current.requestMtu(PREFERRED_MTU)) {
            negotiatedMtu = 23
            if (!current.discoverServices()) retryLinkOrFail("Не удалось запустить поиск BLE-службы")
        }
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
        val result = current.writeDescriptor(cccd, cccdValue)
        Log.i(TAG, "CCCD write submit result=$result value=${cccdValue.toHexUpper()}")
        if (result == BluetoothStatusCodes.SUCCESS) return
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
        if (securityState is SecurityState.Failed) return
        securityState = SecurityState.Failed
        cancelPairingPoll()
        cancelReopen()
        cancelCccdRetry()
        emit { onTransportError(PAIRING_NOT_CONFIRMED) }
        suppressDisconnectEvent = true
        releaseGatt()
    }

    @SuppressLint("MissingPermission")
    private fun startPairing(trigger: PairingTrigger, blockedWrite: ByteArray?) {
        val current = gatt ?: run {
            failPairingNotConfirmed()
            return
        }
        securityState = SecurityState.Pairing(trigger, blockedWrite?.copyOf())
        schedulePairingPoll()
        when (current.device.bondState) {
            BluetoothDevice.BOND_BONDING -> Unit
            BluetoothDevice.BOND_NONE -> if (!current.device.createBond()) failPairingNotConfirmed()
            BluetoothDevice.BOND_BONDED -> {
                /* Protected RX still returning 5/15 with a pre-existing bond is
                 * a real phone/peripheral key mismatch, not a retry condition. */
                securityState = SecurityState.Failed
                cancelPairingPoll()
                emit { onStaleBond() }
                suppressDisconnectEvent = true
                releaseGatt()
            }
        }
    }

    private fun handleBonded() {
        val state = securityState as? SecurityState.Pairing ?: return
        cancelPairingPoll()
        securityState = state.blockedWrite?.let(SecurityState::Resuming) ?: SecurityState.Idle
        if (gatt == null) {
            scheduleOpenGatt(REOPEN_DELAY_MS)
            return
        }
        when {
            state.trigger == PairingTrigger.RX_WRITE && subscribed &&
                securityState is SecurityState.Resuming -> resumeSecurityWrite()
            state.trigger == PairingTrigger.CCCD && !subscribed -> writeCccd()
            else -> beginGattNegotiation()
        }
    }

    private fun resumeSecurityWrite() {
        if (writeInProgress || gatt == null || rx == null || !subscribed) return
        val state = securityState as? SecurityState.Resuming ?: return
        securityState = SecurityState.Idle
        writeQueue.addFirst(state.blockedWrite)
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInProgress || securityState is SecurityState.Pairing ||
            securityState is SecurityState.Resuming
        ) {
            return
        }
        val current = gatt ?: return
        val characteristic = rx ?: return
        val bytes = writeQueue.removeFirstOrNull() ?: return
        writeInProgress = true
        pendingWrite = bytes
        Log.i(TAG, "TX write bytes=${bytes.size} hex=${bytes.toHexUpper()}")
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
        when (
            AndroidGattSecurityPolicy.classifyWrite(
                status = status,
                retryCount = writeRetryCount,
                maxRetries = MAX_WRITE_RETRIES,
            )
        ) {
            WriteDisposition.COMPLETE -> {
                pendingWrite = null
                writeRetryCount = 0
                listener?.onWriteComplete(null)
                drainWriteQueue()
            }
            WriteDisposition.PAIRING_REQUIRED -> {
                val blocked = pendingWrite
                pendingWrite = null
                writeRetryCount = 0
                if (blocked == null) {
                    listener?.onWriteComplete(status.toLong())
                    return
                }
                startPairing(PairingTrigger.RX_WRITE, blocked)
            }
            WriteDisposition.RETRY -> {
                val retry = pendingWrite
                pendingWrite = null
                if (retry == null) {
                    writeRetryCount = 0
                    listener?.onWriteComplete(status.toLong())
                    return
                }
                writeRetryCount++
                writeQueue.addFirst(retry)
                handler.postDelayed(::drainWriteQueue, WRITE_RETRY_BASE_MS * writeRetryCount)
            }
            WriteDisposition.FAIL -> {
                pendingWrite = null
                writeRetryCount = 0
                listener?.onWriteComplete(status.toLong())
            }
        }
    }

    private fun resetWrites() {
        writeQueue.clear()
        writeInProgress = false
        pendingWrite = null
        writeRetryCount = 0
    }

    private fun schedulePairingPoll() {
        cancelPairingPoll()
        pairingPoll = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                if (!pairing) return
                if (gatt?.device?.bondState == BluetoothDevice.BOND_BONDED) handleBonded()
                else handler.postDelayed(this, PAIRING_POLL_MS)
            }
        }.also { handler.postDelayed(it, PAIRING_POLL_MS) }
    }

    private fun cancelPairingPoll() {
        pairingPoll?.let(handler::removeCallbacks)
        pairingPoll = null
    }

    @SuppressLint("MissingPermission")
    private fun connectOnMain(address: String) {
        selectedAddress = address
        securityState = SecurityState.Idle
        subscribed = false
        servicesDiscovered = false
        negotiatedMtu = 23
        resetWrites()
        cancelPairingPoll()
        cancelReopen()
        suppressDisconnectEvent = true
        cccdRetryCount = 0
        cancelCccdRetry()
        connectAttempts = 0
        if (gatt != null || closingGatt != null) {
            suppressDisconnectEvent = true
            releaseGatt()
            scheduleOpenGatt(REOPEN_DELAY_MS)
            return
        }
        suppressDisconnectEvent = false
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
        servicesDiscovered = false
        subscribed = false
        rx = null
        tx = null
        negotiatedMtu = 23
        cccdValue = DplsBle.CCCD_ENABLE_INDICATE_NOTIFY
        resetWrites()
        Log.i(TAG, "connectGatt $address attempt=$connectAttempts security=$securityState")
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
        private const val PAIRING_POLL_MS = 250L
        private const val MAX_WRITE_RETRIES = 3
        private const val WRITE_RETRY_BASE_MS = 150L
        private const val MAX_CCCD_RETRIES = 8
        private const val CCCD_RETRY_BASE_MS = 180L
        private const val GATT_CONN_TIMEOUT = 8
        private const val GATT_CONN_TERMINATE_LOCAL = 22
        private const val GATT_ERROR = 133
        private const val GATT_CCCD_REJECTED = 245
        private const val CLOSE_TIMEOUT_MS = 1_200L
        private const val REOPEN_DELAY_MS = 500L
        private const val SCAN_SETTLE_MS = 180L
        private const val MAX_CONNECT_ATTEMPTS = 4
        private const val PAIRING_NOT_CONFIRMED =
            "Сопряжение не подтверждено. Повторите попытку и подтвердите системный диалог Bluetooth"
        private val TRANSIENT_CCCD_STATUSES = setOf(
            GATT_CONN_TIMEOUT,
            GATT_ERROR,
            GATT_CCCD_REJECTED,
            137,
            143,
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
        )
        private val TRANSIENT_CCCD_SUBMIT = setOf(
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
            BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED,
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

private fun ByteArray.toHexUpper(): String = joinToString("") { byte ->
    "%02X".format(byte.toInt() and 0xff)
}
