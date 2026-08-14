package ru.bolid.testdpls.ble

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import ru.bolid.testdpls.core.app.DplsTransport
import ru.bolid.testdpls.core.app.DplsTransportDevice
import ru.bolid.testdpls.core.app.DplsTransportListener

/** Android-only BluetoothGatt adapter. Product semantics live in common DplsClient. */
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
    private var preSubscribeGatt133Count = 0

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private var pendingWrite: ByteArray? = null
    private var writeRetryCount = 0
    private var pairingTimeout: Runnable? = null
    private var pairingPoll: Runnable? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = acceptScan(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::acceptScan)
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onTransportError("Ошибка BLE-сканирования: $errorCode")
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
                            beginGattNegotiation()
                        }
                        BluetoothDevice.BOND_NONE -> if (pairing) {
                            pairing = false
                            cancelPairingTimeout()
                            listener?.onTransportError("Не удалось создать защищённое BLE-соединение")
                            closeCurrentGatt()
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                ) {
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF -> {
                        closeCurrentGatt()
                        rx = null
                        tx = null
                        subscribed = false
                        resetWrites()
                        listener?.onBluetoothUnavailable()
                    }
                    BluetoothAdapter.STATE_ON -> listener?.onBluetoothAvailable()
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

    @SuppressLint("MissingPermission")
    override fun startScan(): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: return false
        if (!adapter.isEnabled) return false
        stopScan()
        scanning = true
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
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
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        stopScan()
        closeCurrentGatt()
        cancelPairingTimeout()
        selectedAddress = address
        pairing = false
        subscribed = false
        negotiatedMtu = 23
        resetWrites()
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        return gatt != null
    }

    override fun reconnect(): Boolean = selectedAddress?.let(::connect) ?: false

    override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean): Boolean {
        if (bytes.size > negotiatedMtu - ATT_HEADER_BYTES) return false
        handler.post {
            if (flush) resetWrites()
            if (priority) writeQueue.addFirst(bytes.copyOf()) else writeQueue.addLast(bytes.copyOf())
            drainWriteQueue()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    override fun disconnect(clearSelection: Boolean) {
        stopScan()
        cancelPairingTimeout()
        closeCurrentGatt()
        rx = null
        tx = null
        pairing = false
        subscribed = false
        resetWrites()
        if (clearSelection) {
            selectedAddress = null
            preSubscribeGatt133Count = 0
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
        val manufacturer = record.getManufacturerSpecificData(MANUFACTURER_ID)
        val deviceId = manufacturer?.takeIf { it.size >= 4 }?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
        }
        val name = record.deviceName ?: result.device.name ?: deviceId?.let {
            "Test-DPLS-${(it and 0xffff).toString(16).uppercase().padStart(4, '0')}"
        } ?: "Test-DPLS"
        listener?.onDiscovered(DplsTransportDevice(result.device.address, name, deviceId, result.rssi))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) {
            if (current !== gatt) {
                current.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                listener?.onConnected()
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
                            listener?.onTransportError("Не удалось начать сопряжение")
                        }
                    }
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasCurrent = current === gatt
                current.close()
                if (!wasCurrent) return
                gatt = null
                rx = null
                tx = null
                pairing = false
                subscribed = false
                resetWrites()
                cancelPairingTimeout()

                if (status == 133 && current.device.bondState == BluetoothDevice.BOND_BONDED) {
                    preSubscribeGatt133Count++
                    if (preSubscribeGatt133Count >= GATT133_BOND_RECOVERY_THRESHOLD) {
                        Log.w(TAG, "Removing stale DPLS bond after repeated pre-subscribe GATT 133")
                        removeBond(current.device)
                        preSubscribeGatt133Count = 0
                    }
                }
                listener?.onDisconnected(if (status == BluetoothGatt.GATT_SUCCESS) null else "GATT $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@AndroidBleTransport.gatt) return
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            if (!gatt.discoverServices()) listener?.onTransportError("Не удалось запустить поиск BLE-службы")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@AndroidBleTransport.gatt) return
            val service = gatt.getService(SERVICE_UUID)
            rx = service?.getCharacteristic(RX_UUID)
            tx = service?.getCharacteristic(TX_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null) {
                listener?.onTransportError("Служба Test-DPLS не найдена")
                return
            }
            val notify = tx ?: return
            if (!gatt.setCharacteristicNotification(notify, true)) {
                listener?.onTransportError("Не удалось включить BLE-индикации")
                return
            }
            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                listener?.onTransportError("Дескриптор BLE-индикаций не найден")
                return
            }
            val result = gatt.writeDescriptor(cccd, byteArrayOf(0x03, 0x00))
            if (result != BluetoothStatusCodes.SUCCESS) {
                listener?.onTransportError("Не удалось подписаться на BLE-индикации: $result")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt !== this@AndroidBleTransport.gatt || descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onTransportError("Подписка на BLE-индикации отклонена")
                return
            }
            subscribed = true
            preSubscribeGatt133Count = 0
            listener?.onSubscribed((negotiatedMtu - ATT_HEADER_BYTES).coerceAtLeast(20))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== this@AndroidBleTransport.gatt || characteristic.uuid != TX_UUID) return
            listener?.onBytes(value.copyOf())
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== this@AndroidBleTransport.gatt || characteristic.uuid != RX_UUID) return
            handler.post { completeWrite(status) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginGattNegotiation() {
        val current = gatt ?: return
        pairing = false
        cancelPairingTimeout()
        if (!current.requestMtu(PREFERRED_MTU)) {
            negotiatedMtu = 23
            if (!current.discoverServices()) listener?.onTransportError("Не удалось запустить поиск BLE-службы")
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
        if (retry != null && status in TRANSIENT_WRITE_STATUSES && writeRetryCount < MAX_WRITE_RETRIES) {
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
            if (pairing) {
                pairing = false
                listener?.onTransportError(
                    "Сопряжение не подтверждено. Повторите попытку и подтвердите системный диалог Bluetooth",
                )
                closeCurrentGatt()
            }
        }.also { handler.postDelayed(it, PAIRING_TIMEOUT_MS) }
    }

    private fun cancelPairingTimeout() {
        pairingTimeout?.let(handler::removeCallbacks)
        pairingTimeout = null
        pairingPoll?.let(handler::removeCallbacks)
        pairingPoll = null
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt() {
        val current = gatt
        gatt = null
        current?.disconnect()
        current?.close()
    }

    private fun removeBond(device: BluetoothDevice): Boolean = try {
        device.javaClass.getMethod("removeBond").invoke(device) as Boolean
    } catch (_: ReflectiveOperationException) {
        false
    }

    companion object {
        private const val TAG = "TestDplsBle"
        private const val PREFERRED_MTU = 247
        private const val ATT_HEADER_BYTES = 3
        private const val MANUFACTURER_ID = 0x0B01
        private const val PAIRING_TIMEOUT_MS = 45_000L
        private const val PAIRING_POLL_MS = 250L
        private const val GATT133_BOND_RECOVERY_THRESHOLD = 2
        private const val MAX_WRITE_RETRIES = 3
        private const val WRITE_RETRY_BASE_MS = 150L
        private val TRANSIENT_WRITE_STATUSES = setOf(8, 14, 17, 143, 201)

        val SERVICE_UUID: UUID = UUID.fromString("7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001")
        val RX_UUID: UUID = UUID.fromString("7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001")
        val TX_UUID: UUID = UUID.fromString("7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
