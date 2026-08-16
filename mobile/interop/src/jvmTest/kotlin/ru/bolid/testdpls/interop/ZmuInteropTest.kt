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
private const val MODE_SHORT_1 = 3
private const val DEVICE_ID = 0x12345678L
private val sessionToken = ByteArray(8) { (0x24 + it).toByte() }
private val clientNonce = ByteArray(16) { (0xA0 + it).toByte() }

private data class Request(val name: String, val frame: DplsProtocol.Frame)
private data class ExpectedResponse(val type: DplsProtocol.Type, val sequence: Int)

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
    val mode = sessionPayload(1).also { it[12] = MODE_SHORT_1.toByte() }

    return listOf(
        request("identify_start", DplsProtocol.Type.IDENTIFY_START, 0x1001),
        request("identify_stop", DplsProtocol.Type.IDENTIFY_STOP, 0x1002),
        request("hello", DplsProtocol.Type.HELLO, 0x1003, clientNonce),
        request("auth_proof", DplsProtocol.Type.AUTH_PROOF, 0x1004, proof),
        request("state_normal", DplsProtocol.Type.STATE_GET, 0x1005, sessionPrefix()),
        request("device_info", DplsProtocol.Type.DEVICE_INFO_GET, 0x1006, sessionPrefix()),
        request("time_sync", DplsProtocol.Type.TIME_SYNC, 0x1007, timeSync),
        request("short_1", DplsProtocol.Type.MODE_SET, 0x1008, mode),
        request("state_short_1", DplsProtocol.Type.STATE_GET, 0x1009, sessionPrefix()),
        request("log_start", DplsProtocol.Type.LOG_START, 0x100A, sessionPrefix()),
    )
}

private fun expectedResponses(): List<ExpectedResponse> = listOf(
    ExpectedResponse(DplsProtocol.Type.IDENTIFY_START, 0x1001),
    ExpectedResponse(DplsProtocol.Type.IDENTIFY_STOP, 0x1002),
    ExpectedResponse(DplsProtocol.Type.AUTH_CHALLENGE, 0x1003),
    ExpectedResponse(DplsProtocol.Type.AUTH_RESULT, 0x1004),
    ExpectedResponse(DplsProtocol.Type.STATE_REPORT, 0x1005),
    ExpectedResponse(DplsProtocol.Type.DEVICE_INFO_REPORT, 0x1006),
    ExpectedResponse(DplsProtocol.Type.TIME_SYNC, 0x1007),
    ExpectedResponse(DplsProtocol.Type.COMMAND_RESULT, 0x1008),
    ExpectedResponse(DplsProtocol.Type.STATE_REPORT, 0x1009),
    ExpectedResponse(DplsProtocol.Type.LOG_INFO, 0x100A),
)

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
        check(frame.type == want.type) { "frame $index type=${frame.type}, expected=${want.type}" }
        check(frame.sequence == want.sequence) {
            "frame $index sequence=${frame.sequence}, expected=${want.sequence}"
        }
        check(frame.isResponse && !frame.isError) { "frame $index is not a successful response" }
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
    val nameLength = info.payload[11].toInt() and 0xff
    check(info.payload.copyOfRange(12, 12 + nameLength).decodeToString() == "Test-DPLS-ZMU")

    val command = frames[7]
    check(command.payload.size == 4)
    check((command.payload[0].toInt() and 0xff) == 0)
    check((command.payload[1].toInt() and 0xff) == MODE_SHORT_1)
    check(readU16(command.payload, 2) == 300)

    val shortState = frames[8]
    check((shortState.payload[0].toInt() and 0xff) == MODE_SHORT_1)
    check(text.contains("ZMU_E2E_OK")) { "ARM harness did not reach completion" }
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
