package ru.bolid.testdpls.interop

import java.io.File
import kotlin.test.Test
import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.protocol.putU32
import ru.bolid.testdpls.core.protocol.readU16
import ru.bolid.testdpls.core.protocol.readU32

private const val SESSION_ID = 0x13121110L
private const val MODE_NORMAL = 0
private const val MODE_OPEN_T = 1
private const val MODE_OPEN_MAIN = 2
private const val MODE_SHORT_1 = 3
private const val MODE_SHORT_2 = 4
private const val MODE_SHORT_T = 5
private const val DEVICE_ID = 0x12345678L
private val sessionToken = ByteArray(8) { (0x24 + it).toByte() }
private val clientNonce = ByteArray(16) { (0xA0 + it).toByte() }

/** Phone-E2E mode list (wire values), device-free on zmu. */
private val TEST_MODES = listOf(
    "short_1" to MODE_SHORT_1,
    "short_2" to MODE_SHORT_2,
    "short_t" to MODE_SHORT_T,
    "open_t" to MODE_OPEN_T,
    "open_main" to MODE_OPEN_MAIN,
)

private data class Request(val name: String, val frame: DplsProtocol.Frame)
private data class ExpectedResponse(val name: String, val type: DplsProtocol.Type, val sequence: Int)

private fun sessionPrefix(): ByteArray = ByteArray(12).also { payload ->
    putU32(payload, 0, SESSION_ID)
    sessionToken.copyInto(payload, 4)
}

private fun sessionPayload(extra: Int = 0): ByteArray = ByteArray(12 + extra).also { payload ->
    sessionPrefix().copyInto(payload)
}

private fun request(
    name: String,
    type: DplsProtocol.Type,
    sequence: Int,
    payload: ByteArray = byteArrayOf(),
): Request = Request(
    name,
    DplsProtocol.Frame(
        type = type,
        sequence = sequence,
        flags = DplsProtocol.Flags.REQUEST,
        payload = payload,
    ),
)

private fun requests(): List<Request> {
    val proof = ByteArray(48).also { payload ->
        clientNonce.copyInto(payload, 0)
        repeat(32) { index -> payload[16 + index] = 0x55 }
    }
    val timeSync = sessionPayload(4).also { putU32(it, 12, 1_786_732_800L) }
    val deviceName = "ZmuLab01".encodeToByteArray()
    check(deviceName.size in 1..16)
    val nameSet = sessionPayload(1 + deviceName.size).also { payload ->
        payload[12] = deviceName.size.toByte()
        deviceName.copyInto(payload, 13)
    }
    val keepAlive = sessionPrefix()
    val logAck = sessionPayload(2).also { payload ->
        payload[12] = 0
        payload[13] = 0
    }
    val passwordSet = sessionPayload(48).also { payload ->
        repeat(16) { index -> payload[12 + index] = (0x60 + index).toByte() }
        repeat(32) { index -> payload[28 + index] = (0x80 + index).toByte() }
    }

    val out = mutableListOf(
        request("identify_start", DplsProtocol.Type.IDENTIFY_START, 0x1001),
        request("identify_stop", DplsProtocol.Type.IDENTIFY_STOP, 0x1002),
        request("hello", DplsProtocol.Type.HELLO, 0x1003, clientNonce),
        request("auth_proof", DplsProtocol.Type.AUTH_PROOF, 0x1004, proof),
        request("state_normal", DplsProtocol.Type.STATE_GET, 0x1005, sessionPrefix()),
        request("device_info", DplsProtocol.Type.DEVICE_INFO_GET, 0x1006, sessionPrefix()),
        request("time_sync", DplsProtocol.Type.TIME_SYNC, 0x1007, timeSync),
        request("keep_alive", DplsProtocol.Type.KEEP_ALIVE, 0x1008, keepAlive),
    )

    var sequence = 0x1100
    for ((name, mode) in TEST_MODES) {
        val modePayload = sessionPayload(1).also { it[12] = mode.toByte() }
        val normalPayload = sessionPayload(1).also { it[12] = MODE_NORMAL.toByte() }
        out += request("mode_$name", DplsProtocol.Type.MODE_SET, sequence++, modePayload)
        out += request("state_$name", DplsProtocol.Type.STATE_GET, sequence++, sessionPrefix())
        out += request("mode_${name}_off", DplsProtocol.Type.MODE_SET, sequence++, normalPayload)
    }

    out += listOf(
        request("name_set", DplsProtocol.Type.NAME_SET, 0x1201, nameSet),
        request("log_start", DplsProtocol.Type.LOG_START, 0x1202, sessionPrefix()),
        request("log_ack", DplsProtocol.Type.LOG_ACK, 0x1203, logAck),
        request("password_set", DplsProtocol.Type.PASSWORD_SET, 0x1204, passwordSet),
    )
    return out
}

private fun expectedResponses(): List<ExpectedResponse> {
    val out = mutableListOf(
        ExpectedResponse("identify_start", DplsProtocol.Type.IDENTIFY_START, 0x1001),
        ExpectedResponse("identify_stop", DplsProtocol.Type.IDENTIFY_STOP, 0x1002),
        ExpectedResponse("hello", DplsProtocol.Type.AUTH_CHALLENGE, 0x1003),
        ExpectedResponse("auth_proof", DplsProtocol.Type.AUTH_RESULT, 0x1004),
        ExpectedResponse("state_normal", DplsProtocol.Type.STATE_REPORT, 0x1005),
        ExpectedResponse("device_info", DplsProtocol.Type.DEVICE_INFO_REPORT, 0x1006),
        ExpectedResponse("time_sync", DplsProtocol.Type.TIME_SYNC, 0x1007),
        ExpectedResponse("keep_alive", DplsProtocol.Type.KEEP_ALIVE, 0x1008),
    )
    var sequence = 0x1100
    for ((name, _) in TEST_MODES) {
        out += ExpectedResponse("mode_$name", DplsProtocol.Type.COMMAND_RESULT, sequence++)
        out += ExpectedResponse("state_$name", DplsProtocol.Type.STATE_REPORT, sequence++)
        out += ExpectedResponse("mode_${name}_off", DplsProtocol.Type.COMMAND_RESULT, sequence++)
    }
    out += listOf(
        ExpectedResponse("name_set", DplsProtocol.Type.SETTINGS_RESULT, 0x1201),
        ExpectedResponse("log_start", DplsProtocol.Type.LOG_INFO, 0x1202),
        ExpectedResponse("log_ack", DplsProtocol.Type.LOG_CHUNK, 0x1203),
        ExpectedResponse("password_set", DplsProtocol.Type.SETTINGS_RESULT, 0x1204),
    )
    return out
}

private fun writeHeader(output: File) {
    output.parentFile?.mkdirs()
    val encoded = requests().map { it.name to encodeFrame(it.frame) }
    output.writeText(
        buildString {
            appendLine("#ifndef DPLS_ZMU_VECTORS_H")
            appendLine("#define DPLS_ZMU_VECTORS_H")
            appendLine("#include <stdint.h>")
            appendLine("typedef struct { const uint8_t *data; uint16_t length; } zmu_request_t;")
            encoded.forEachIndexed { index, (name, bytes) ->
                append("static const uint8_t zmu_req_$index[] = {")
                append(bytes.joinToString(",") { byte -> "0x%02X".format(byte.toInt() and 0xff) })
                appendLine("}; /* $name */")
            }
            appendLine("static const zmu_request_t zmu_requests[] = {")
            encoded.indices.forEach { index ->
                appendLine("    { zmu_req_$index, (uint16_t)sizeof(zmu_req_$index) },")
            }
            appendLine("};")
            appendLine("#define ZMU_REQUEST_COUNT ((uint16_t)(sizeof(zmu_requests) / sizeof(zmu_requests[0])))")
            appendLine("#endif")
        },
    )
}

private fun parseHex(line: String): ByteArray {
    val text = line.removePrefix("FRAME ").trim()
    check(text.length % 2 == 0) { "odd hex line: $line" }
    return ByteArray(text.length / 2) { index ->
        text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun verifyOutput(input: File) {
    val text = input.readText()
    val rawFrames = text.lineSequence()
        .filter { it.startsWith("FRAME ") }
        .map(::parseHex)
        .toList()
    val expected = expectedResponses()
    check(rawFrames.size == expected.size) {
        "zmu produced ${rawFrames.size} frames, expected ${expected.size}\n$text"
    }

    val frames = rawFrames.mapIndexed { index, bytes ->
        when (val decoded = decodeFrame(bytes)) {
            is DplsProtocol.DecodeResult.Success -> decoded.frame
            is DplsProtocol.DecodeResult.Failure -> error("frame $index: ${decoded.reason}")
        }
    }

    frames.zip(expected).forEachIndexed { index, (frame, want) ->
        check(frame.type == want.type) {
            "frame $index (${want.name}) type=${frame.type}, expected=${want.type}"
        }
        check(frame.sequence == want.sequence) {
            "frame $index (${want.name}) sequence=${frame.sequence}, expected=${want.sequence}"
        }
        check(frame.isResponse && !frame.isError) {
            "frame $index (${want.name}) is not a successful response"
        }
    }

    val challenge = frames[2]
    check(challenge.payload.size == 37)
    check(readU32(challenge.payload, 0) == SESSION_ID)
    check((challenge.payload[36].toInt() and 0xff) == 1)

    val auth = frames[3]
    check(auth.payload.size == 11)
    check((auth.payload[0].toInt() and 0xff) == 0)
    check(auth.payload.copyOfRange(3, 11).contentEquals(sessionToken))

    val normal = frames[4]
    check(normal.payload.size == 25)
    check((normal.payload[0].toInt() and 0xff) == MODE_NORMAL)
    check(readU16(normal.payload, 2) == 12_000)
    check(readU16(normal.payload, 17) == 12_000)
    check(readU16(normal.payload, 23) == 5_000)

    val info = frames[5]
    check(readU32(info.payload, 0) == DEVICE_ID)
    check(info.payload[4] == DplsProtocol.VERSION)

    var index = 8
    for ((_, mode) in TEST_MODES) {
        val command = frames[index++]
        check(command.payload.size == 4)
        check((command.payload[0].toInt() and 0xff) == 0)
        check((command.payload[1].toInt() and 0xff) == mode)
        check(readU16(command.payload, 2) == 300)
        val state = frames[index++]
        check((state.payload[0].toInt() and 0xff) == mode)
        val off = frames[index++]
        check((off.payload[0].toInt() and 0xff) == 0)
        check((off.payload[1].toInt() and 0xff) == MODE_NORMAL)
    }

    val nameResult = frames[index++]
    check((nameResult.payload[0].toInt() and 0xff) == 0)

    val logInfo = frames[index++]
    check(logInfo.type == DplsProtocol.Type.LOG_INFO)

    val logChunk = frames[index++]
    check(logChunk.type == DplsProtocol.Type.LOG_CHUNK)
    check(logChunk.payload.size >= 3)

    val passwordResult = frames[index]
    check((passwordResult.payload[0].toInt() and 0xff) == 0)

    check(text.contains("ZMU_E2E_OK")) { "ARM harness did not reach completion" }
    check(text.contains("SCENARIO_MODES_OK")) { "mode matrix did not complete" }
    check(text.contains("SCENARIO_SETTINGS_OK")) { "settings/journal scenario did not complete" }
}

class ZmuInteropTest {
    @Test
    fun generateVectorsWhenRequested() {
        val path = System.getenv("DPLS_ZMU_GENERATE") ?: return
        writeHeader(File(path))
    }

    @Test
    fun verifyOutputWhenRequested() {
        val path = System.getenv("DPLS_ZMU_VERIFY") ?: return
        verifyOutput(File(path))
    }
}
