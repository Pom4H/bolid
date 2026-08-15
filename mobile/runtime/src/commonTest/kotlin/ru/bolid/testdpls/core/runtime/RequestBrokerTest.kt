package ru.bolid.testdpls.core.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.protocol.DplsProtocol

class RequestBrokerTest {
    @Test
    fun responseIsCorrelatedByFrameSequence() = runBlocking {
        val broker = RequestBroker(startSequence = 42)
        var sent: DplsProtocol.Frame? = null
        val request = async {
            broker.request(DplsProtocol.Type.STATE_GET) { frame -> sent = frame }
        }
        while (sent == null) kotlinx.coroutines.yield()
        val outgoing = requireNotNull(sent)
        assertEquals(42, outgoing.sequence)
        assertTrue(outgoing.isRequest)
        assertTrue(
            broker.accept(
                DplsProtocol.Frame(
                    type = DplsProtocol.Type.STATE_REPORT,
                    sequence = outgoing.sequence,
                    flags = DplsProtocol.Flags.RESPONSE,
                    payload = byteArrayOf(1, 2, 3),
                ),
            ),
        )
        assertEquals(DplsProtocol.Type.STATE_REPORT, request.await().type)
        assertEquals(0, broker.pendingCount())
    }

    @Test
    fun unrelatedResponseCannotCompleteRequest() = runBlocking {
        val broker = RequestBroker(startSequence = 7)
        var sent: DplsProtocol.Frame? = null
        val request = async {
            broker.request(DplsProtocol.Type.DEVICE_INFO_GET, timeoutMillis = 50) { frame -> sent = frame }
        }
        while (sent == null) kotlinx.coroutines.yield()
        assertTrue(
            !broker.accept(
                DplsProtocol.Frame(
                    type = DplsProtocol.Type.DEVICE_INFO_REPORT,
                    sequence = 99,
                    flags = DplsProtocol.Flags.RESPONSE,
                ),
            ),
        )
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { request.await() }
    }
}
