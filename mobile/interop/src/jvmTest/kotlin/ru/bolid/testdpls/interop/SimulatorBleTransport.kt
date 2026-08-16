package ru.bolid.testdpls.interop

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import ru.bolid.testdpls.core.app.DplsTransport
import ru.bolid.testdpls.core.app.DplsTransportDevice
import ru.bolid.testdpls.core.app.DplsTransportListener

/**
 * Soft-BLE adapter: maps [DplsTransport] onto the host `dpls_simulator` stdio protocol.
 *
 * stdin:  CONNECT / CCCD 3 / FRAME <hex> / TICK <ms> / CONFIRM / DISCONNECT / QUIT
 * stdout: READY / TX <hex> / ACCEPT / DONE / MODE / LED / …
 *
 * Indications are queued (like a BLE callback looper) so [DplsClient] can finish
 * assigning request sequences / pending operations before [onBytes] runs. Tests
 * must call [drain] (see SoftBleBridgeTest.awaitCondition).
 */
class SimulatorBleTransport(
    private val simulatorPath: String,
) : DplsTransport {
    private val lock = ReentrantLock()
    private lateinit var listener: DplsTransportListener
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var linked = false
    private var closed = false
    private var draining = false
    private val deferredCommands = ArrayDeque<String>()
    private val pendingIndications = ArrayDeque<ByteArray>()
    private val pendingDisconnects = ArrayDeque<String?>()

    val device = DplsTransportDevice(
        address = ADDRESS,
        name = DEVICE_NAME,
        deviceId = DEVICE_ID,
        rssi = -42,
    )

    private var pendingLinkUp = false

    fun start(): Unit = lock.withLock {
        check(process == null) { "simulator already started" }
        val builder = ProcessBuilder(simulatorPath, "--name", DEVICE_NAME)
            .redirectErrorStream(true)
        val started = builder.start()
        process = started
        reader = BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
        while (true) {
            val line = readLineLocked()
            if (line == "READY DPLS2") break
            check(
                line.startsWith("JOURNAL ") ||
                    line.startsWith("MODE ") ||
                    line.startsWith("LED ") ||
                    line.startsWith("STATE ") ||
                    line.startsWith("DIAG "),
            ) { "unexpected simulator banner: $line" }
        }
    }

    /** Notify the client that the soft radio is up (call once after [setListener]). */
    fun notifyRadioReady() {
        listener.onBluetoothAvailable()
    }

    /**
     * Complete GAP/GATT bring-up after [connect]. Deferred so callers like
     * [ru.bolid.testdpls.core.app.DplsClient.identify] can set post-connect flags first
     * (same sequencing as FakeTransport unit tests).
     */
    fun completeLink() {
        check(pendingLinkUp) { "no pending soft-BLE link-up" }
        pendingLinkUp = false
        listener.onConnected()
        listener.onSubscribed(WRITE_LIMIT)
    }

    /** Complete GAP/GATT if the client already called [connect] (auto-reconnect). */
    fun finishLinkIfPending(): Boolean {
        if (!pendingLinkUp) return false
        completeLink()
        return true
    }

    /**
     * Deliver queued TX/DISCONNECT callbacks and flush any FRAME commands that were
     * enqueued from nested [send] calls during those callbacks.
     */
    fun drain(): Unit = lock.withLock {
        if (draining) return
        draining = true
        try {
            while (true) {
                while (deferredCommands.isNotEmpty()) {
                    executeCommandLocked(deferredCommands.removeFirst())
                }
                when {
                    pendingIndications.isNotEmpty() -> {
                        listener.onBytes(pendingIndications.removeFirst())
                        /* Samsung writes CCCD 0x03 and never sends ATT CFM.
                         * PHY6252 advances the notify queue on the 80 ms pace tick. */
                        deferredCommands.addLast("TICK 80")
                    }
                    pendingDisconnects.isNotEmpty() ->
                        listener.onDisconnected(pendingDisconnects.removeFirst())
                    else -> break
                }
            }
        } finally {
            draining = false
        }
    }

    fun tick(ms: Int): Unit = lock.withLock {
        require(ms >= 0)
        runCommandLocked("TICK $ms")
    }

    fun inject(command: String): Unit = lock.withLock {
        runCommandLocked(command)
    }

    override fun setListener(listener: DplsTransportListener) {
        this.listener = listener
    }

    override fun startScan(): Boolean {
        listener.onDiscovered(device)
        return true
    }

    override fun stopScan() = Unit

    override fun connect(address: String): Boolean = lock.withLock {
        check(!closed)
        check(address == ADDRESS) { "unknown soft-BLE address: $address" }
        runCommandLocked("CONNECT")
        runCommandLocked("CCCD 3")
        linked = true
        pendingLinkUp = true
        return true
    }

    override fun reconnect(): Boolean = connect(ADDRESS)

    override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean): Boolean = lock.withLock {
        if (!linked || closed) return false
        runCommandLocked("FRAME ${bytes.toHex()}")
        // Match FakeTransport: write-complete is synchronous; indications are async ([drain]).
        listener.onWriteComplete(null)
        return true
    }

    override fun readRssi(): Boolean {
        if (!linked) return false
        listener.onRssi(device.rssi)
        return true
    }

    override fun disconnect(clearSelection: Boolean) {
        lock.withLock {
            if (!linked) return
            runCommandLocked("DISCONNECT")
            linked = false
        }
        listener.onDisconnected(null)
    }

    override fun hasConnection(): Boolean = linked

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            linked = false
            pendingIndications.clear()
            pendingDisconnects.clear()
            deferredCommands.clear()
            try {
                if (process?.isAlive == true) {
                    writer?.apply {
                        write("QUIT\n")
                        flush()
                    }
                    process?.waitFor(2, TimeUnit.SECONDS)
                }
            } finally {
                writer?.close()
                reader?.close()
                process?.destroyForcibly()
                process = null
                reader = null
                writer = null
            }
        }
    }

    private fun runCommandLocked(command: String) {
        if (draining) {
            // Real BLE is async: never nest a new stdio FRAME while delivering onBytes
            // (DplsClient often request()s again, e.g. DEVICE_INFO_GET after SETTINGS_RESULT).
            deferredCommands.addLast(command)
            return
        }
        executeCommandLocked(command)
    }

    private fun executeCommandLocked(command: String) {
        val out = writer ?: error("simulator not started")
        out.write(command)
        out.write("\n")
        out.flush()
        while (true) {
            val line = readLineLocked()
            when {
                line == "DONE" -> return
                line.startsWith("TX ") ->
                    pendingIndications.addLast(line.removePrefix("TX ").trim().fromHex())
                line == "TX_DROPPED" -> Unit
                line == "DISCONNECT" -> {
                    linked = false
                    pendingDisconnects.addLast("simulator-disconnect")
                }
                line.startsWith("ACCEPT ") ||
                    line.startsWith("MODE ") ||
                    line.startsWith("LED ") ||
                    line.startsWith("DIAG ") ||
                    line.startsWith("STATE ") ||
                    line.startsWith("JOURNAL ") ||
                    line.startsWith("ERROR ") -> {
                    // Host-side hardware breadcrumbs for session capture parity.
                    System.err.println("TestDplsSim: $line")
                }
                else -> error("unexpected simulator output: $line (after $command)")
            }
        }
    }

    private fun readLineLocked(): String {
        val input = reader ?: error("simulator not started")
        return input.readLine() ?: error("simulator closed stdout")
    }

    companion object {
        const val ADDRESS = "sim:dpls"
        const val DEVICE_NAME = "Test-DPLS-SIM"
        const val DEVICE_ID = 0x1234L
        const val WRITE_LIMIT = 244

        fun resolveBinary(): String {
            val fromEnv = System.getenv("DPLS_SIMULATOR")
            if (!fromEnv.isNullOrBlank()) return fromEnv
            error(
                "Set DPLS_SIMULATOR to the dpls_simulator binary " +
                    "(cmake --build firmware/build --target dpls_simulator)",
            )
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02X".format(byte.toInt() and 0xff)
}

private fun String.fromHex(): ByteArray {
    check(length % 2 == 0) { "odd hex length: $this" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
