package ru.bolid.testdpls.core.session

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameSequencerTest {
    @Test
    fun wrapsAtSixteenBits() {
        val sequences = FrameSequencer()
        assertEquals(1, sequences.next())
        repeat(0xfffe) { sequences.next() }
        assertEquals(0, sequences.next())
        assertEquals(1, sequences.next())
    }

    @Test
    fun resetStartsAtOne() {
        val sequences = FrameSequencer()
        repeat(5) { sequences.next() }
        sequences.reset()
        assertEquals(1, sequences.next())
    }
}
