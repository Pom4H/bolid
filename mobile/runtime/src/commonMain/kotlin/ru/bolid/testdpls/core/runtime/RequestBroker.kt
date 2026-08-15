package ru.bolid.testdpls.core.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import ru.bolid.testdpls.core.protocol.DplsProtocol

class DplsRemoteError(
    val code: Int,
    val frame: DplsProtocol.Frame,
) : RuntimeException("DPLS error $code for transaction ${frame.sequence}")

/**
 * Owns the only mutable request-correlation table in the runtime.
 * Callers no longer need awaitingFoo/timeSyncPending/pendingCommandId flags.
 */
class RequestBroker(
    startSequence: Int = 1,
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<Int, CompletableDeferred<DplsProtocol.Frame>>()
    private var next = startSequence and 0xffff

    suspend fun request(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        send: suspend (DplsProtocol.Frame) -> Unit,
    ): DplsProtocol.Frame {
        val slot = CompletableDeferred<DplsProtocol.Frame>()
        val sequence = mutex.withLock {
            val id = allocateSequence()
            check(pending.put(id, slot) == null)
            id
        }
        try {
            send(
                DplsProtocol.Frame(
                    type = type,
                    sequence = sequence,
                    flags = DplsProtocol.Flags.REQUEST,
                    payload = payload,
                ),
            )
            val response = withTimeout(timeoutMillis) { slot.await() }
            if (response.isError) {
                throw DplsRemoteError(response.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0, response)
            }
            return response
        } finally {
            mutex.withLock { pending.remove(sequence) }
        }
    }

    suspend fun accept(frame: DplsProtocol.Frame): Boolean {
        if (!frame.isResponse && !frame.isError) return false
        val slot = mutex.withLock { pending[frame.sequence] } ?: return false
        return slot.complete(frame)
    }

    suspend fun cancelAll(cause: Throwable = LinkClosedException()) {
        val waiters = mutex.withLock {
            pending.values.toList().also { pending.clear() }
        }
        waiters.forEach { it.completeExceptionally(cause) }
    }

    suspend fun pendingCount(): Int = mutex.withLock { pending.size }

    private fun allocateSequence(): Int {
        repeat(0x1_0000) {
            val candidate = next
            next = (next + 1) and 0xffff
            if (candidate !in pending) return candidate
        }
        error("No free DPLS transaction ids")
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}

class LinkClosedException : RuntimeException("DPLS link closed")
