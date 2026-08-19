import CoreBluetooth
import Foundation

let serviceUUID = CBUUID(string: "7B5F1000-5D7A-4D2F-9A4C-14B7D5F00001")
let rxUUID = CBUUID(string: "7B5F1001-5D7A-4D2F-9A4C-14B7D5F00001")
let txUUID = CBUUID(string: "7B5F1002-5D7A-4D2F-9A4C-14B7D5F00001")

func emit(_ line: String) {
    fputs(line + "\n", stdout)
    fflush(stdout)
}

func emitError(_ message: String) {
    emit("ERROR \(message)")
}

func parseHex(_ text: String) -> Data? {
    let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard clean.count % 2 == 0, clean.count > 0 else { return nil }
    var data = Data()
    var index = clean.startIndex
    while index < clean.endIndex {
        let next = clean.index(index, offsetBy: 2)
        guard let byte = UInt8(clean[index..<next], radix: 16) else { return nil }
        data.append(byte)
        index = next
    }
    return data
}

func hex(_ data: Data) -> String {
    data.map { String(format: "%02X", $0) }.joined()
}

func jsonEscape(_ text: String) -> String {
    text
        .replacingOccurrences(of: "\\", with: "\\\\")
        .replacingOccurrences(of: "\"", with: "\\\"")
}

func parseAirDeviceId(_ name: String) -> UInt32? {
    let normalized = name.trimmingCharacters(in: .whitespacesAndNewlines)
    let suffix: String
    if normalized.hasPrefix("Test-DPLS-") && normalized.count == 14 {
        suffix = String(normalized.suffix(4))
    } else if normalized.hasPrefix("DPLS") && normalized.count == 8 {
        suffix = String(normalized.suffix(4))
    } else {
        return nil
    }
    return UInt32(suffix, radix: 16)
}

func isDplsAdvertisement(name: String, services: [CBUUID], overflow: [CBUUID]) -> Bool {
    if name.hasPrefix("Test-DPLS") || name.hasPrefix("DPLS") { return true }
    return services.contains(serviceUUID) || overflow.contains(serviceUUID)
}

func isPairingWriteError(_ error: Error) -> Bool {
    let ns = error as NSError
    if (ns.domain == "CBATTErrorDomain" || ns.domain == "CBErrorDomain") && (ns.code == 5 || ns.code == 15) {
        return true
    }
    let text = error.localizedDescription.lowercased()
    return text.contains("encrypt") || text.contains("auth") || text.contains("pair")
}

final class LineReader {
    private var buffer = Data()
    var onLine: ((String) -> Void)?

    func feed(_ chunk: Data) {
        buffer.append(chunk)
        while let range = buffer.firstRange(of: Data([0x0A])) {
            let lineData = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
            buffer.removeSubrange(buffer.startIndex..<range.upperBound)
            var line = String(data: lineData, encoding: .utf8) ?? ""
            if line.hasSuffix("\r") { line.removeLast() }
            if !line.isEmpty { onLine?(line) }
        }
    }
}

final class CentralRole: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private var manager: CBCentralManager!
    private var seen: [UUID: CBPeripheral] = [:]
    private var linked: CBPeripheral?
    private var rx: CBCharacteristic?
    private var tx: CBCharacteristic?
    private var pendingConnect: UUID?
    private var pendingWrites: [Data] = []
    private var lastWrite: Data?
    private var subscribed = false
    private var discoveringAll = false
    private var pairingTries = 0
    private let queue = DispatchQueue.main

    func start() {
        manager = CBCentralManager(
            delegate: self,
            queue: queue,
            options: [CBCentralManagerOptionShowPowerAlertKey: true]
        )
    }

    func handle(_ line: String) {
        if line == "SCAN" {
            startScan()
            return
        }
        if line == "STOP" {
            if manager.state == .poweredOn { manager.stopScan() }
            return
        }
        if line == "DISCONNECT" {
            if let linked { manager.cancelPeripheralConnection(linked) }
            return
        }
        if line == "QUIT" {
            if manager.state == .poweredOn { manager.stopScan() }
            if let linked { manager.cancelPeripheralConnection(linked) }
            exit(0)
        }
        if line.hasPrefix("CONNECT ") {
            let id = String(line.dropFirst(8)).trimmingCharacters(in: .whitespaces)
            guard let uuid = UUID(uuidString: id) else {
                emitError("unknown-peripheral")
                emit("DISCONNECTED")
                return
            }
            connect(uuid)
            return
        }
        if line.hasPrefix("WRITE ") {
            let hexStr = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
            guard let data = parseHex(hexStr) else { return }
            if let linked, let rx, subscribed {
                write(data, on: linked, characteristic: rx)
            } else {
                pendingWrites.append(data)
            }
        }
    }

    private func startScan() {
        guard manager.state == .poweredOn else { return }
        manager.scanForPeripherals(withServices: [serviceUUID], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: true,
        ])
    }

    private func resolve(_ uuid: UUID) -> CBPeripheral? {
        if let known = seen[uuid] { return known }
        if let retrieved = manager.retrievePeripherals(withIdentifiers: [uuid]).first {
            seen[uuid] = retrieved
            return retrieved
        }
        if let connected = manager.retrieveConnectedPeripherals(withServices: [serviceUUID])
            .first(where: { $0.identifier == uuid })
        {
            seen[uuid] = connected
            return connected
        }
        return nil
    }

    private func connect(_ uuid: UUID) {
        guard manager.state == .poweredOn else {
            pendingConnect = uuid
            return
        }
        guard let peripheral = resolve(uuid) else {
            pendingConnect = uuid
            startScan()
            emitError("waiting-for-peripheral")
            return
        }
        pendingConnect = nil
        subscribed = false
        discoveringAll = false
        pairingTries = 0
        pendingWrites.removeAll()
        rx = nil
        tx = nil
        manager.stopScan()
        linked = peripheral
        peripheral.delegate = self
        if peripheral.state == .connected {
            emit("CONNECTED \(peripheral.identifier.uuidString)")
            peripheral.discoverServices([serviceUUID])
            return
        }
        manager.connect(peripheral, options: nil)
        queue.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self, self.linked?.identifier == uuid, peripheral.state == .connecting else { return }
            self.manager.connect(peripheral, options: nil)
        }
    }

    private func write(_ data: Data, on peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        lastWrite = data
        peripheral.writeValue(data, for: characteristic, type: .withResponse)
    }

    private func flushWrites() {
        guard let linked, let rx, subscribed else { return }
        let queued = pendingWrites
        pendingWrites.removeAll()
        for data in queued {
            write(data, on: linked, characteristic: rx)
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            emit("READY")
            if let pendingConnect { connect(pendingConnect) }
            else { startScan() }
        } else {
            emitError("bluetooth \(central.state.rawValue)")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? peripheral.name
            ?? "Test-DPLS"
        let services = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? []
        let overflow = (advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey] as? [CBUUID]) ?? []
        guard isDplsAdvertisement(name: name, services: services, overflow: overflow) else { return }
        seen[peripheral.identifier] = peripheral
        if pendingConnect == peripheral.identifier {
            connect(peripheral.identifier)
        }
        let deviceId = parseAirDeviceId(name).map { String($0) } ?? "null"
        emit(
            "ADV {\"id\":\"\(peripheral.identifier.uuidString)\",\"rssi\":\(RSSI.intValue)," +
            "\"name\":\"\(jsonEscape(name))\",\"deviceId\":\(deviceId),\"firmware\":null," +
            "\"status\":0}"
        )
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        emit("CONNECTED \(peripheral.identifier.uuidString)")
        peripheral.delegate = self
        discoveringAll = false
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        emitError(error?.localizedDescription ?? "connect-failed")
        emit("DISCONNECTED")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        rx = nil
        tx = nil
        subscribed = false
        pendingWrites.removeAll()
        if linked?.identifier == peripheral.identifier { linked = nil }
        emit("DISCONNECTED")
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { emitError(error.localizedDescription); return }
        if let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) {
            peripheral.discoverCharacteristics([rxUUID, txUUID], for: service)
            return
        }
        if !discoveringAll {
            discoveringAll = true
            peripheral.discoverServices(nil)
            return
        }
        emitError("no-service")
        emit("DISCONNECTED")
        manager.cancelPeripheralConnection(peripheral)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { emitError(error.localizedDescription); return }
        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == rxUUID { rx = characteristic }
            if characteristic.uuid == txUUID {
                tx = characteristic
                if characteristic.isNotifying {
                    markSubscribed()
                } else {
                    peripheral.setNotifyValue(true, for: characteristic)
                }
            }
        }
        if tx == nil { emitError("no-tx") }
        if rx == nil { emitError("no-rx") }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            emitError(error.localizedDescription)
            return
        }
        if characteristic.uuid == txUUID && characteristic.isNotifying {
            markSubscribed()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error { emitError(error.localizedDescription); return }
        guard characteristic.uuid == txUUID, let value = characteristic.value else { return }
        emit("TX \(hex(value))")
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error, isPairingWriteError(error), pairingTries < 40, let lastWrite {
            pairingTries += 1
            queue.asyncAfter(deadline: .now() + 0.08) { [weak self] in
                guard let self, let linked = self.linked, let rx = self.rx else { return }
                self.write(lastWrite, on: linked, characteristic: rx)
            }
            return
        }
        if let error { emitError(error.localizedDescription) }
        else { pairingTries = 0 }
    }

    private func markSubscribed() {
        guard !subscribed, rx != nil else { return }
        subscribed = true
        emit("SUBSCRIBED")
        flushWrites()
    }
}

final class PeripheralRole: NSObject, CBPeripheralManagerDelegate {
    private var manager: CBPeripheralManager!
    private var rxChar: CBMutableCharacteristic!
    private var txChar: CBMutableCharacteristic!
    private var pendingTx: [Data] = []
    private let deviceId: UInt32

    init(deviceId: UInt32) {
        self.deviceId = deviceId
    }

    func start() {
        manager = CBPeripheralManager(delegate: self, queue: DispatchQueue.main)
    }

    func handle(_ line: String) {
        if line == "QUIT" {
            manager.stopAdvertising()
            exit(0)
        }
        if line.hasPrefix("TX ") {
            let hexStr = String(line.dropFirst(3)).trimmingCharacters(in: .whitespaces)
            guard let data = parseHex(hexStr) else { return }
            if !manager.updateValue(data, for: txChar, onSubscribedCentrals: nil) {
                pendingTx.append(data)
            }
        }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else {
            emitError("bluetooth \(peripheral.state.rawValue)")
            return
        }
        rxChar = CBMutableCharacteristic(
            type: rxUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        txChar = CBMutableCharacteristic(
            type: txUUID,
            properties: [.notify, .indicate],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [rxChar, txChar]
        peripheral.removeAllServices()
        peripheral.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            emitError(error.localizedDescription)
            return
        }
        // Flags + 128-bit UUID leave 10 bytes for the local name AD.
        // The lab uses the same UUID/name discovery contract as current firmware.
        let suffix = String(format: "%04X", UInt16(truncatingIfNeeded: deviceId))
        peripheral.startAdvertising([
            CBAdvertisementDataLocalNameKey: "DPLS\(suffix)",
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
        ])
        emit("READY")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == rxUUID, let value = request.value {
                emit("RX \(hex(value))")
                peripheral.respond(to: request, withResult: .success)
            } else {
                peripheral.respond(to: request, withResult: .requestNotSupported)
            }
        }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        if characteristic.uuid == txUUID {
            emit("CONNECTED")
            emit("SUBSCRIBED")
        }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        if characteristic.uuid == txUUID { emit("DISCONNECTED") }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        while let data = pendingTx.first {
            if peripheral.updateValue(data, for: txChar, onSubscribedCentrals: nil) {
                pendingTx.removeFirst()
            } else {
                break
            }
        }
    }
}

func argValue(_ args: [String], _ name: String) -> String? {
    guard let index = args.firstIndex(of: name), index + 1 < args.count else { return nil }
    return args[index + 1]
}

let args = Array(CommandLine.arguments.dropFirst())
let mode = args.first ?? ""
let reader = LineReader()
FileHandle.standardInput.readabilityHandler = { handle in
    let chunk = handle.availableData
    if chunk.isEmpty {
        exit(0)
    }
    DispatchQueue.main.async { reader.feed(chunk) }
}

if mode == "central" {
    let central = CentralRole()
    reader.onLine = { line in DispatchQueue.main.async { central.handle(line) } }
    central.start()
    RunLoop.main.run()
} else if mode == "peripheral" {
    let idText = argValue(args, "--id") ?? "0x1234"
    let deviceId = UInt32(idText.replacingOccurrences(of: "0x", with: ""), radix: 16)
        ?? UInt32(idText) ?? 0x1234
    let peripheral = PeripheralRole(deviceId: deviceId)
    reader.onLine = { line in DispatchQueue.main.async { peripheral.handle(line) } }
    peripheral.start()
    RunLoop.main.run()
} else {
    fputs("usage: dpls-ble central | peripheral [--id HEX]\n", stderr)
    exit(2)
}
