@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.bolid.testdpls.core.app

import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
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
    /** CoreBluetooth does not expose bond state. The protected RX write itself is
     * the observable security transition, so its blocked frame is the state. */
    private sealed interface SecurityState {
        data object Idle : SecurityState
        data class Pairing(val blockedWrite: ByteArray) : SecurityState
        data class Resuming(val blockedWrite: ByteArray) : SecurityState
        data object Failed : SecurityState
    }

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
    private var subscribed = false
    private var securityState: SecurityState = SecurityState.Idle
    private var securityEpoch = 0L

    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                listener?.onBluetoothAvailable()
            } else {
                clearSecurityState()
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
            listener?.onDiscovered(
                DplsBle.discovered(
                    address = address,
                    advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String,
                    peripheralName = didDiscoverPeripheral.name,
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
            if (securityState is SecurityState.Pairing || securityState is SecurityState.Resuming) {
                scheduleSecurityReconnect(didFailToConnectPeripheral)
                return
            }
            deliverLinkFailure(didFailToConnectPeripheral, "connect", error)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            if (!isSelected(didDisconnectPeripheral)) return
            if (error != null && isStaleBondError(error)) {
                reportStaleBond(didDisconnectPeripheral)
                return
            }
            val securityHandshake = securityState is SecurityState.Pairing ||
                securityState is SecurityState.Resuming
            peripheral = null
            rx = null
            subscribed = false
            resetWrites()
            if (securityHandshake) {
                /* SMP may tear down the ACL and finish key establishment around
                 * that disconnect. Preserve the blocked HELLO and reconnect; the
                 * protected-write result, not the disconnect status, decides. */
                scheduleSecurityReconnect(didDisconnectPeripheral)
                return
            }
            deliverLinkFailure(didDisconnectPeripheral, "disconnect", error)
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
            if (error != null && isPairingWriteError(error)) {
                val blocked = inFlightWrite ?: run {
                    listener?.onWriteComplete(error.code)
                    return
                }
                inFlightWrite = null
                securityState = SecurityState.Pairing(blocked.copyOf())
                scheduleSecurityRetry(peripheral)
                return
            }
            inFlightWrite = null
            if (error == null) clearSecurityState()
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
        clearSecurityState()
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
        clearSecurityState()
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
        if (!subscribed) {
            subscribed = true
            listener?.onSubscribed(writeLimit)
        }
        when (securityState) {
            is SecurityState.Pairing -> scheduleSecurityRetry(peripheral ?: return)
            is SecurityState.Resuming -> resumeSecurityWrite()
            else -> Unit
        }
    }

    private fun drainWrites() {
        if (writeInProgress || securityState is SecurityState.Pairing ||
            securityState is SecurityState.Resuming
        ) {
            return
        }
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
    }

    private fun clearSecurityState() {
        securityEpoch++
        securityState = SecurityState.Idle
    }

    private fun scheduleSecurityRetry(target: CBPeripheral) {
        val state = securityState as? SecurityState.Pairing ?: return
        val epoch = ++securityEpoch
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, SECURITY_RETRY_NS), dispatch_get_main_queue()) {
            if (epoch != securityEpoch || !isSelected(target)) return@dispatch_after
            val currentState = securityState as? SecurityState.Pairing ?: return@dispatch_after
            if (target.state != CBPeripheralStateConnected || peripheral !== target || !subscribed || rx == null) {
                scheduleSecurityReconnect(target)
                return@dispatch_after
            }
            securityState = SecurityState.Resuming(currentState.blockedWrite)
            resumeSecurityWrite()
        }
        check(state.blockedWrite.isNotEmpty())
    }

    private fun resumeSecurityWrite() {
        if (writeInProgress || peripheral == null || rx == null || !subscribed) return
        val state = securityState as? SecurityState.Resuming ?: return
        securityState = SecurityState.Idle
        writeQueue.addFirst(state.blockedWrite)
        drainWrites()
    }

    private fun scheduleSecurityReconnect(target: CBPeripheral) {
        val state = securityState
        if (state !is SecurityState.Pairing && state !is SecurityState.Resuming) return
        val epoch = ++securityEpoch
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, SECURITY_RECONNECT_NS), dispatch_get_main_queue()) {
            if (epoch != securityEpoch || !isSelected(target)) return@dispatch_after
            if (target.state == CBPeripheralStateConnected) {
                peripheral = target
                target.delegate = delegate
                target.discoverServices(listOf(serviceUuid))
            } else if (target.state != CBPeripheralStateConnecting) {
                peripheral = target
                target.delegate = delegate
                central.connectPeripheral(target, options = null)
            }
        }
    }

    private fun reportStaleBond(peripheral: CBPeripheral) {
        securityEpoch++
        securityState = SecurityState.Failed
        inFlightWrite = null
        listener?.onStaleBond()
        central.cancelPeripheralConnection(peripheral)
    }

    private fun deliverLinkFailure(peripheral: CBPeripheral, stage: String, error: NSError?) {
        if (error != null && isStaleBondError(error)) {
            reportStaleBond(peripheral)
            return
        }
        val detail = error?.let { "$stage: ${it.domain}/${it.code}: ${it.localizedDescription}" }
        listener?.onDisconnected(detail)
    }

    private fun deliverNsError(peripheral: CBPeripheral, prefix: String, error: NSError) {
        if (isStaleBondError(error)) {
            reportStaleBond(peripheral)
            return
        }
        listener?.onTransportError("$prefix: ${error.domain}/${error.code}: ${error.localizedDescription}")
    }

    private fun isPairingWriteError(error: NSError): Boolean {
        val domain = error.domain
        if (domain != "CBATTErrorDomain" && domain != "CBErrorDomain") return false
        return error.code == CBATT_INSUFFICIENT_AUTHENTICATION ||
            error.code == CBATT_INSUFFICIENT_ENCRYPTION
    }

    /* encryptionTimedOut is not proof of stale keys: it can happen during a
     * perfectly fresh pairing on a slow/noisy link. Only the explicit
     * peerRemovedPairing signal is deterministic stale-bond evidence here. */
    private fun isStaleBondError(error: NSError): Boolean =
        error.domain == "CBErrorDomain" && error.code == CBERROR_PEER_REMOVED_PAIRING

    companion object {
        private const val CBATT_INSUFFICIENT_AUTHENTICATION = 5L
        private const val CBATT_INSUFFICIENT_ENCRYPTION = 15L
        private const val CBERROR_PEER_REMOVED_PAIRING = 14L
        private const val SECURITY_RETRY_NS = 500_000_000L
        private const val SECURITY_RECONNECT_NS = 500_000_000L
    }
}
