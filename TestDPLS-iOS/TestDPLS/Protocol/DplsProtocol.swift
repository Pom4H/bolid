import Foundation

enum DplsProtocol {
    static let version: UInt8 = 1
    static let headerSize = 7
    static let trailerSize = 2
    static let overhead = headerSize + trailerSize

    enum MessageType: UInt8 {
        case hello = 0x01
        case authChallenge = 0x02
        case authProof = 0x03
        case authResult = 0x04
        case setup = 0x05
        case deviceInfoGet = 0x06
        case deviceInfoReport = 0x07
        case nameSet = 0x08
        case passwordSet = 0x09
        case settingsResult = 0x0a
        case stateGet = 0x10
        case stateReport = 0x11
        case modeSet = 0x12
        case commandResult = 0x13
        case identifyStart = 0x14
        case identifyStop = 0x15
        case logStart = 0x20
        case logInfo = 0x21
        case logChunk = 0x22
        case logAck = 0x23
        case logFinish = 0x24
        case logResult = 0x25
        case keepAlive = 0x30
        case error = 0x7f
    }

    struct Frame: Equatable {
        let type: MessageType
        let sequence: UInt16
        var flags: UInt8 = 0
        var payload: Data = Data()
    }

    enum DecodeResult: Equatable {
        case success(Frame)
        case failure(String)
    }

    static func encode(_ frame: Frame) -> Data {
        precondition(frame.payload.count <= 0xffff)
        var bytes = Data(capacity: overhead + frame.payload.count)
        bytes.append(version)
        bytes.append(frame.type.rawValue)
        bytes.append(frame.flags)
        bytes.append(contentsOf: withUnsafeBytes(of: frame.sequence.littleEndian, Array.init))
        bytes.append(contentsOf: withUnsafeBytes(of: UInt16(frame.payload.count).littleEndian, Array.init))
        bytes.append(frame.payload)
        let crc = crc16(bytes)
        bytes.append(contentsOf: withUnsafeBytes(of: UInt16(crc).littleEndian, Array.init))
        return bytes
    }

    static func decode(_ bytes: Data) -> DecodeResult {
        guard bytes.count >= overhead else { return .failure("Короткий кадр") }
        guard bytes[0] == version else { return .failure("Версия протокола не поддерживается") }
        guard let type = MessageType(rawValue: bytes[1]) else { return .failure("Неизвестный тип сообщения") }
        let flags = bytes[2]
        let sequence = readU16(bytes, at: 3)
        let payloadLength = Int(readU16(bytes, at: 5))
        guard bytes.count == overhead + payloadLength else { return .failure("Неверная длина кадра") }
        let expected = Int(readU16(bytes, at: bytes.count - 2))
        let actual = crc16(bytes.prefix(bytes.count - 2))
        guard expected == actual else { return .failure("Ошибка CRC16") }
        let payload = bytes.subdata(in: headerSize ..< headerSize + payloadLength)
        return .success(Frame(type: type, sequence: sequence, flags: flags, payload: payload))
    }

    /// CRC-16/CCITT-FALSE (init 0xFFFF, poly 0x1021, no reflection) — same as firmware.
    static func crc16(_ bytes: Data) -> Int {
        var crc = 0xffff
        for byte in bytes {
            crc ^= Int(byte) << 8
            for _ in 0 ..< 8 {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1)
                crc &= 0xffff
            }
        }
        return crc
    }

    private static func readU16(_ data: Data, at offset: Int) -> UInt16 {
        UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
    }
}

enum LittleEndian {
    static func u8(_ data: Data, _ offset: inout Int) -> UInt8 {
        defer { offset += 1 }
        return data[offset]
    }

    static func u16(_ data: Data, _ offset: inout Int) -> UInt16 {
        defer { offset += 2 }
        return UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
    }

    static func u32(_ data: Data, _ offset: inout Int) -> UInt32 {
        defer { offset += 4 }
        return UInt32(data[offset])
            | (UInt32(data[offset + 1]) << 8)
            | (UInt32(data[offset + 2]) << 16)
            | (UInt32(data[offset + 3]) << 24)
    }

    static func appendU8(_ data: inout Data, _ value: UInt8) { data.append(value) }
    static func appendU16(_ data: inout Data, _ value: UInt16) {
        data.append(contentsOf: withUnsafeBytes(of: value.littleEndian, Array.init))
    }
    static func appendU32(_ data: inout Data, _ value: UInt32) {
        data.append(contentsOf: withUnsafeBytes(of: value.littleEndian, Array.init))
    }
}
