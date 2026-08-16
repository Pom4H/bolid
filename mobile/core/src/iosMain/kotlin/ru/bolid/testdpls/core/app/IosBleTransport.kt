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
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBPeripheralStateConnecting
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/** Thin CoreBluetooth implementation of the shared [DplsTransport] boundary. */
internal class IosBleTransport : DplsTransport {
    private var listener: DplsTransportListener? = null
    private val serviceUuid = CBUUID.UUIDWithString(DplsBle.SERVICE_UUID)
    private val rxUuid = CBUUID.UUIDWithString(DplsBle.RX_UUID)
    private val txUuid = CBUUID.UUIDWithString(DplsBle.TX_UUID)
    private val known = mutableMapOf<String, CBPeripheral>()
    private var selectedAddress: String? = null
    private var peripheral: CBPeripheral? = null
    private var rx: CBCharacteristic? = null
    private var writeLimit = 20
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private var inFlightWrite: ByteArray? = null
    private var pairingRetryCount = 0
    private var subscribed = false

    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                listener?.onBluetoothAvailable()
            } else {
                peripheral = null
                rx = null
                subscribed = false
                resetWrites()
                listener?.onBluetoothUnavailable()
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
            listener?.onDiscovered(
                DplsBle.discovered(
                    address = address,
                    advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String,
                    peripheralName = didDiscoverPeripheral.name,
                    manufacturerPayload = manufacturer?.toByteArrayCopy(),
                    manufacturerIncludesCompanyId = true,
                    rssi = RSSI.intValue,
                ),
            )
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            if (!isSelected(didConnectPeripheral)) {
                central.cancelPeripheralConnection(didConnectPeripheral)
                return
            }
            peripheral = didConnectPeripheral
            didConnectPeripheral.delegate = this
            listener?.onConnected()
            didConnectPeripheral.discoverServices(listOf(serviceUuid))
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            if (!isSelected(didFailToConnectPeripheral)) return
            deliverLinkFailure(didFailToConnectPeripheral, error)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            if (!isSelected(didDisconnectPeripheral)) return
            peripheral = null
            rx = null
            subscribed = false
            resetWrites()
            deliverLinkFailure(didDisconnectPeripheral, error)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (!isSelected(peripheral)) return
            if (didDiscoverServices != null) {
                deliverNsError(peripheral, "Поиск службы", didDiscoverServices)
                return
            }
            val service = peripheral.services
                ?.filterIsInstance<CBService>()
                ?.firstOrNull { uuidMatches(it.UUID, serviceUuid) }
            if (service == null) {
                listener?.onTransportError("Служба Test-DPLS не найдена")
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
            if (!isSelected(peripheral)) return
            if (error != null) {
                deliverNsError(peripheral, "Характеристики", error)
                return
            }
            val characteristics = didDiscoverCharacteristicsForService.characteristics
                ?.filterIsInstance<CBCharacteristic>()
                .orEmpty()
            rx = characteristics.firstOrNull { uuidMatches(it.UUID, rxUuid) }
            val notify = characteristics.firstOrNull { uuidMatches(it.UUID, txUuid) }
            if (rx == null || notify == null) {
                listener?.onTransportError("Служба Test-DPLS не найдена")
                return
            }
            writeLimit = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse)
                .toInt()
                .coerceAtLeast(20)
            if (notify.isNotifying) completeSubscribe() else peripheral.setNotifyValue(true, notify)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (!isSelected(peripheral) || !uuidMatches(didUpdateNotificationStateForCharacteristic.UUID, txUuid)) return
            if (error != null) {
                deliverNsError(peripheral, "Подписка на BLE-события", error)
                return
            }
            completeSubscribe()
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (!isSelected(peripheral) || !uuidMatches(didUpdateValueForCharacteristic.UUID, txUuid)) return
            if (error != null) {
                deliverNsError(peripheral, "Ошибка BLE-индикации", error)
                return
            }
            didUpdateValueForCharacteristic.value?.let { listener?.onBytes(it.toByteArrayCopy()) }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (!isSelected(peripheral) || !uuidMatches(didWriteValueForCharacteristic.UUID, rxUuid)) return
            writeInProgress = false
            if (error != null && isStaleBondError(error)) {
                reportStaleBond(peripheral)
                return
            }
            if (error != null && isPairingWriteError(error) && pairingRetryCount < PAIRING_WRITE_RETRIES) {
                pairingRetryCount++
                inFlightWrite?.let { writeQueue.addFirst(it.copyOf()) }
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, PAIRING_RETRY_NS), dispatch_get_main_queue()) {
                    drainWrites()
                }
                return
            }
            if (error != null && isPairingWriteError(error)) {
                reportStaleBond(peripheral)
                return
            }
            pairingRetryCount = 0
            inFlightWrite = null
            listener?.onWriteComplete(error?.code)
            drainWrites()
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didReadRSSI: NSNumber,
            error: NSError?,
        ) {
            if (!isSelected(peripheral) || error != null) return
            listener?.onRssi(didReadRSSI.intValue)
        }
    }

    private val central = CBCentralManager(delegate = delegate, queue = null)

    override fun setListener(listener: DplsTransportListener) {
        this.listener = listener
        if (central.state == CBManagerStatePoweredOn) listener.onBluetoothAvailable()
    }

    override fun startScan(): Boolean {
        if (central.state != CBManagerStatePoweredOn) return false
        central.stopScan()
        central.scanForPeripheralsWithServices(
            listOf(serviceUuid),
            mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true),
        )
        return true
    }

    override fun stopScan() = central.stopScan()

    override fun connect(address: String): Boolean {
        val target = known[address] ?: retrieve(address) ?: return false
        known[address] = target
        stopScan()
        val previous = peripheral
        if (previous != null && previous.identifier.UUIDString != address) {
            central.cancelPeripheralConnection(previous)
        }
        selectedAddress = address
        peripheral = target
        rx = null
        subscribed = false
        resetWrites()
        target.delegate = delegate
        when (target.state) {
            CBPeripheralStateConnected -> {
                listener?.onConnected()
                target.discoverServices(listOf(serviceUuid))
            }
            CBPeripheralStateConnecting -> Unit
            else -> central.connectPeripheral(target, options = null)
        }
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, GAP_CONNECT_NUDGE_NS), dispatch_get_main_queue()) {
            val pending = peripheral ?: return@dispatch_after
            if (!isSelected(pending)) return@dispatch_after
            if (pending.state == CBPeripheralStateConnecting) {
                central.connectPeripheral(pending, options = null)
            }
        }
        return true
    }

    override fun reconnect(): Boolean = selectedAddress?.let(::connect) ?: false

    override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean): Boolean {
        if (bytes.size > writeLimit) return false
        if (flush) writeQueue.clear()
        if (priority) writeQueue.addFirst(bytes.copyOf()) else writeQueue.addLast(bytes.copyOf())
        drainWrites()
        return true
    }

    override fun readRssi(): Boolean {
        val current = peripheral ?: return false
        if (current.state != CBPeripheralStateConnected) return false
        current.readRSSI()
        return true
    }

    override fun disconnect(clearSelection: Boolean) {
        stopScan()
        peripheral?.let(central::cancelPeripheralConnection)
        peripheral = null
        rx = null
        subscribed = false
        resetWrites()
        if (clearSelection) selectedAddress = null
    }

    override fun hasConnection(): Boolean = subscribed && rx != null

    override fun close() {
        disconnect(clearSelection = true)
        listener = null
        known.clear()
    }

    private fun retrieve(address: String): CBPeripheral? {
        val uuid = NSUUID(uUIDString = address)
        return central.retrievePeripheralsWithIdentifiers(listOf(uuid))
            .filterIsInstance<CBPeripheral>()
            .firstOrNull()
            ?: central.retrieveConnectedPeripheralsWithServices(listOf(serviceUuid))
                .filterIsInstance<CBPeripheral>()
                .firstOrNull { it.identifier.UUIDString == address }
    }

    private fun isSelected(candidate: CBPeripheral): Boolean =
        candidate.identifier.UUIDString == selectedAddress

    private fun uuidMatches(left: CBUUID?, right: CBUUID): Boolean {
        val value = left?.UUIDString ?: return false
        return value.equals(right.UUIDString, ignoreCase = true)
    }

    private fun completeSubscribe() {
        if (subscribed) return
        subscribed = true
        listener?.onSubscribed(writeLimit)
    }

    private fun drainWrites() {
        if (writeInProgress) return
        val target = peripheral ?: return
        val characteristic = rx ?: return
        val next = writeQueue.removeFirstOrNull() ?: return
        inFlightWrite = next
        writeInProgress = true
        target.writeValue(next.toNSDataCopy(), characteristic, CBCharacteristicWriteWithResponse)
    }

    private fun resetWrites() {
        writeQueue.clear()
        writeInProgress = false
        inFlightWrite = null
        pairingRetryCount = 0
    }

    private fun reportStaleBond(peripheral: CBPeripheral) {
        pairingRetryCount = 0
        inFlightWrite = null
        listener?.onStaleBond()
        central.cancelPeripheralConnection(peripheral)
    }

    private fun deliverLinkFailure(peripheral: CBPeripheral, error: NSError?) {
        if (error != null && isStaleBondError(error)) {
            reportStaleBond(peripheral)
            return
        }
        listener?.onDisconnected(error?.localizedDescription)
    }

    private fun deliverNsError(peripheral: CBPeripheral, prefix: String, error: NSError) {
        if (isStaleBondError(error)) {
            reportStaleBond(peripheral)
            return
        }
        listener?.onTransportError("$prefix: ${error.localizedDescription}")
    }

    private fun isPairingWriteError(error: NSError): Boolean {
        val domain = error.domain
        if (domain != "CBATTErrorDomain" && domain != "CBErrorDomain") return false
        return error.code == CBATT_INSUFFICIENT_AUTHENTICATION ||
            error.code == CBATT_INSUFFICIENT_ENCRYPTION
    }

    private fun isStaleBondError(error: NSError): Boolean {
        if (error.domain == "CBErrorDomain" &&
            (error.code == CBERROR_PEER_REMOVED_PAIRING || error.code == CBERROR_ENCRYPTION_TIMED_OUT)
        ) {
            return true
        }
        return looksLikeStaleBondError(error.localizedDescription)
    }

    companion object {
        private const val CBATT_INSUFFICIENT_AUTHENTICATION = 5L
        private const val CBATT_INSUFFICIENT_ENCRYPTION = 15L
        private const val CBERROR_PEER_REMOVED_PAIRING = 14L
        private const val CBERROR_ENCRYPTION_TIMED_OUT = 15L
        private const val PAIRING_WRITE_RETRIES = 60
        private const val PAIRING_RETRY_NS = 50_000_000L
        private const val GAP_CONNECT_NUDGE_NS = 2_000_000_000L
    }
}
