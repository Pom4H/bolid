#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
WIRE = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsWire.kt"
WIRE_TEST = ROOT / "mobile/core/src/commonTest/kotlin/ru/bolid/testdpls/core/app/DplsWireTest.kt"
SNV = ROOT / "firmware/phy6252/dpls_phy6252_snv_guard.c"
TIMEOUT = ROOT / "tools/test_ble_timeout_contract.py"
SNV_CONTRACT = ROOT / "tools/test_phy6252_snv_guard_contract.py"
ARCH = ROOT / "tools/architecture_guard.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}: {old!r}")
    return text.replace(old, new)


def require_absent(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise SystemExit(f"{label}: still contains {needle!r}")


# Android: keep one product connection deadline in DplsClient. The transport
# watches Android bond events/polling, but owns no second human-vs-timer deadline.
a = ANDROID.read_text(encoding="utf-8")
for line in (
    "    private var servicesDiscovered = false\n",
    "    private var writeInProgress = false\n",
    "    private var pairingTimeout: Runnable? = null\n",
):
    if line not in a:
        raise SystemExit(f"android field anchor missing: {line!r}")
    a = a.replace(line, "", 1)

a = a.replace("cancelPairingTimeout()", "cancelPairingWatch()")
a = a.replace("schedulePairingTimeout()", "watchPairing()")
a = a.replace("            servicesDiscovered = false\n", "")
a = a.replace("                servicesDiscovered = false\n", "")
a = replace_once(a, "            if (servicesDiscovered || subscribed) return\n", "            if (rx != null || subscribed) return\n", "android service discovery truth")
a = replace_once(a, "            servicesDiscovered = true\n", "", "android service discovery assignment")
a = replace_once(a, "        if (writeInProgress || gatt == null || rx == null || !subscribed) return\n", "        if (pendingWrite != null || gatt == null || rx == null || !subscribed) return\n", "android resume write")
a = replace_once(a, "        if (writeInProgress || securityState is SecurityState.Pairing ||\n", "        if (pendingWrite != null || securityState is SecurityState.Pairing ||\n", "android drain ownership")
a = replace_once(a, "        writeInProgress = true\n        pendingWrite = bytes\n", "        pendingWrite = bytes\n", "android write submit state")
a = replace_once(a, "        if (result != BluetoothStatusCodes.SUCCESS) {\n            writeInProgress = false\n            completeWrite(result)\n        }\n", "        if (result != BluetoothStatusCodes.SUCCESS) completeWrite(result)\n", "android submit failure")
a = replace_once(a, "    private fun completeWrite(status: Int) {\n        writeInProgress = false\n", "    private fun completeWrite(status: Int) {\n", "android completion ownership")
a = replace_once(a, "        writeQueue.clear()\n        writeInProgress = false\n        pendingWrite = null\n", "        writeQueue.clear()\n        pendingWrite = null\n", "android reset write state")
old_watch = '''    private fun watchPairing() {
        cancelPairingWatch()
        pairingPoll = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                if (!pairing) return
                if (gatt?.device?.bondState == BluetoothDevice.BOND_BONDED) handleBonded()
                else handler.postDelayed(this, PAIRING_POLL_MS)
            }
        }.also { handler.postDelayed(it, PAIRING_POLL_MS) }
        pairingTimeout = Runnable {
            if (!pairing) return@Runnable
            Log.i(TAG, "pairing timeout state=$securityState")
            failPairingNotConfirmed()
        }.also { handler.postDelayed(it, PAIRING_TIMEOUT_MS) }
    }

    private fun cancelPairingWatch() {
        pairingTimeout?.let(handler::removeCallbacks)
        pairingTimeout = null
        pairingPoll?.let(handler::removeCallbacks)
        pairingPoll = null
    }
'''
new_watch = '''    private fun watchPairing() {
        cancelPairingWatch()
        pairingPoll = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                if (!pairing) return
                if (gatt?.device?.bondState == BluetoothDevice.BOND_BONDED) handleBonded()
                else handler.postDelayed(this, PAIRING_POLL_MS)
            }
        }.also { handler.postDelayed(it, PAIRING_POLL_MS) }
    }

    private fun cancelPairingWatch() {
        pairingPoll?.let(handler::removeCallbacks)
        pairingPoll = null
    }
'''
a = replace_once(a, old_watch, new_watch, "android pairing watcher")
a = replace_once(a, "        private const val PAIRING_TIMEOUT_MS = 45_000L\n", "", "android pairing deadline")
for needle in ("servicesDiscovered", "writeInProgress", "pairingTimeout", "PAIRING_TIMEOUT_MS"):
    require_absent(a, needle, "android compact state")
ANDROID.write_text(a, encoding="utf-8")

# Wire: delete the unused STATE_REPORT watermark. DplsClient already owns
# operation/session correlation; a second unused correlation truth only adds risk.
WIRE.write_text('''package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.session.FrameSequencer

/** Wire mechanics only: frame sequence and codec. */
internal class DplsWire(
    private val transport: DplsTransport,
    private val fail: (String) -> Unit,
) {
    private val sequencer = FrameSequencer()

    fun reset() = sequencer.reset()

    fun decode(bytes: ByteArray): DplsProtocol.DecodeResult = decodeFrame(bytes)

    fun request(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
        priority: Boolean = false,
        flush: Boolean = false,
    ): Int? {
        val sequence = sequencer.next()
        return sequence.takeIf {
            send(type, sequence, DplsProtocol.Flags.REQUEST, payload, priority, flush)
        }
    }

    fun oneWay(type: DplsProtocol.Type, payload: ByteArray = byteArrayOf()) {
        send(type, sequencer.next(), 0, payload, priority = false, flush = false)
    }

    private fun send(
        type: DplsProtocol.Type,
        sequence: Int,
        flags: Int,
        payload: ByteArray,
        priority: Boolean,
        flush: Boolean,
    ): Boolean {
        val bytes = encodeFrame(DplsProtocol.Frame(type, sequence, flags, payload))
        if (transport.send(bytes, priority, flush)) return true
        fail("Кадр ${bytes.size} байт не помещается в BLE write limit")
        return false
    }
}
''', encoding="utf-8")
if WIRE_TEST.exists():
    WIRE_TEST.unlink()

# Firmware: pending staged data is itself the disconnect request. Remove the
# duplicate boolean owner, and route every app-owned physical write through the
# same watchdog-bounded function.
s = SNV.read_text(encoding="utf-8")
s = replace_once(s, "static dpls_deferred_snv_t deferred;\nstatic bool disconnect_requested;\n", "static dpls_deferred_snv_t deferred;\n", "snv duplicate owner")
s = replace_once(s, "bool dpls_phy6252_snv_disconnect_requested(void)\n{\n    return disconnect_requested;\n}\n", "bool dpls_phy6252_snv_disconnect_requested(void)\n{\n    return deferred.pending && dpls_phy6252_link_active();\n}\n", "snv disconnect predicate")
s = replace_once(s, "    if (!deferred.pending) {\n        disconnect_requested = false;\n        return true;\n    }\n", "    if (!deferred.pending) return true;\n", "snv empty flush")
s = replace_once(s, "    deferred.len = 0u;\n    disconnect_requested = false;\n    return true;\n", "    deferred.len = 0u;\n    return true;\n", "snv flush complete")
s = replace_once(s, "        return osal_snv_write(id, len, data);\n", "        return physical_write(id, len, data);\n", "snv unlinked physical write")
s = replace_once(s, "        disconnect_requested = true;\n        LOG(\"DPLS SNV second active-link write rejected id=0x%02x pending=0x%02x\\n\",\n", "        LOG(\"DPLS SNV second active-link write rejected id=0x%02x pending=0x%02x\\n\",\n", "snv concurrent write")
s = replace_once(s, "    memcpy(deferred.data, data, len);\n    disconnect_requested = true;\n    LOG(\"DPLS SNV deferred id=0x%02x len=%u\\n\", (unsigned)id, (unsigned)len);\n", "    memcpy(deferred.data, data, len);\n    LOG(\"DPLS SNV deferred id=0x%02x len=%u\\n\", (unsigned)id, (unsigned)len);\n", "snv stage")
require_absent(s, "disconnect_requested", "snv duplicate state")
SNV.write_text(s, encoding="utf-8")

# CI contract: one mobile deadline, one independent defensive firmware deadline.
TIMEOUT.write_text('''#!/usr/bin/env python3
"""Fail if a second mobile BLE deadline or timeout race returns."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
FIRMWARE = ROOT / "firmware/phy6252/dpls_phy6252_app.c"


def constant(path: Path, name: str) -> int:
    text = path.read_text(encoding="utf-8")
    for pattern in (
        rf"\\b{name}\\s*=\\s*([0-9_]+)(?:[uUlL]*)\\b",
        rf"^[ \\t]*#define[ \\t]+{name}[ \\t]+([0-9_]+)(?:[uUlL]*)\\b",
    ):
        match = re.search(pattern, text, flags=re.MULTILINE)
        if match is not None:
            return int(match.group(1).replace("_", ""))
    raise SystemExit(f"{path.relative_to(ROOT)}: constant {name} not found")


android = ANDROID.read_text(encoding="utf-8")
for forbidden in ("PAIRING_TIMEOUT_MS", "pairingTimeout"):
    if forbidden in android:
        raise SystemExit(f"Android transport must not own a second connection deadline: {forbidden}")

connect_ms = constant(CLIENT, "CONNECT_TIMEOUT_MS")
firmware_ms = constant(FIRMWARE, "DPLS_LINK_ENCRYPT_TIMEOUT_MS")
if connect_ms >= firmware_ms or firmware_ms - connect_ms < 5_000:
    raise SystemExit(
        f"BLE timeout ordering broken: mobile={connect_ms}ms firmware={firmware_ms}ms"
    )

client = CLIENT.read_text(encoding="utf-8")
subscribed = client[client.index("override fun onSubscribed"):client.index("override fun onBytes")]
if "armConnectTimeout()" not in subscribed:
    raise SystemExit("DplsClient must restart its single deadline after CCCD subscription")

print(f"BLE timeout contract: PASS mobile={connect_ms}ms firmware={firmware_ms}ms")
''', encoding="utf-8")

# Static guards follow the simplified ownership model.
c = SNV_CONTRACT.read_text(encoding="utf-8")
c = replace_once(c, 'require(GUARD, "disconnect_requested = true;")\n', 'require(GUARD, "return deferred.pending && dpls_phy6252_link_active();")\n', "snv contract duplicate owner")
SNV_CONTRACT.write_text(c, encoding="utf-8")

arch = ARCH.read_text(encoding="utf-8")
anchor = 'forbid_text(ANDROID_BLE, "POST_BOND_SETTLE_MS", "pairing may not depend on post-bond magic delay")\n'
insert = anchor + 'forbid_text(ANDROID_BLE, "PAIRING_TIMEOUT_MS", "Android transport must not own a second connection deadline")\nforbid_text(ANDROID_BLE, "pairingTimeout", "Android transport must not own a second timeout state")\n'
arch = replace_once(arch, anchor, insert, "architecture single mobile deadline")
ARCH.write_text(arch, encoding="utf-8")

print("RC6 compact migration applied")
