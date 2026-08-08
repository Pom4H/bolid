import Foundation

enum DplsMode: Int, CaseIterable, Identifiable {
    case normal = 0
    case openT = 1
    case openMain = 2
    case short1 = 3
    case short2 = 4
    case shortT = 5

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .normal: return "Норма"
        case .openT: return "Обрыв +Т"
        case .openMain: return "Обрыв магистрали"
        case .short1: return "КЗ +1"
        case .short2: return "КЗ +2"
        case .shortT: return "КЗ +Т"
        }
    }

    var dangerous: Bool { self != .normal }

    var portHint: String {
        switch self {
        case .normal: return "Штатное прохождение линии"
        case .openT: return "Ответвление +Т"
        case .openMain: return "Магистраль +1 ↔ +2"
        case .short1: return "Порт +1"
        case .short2: return "Порт +2"
        case .shortT: return "Ответвление +Т"
        }
    }

    var controllerEffect: String {
        switch self {
        case .normal: return ""
        case .openT: return "КДЛ: потеря устройств ответвления"
        case .openMain: return "КДЛ: «Нет связи» с устройствами за разрывом"
        case .short1, .short2, .shortT: return "КДЛ: «Короткое замыкание ДПЛС»"
        }
    }

    static func fromWire(_ value: Int) -> DplsMode? { DplsMode(rawValue: value) }
}

enum PowerSource {
    case dpls, reserve
    var title: String { self == .dpls ? "ДПЛС" : "Резерв" }
}

struct DplsEventTime {
    let dateLabel: String?
    let time: String
    let full: String
}

func dplsEventTime(_ e: EventRecord, currentRunFirstSeq: UInt32, bootEpochSec: Int64?) -> DplsEventTime {
    if e.sequence >= currentRunFirstSeq, let boot = bootEpochSec {
        let date = Date(timeIntervalSince1970: TimeInterval(boot + Int64(e.timestampSeconds)))
        let cal = Calendar.current
        let c = cal.dateComponents([.day, .month, .year, .hour, .minute, .second], from: date)
        let time = String(format: "%02d:%02d:%02d", c.hour ?? 0, c.minute ?? 0, c.second ?? 0)
        let dateLabel = String(format: "%02d.%02d.%04d", c.day ?? 0, c.month ?? 0, c.year ?? 0)
        return DplsEventTime(dateLabel: dateLabel, time: time, full: "\(dateLabel) \(time)")
    }
    let t = e.timestampSeconds
    let rel = String(format: "+%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    return DplsEventTime(dateLabel: nil, time: rel, full: "\(rel) (от запуска)")
}

func dplsEventTitle(type: Int, parameter: Int) -> String {
    switch type {
    case 1: return "Запуск устройства"
    case 2: return "BLE подключение"
    case 3: return "BLE отключение"
    case 4: return "Успешный вход"
    case 5: return "Ошибка входа · попытка \(parameter)"
    case 6: return "Вход заблокирован"
    case 7: return "Режим: \(DplsMode.fromWire(parameter)?.title ?? "код \(parameter)")"
    case 8: return dplsAutoReturnTitle(parameter)
    case 9: return "Идентификация начата"
    case 10: return "Идентификация остановлена"
    case 11: return "Пароль установлен"
    case 12: return "Питание: \(parameter == 0 ? "от ДПЛС" : "от резерва")"
    case 13: return "Резерв: \(parameter == 0 ? "норма" : "низкий заряд")"
    case 14: return "Автоизоляция КЗ: \(parameter == 0 ? "снята" : "активна")"
    default: return "Событие \(type) · \(parameter)"
    }
}

private func dplsAutoReturnTitle(_ reason: Int) -> String {
    switch reason {
    case 0: return "Автовозврат в «Норма» (команда оператора)"
    case 1: return "Автовозврат в «Норма» (таймер)"
    case 2: return "Автовозврат в «Норма» (таймаут сессии)"
    case 3: return "Автовозврат в «Норма» (отключение BLE)"
    case 4: return "Автовозврат в «Норма» (низкий резерв)"
    case 5: return "Автовозврат в «Норма» (ошибка)"
    case 6: return "Автовозврат в «Норма» (перезапуск)"
    case 7: return "Автовозврат в «Норма» (автоизоляция КЗ)"
    default: return "Автовозврат в «Норма»"
    }
}

enum ConnectionPhase: String {
    case idle, scanning, connecting, pairing, negotiatingMtu, discovering
    case subscribing, authenticating, synchronizing, ready, reconnecting, error
}

struct DiscoveredDevice: Identifiable, Equatable {
    /// iOS CoreBluetooth peripheral UUID (MAC недоступен).
    var id: String { address }
    let address: String
    let advertisedName: String
    var userName: String?
    var deviceId: UInt32?
    var rssi: Int
}

struct DeviceState: Equatable {
    var mode: DplsMode
    var voltageMv: Int
    var powerSource: PowerSource
    var reserveLow: Bool
    var realShort: Bool
    var automaticReturnSeconds: Int
    var uptimeSeconds: UInt32
    var revision: UInt32
    var receivedAtMillis: Int64 = 0
    var lineVoltageValid: Bool = true
    var reserveValid: Bool = true
    var powerValid: Bool = true
    var autoIsoValid: Bool = true
    var adcCalibrated: Bool = false
}

struct EventRecord: Identifiable, Equatable {
    var id: UInt32 { sequence }
    let sequence: UInt32
    let timestampSeconds: UInt32
    let type: Int
    let parameter: Int
}

struct DeviceInfo: Equatable {
    let deviceId: UInt32
    let protocolVersion: Int
    let firmwareVersion: String
    let hardwareRevision: Int
    let adcPresent: Bool
    let hardwareReadback: Bool
    let adcCalibrated: Bool
    let userName: String

    var shortId: String { String(format: "DPLS-%08X", deviceId) }
}

enum SettingsOp {
    case none, inProgress, done, failed
}

func utf8Truncate(_ value: String, maxBytes: Int) -> Data {
    var result = Data()
    for scalar in value.unicodeScalars {
        let piece = String(scalar).data(using: .utf8) ?? Data()
        if result.count + piece.count > maxBytes { break }
        result.append(piece)
    }
    return result
}

struct DplsUiState: Equatable {
    var phase: ConnectionPhase = .idle
    var statusText: String = "Готово к поиску"
    var devices: [DiscoveredDevice] = []
    var selectedDevice: DiscoveredDevice?
    var initialized: Bool = false
    var credentialsReady: Bool = false
    var authenticated: Bool = false
    var state: DeviceState?
    var pendingMode: DplsMode?
    var commandInProgress: Bool = false
    var staleState: Bool = false
    var lastAckMillis: Int64?
    var eventLog: [EventRecord] = []
    var deviceBootEpochSeconds: Int64?
    var logProgress: Float?
    var identifyActive: Bool = false
    var identifyLedLive: Bool = false
    var setupName: String = ""
    var setupPassword: String = ""
    var setupRepeatPassword: String = ""
    var awaitingUserPassword: Bool = false
    var deviceInfo: DeviceInfo?
    var settingsOp: SettingsOp = .none
    var settingsError: String?
    var error: String?

    var controlsEnabled: Bool {
        phase == .ready && authenticated && !commandInProgress
    }

    var setupFormReady: Bool {
        credentialsReady &&
            setupPassword.count >= 8 &&
            (initialized || (setupRepeatPassword == setupPassword && !setupName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
    }
}
