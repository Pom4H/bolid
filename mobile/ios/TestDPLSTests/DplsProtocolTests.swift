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

    func testDecodeRejectsUnknownTypeAndShortBuffer() {
        var encoded = DplsProtocol.encode(DplsProtocol.Frame(type: .keepAlive, sequence: 0, payload: Data()))
        encoded[1] = 0x55
        guard case .failure = DplsProtocol.decode(encoded) else {
            return XCTFail("expected unknown type failure")
        }
        guard case .failure = DplsProtocol.decode(Data([1, 2, 3])) else {
            return XCTFail("expected short buffer failure")
        }
        XCTAssertNil(DplsProtocol.MessageType(rawValue: 0x55))
        XCTAssertEqual(DplsProtocol.MessageType.error.rawValue, 0x7f)
    }

    func testLittleEndianHelpers() {
        var data = Data()
        LittleEndian.appendU32(&data, 0x0a0b0c0d)
        LittleEndian.appendU8(&data, 0x11)
        LittleEndian.appendU16(&data, 0x2233)
        var offset = 0
        XCTAssertEqual(LittleEndian.u32(data, &offset), 0x0a0b0c0d)
        XCTAssertEqual(LittleEndian.u8(data, &offset), 0x11)
        XCTAssertEqual(LittleEndian.u16(data, &offset), 0x2233)
    }

    func testModeTitlesAndEventCopy() {
        XCTAssertEqual(DplsMode.fromWire(2), .openMain)
        XCTAssertNil(DplsMode.fromWire(99))
        XCTAssertTrue(DplsMode.short1.dangerous)
        XCTAssertFalse(DplsMode.normal.dangerous)
        XCTAssertEqual(PowerSource.dpls.title, "ДПЛС")
        XCTAssertEqual(dplsEventTitle(type: 1, parameter: 0), "Запуск устройства")
        XCTAssertTrue(dplsEventTitle(type: 7, parameter: 3).contains("КЗ +1"))
        XCTAssertTrue(dplsEventTitle(type: 8, parameter: 1).contains("таймер"))
        XCTAssertTrue(dplsEventTitle(type: 8, parameter: 99).contains("Норма"))
        XCTAssertTrue(dplsEventTitle(type: 99, parameter: 1).contains("Событие"))
        for reason in 0...7 {
            XCTAssertTrue(dplsEventTitle(type: 8, parameter: reason).contains("Норма"))
        }
        let info = DeviceInfo(
            deviceId: 0x1fe3d5c3,
            protocolVersion: 1,
            firmwareVersion: "1.2.0",
            hardwareRevision: 2,
            adcPresent: true,
            hardwareReadback: false,
            adcCalibrated: false,
            userName: "Kit"
        )
        XCTAssertEqual(info.shortId, "DPLS-1FE3D5C3")
        var ui = DplsUiState()
        XCTAssertFalse(ui.controlsEnabled)
        ui.phase = .ready
        ui.authenticated = true
        XCTAssertTrue(ui.controlsEnabled)
        ui.credentialsReady = true
        ui.initialized = true
        ui.setupPassword = "password1"
        XCTAssertTrue(ui.setupFormReady)
        let snap = DeviceState(
            mode: .normal,
            voltageMv: 24000,
            powerSource: .dpls,
            reserveLow: false,
            realShort: false,
            automaticReturnSeconds: 0,
            uptimeSeconds: 1,
            revision: 1
        )
        ui.state = snap
        XCTAssertFalse(ui.needsPeriodicStateRefresh)
        ui.state?.mode = .short1
        XCTAssertTrue(ui.needsPeriodicStateRefresh)
        ui.commandInProgress = true
        XCTAssertFalse(ui.needsPeriodicStateRefresh)
        ui.commandInProgress = false
        ui.phase = .error
        ui.state?.mode = .normal
        XCTAssertTrue(ui.needsPeriodicStateRefresh)
        let event = EventRecord(sequence: 10, timestampSeconds: 3661, type: 1, parameter: 6)
        let relative = dplsEventTime(event, currentRunFirstSeq: 11, bootEpochSec: 1_700_000_000)
        XCTAssertNil(relative.dateLabel)
        XCTAssertTrue(relative.full.contains("от запуска"))
        let calendar = dplsEventTime(event, currentRunFirstSeq: 10, bootEpochSec: 1_700_000_000)
        XCTAssertNotNil(calendar.dateLabel)
    }
}
