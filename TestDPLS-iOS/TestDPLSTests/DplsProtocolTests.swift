import XCTest
@testable import TestDPLS

final class DplsProtocolTests: XCTestCase {
    func testCrc16MatchesCcittFalseCheckVector() {
        let data = Data("123456789".utf8)
        XCTAssertEqual(DplsProtocol.crc16(data), 0x29B1)
    }

    func testEncodeThenDecodeRoundTripsFrame() {
        let payload = Data([0x11, 0x22, 0x33, 0x44, 0x55])
        let frame = DplsProtocol.Frame(type: .modeSet, sequence: 0x1234, payload: payload)
        let encoded = DplsProtocol.encode(frame)
        XCTAssertEqual(encoded.count, DplsProtocol.overhead + payload.count)

        let decoded = DplsProtocol.decode(encoded)
        guard case .success(let out) = decoded else {
            return XCTFail("decode failed")
        }
        XCTAssertEqual(out.type, .modeSet)
        XCTAssertEqual(out.sequence, 0x1234)
        XCTAssertEqual(out.payload, payload)
    }

    func testEncodeLayoutIsVersionTypeFlagsSeqLenPayloadCrc() {
        let frame = DplsProtocol.Frame(type: .hello, sequence: 1, payload: Data())
        let encoded = DplsProtocol.encode(frame)
        XCTAssertEqual(encoded.count, DplsProtocol.overhead)
        XCTAssertEqual(encoded[0], DplsProtocol.version)
        XCTAssertEqual(encoded[1], DplsProtocol.MessageType.hello.rawValue)
        XCTAssertEqual(encoded[2], 0)
        XCTAssertEqual(encoded[3], 1)
        XCTAssertEqual(encoded[4], 0)
        XCTAssertEqual(encoded[5], 0)
        XCTAssertEqual(encoded[6], 0)
    }

    func testDecodeRejectsCorruptCrc() {
        var encoded = DplsProtocol.encode(
            DplsProtocol.Frame(type: .stateGet, sequence: 7, payload: Data([1, 2, 3]))
        )
        encoded[encoded.count - 1] &+= 1
        guard case .failure = DplsProtocol.decode(encoded) else {
            return XCTFail("expected failure")
        }
    }

    func testDecodeRejectsWrongLength() {
        let encoded = DplsProtocol.encode(
            DplsProtocol.Frame(type: .stateGet, sequence: 7, payload: Data([1, 2, 3]))
        )
        let truncated = encoded.prefix(encoded.count - 1)
        guard case .failure = DplsProtocol.decode(Data(truncated)) else {
            return XCTFail("expected failure")
        }
    }

    func testDecodeRejectsUnknownVersion() {
        var encoded = DplsProtocol.encode(
            DplsProtocol.Frame(type: .keepAlive, sequence: 0, payload: Data())
        )
        encoded[0] = 0x7f
        guard case .failure = DplsProtocol.decode(encoded) else {
            return XCTFail("expected failure")
        }
    }

    func testUtf8TruncateDoesNotSplitMultibyte() {
        let name = "Тест-ДПЛС-длинное"
        let truncated = utf8Truncate(name, maxBytes: 10)
        XCTAssertLessThanOrEqual(truncated.count, 10)
        XCTAssertNotNil(String(data: truncated, encoding: .utf8))
    }
}
