@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.bolid.testdpls.core.app

import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

internal interface IosBleTransportListener {
    fun onBluetoothAvailable()
    fun onBluetoothUnavailable()
    fun onDiscovered(device: IosDiscoveredDevice)
    fun onConnected()
    fun onSubscribed(writeLimit: Int)
    fun onBytes(bytes: ByteArray)
    fun onWriteComplete(errorCode: Long?)
    fun onDisconnected(error: String?)
    fun onTransportError(message: String)
}

internal data class IosDiscoveredDevice(
    val address: String,
    val name: String,
    val deviceId: Long?,
    val rssi: Int,
)

/** Thin CoreBluetooth adapter. It owns no Test-DPLS protocol or session semantics. */
internal class IosBleTransport(
    private val listener: IosBleTransportListener,
) {
    private val serviceUuid = CBUUID.UUIDWithString(SERVICE_UUID)
    private val rxUuid = CBUUID.UUIDWithString(RX_UUID)
    private val txUuid = CBUUID.UUIDWithString(TX_UUID)
    private val known = mutableMapOf<String, CBPeripheral>()
    private var selectedAddress: String? = null
    private var peripheral: CBPeripheral? = null
    private var rx: CBCharacteristic? = null
    private var writeLimit = 20
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false

    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                listener.onBluetoothAvailable()
            } else {
                peripheral = null
                rx = null
                resetWrites()
                listener.onBluetoothUnavailable()
            }
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val address = didDiscoverPeripheral.identifier.UUIDString
            known[address] = didDiscoverPeripheral
            val manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData
            val deviceId = manufacturer?.toByteArrayCopy()?.let(::parseManufacturerDeviceId)
            val localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
            val name = localName ?: didDiscoverPeripheral.name ?: deviceId?.let {
                "Test-DPLS-${(it and 0xffff).toString(16).uppercase().padStart(4, '0')}"
            } ?: "Test-DPLS"
            listener.onDiscovered(IosDiscoveredDevice(address, name, deviceId, RSSI.intValue))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            if (didConnectPeripheral.identifier.UUIDString != selectedAddress) {
                central.cancelPeripheralConnection(didConnectPeripheral)
                return
            }
            peripheral = didConnectPeripheral
            didConnectPeripheral.delegate = this
            listener.onConnected()
            didConnectPeripheral.discoverServices(listOf(serviceUuid))
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            if (didFailToConnectPeripheral.identifier.UUIDString != selectedAddress) return
            listener.onDisconnected(error?.localizedDescription)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            if (peripheral !== didDisconnectPeripheral) return
            peripheral = null
            rx = null
            resetWrites()
            listener.onDisconnected(error?.localizedDescription)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (!isCurrent(peripheral)) return
            if (didDiscoverServices != null) {
                listener.onTransportError("Поиск службы: ${didDiscoverServices.localizedDescription}")
                return
            }
            val service = peripheral.services
                ?.filterIsInstance<CBService>()
                ?.firstOrNull { it.UUID == serviceUuid }
            if (service == null) {
                listener.onTransportError("Служба Test-DPLS не найдена")
                return
            }
            peripheral.discoverCharacteristics(listOf(rxUuid, txUuid), service)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            if (!isCurrent(peripheral)) return
            if (error != null) {
                listener.onTransportError("Характеристики: ${error.localizedDescription}")
                return
            }
            val characteristics = didDiscoverCharacteristicsForService.characteristics
                ?.filterIsInstance<CBCharacteristic>()
                .orEmpty()
            rx = characteristics.firstOrNull { it.UUID == rxUuid }
            val notify = characteristics.firstOrNull { it.UUID == txUuid }
            if (rx == null || notify == null) {
                listener.onTransportError("Служба Test-DPLS не найдена")
                return
            }
            writeLimit = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse)
                .toInt()
                .coerceAtLeast(20)
            peripheral.setNotifyValue(true, notify)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (!isCurrent(peripheral) || didUpdateNotificationStateForCharacteristic.UUID != txUuid) return
            if (error != null) {
                listener.onTransportError("Подписка на BLE-события: ${error.localizedDescription}")
                return
            }
            listener.onSubscribed(writeLimit)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (!isCurrent(peripheral) || didUpdateValueForCharacteristic.UUID != txUuid) return
            if (error != null) {
                listener.onTransportError("Ошибка BLE-индикации: ${error.localizedDescription}")
                return
            }
            didUpdateValueForCharacteristic.value?.let { listener.onBytes(it.toByteArrayCopy()) }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (!isCurrent(peripheral) || didWriteValueForCharacteristic.UUID != rxUuid) return
            writeInProgress = false
            listener.onWriteComplete(error?.code)
            drainWrites()
        }
    }

    private val central = CBCentralManager(delegate = delegate, queue = null)

    fun startScan(): Boolean {
        if (central.state != CBManagerStatePoweredOn) return false
        central.stopScan()
        central.scanForPeripheralsWithServices(
            listOf(serviceUuid),
            mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true),
        )
        return true
    }

    fun stopScan() = central.stopScan()

    fun connect(address: String): Boolean {
        val target = known[address] ?: return false
        stopScan()
        disconnect(clearSelection = false)
        selectedAddress = address
        peripheral = target
        target.delegate = delegate
        central.connectPeripheral(target, options = null)
        return true
    }

    fun reconnect(): Boolean = selectedAddress?.let(::connect) ?: false

    fun send(bytes: ByteArray, priority: Boolean = false, flush: Boolean = false): Boolean {
        if (bytes.size > writeLimit) return false
        if (flush) resetWrites()
        if (priority) writeQueue.addFirst(bytes.copyOf()) else writeQueue.addLast(bytes.copyOf())
        drainWrites()
        return true
    }

    fun disconnect(clearSelection: Boolean = true) {
        stopScan()
        peripheral?.let(central::cancelPeripheralConnection)
        peripheral = null
        rx = null
        resetWrites()
        if (clearSelection) selectedAddress = null
    }

    fun hasConnection(): Boolean = peripheral != null && rx != null

    private fun isCurrent(candidate: CBPeripheral): Boolean = peripheral === candidate

    private fun drainWrites() {
        if (writeInProgress) return
        val target = peripheral ?: return
        val characteristic = rx ?: return
        val next = writeQueue.removeFirstOrNull() ?: return
        writeInProgress = true
        target.writeValue(next.toNSDataCopy(), characteristic, CBCharacteristicWriteWithResponse)
    }

    private fun resetWrites() {
        writeQueue.clear()
        writeInProgress = false
    }

    private fun parseManufacturerDeviceId(data: ByteArray): Long? {
        if (data.size < 6) return null
        val company = (data[0].toInt() and 0xff) or ((data[1].toInt() and 0xff) shl 8)
        if (company != MANUFACTURER_ID) return null
        return (data[2].toLong() and 0xff) or
            ((data[3].toLong() and 0xff) shl 8) or
            ((data[4].toLong() and 0xff) shl 16) or
            ((data[5].toLong() and 0xff) shl 24)
    }

    companion object {
        private const val SERVICE_UUID = "7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001"
        private const val RX_UUID = "7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001"
        private const val TX_UUID = "7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001"
        private const val MANUFACTURER_ID = 0x0B01
    }
}
