import Foundation
import CoreBluetooth
import Combine
import os.log

/// Owns the single CoreBluetooth connection and the Test-DPLS application session.
@MainActor
final class BleClient: NSObject, ObservableObject {
    static let serviceUUID = CBUUID(string: "7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001")
    static let rxUUID = CBUUID(string: "7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001")
    static let txUUID = CBUUID(string: "7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001")
    private static let manufacturerID: UInt16 = 0x0B01
    private static let maxLogEvents = 200

    @Published private(set) var uiState = DplsUiState()

    private let log = Logger(subsystem: "ru.bolid.testdpls", category: "Ble")
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var txCharacteristic: CBCharacteristic?
    private var knownPeripherals: [UUID: CBPeripheral] = [:]

    private var scanning = false
    private var selectedAddress: String?
    private var reconnectAttempt = 0
    private var negotiatedWriteLimit = 20
    private var sequence: UInt16 = 1
    private var commandId: UInt32 = 1
    private var sessionId: UInt32 = 0
    private var sessionToken = Data(count: 8)
    private var clientNonce = Data(count: 16)
    private var deviceNonce = Data(count: 16)
    private var authSalt = Data(count: 16)
    private var cachedVerifier: Data?
    private var pendingSetupName: String?
    private var initialized = false
    private var reachedReady = false
    private var awaitingDeviceInfo = false
    private var legacyFirmware = false

    private enum PendingSettings {
        case name(commandId: UInt32)
        case password(commandId: UInt32, newVerifier: Data)
        var commandId: UInt32 {
            switch self {
            case .name(let id), .password(let id, _): return id
            }
        }
    }
    private var pendingSettings: PendingSettings?

    private var logBytes = Data()
    private var logExpectedBytes = 0
    private var logExpectedEvents = 0
    private var logReceivedEvents = 0
    private var logChunkReceived: [Bool] = []
    private var logNextChunk = 0
    private var logInfoReceived = false
    private var logLoadPending = false
    private var pendingLogAckIndex: Int?
    private var logPendingChunks: [(Int, Data)] = []

    private var identifyAfterConnect = false
    private var pendingIdentifyAck = false
    private var writeQueue: [Data] = []
    private var writeInProgress = false

    private var scanStopWork: DispatchWorkItem?
    private var reconnectWork: DispatchWorkItem?
    private var keepAliveWork: DispatchWorkItem?
    private var preAuthKeepAliveWork: DispatchWorkItem?
    private var stateRefreshWork: DispatchWorkItem?
    private var logLoadTimeoutWork: DispatchWorkItem?
    private var logAckWork: DispatchWorkItem?
    private var settingsTimeoutWork: DispatchWorkItem?
    private var commandTimeoutWork: DispatchWorkItem?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil, options: [
            CBCentralManagerOptionShowPowerAlertKey: true,
        ])
    }

    // MARK: - Public API

    func startScan() {
        disconnectGatt(clearSelection: true)
        uiState = DplsUiState(phase: .scanning, statusText: "Поиск Test-DPLS…")
        guard central.state == .poweredOn else {
            fail("Включите Bluetooth")
            return
        }
        scanning = true
        central.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        scanStopWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                if self?.scanning == true { self?.stopScan() }
            }
        }
        scanStopWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: work)
    }

    func stopScan() {
        guard scanning else { return }
        central.stopScan()
        scanning = false
        scanStopWork?.cancel()
        uiState.phase = .idle
        uiState.statusText = uiState.devices.isEmpty ? "Устройства не найдены" : "Выберите устройство"
    }

    func showExternalError(_ message: String) { fail(message) }

    func connect(address: String) {
        stopScan()
        cancelReconnect()
        guard let uuid = UUID(uuidString: address),
              let device = knownPeripherals[uuid] ?? central.retrievePeripherals(withIdentifiers: [uuid]).first
        else {
            fail("Устройство недоступно. Запустите поиск снова.")
            return
        }
        knownPeripherals[uuid] = device
        selectedAddress = address
        legacyFirmware = false
        awaitingDeviceInfo = false
        uiState.phase = .connecting
        uiState.statusText = "Подключение…"
        uiState.selectedDevice = uiState.devices.first { $0.address == address }
        uiState.credentialsReady = false
        uiState.setupPassword = ""
        uiState.setupRepeatPassword = ""
        uiState.identifyLedLive = false
        uiState.error = nil
        closeCurrentGatt()
        pendingIdentifyAck = false
        peripheral = device
        device.delegate = self
        central.connect(device, options: nil)
        log.info("connect address=\(address, privacy: .public) attempt=\(self.reconnectAttempt)")
    }

    func identify(address: String) {
        identifyAfterConnect = true
        pendingIdentifyAck = false
        uiState.identifyActive = true
        uiState.identifyLedLive = false
        connect(address: address)
    }

    func stopIdentify() {
        identifyAfterConnect = false
        pendingIdentifyAck = false
        uiState.identifyActive = false
        uiState.identifyLedLive = false
        if rxCharacteristic != nil {
            sendPriority(.identifyStop)
        }
    }

    func confirmIdentifiedDevice() {
        stopIdentify()
        cancelPreAuthKeepAlive()
        guard !uiState.credentialsReady, rxCharacteristic != nil else { return }
        uiState.phase = .authenticating
        uiState.statusText = "Подключение…"
        uiState.identifyActive = false
        uiState.identifyLedLive = false
        uiState.error = nil
        sendPriority(.hello, clientNonce)
    }

    func updateSetupName(_ name: String) { uiState.setupName = name }
    func updateSetupPassword(_ password: String) { uiState.setupPassword = password }
    func updateSetupRepeatPassword(_ password: String) { uiState.setupRepeatPassword = password }

    func authenticate(password: String) {
        guard password.count >= 8 else { return fail("Пароль должен содержать не менее 8 символов") }
        cancelPreAuthKeepAlive()
        let verifier = DplsCrypto.deriveVerifier(password: password, salt: authSalt)
        cachedVerifier = verifier
        sendAuthProof(verifier)
    }

    func setup(deviceName: String, password: String) {
        let trimmed = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return fail("Введите имя устройства") }
        guard password.count >= 8 else { return fail("Пароль должен содержать не менее 8 символов") }
        let salt = DplsCrypto.randomBytes(16)
        let verifier = DplsCrypto.deriveVerifier(password: password, salt: salt)
        cachedVerifier = verifier
        pendingSetupName = trimmed
        let name = utf8Truncate(trimmed, maxBytes: 31)
        var payload = Data()
        LittleEndian.appendU32(&payload, sessionId)
        LittleEndian.appendU8(&payload, UInt8(name.count))
        payload.append(name)
        payload.append(salt)
        payload.append(verifier)
        send(.setup, payload)
    }

    func requestMode(_ mode: DplsMode) {
        guard uiState.controlsEnabled else { return }
        uiState.pendingMode = mode
    }

    func cancelMode() { uiState.pendingMode = nil }

    func confirmMode() {
        guard let mode = uiState.pendingMode else { return }
        let id = commandId
        commandId &+= 1
        var payload = Data()
        LittleEndian.appendU32(&payload, sessionId)
        payload.append(sessionToken)
        LittleEndian.appendU32(&payload, id)
        LittleEndian.appendU8(&payload, UInt8(mode.rawValue))
        uiState.commandInProgress = true
        uiState.pendingMode = nil
        uiState.statusText = "Команда отправлена…"
        updateStateRefreshSchedule()
        send(.modeSet, payload)
        commandTimeoutWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self, self.uiState.commandInProgress else { return }
                self.send(.stateGet, self.authenticatedPayload())
                self.uiState.statusText = "Запрос состояния устройства…"
            }
        }
        commandTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    func returnToNormal() {
        uiState.pendingMode = .normal
        confirmMode()
    }

    func requestDeviceInfo() {
        guard uiState.authenticated, peripheral != nil else { return }
        requestDeviceInfoInternal()
    }

    func clearSettingsOp() {
        clearPendingSettings()
        uiState.settingsOp = .none
        uiState.settingsError = nil
    }

    func setDeviceName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            uiState.settingsOp = .failed
            uiState.settingsError = "Введите имя устройства"
            return
        }
        guard uiState.authenticated, peripheral != nil else {
            uiState.settingsOp = .failed
            uiState.settingsError = "Нет соединения с устройством"
            return
        }
        let nameBytes = utf8Truncate(trimmed, maxBytes: 31)
        let id = commandId
        commandId &+= 1
        armPendingSettings(.name(commandId: id))
        var payload = Data()
        LittleEndian.appendU32(&payload, sessionId)
        payload.append(sessionToken)
        LittleEndian.appendU32(&payload, id)
        LittleEndian.appendU8(&payload, UInt8(nameBytes.count))
        payload.append(nameBytes)
        uiState.settingsOp = .inProgress
        uiState.settingsError = nil
        send(.nameSet, payload)
    }

    func changePassword(current: String, newPassword: String) {
        guard newPassword.count >= 8 else {
            uiState.settingsOp = .failed
            uiState.settingsError = "Пароль должен содержать не менее 8 символов"
            return
        }
        guard uiState.authenticated, peripheral != nil else {
            uiState.settingsOp = .failed
            uiState.settingsError = "Нет соединения с устройством"
            return
        }
        let currentVerifier = DplsCrypto.deriveVerifier(password: current, salt: authSalt)
        guard let cached = cachedVerifier, cached == currentVerifier else {
            uiState.settingsOp = .failed
            uiState.settingsError = "Неверный текущий пароль"
            return
        }
        let newSalt = DplsCrypto.randomBytes(16)
        let newVerifier = DplsCrypto.deriveVerifier(password: newPassword, salt: newSalt)
        let id = commandId
        commandId &+= 1
        armPendingSettings(.password(commandId: id, newVerifier: newVerifier))
        var payload = Data()
        LittleEndian.appendU32(&payload, sessionId)
        payload.append(sessionToken)
        LittleEndian.appendU32(&payload, id)
        payload.append(newSalt)
        payload.append(newVerifier)
        uiState.settingsOp = .inProgress
        uiState.settingsError = nil
        send(.passwordSet, payload)
    }

    func loadEventLog() {
        guard uiState.logProgress == nil else { return }
        logLoadPending = true
        logLoadTimeoutWork?.cancel()
        cancelKeepAlive()
        cancelStateRefresh()
        resetLogTransfer()
        uiState.logProgress = 0
        uiState.eventLog = []
        uiState.error = nil
        var window = Data()
        LittleEndian.appendU16(&window, 0)
        sendPriority(.logStart, authenticatedPayload() + window, flush: true)
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self, self.uiState.logProgress != nil else { return }
                self.logInfoReceived = false
                self.logLoadPending = false
                self.uiState.logProgress = nil
                self.uiState.error = "Не удалось загрузить журнал"
                self.scheduleKeepAlive()
                self.updateStateRefreshSchedule()
            }
        }
        logLoadTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 240, execute: work)
    }

    func refreshState() {
        guard uiState.authenticated, peripheral != nil, uiState.logProgress == nil else { return }
        send(.stateGet, authenticatedPayload())
        updateStateRefreshSchedule()
    }

    func disconnect() {
        selectedAddress = nil
        cachedVerifier = nil
        disconnectGatt(clearSelection: true)
        uiState = DplsUiState()
    }

    func eventLogCsv() -> String {
        let boot = uiState.deviceBootEpochSeconds
        let firstSeq = uiState.eventLog.filter { $0.type == 1 }.map(\.sequence).max() ?? 0
        var out = "sequence;datetime;uptime_seconds;event_type;parameter;event\n"
        for e in uiState.eventLog {
            let ts = dplsEventTime(e, currentRunFirstSeq: firstSeq, bootEpochSec: boot)
            out += "\(e.sequence);\(ts.full);\(e.timestampSeconds);\(e.type);\(e.parameter);\"\(dplsEventTitle(type: e.type, parameter: e.parameter))\"\n"
        }
        return out
    }

    func eventLogTxt() -> String {
        let boot = uiState.deviceBootEpochSeconds
        let firstSeq = uiState.eventLog.filter { $0.type == 1 }.map(\.sequence).max() ?? 0
        var out = "Журнал событий Тест-ДПЛС\n"
        out += "Устройство: \(uiState.deviceInfo?.userName ?? uiState.selectedDevice?.userName ?? "—")\n"
        out += "Записей: \(uiState.eventLog.count)\n"
        out += String(repeating: "—", count: 32) + "\n"
        for e in uiState.eventLog {
            let ts = dplsEventTime(e, currentRunFirstSeq: firstSeq, bootEpochSec: boot)
            out += "#\(e.sequence)  \(ts.full)  \(dplsEventTitle(type: e.type, parameter: e.parameter))\n"
        }
        return out
    }

    // MARK: - GATT lifecycle

    private func beginGattNegotiation() {
        guard let peripheral else { return }
        guard [.connecting, .pairing, .reconnecting].contains(uiState.phase) else { return }
        // iOS negotiates ATT MTU automatically; discover services next.
        uiState.phase = .discovering
        uiState.statusText = "Подключение…"
        peripheral.discoverServices([Self.serviceUUID])
    }

    private func acceptScan(peripheral: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        knownPeripherals[peripheral.identifier] = peripheral
        let address = peripheral.identifier.uuidString
        var deviceId: UInt32?
        if let mfg = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data, mfg.count >= 6 {
            let company = UInt16(mfg[0]) | (UInt16(mfg[1]) << 8)
            if company == Self.manufacturerID {
                var o = 2
                deviceId = LittleEndian.u32(mfg, &o)
            }
        }
        let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let name = localName
            ?? peripheral.name
            ?? deviceId.map { String(format: "Test-DPLS-%04X", $0 & 0xffff) }
            ?? "Test-DPLS"
        let discovered = DiscoveredDevice(
            address: address,
            advertisedName: name,
            userName: nil,
            deviceId: deviceId,
            rssi: rssi.intValue
        )
        if !uiState.devices.contains(where: { $0.address == address }) {
            log.info("Scan: \(name, privacy: .public) rssi=\(rssi.intValue)")
        }
        var devices = uiState.devices.filter { $0.address != address }
        devices.append(discovered)
        devices.sort { $0.rssi > $1.rssi }
        uiState.devices = devices
        uiState.statusText = "Найдено: \(devices.count)"
        if selectedAddress == address, uiState.phase == .reconnecting {
            connect(address: address)
        }
    }

    // MARK: - Frame handling

    private func handleFrame(_ bytes: Data) {
        switch DplsProtocol.decode(bytes) {
        case .failure(let reason): fail(reason)
        case .success(let frame): handleMessage(frame)
        }
    }

    private func handleMessage(_ frame: DplsProtocol.Frame) {
        let payload = frame.payload
        var offset = 0
        switch frame.type {
        case .authChallenge:
            guard payload.count >= 37 else { return fail("Повреждённый AUTH_CHALLENGE") }
            sessionId = LittleEndian.u32(payload, &offset)
            deviceNonce = payload.subdata(in: offset ..< offset + 16); offset += 16
            authSalt = payload.subdata(in: offset ..< offset + 16); offset += 16
            initialized = LittleEndian.u8(payload, &offset) != 0
            let autoAuth = initialized && cachedVerifier != nil
            uiState.initialized = initialized
            uiState.credentialsReady = true
            uiState.awaitingUserPassword = !autoAuth
            uiState.statusText = autoAuth ? "Вход…" : "Подключено"
            if uiState.setupName.isEmpty {
                uiState.setupName = uiState.selectedDevice?.userName ?? "Test-DPLS-001"
            }
            uiState.setupPassword = ""
            uiState.setupRepeatPassword = ""
            schedulePreAuthKeepAlive()
            if autoAuth, let verifier = cachedVerifier {
                sendAuthProof(verifier)
            }

        case .authResult:
            if uiState.authenticated { return }
            cancelPreAuthKeepAlive()
            let status = LittleEndian.u8(payload, &offset)
            let retryAfter = payload.count >= 3 ? Int(LittleEndian.u16(payload, &offset)) : 0
            if status != 0 && status == 3 {
                uiState.phase = .reconnecting
                uiState.statusText = "Настройка сохранена. Повторное подключение…"
                uiState.credentialsReady = true
                uiState.initialized = true
                uiState.awaitingUserPassword = false
                uiState.setupPassword = ""
                uiState.setupRepeatPassword = ""
                uiState.error = nil
                return
            }
            if status != 0 {
                uiState.awaitingUserPassword = true
                return fail(retryAfter > 0 ? "Аутентификация заблокирована на \(retryAfter) с" : "Неверный пароль")
            }
            if payload.count - offset >= 8 {
                sessionToken = payload.subdata(in: offset ..< offset + 8)
            }
            uiState.authenticated = true
            uiState.awaitingUserPassword = false
            uiState.identifyActive = false
            uiState.identifyLedLive = false
            uiState.phase = .synchronizing
            uiState.statusText = "Чтение состояния…"
            uiState.error = nil
            send(.stateGet, authenticatedPayload())
            scheduleKeepAlive()

        case .commandResult:
            guard payload.count >= 8 else { return fail("Повреждённый COMMAND_RESULT") }
            _ = LittleEndian.u32(payload, &offset)
            let result = LittleEndian.u8(payload, &offset)
            _ = LittleEndian.u8(payload, &offset)
            _ = LittleEndian.u16(payload, &offset)
            if result != 0 { return fail(commandRejectReason(Int(result))) }
            uiState.commandInProgress = false
            uiState.statusText = "Команда применена, чтение состояния…"
            uiState.lastAckMillis = Int64(Date().timeIntervalSince1970 * 1000)
            if uiState.logProgress == nil {
                send(.stateGet, authenticatedPayload())
            }

        case .deviceInfoReport:
            parseDeviceInfo(payload)

        case .settingsResult:
            guard payload.count >= 5 else { return }
            let cmdId = LittleEndian.u32(payload, &offset)
            let status = LittleEndian.u8(payload, &offset)
            guard let op = pendingSettings, op.commandId == cmdId else { return }
            settingsTimeoutWork?.cancel()
            pendingSettings = nil
            if status == 0 {
                if case .password(_, let newVerifier) = op {
                    cachedVerifier = newVerifier
                } else if case .name = op {
                    if peripheral != nil { requestDeviceInfoInternal() }
                }
                uiState.settingsOp = .done
                uiState.settingsError = nil
            } else {
                uiState.settingsOp = .failed
                uiState.settingsError = "Устройство отклонило изменение (код \(status))"
            }

        case .stateReport:
            parseState(payload)

        case .logInfo:
            guard payload.count >= 10 else { return failLog("Повреждённый LOG_INFO") }
            _ = LittleEndian.u32(payload, &offset)
            let totalBytes = Int(Int32(bitPattern: LittleEndian.u32(payload, &offset)))
            let rawCount = Int(LittleEndian.u16(payload, &offset))
            logExpectedEvents = min(min(rawCount, totalBytes / 10), Self.maxLogEvents)
            logExpectedEvents = max(0, logExpectedEvents)
            logExpectedBytes = logExpectedEvents * 10
            logInfoReceived = true
            logReceivedEvents = 0
            logNextChunk = 0
            if logExpectedEvents == 0 {
                logBytes = Data()
                logChunkReceived = []
                finishLog()
                return
            }
            logBytes = Data(count: logExpectedBytes)
            logChunkReceived = Array(repeating: false, count: logExpectedEvents)
            for (chunk, data) in logPendingChunks.sorted(by: { $0.0 < $1.0 }) {
                applyLogChunk(chunk, data)
            }
            logPendingChunks.removeAll()
            afterChunkBatch()

        case .logChunk:
            parseLogChunk(payload)

        case .logResult:
            finishLog()

        case .error:
            let code = Int(payload.first ?? 0)
            if uiState.logProgress != nil { return failLog("Ошибка загрузки журнала: \(code)") }
            if code == 5 && awaitingDeviceInfo {
                awaitingDeviceInfo = false
                legacyFirmware = true
                return
            }
            if code == 5 && pendingSettings != nil {
                clearPendingSettings()
                legacyFirmware = true
                uiState.settingsOp = .failed
                uiState.settingsError = "Прошивка устройства не поддерживает изменение настроек"
                return
            }
            fail(deviceErrorReason(code))

        default:
            break
        }
    }

    private func parseDeviceInfo(_ raw: Data) {
        awaitingDeviceInfo = false
        guard raw.count >= 12 else { return }
        var o = 0
        let deviceId = LittleEndian.u32(raw, &o)
        let proto = Int(LittleEndian.u8(raw, &o))
        let major = Int(LittleEndian.u8(raw, &o))
        let minor = Int(LittleEndian.u8(raw, &o))
        let patch = Int(LittleEndian.u8(raw, &o))
        let hwRev = Int(LittleEndian.u8(raw, &o))
        let caps = Int(LittleEndian.u8(raw, &o))
        _ = LittleEndian.u8(raw, &o)
        let nameLen = Int(LittleEndian.u8(raw, &o))
        let name: String
        if nameLen > 0, 12 + nameLen <= raw.count {
            name = String(data: raw.subdata(in: 12 ..< 12 + nameLen), encoding: .utf8) ?? ""
        } else {
            name = ""
        }
        let info = DeviceInfo(
            deviceId: deviceId,
            protocolVersion: proto,
            firmwareVersion: "\(major).\(minor).\(patch)",
            hardwareRevision: hwRev,
            adcPresent: (caps & 0x01) != 0,
            hardwareReadback: (caps & 0x02) != 0,
            adcCalibrated: (caps & 0x04) != 0,
            multiVoltageReport: (caps & 0x08) != 0,
            userName: name
        )
        uiState.deviceInfo = info
        if var selected = uiState.selectedDevice {
            selected.userName = name.isEmpty ? selected.userName : name
            uiState.selectedDevice = selected
        }
    }

    private func parseState(_ payload: Data) {
        guard payload.count >= 16 else { return fail("Повреждённый STATE_REPORT") }
        var o = 0
        let mode = DplsMode.fromWire(Int(LittleEndian.u8(payload, &o))) ?? .normal
        let power = LittleEndian.u8(payload, &o) == 0 ? PowerSource.dpls : PowerSource.reserve
        let voltage = Int(LittleEndian.u16(payload, &o))
        let automaticReturn = Int(LittleEndian.u16(payload, &o))
        let reserveLow = LittleEndian.u8(payload, &o) != 0
        let flags = LittleEndian.u8(payload, &o)
        let realShort = (flags & 0x02) != 0
        let uptimeSeconds = LittleEndian.u32(payload, &o)
        let revision = LittleEndian.u32(payload, &o)
        let validity = payload.count > 16 ? Int(LittleEndian.u8(payload, &o)) : 0x00
        let extended = payload.count >= o + 8
        let port1 = extended ? Int(LittleEndian.u16(payload, &o)) : voltage
        let port2 = extended ? Int(LittleEndian.u16(payload, &o)) : 0
        let portT = extended ? Int(LittleEndian.u16(payload, &o)) : 0
        let reserveMv = extended ? Int(LittleEndian.u16(payload, &o)) : 0
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let bootEpoch = Int64(Date().timeIntervalSince1970) - Int64(uptimeSeconds)
        uiState.phase = .ready
        uiState.statusText = "Состояние получено"
        uiState.state = DeviceState(
            mode: mode,
            voltageMv: voltage,
            powerSource: power,
            reserveLow: reserveLow,
            realShort: realShort,
            automaticReturnSeconds: automaticReturn,
            uptimeSeconds: uptimeSeconds,
            revision: revision,
            receivedAtMillis: nowMs,
            lineVoltageValid: (validity & 0x01) != 0,
            reserveValid: (validity & 0x02) != 0,
            powerValid: (validity & 0x04) != 0,
            autoIsoValid: (validity & 0x08) != 0,
            adcCalibrated: (validity & 0x10) != 0,
            port1VoltageMv: port1,
            port2VoltageMv: port2,
            portTVoltageMv: portT,
            reserveVoltageMv: reserveMv,
            port1VoltageValid: (validity & 0x01) != 0,
            port2VoltageValid: extended && (validity & 0x20) != 0,
            portTVoltageValid: extended && (validity & 0x40) != 0,
            reserveVoltageValid: extended && (validity & 0x02) != 0
        )
        uiState.deviceBootEpochSeconds = bootEpoch
        uiState.authenticated = true
        uiState.identifyActive = false
        uiState.identifyLedLive = false
        uiState.commandInProgress = false
        uiState.staleState = false
        uiState.lastAckMillis = nowMs
        uiState.error = nil
        reachedReady = true
        reconnectAttempt = 0
        if uiState.logProgress == nil { updateStateRefreshSchedule() }
        if uiState.deviceInfo == nil && !legacyFirmware && !awaitingDeviceInfo && uiState.logProgress == nil {
            requestDeviceInfoInternal()
        }
        if logLoadPending {
            loadEventLog()
        }
    }

    private func parseLogChunk(_ payload: Data) {
        guard payload.count >= 3 else { return }
        var o = 0
        let first = Int(LittleEndian.u16(payload, &o))
        let count = Int(LittleEndian.u8(payload, &o))
        guard count > 0, payload.count - o >= count * 10 else { return }
        if !logInfoReceived {
            for i in 0 ..< count {
                let data = payload.subdata(in: o ..< o + 10); o += 10
                let idx = first + i
                logPendingChunks.removeAll { $0.0 == idx }
                logPendingChunks.append((idx, data))
            }
            return
        }
        for i in 0 ..< count {
            let data = payload.subdata(in: o ..< o + 10); o += 10
            applyLogChunk(first + i, data)
        }
        afterChunkBatch()
    }

    private func applyLogChunk(_ chunk: Int, _ data: Data) {
        guard logExpectedEvents > 0, chunk >= 0, chunk < logExpectedEvents else { return }
        guard !logChunkReceived[chunk] else { return }
        let start = chunk * 10
        logBytes.replaceSubrange(start ..< start + 10, with: data)
        logChunkReceived[chunk] = true
        logReceivedEvents += 1
    }

    private func afterChunkBatch() {
        guard logExpectedEvents > 0 else { return }
        let progress = Float(logReceivedEvents) / Float(logExpectedEvents)
        uiState.logProgress = min(max(progress, 0.05), 1)
        if logReceivedEvents >= logExpectedEvents {
            finishLog()
        } else {
            scheduleLogAck()
        }
    }

    private func scheduleLogAck() {
        let next = logChunkReceived.firstIndex(where: { !$0 }) ?? logExpectedEvents
        pendingLogAckIndex = next
        logAckWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in self?.flushLogAck() }
        }
        logAckWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.02, execute: work)
    }

    private func flushLogAck() {
        guard uiState.logProgress != nil else { return }
        if writeInProgress {
            scheduleLogAck()
            return
        }
        guard let index = pendingLogAckIndex else { return }
        pendingLogAckIndex = nil
        logNextChunk = index
        var chunk = Data()
        LittleEndian.appendU16(&chunk, UInt16(index))
        let bytes = DplsProtocol.encode(
            DplsProtocol.Frame(type: .logAck, sequence: nextSequence(), payload: authenticatedPayload() + chunk)
        )
        writeQueue.removeAll()
        writeQueue.append(bytes)
        drainWriteQueue()
    }

    private func finishLog() {
        logLoadTimeoutWork?.cancel()
        logAckWork?.cancel()
        logInfoReceived = false
        var records: [EventRecord] = []
        var o = 0
        while o + 10 <= logBytes.count {
            let seq = LittleEndian.u32(logBytes, &o)
            let ts = LittleEndian.u32(logBytes, &o)
            let type = Int(LittleEndian.u8(logBytes, &o))
            let param = Int(LittleEndian.u8(logBytes, &o))
            records.append(EventRecord(sequence: seq, timestampSeconds: ts, type: type, parameter: param))
        }
        records.sort { $0.sequence > $1.sequence }
        logLoadPending = false
        uiState.eventLog = records
        uiState.logProgress = nil
        uiState.statusText = "Журнал загружен: \(records.count) записей"
        uiState.error = nil
        scheduleKeepAlive()
        updateStateRefreshSchedule()
    }

    // MARK: - Send / crypto helpers

    private func sendAuthProof(_ verifier: Data) {
        var signed = Data()
        signed.append(deviceNonce)
        signed.append(clientNonce)
        LittleEndian.appendU32(&signed, sessionId)
        let mac = DplsCrypto.hmacSHA256(key: verifier, message: signed)
        send(.authProof, clientNonce + mac)
    }

    private func authenticatedPayload() -> Data {
        var data = Data()
        LittleEndian.appendU32(&data, sessionId)
        data.append(sessionToken)
        return data
    }

    private func send(_ type: DplsProtocol.MessageType, _ payload: Data = Data()) {
        enqueueWrite(DplsProtocol.encode(DplsProtocol.Frame(type: type, sequence: nextSequence(), payload: payload)))
    }

    private func sendPriority(_ type: DplsProtocol.MessageType, _ payload: Data = Data(), flush: Bool = false) {
        if flush { resetWriteState() }
        let bytes = DplsProtocol.encode(DplsProtocol.Frame(type: type, sequence: nextSequence(), payload: payload))
        if bytes.count > negotiatedWriteLimit {
            fail("Кадр \(bytes.count) байт не помещается в лимит записи \(negotiatedWriteLimit)")
            return
        }
        writeQueue.insert(bytes, at: 0)
        drainWriteQueue()
    }

    private func enqueueWrite(_ bytes: Data) {
        if bytes.count > negotiatedWriteLimit {
            fail("Кадр \(bytes.count) байт не помещается в лимит записи \(negotiatedWriteLimit)")
            return
        }
        if uiState.logProgress != nil { return }
        writeQueue.append(bytes)
        drainWriteQueue()
    }

    private func drainWriteQueue() {
        guard !writeInProgress,
              let peripheral,
              let rx = rxCharacteristic,
              !writeQueue.isEmpty
        else { return }
        let bytes = writeQueue.removeFirst()
        writeInProgress = true
        peripheral.writeValue(bytes, for: rx, type: .withResponse)
    }

    private func nextSequence() -> UInt16 {
        let current = sequence
        sequence = sequence &+ 1
        return current
    }

    private func requestDeviceInfoInternal() {
        awaitingDeviceInfo = true
        send(.deviceInfoGet, authenticatedPayload())
    }

    private func armPendingSettings(_ op: PendingSettings) {
        clearPendingSettings()
        pendingSettings = op
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self, self.pendingSettings != nil else { return }
                self.clearPendingSettings()
                self.uiState.settingsOp = .failed
                self.uiState.settingsError = "Устройство не ответило на изменение настроек"
            }
        }
        settingsTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: work)
    }

    private func clearPendingSettings() {
        settingsTimeoutWork?.cancel()
        pendingSettings = nil
    }

    private func resetLogTransfer() {
        logBytes = Data()
        logExpectedBytes = 0
        logExpectedEvents = 0
        logReceivedEvents = 0
        logChunkReceived = []
        logNextChunk = 0
        logInfoReceived = false
        pendingLogAckIndex = nil
        logPendingChunks.removeAll()
    }

    private func resetWriteState() {
        writeQueue.removeAll()
        writeInProgress = false
    }

    // MARK: - Timers / reconnect

    private func schedulePreAuthKeepAlive() {
        cancelPreAuthKeepAlive()
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                if self.uiState.identifyActive {
                    self.schedulePreAuthKeepAlive()
                    return
                }
                if self.uiState.credentialsReady && !self.uiState.authenticated && self.peripheral != nil {
                    self.send(.keepAlive)
                    self.schedulePreAuthKeepAlive()
                }
            }
        }
        preAuthKeepAliveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    private func cancelPreAuthKeepAlive() { preAuthKeepAliveWork?.cancel(); preAuthKeepAliveWork = nil }

    private func scheduleKeepAlive() {
        cancelKeepAlive()
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                if self.uiState.authenticated, self.peripheral != nil, self.uiState.logProgress == nil,
                   !self.uiState.needsPeriodicStateRefresh {
                    self.send(.keepAlive, self.authenticatedPayload())
                }
                if self.uiState.authenticated, self.peripheral != nil {
                    self.scheduleKeepAlive()
                }
            }
        }
        keepAliveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    private func cancelKeepAlive() { keepAliveWork?.cancel(); keepAliveWork = nil }

    private func updateStateRefreshSchedule() {
        cancelStateRefresh()
        guard uiState.needsPeriodicStateRefresh, peripheral != nil else { return }
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                guard self.uiState.needsPeriodicStateRefresh, self.peripheral != nil else { return }
                self.send(.stateGet, self.authenticatedPayload())
                self.updateStateRefreshSchedule()
            }
        }
        stateRefreshWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: work)
    }

    private func cancelStateRefresh() { stateRefreshWork?.cancel(); stateRefreshWork = nil }

    private func scheduleReconnect() {
        guard reconnectWork == nil else { return }
        cancelPreAuthKeepAlive()
        rxCharacteristic = nil
        txCharacteristic = nil
        resetWriteState()
        sessionToken = Data(count: 8)
        if pendingSettings != nil {
            clearPendingSettings()
            if uiState.settingsOp == .inProgress {
                uiState.settingsOp = .failed
                uiState.settingsError = "Связь прервана до подтверждения изменения"
            }
        }
        if !reachedReady && reconnectAttempt >= 3 {
            fail("Не удалось установить устойчивое BLE-соединение")
            return
        }
        uiState.phase = .reconnecting
        uiState.statusText = reachedReady || logLoadPending ? "Восстановление связи…" : "Подключение…"
        uiState.staleState = uiState.state != nil
        uiState.credentialsReady = cachedVerifier != nil
        uiState.authenticated = false
        guard let address = selectedAddress else { return }
        let delays: [Double] = [0.5, 1, 2, 4, 5]
        let delay = delays[min(reconnectAttempt, delays.count - 1)]
        reconnectAttempt += 1
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.reconnectWork = nil
                if self.selectedAddress == address { self.connect(address: address) }
            }
        }
        reconnectWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func cancelReconnect() { reconnectWork?.cancel(); reconnectWork = nil }

    private func disconnectGatt(clearSelection: Bool) {
        stopScan()
        cancelPreAuthKeepAlive()
        cancelKeepAlive()
        cancelStateRefresh()
        cancelReconnect()
        clearPendingSettings()
        awaitingDeviceInfo = false
        closeCurrentGatt()
        rxCharacteristic = nil
        txCharacteristic = nil
        resetWriteState()
        reachedReady = false
        reconnectAttempt = 0
        if clearSelection { selectedAddress = nil }
    }

    private func closeCurrentGatt() {
        if let peripheral {
            central.cancelPeripheralConnection(peripheral)
        }
        self.peripheral = nil
    }

    private func failLog(_ message: String) {
        log.error("\(message, privacy: .public)")
        logLoadTimeoutWork?.cancel()
        logInfoReceived = false
        logLoadPending = false
        uiState.logProgress = nil
        uiState.error = message
        scheduleKeepAlive()
    }

    private func fail(_ message: String) {
        log.error("\(message, privacy: .public)")
        logLoadTimeoutWork?.cancel()
        logInfoReceived = false
        uiState.phase = .error
        uiState.statusText = message
        uiState.error = message
        uiState.commandInProgress = false
        uiState.logProgress = nil
    }

    private func deviceErrorReason(_ code: Int) -> String {
        switch code {
        case 7:
            return "Окно первичной настройки закрыто. Выключите и включите устройство, затем повторите настройку в течение нескольких минут."
        default:
            return "Ошибка устройства: \(code)"
        }
    }

    private func commandRejectReason(_ status: Int) -> String {
        switch status {
        case 3: return "Команда отклонена: недопустимый режим"
        case 4: return "Команда отклонена: аппаратное переключение не удалось"
        case 5: return "Команда отклонена: активна автоизоляция реального КЗ"
        default: return "Команда отклонена устройством: \(status)"
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension BleClient: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            switch central.state {
            case .poweredOn:
                reconnectAttempt = 0
                if selectedAddress != nil { scheduleReconnect() }
            case .poweredOff, .unauthorized, .unsupported:
                cancelPreAuthKeepAlive()
                cancelKeepAlive()
                cancelStateRefresh()
                cancelReconnect()
                closeCurrentGatt()
                scanning = false
                rxCharacteristic = nil
                txCharacteristic = nil
                resetWriteState()
                sessionToken = Data(count: 8)
                cachedVerifier = nil
                uiState.phase = .reconnecting
                uiState.statusText = "Bluetooth выключен"
                uiState.credentialsReady = false
                uiState.authenticated = false
                uiState.staleState = uiState.state != nil
                uiState.commandInProgress = false
            default:
                break
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        Task { @MainActor in
            acceptScan(peripheral: peripheral, advertisementData: advertisementData, rssi: RSSI)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            guard peripheral.identifier.uuidString == selectedAddress else {
                central.cancelPeripheralConnection(peripheral)
                return
            }
            log.info("Connected \(peripheral.identifier.uuidString, privacy: .public)")
            // Pairing/encryption is triggered by iOS when writing encrypted chars.
            uiState.phase = .connecting
            beginGattNegotiation()
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            log.error("Connect failed: \(error?.localizedDescription ?? "?", privacy: .public)")
            if selectedAddress != nil { scheduleReconnect() }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            guard peripheral.identifier.uuidString == selectedAddress || self.peripheral === peripheral else { return }
            let wasLoadingLog = uiState.logProgress != nil
            if self.peripheral === peripheral { self.peripheral = nil }
            if wasLoadingLog {
                logLoadTimeoutWork?.cancel()
                logInfoReceived = false
                logLoadPending = true
                resetWriteState()
                uiState.logProgress = nil
                uiState.error = nil
                uiState.statusText = "Восстановление связи…"
            }
            if selectedAddress != nil {
                scheduleReconnect()
            } else {
                uiState.phase = .idle
            }
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BleClient: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in
            guard peripheral === self.peripheral, uiState.phase == .discovering else { return }
            if let error {
                return fail("Поиск службы: \(error.localizedDescription)")
            }
            guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
                return fail("Служба Test-DPLS не найдена")
            }
            uiState.phase = .subscribing
            uiState.statusText = "Подключение…"
            peripheral.discoverCharacteristics([Self.rxUUID, Self.txUUID], for: service)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        Task { @MainActor in
            guard peripheral === self.peripheral else { return }
            if let error {
                return fail("Характеристики: \(error.localizedDescription)")
            }
            rxCharacteristic = service.characteristics?.first { $0.uuid == Self.rxUUID }
            txCharacteristic = service.characteristics?.first { $0.uuid == Self.txUUID }
            guard let tx = txCharacteristic, rxCharacteristic != nil else {
                return fail("Служба Test-DPLS не найдена")
            }
            negotiatedWriteLimit = max(
                peripheral.maximumWriteValueLength(for: .withResponse),
                20
            )
            log.info("Write limit=\(self.negotiatedWriteLimit)")
            uiState.phase = .subscribing
            peripheral.setNotifyValue(true, for: tx)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            guard peripheral === self.peripheral, characteristic.uuid == Self.txUUID else { return }
            if let error {
                return fail("Подписка на BLE-события: \(error.localizedDescription)")
            }
            clientNonce = DplsCrypto.randomBytes(16)
            if identifyAfterConnect {
                identifyAfterConnect = false
                pendingIdentifyAck = true
                uiState.phase = .authenticating
                uiState.statusText = "Показать на объекте…"
                // Pairing prompt typically appears on first encrypted write.
                uiState.phase = .pairing
                uiState.statusText = "Подтвердите сопряжение…"
                send(.identifyStart)
            } else {
                uiState.phase = .pairing
                uiState.statusText = "Подключение…"
                send(.hello, clientNonce)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            guard peripheral === self.peripheral, characteristic.uuid == Self.txUUID else { return }
            if let error {
                log.error("Indication error: \(error.localizedDescription, privacy: .public)")
                return
            }
            guard let value = characteristic.value else { return }
            handleFrame(value)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            guard peripheral === self.peripheral, characteristic.uuid == Self.rxUUID else { return }
            writeInProgress = false
            if let error {
                // Encryption/pairing in progress — retry shortly.
                let ns = error as NSError
                log.error("TX write error domain=\(ns.domain, privacy: .public) code=\(ns.code)")
                if uiState.phase == .pairing || uiState.identifyActive || (uiState.credentialsReady && !uiState.authenticated) {
                    // Re-queue and continue; iOS may show pairing dialog.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                        Task { @MainActor in self?.drainWriteQueue() }
                    }
                    return
                }
                if reachedReady {
                    if let peripheral = self.peripheral {
                        central.cancelPeripheralConnection(peripheral)
                    }
                    return
                }
                fail("Ошибка передачи BLE: \(ns.code)")
                return
            }
            if pendingIdentifyAck {
                pendingIdentifyAck = false
                uiState.identifyLedLive = true
                if uiState.phase == .pairing {
                    uiState.phase = .authenticating
                    uiState.statusText = "Показать на объекте…"
                }
            } else if uiState.phase == .pairing {
                uiState.phase = .authenticating
                uiState.statusText = "Подключение…"
            }
            drainWriteQueue()
            flushLogAck()
        }
    }
}
