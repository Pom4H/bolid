package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32

class JournalMachineTest {
    @Test
    fun latestPageThenOlderPageNeedsNoBooleanProtocolState() {
        val machine = JournalMachine(pageSize = 2, maxEvents = 10)
        machine.begin(incremental = false)
        val info = ByteArray(10).also {
            putU32(it, 4, 40)
            putU16(it, 8, 4)
        }
        assertEquals(2, assertIs<JournalMachine.Effect.Ack>(machine.info(info)).index)
        assertIs<JournalMachine.Effect.Pause>(machine.chunk(chunk(2, 3, 4)))
        assertEquals(listOf(4L, 3L), machine.snapshot(false).records.map { it.sequence })
        assertTrue(machine.snapshot(false).hasMore)
        assertEquals(0, assertIs<JournalMachine.Effect.Ack>(machine.more()).index)
        assertIs<JournalMachine.Effect.Complete>(machine.chunk(chunk(0, 1, 2)))
        assertFalse(machine.snapshot(false).hasMore)
    }

    private fun chunk(first: Int, vararg sequence: Int): ByteArray = ByteArray(3 + sequence.size * 10).also { raw ->
        putU16(raw, 0, first)
        raw[2] = sequence.size.toByte()
        sequence.forEachIndexed { index, value ->
            val offset = 3 + index * 10
            putU32(raw, offset, value.toLong())
            putU32(raw, offset + 4, value.toLong())
            raw[offset + 8] = 4
        }
    }
}
