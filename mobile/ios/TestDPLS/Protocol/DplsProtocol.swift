import Foundation
import DplsCore

enum DplsProtocol {
    static let version: UInt8 = 1
    static let headerSize = 7
    static let trailerSize = 2
    static let overhead = headerSize + trailerSize

    private static let codec = DplsCodecBridge()

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
        guard let hex = codec.encodeHex(
            type: Int32(frame.type.rawValue),
            sequence: Int32(frame.sequence),
            flags: Int32(frame.flags),
            payloadHex: frame.payload.dplsHex
        ), let data = Data(dplsHex: hex) else {
            preconditionFailure("DplsCore rejected a locally constructed frame")
        }
        return data
    }

    static func decode(_ bytes: Data) -> DecodeResult {
        guard let decoded = codec.decodeHex(frameHex: bytes.dplsHex) else {
            return .failure("Неверное hex-представление кадра")
        }
        if let error = decoded.error { return .failure(error) }
        guard decoded.type >= 0,
              decoded.type <= 0xff,
              decoded.sequence >= 0,
              decoded.sequence <= 0xffff,
              decoded.flags >= 0,
              decoded.flags <= 0xff,
              let type = MessageType(rawValue: UInt8(decoded.type)),
              let payload = Data(dplsHex: decoded.payloadHex) else {
            return .failure("DplsCore вернул некорректный кадр")
        }
        return .success(
            Frame(
                type: type,
                sequence: UInt16(decoded.sequence),
                flags: UInt8(decoded.flags),
                payload: payload
            )
        )
    }

    static func crc16(_ bytes: Data) -> Int {
        Int(codec.crc16Hex(bytesHex: bytes.dplsHex))
    }
}

private extension Data {
    var dplsHex: String {
        map { String(format: "%02x", $0) }.joined()
    }

    init?(dplsHex: String) {
        guard dplsHex.count.isMultiple(of: 2) else { return nil }
        self.init(capacity: dplsHex.count / 2)
        var index = dplsHex.startIndex
        while index < dplsHex.endIndex {
            let next = dplsHex.index(index, offsetBy: 2)
            guard let byte = UInt8(dplsHex[index ..< next], radix: 16) else { return nil }
            append(byte)
            index = next
        }
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
